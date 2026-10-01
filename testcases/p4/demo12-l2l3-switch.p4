// demo12：二层/三层混合交换机 —— 并行查找 + 合并仲裁 + 报文编辑 + 报文重组
//          **v4：可选封装**（1 层 OpaqueTag + 0..4 层 **802.1Q VLAN**）
//
// 与 v3 的差异（本次新增）：报文格式支持**层数可选**的封装：
//   Ethernet → [OpaqueTag] → [802.1Q VLAN × 0..4] → ethertype → IPv4 → (UDP | TCP)
// 并在 ingress 里按层数分别处理（这才是"可选"的落点）：
//   - classify 解析封装链：otagV / vlanDepth(0..4) / innerVid / isIpv4
//   - mac_table 按 **(目的 MAC, 内层 VID)** 查找（二层转发按 VLAN 隔离）
//   - rewrite 依当前层数做 VLAN 增删：
//       L3 上联口（trunk）：层数 < 4 才**加一层**外层 VLAN（满 4 层 ⇒ 饱和保护，不动）
//       L2 用户口（access）：层数 > 0 才**剥一层**外层 VLAN（0 层 ⇒ 无可剥，不动）
//
// ⚠️ 子集限制与应对（无 header stack / setValid / 变长 extract）：
//   「可选」封装按**固定槽位**实现 —— 每个可选层占一个固定偏移的槽位，
//   不存在时槽位为全 0，存在与否由该槽位自身的 TPID 判定 ⇒ 层数天然可选（0..4）。
//   这要求入包缓冲**归一化到最坏布局**（缺的层补 0），也正是多数交换芯片内部
//   报文总线的形态。802.1Q 标签内部没有"下一层类型"字段，故标签链末端单独放一个
//   2 字节 `etype`（等价于真实帧里最后一个标签之后的那 2 字节）。
//
// 处理流水（拍数按 proc 相位）：
//   parser(eth → otag → vlan0..3 → etype → ipv4 → udp/tcp，固定槽位)
//   → classify()          封装链解析 + 预分类（isL3）+ 清理决策字段
//   → mac_table  ┐        runtime 表，并行查找组 g0：同拍发 key / 同拍收 rsp
//   → route_table┘        命中结果写入各自的决策字段
//   → resolve()           合并仲裁（L3 > L2 > 泛洪）+ **VLAN 增删决策**（doPush/doPop）
//   → rewrite()           报文编辑（MAC / TTL / 校验和 / **VLAN 搬移**）+ 字节统计
//   → ttl_guard           静态表：路由后 TTL == 0 ⇒ 丢弃
//   → Deparser            emit 序重组报文
//
// 其余子集约束的应对（工具链边界见 docs/Demo12-L2L3交换机.md §2）：
//   - 无 if/else ⇒ 决策合并与报文编辑全部用三元表达式 + "meta 参与表 key" 门控
//   - 无 apply 内局部变量 ⇒ 旧 TTL 用表达式复用（先算校验和再减 TTL，顺序不能反）
//   - 表 key 只能是字段路径 ⇒ L3 是 /32 主机路由（无 LPM）
#include <core.p4>

// p4c: pkt-window 640
//   固定槽位最坏布局 = eth14B + otag6B + vlan×4 16B + etype2B + ipv4 20B + tcp20B
//                    = 78 字节 = 624 位（取 640 留余量）

// ---------------- 协议常量 ----------------
const bit<16> ET_IPV4   = 16w0x0800;
const bit<16> ET_ARP    = 16w0x0806;
const bit<8>  PROTO_UDP = 8w17;
const bit<8>  PROTO_TCP = 8w6;

const bit<16> TPID_VLAN = 16w0x8100;   // 802.1Q（VLAN 槽位标识）
const bit<16> TPID_OTAG = 16w0x8200;   // OpaqueTag 槽位标识（内部带内元数据）

// ---------------- 转发类型 / 丢弃原因 ----------------
const bit<8> FWD_L2    = 8w1;
const bit<8> FWD_L3    = 8w2;
const bit<8> FWD_FLOOD = 8w3;
const bit<8> FWD_DROP  = 8w4;

const bit<8> DROP_NONE = 8w0;
const bit<8> DROP_TTL  = 8w1;

// ---------------- 交换机本机参数（Demo 用常量固化） ----------------
const bit<48> SWITCH_MAC = 48w0x020000000001;   // 三层口/交换机 MAC，也是 L3 重写后的源 MAC
const bit<48> NH_MAC_1   = 48w0x001122334455;   // 10.0.0.1 的下一跳 MAC
const bit<48> NH_MAC_2   = 48w0x00aabbccddee;   // 10.0.0.2 的下一跳 MAC
const bit<48> NH_MAC_3   = 48w0x00deadbeef01;   // 10.0.0.3 的下一跳 MAC

// 端口 0 = 上联口（L3 出口，trunk 带 VLAN），端口 1..3 = 用户接入口（access 不带 VLAN）
const bit<16> PM_FLOOD   = 16w0x000e;           // 泛洪掩码：端口 1/2/3（不含上联）
const bit<12> UPLINK_VID = 12w100;              // 上联口出方向打的外层 VLAN

// ---------------- headers ----------------
header ethernet_h {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

// OpaqueTag（交换机内部带内标签：源端口 / 入 VLAN / 时间戳等）。
// 它是**自定义**标签，故带 nextType —— 用于"VLAN 全剥光后"指回 payload 类型。
header opaquetag_h {
    bit<16> tpid;
    bit<16> nextType;
    bit<16> tagData;
}

// **802.1Q VLAN 标签**（标准 4 字节）：TPID(16) + TCI(16) = PCP(3) | DEI(1) | VID(12)。
// 标签内部没有"下一层类型"字段（真实帧里它就在下一个标签/etype 的位置），
// 固定槽位下由下一个槽位的 TPID 或链末端的 `etype` 承接这一语义。
header vlan_h {
    bit<16> tpid;
    bit<3>  pcp;
    bit<1>  dei;
    bit<12> vid;
}

// 标签链末端承载的 ethertype（等价于真实帧里最后一个标签之后的那 2 字节）
header etype_h {
    bit<16> value;
}

header ipv4_h {
    bit<4>  version;
    bit<4>  ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<3>  flags;
    bit<13> fragOffset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> hdrChecksum;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

header udp_h {
    bit<16> srcPort;
    bit<16> dstPort;
    bit<16> length;
    bit<16> checksum;
}

header tcp_h {
    bit<16> srcPort;
    bit<16> dstPort;
    bit<32> seqNo;
    bit<32> ackNo;
    bit<4>  dataOffset;
    bit<3>  reserved;
    bit<3>  ecnBits;
    bit<6>  ctrlBits;
    bit<16> window;
    bit<16> checksum;
    bit<16> urgentPtr;
}

// 可选层各占一个固定槽位（vlan0..vlan3）；无 header stack，故写成 4 个独立实例
struct headers_t {
    ethernet_h  ethernet;
    opaquetag_h otag;
    vlan_h      vlan0;
    vlan_h      vlan1;
    vlan_h      vlan2;
    vlan_h      vlan3;
    etype_h     etype;
    ipv4_h      ipv4;
    udp_h       udp;
    tcp_h       tcp;
}

// 决策信息（内部，不进对外报文 —— 见 Deparser 的 emit 清单）
struct metadata_t {
    // 预分类（classify 写）
    bit<8>  isL3;       // 门控位：目的 MAC == 交换机 MAC 且标签链末端指向 IPv4
    bit<8>  isIpv4;
    bit<16> ipLen;      // IPv4 totalLen（非 IPv4 包该槽位为 0）
    bit<8>  dropReason;
    // 封装链解析（classify 写）
    bit<8>  otagV;      // OpaqueTag 存在
    bit<4>  vlanDepth;  // VLAN 层数 0..4
    bit<12> innerVid;   // 最内层 VLAN 的 VID（无 VLAN 时为 0）
    // mac_table 查找结果
    bit<8>  macHit;
    bit<4>  macPort;
    // route_table 查找结果
    bit<8>  rtHit;
    bit<4>  rtPort;
    bit<48> rtDstMac;   // 下一跳 MAC（编辑后的目的 MAC）
    bit<48> rtSrcMac;   // 重写后的源 MAC
    // 合并仲裁 + VLAN 增删决策（resolve 写）
    bit<8>  fwdType;    // FWD_*
    bit<16> outPort;    // 出端口位图（单播 = 独热，泛洪 = 掩码）
    bit<4>  outPortIdx; // 出端口号（泛洪 = 15）
    bit<8>  doPush;     // trunk 上联：加一层外层 VLAN（层数 < 4 才成立）
    bit<8>  doPop;      // access 用户口：剥一层外层 VLAN（层数 > 0 才成立）
}

// ---------------- parser ----------------
// ⚠️ 固定槽位：每个可选层都在**同一偏移**上无条件 extract（子集不支持变长），
//    层是否存在由槽位自身的 tpid 在 ingress 里判定 ⇒ 层数天然可选（0..4）。
parser Top(packet_in pkt, out headers_t hdr) {
    state start {
        transition parse_ethernet;
    }

    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition parse_otag;
    }

    state parse_otag {
        pkt.extract(hdr.otag);
        transition parse_vlan0;
    }

    state parse_vlan0 {
        pkt.extract(hdr.vlan0);
        transition parse_vlan1;
    }

    state parse_vlan1 {
        pkt.extract(hdr.vlan1);
        transition parse_vlan2;
    }

    state parse_vlan2 {
        pkt.extract(hdr.vlan2);
        transition parse_vlan3;
    }

    state parse_vlan3 {
        pkt.extract(hdr.vlan3);
        transition parse_etype;
    }

    state parse_etype {
        pkt.extract(hdr.etype);
        transition parse_ipv4;
    }

    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition select(hdr.ipv4.protocol) {
            PROTO_UDP : parse_udp;
            PROTO_TCP : parse_tcp;
            default   : accept;
        }
    }

    state parse_udp {
        pkt.extract(hdr.udp);
        transition accept;
    }

    state parse_tcp {
        pkt.extract(hdr.tcp);
        transition accept;
    }
}

// ---------------- ingress control ----------------
control Ingress(inout headers_t hdr, inout metadata_t meta) {
    Register(bit<32>, 8) portBytes;    // 每出端口累计字节数（resolve 条件累加）
    Counter(bit<32>, 4)  fwdCnt;       // 每类转发包计数（表 action 内，hit 自动门控）
                                       //   0=L2  1=L3  2=丢弃

    // ---- 预分类 + 封装链解析：只看报文字段，不依赖任何表 ⇒ 并行查找的前提 ----
    action classify() {
        // ① 存在性：槽位恒在，存在 ⇔ 槽位自身的 tpid 命中（层数由此天然可选）
        meta.otagV = (hdr.otag.tpid == TPID_OTAG) ? 8w1 : 8w0;
        meta.vlanDepth = ((hdr.vlan0.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan1.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan2.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan3.tpid == TPID_VLAN) ? 4w1 : 4w0);
        // ② 最内层 VLAN 的 VID（从内往外找第一个存在的槽位）
        meta.innerVid = (hdr.vlan3.tpid == TPID_VLAN) ? hdr.vlan3.vid
                      : (hdr.vlan2.tpid == TPID_VLAN) ? hdr.vlan2.vid
                      : (hdr.vlan1.tpid == TPID_VLAN) ? hdr.vlan1.vid
                      : (hdr.vlan0.tpid == TPID_VLAN) ? hdr.vlan0.vid : 12w0;
        // ③ 标签链末端指向的类型（802.1Q 内部没有该字段 ⇒ 由链末端 etype 承接）
        meta.isIpv4 = (hdr.etype.value == ET_IPV4) ? 8w1 : 8w0;
        // ④ 三层门控（读报文字段，不存在"读自己刚写的值"问题）
        meta.isL3 = ((hdr.ethernet.dstAddr == SWITCH_MAC)
                     && (hdr.etype.value == ET_IPV4)) ? 8w1 : 8w0;
        meta.ipLen = hdr.ipv4.totalLen;             // 非 IPv4 包该槽位为 0
        meta.dropReason = DROP_NONE;
        meta.macHit = 8w0;
        meta.rtHit  = 8w0;
    }

    // ---- mac_table 的动作：只写自己的决策字段，不改报文 ----
    action l2_forward(bit<4> port) {
        meta.macHit  = 8w1;
        meta.macPort = port;
        fwdCnt.count(8w0);
    }
    action l2_miss() {
        meta.macHit = 8w0;
    }

    // ---- route_table 的动作：只写自己的决策字段，不改报文 ----
    action l3_forward(bit<4> port, bit<48> nexthopMac, bit<48> srcMac) {
        meta.rtHit    = 8w1;
        meta.rtPort   = port;
        meta.rtDstMac = nexthopMac;
        meta.rtSrcMac = srcMac;
        fwdCnt.count(8w1);
    }
    action l3_miss() {
        meta.rtHit = 8w0;
    }

    // ---- 阶段 1：合并仲裁 + VLAN 增删决策（只写决策字段）----
    // ⚠️ 同一 action 内读到的是**入口快照**，故 doPush/doPop 必须由表结果
    //（rtHit/macHit，上一相位写入）直接判定，不能读本 action 刚写的 fwdType。
    action resolve() {
        meta.fwdType = (meta.rtHit == 8w1) ? FWD_L3
                     : ((meta.macHit == 8w1) ? FWD_L2 : FWD_FLOOD);
        meta.outPortIdx = (meta.rtHit == 8w1) ? meta.rtPort
                        : ((meta.macHit == 8w1) ? meta.macPort : 4w15);
        meta.outPort = (meta.rtHit == 8w1) ? (16w1 << meta.rtPort)
                     : ((meta.macHit == 8w1) ? (16w1 << meta.macPort) : PM_FLOOD);
        // trunk 上联：加一层；**满 4 层 ⇒ 饱和保护不加**（层数可选的边界情形）
        meta.doPush = ((meta.rtHit == 8w1) && (meta.vlanDepth != 4w4)) ? 8w1 : 8w0;
        // access 用户口：剥一层；**0 层 ⇒ 无可剥**
        meta.doPop = ((meta.rtHit != 8w1) && (meta.macHit == 8w1)
                      && (meta.vlanDepth != 4w0)) ? 8w1 : 8w0;
    }

    // ---- 阶段 2：报文编辑 + 统计（读 resolve 的决策结果）----
    action rewrite() {
        // ① IP 校验和增量更新（RFC 1624：HC' = ~(~HC + ~m + m')，m = 旧 TTL）
        hdr.ipv4.hdrChecksum = (meta.rtHit == 8w1 && hdr.ipv4.ttl != 8w0)
            ? ~(~hdr.ipv4.hdrChecksum
                + ~((bit<16>)hdr.ipv4.ttl)
                + ((bit<16>)hdr.ipv4.ttl - 16w1))
            : hdr.ipv4.hdrChecksum;
        // ② TTL 递减
        hdr.ipv4.ttl = (meta.rtHit == 8w1 && hdr.ipv4.ttl != 8w0)
            ? hdr.ipv4.ttl - 8w1
            : hdr.ipv4.ttl;
        // ③ 重写二层地址
        hdr.ethernet.dstAddr = (meta.rtHit == 8w1) ? meta.rtDstMac : hdr.ethernet.dstAddr;
        hdr.ethernet.srcAddr = (meta.rtHit == 8w1) ? meta.rtSrcMac : hdr.ethernet.srcAddr;

        // ④ VLAN 增删（802.1Q 4B 槽位内容搬移）：
        //    push：vlan3←vlan2、vlan2←vlan1、vlan1←vlan0、vlan0←新标签
        //    pop ：vlan0←vlan1、vlan1←vlan2、vlan2←vlan3、vlan3←0
        //    ⚠️ 同一 action 内读的是入口快照 ⇒ 整槽搬移正是所需语义。
        hdr.vlan3.tpid = (meta.doPush == 8w1) ? hdr.vlan2.tpid
                       : ((meta.doPop == 8w1) ? 16w0 : hdr.vlan3.tpid);
        hdr.vlan3.pcp = (meta.doPush == 8w1) ? hdr.vlan2.pcp
                       : ((meta.doPop == 8w1) ? 3w0 : hdr.vlan3.pcp);
        hdr.vlan3.dei = (meta.doPush == 8w1) ? hdr.vlan2.dei
                       : ((meta.doPop == 8w1) ? 1w0 : hdr.vlan3.dei);
        hdr.vlan3.vid = (meta.doPush == 8w1) ? hdr.vlan2.vid
                       : ((meta.doPop == 8w1) ? 12w0 : hdr.vlan3.vid);

        hdr.vlan2.tpid = (meta.doPush == 8w1) ? hdr.vlan1.tpid
                       : ((meta.doPop == 8w1) ? hdr.vlan3.tpid : hdr.vlan2.tpid);
        hdr.vlan2.pcp = (meta.doPush == 8w1) ? hdr.vlan1.pcp
                       : ((meta.doPop == 8w1) ? hdr.vlan3.pcp : hdr.vlan2.pcp);
        hdr.vlan2.dei = (meta.doPush == 8w1) ? hdr.vlan1.dei
                       : ((meta.doPop == 8w1) ? hdr.vlan3.dei : hdr.vlan2.dei);
        hdr.vlan2.vid = (meta.doPush == 8w1) ? hdr.vlan1.vid
                       : ((meta.doPop == 8w1) ? hdr.vlan3.vid : hdr.vlan2.vid);

        hdr.vlan1.tpid = (meta.doPush == 8w1) ? hdr.vlan0.tpid
                       : ((meta.doPop == 8w1) ? hdr.vlan2.tpid : hdr.vlan1.tpid);
        hdr.vlan1.pcp = (meta.doPush == 8w1) ? hdr.vlan0.pcp
                       : ((meta.doPop == 8w1) ? hdr.vlan2.pcp : hdr.vlan1.pcp);
        hdr.vlan1.dei = (meta.doPush == 8w1) ? hdr.vlan0.dei
                       : ((meta.doPop == 8w1) ? hdr.vlan2.dei : hdr.vlan1.dei);
        hdr.vlan1.vid = (meta.doPush == 8w1) ? hdr.vlan0.vid
                       : ((meta.doPop == 8w1) ? hdr.vlan2.vid : hdr.vlan1.vid);

        // vlan0：push 时写新标签（UPLINK_VID）
        hdr.vlan0.tpid = (meta.doPush == 8w1) ? TPID_VLAN
                       : ((meta.doPop == 8w1) ? hdr.vlan1.tpid : hdr.vlan0.tpid);
        hdr.vlan0.pcp = (meta.doPush == 8w1) ? 3w0
                       : ((meta.doPop == 8w1) ? hdr.vlan1.pcp : hdr.vlan0.pcp);
        hdr.vlan0.dei = (meta.doPush == 8w1) ? 1w0
                       : ((meta.doPop == 8w1) ? hdr.vlan1.dei : hdr.vlan0.dei);
        hdr.vlan0.vid = (meta.doPush == 8w1) ? UPLINK_VID
                       : ((meta.doPop == 8w1) ? hdr.vlan1.vid : hdr.vlan0.vid);

        // ⑤ 最外层指针（802.1Q 下即 eth.etherType，或 OpaqueTag 后的 nextType）：
        //    push ⇒ 指向 VLAN；pop 后若还有层 ⇒ 仍指向 VLAN，剥光 ⇒ 指向链末端类型
        hdr.ethernet.etherType = (meta.doPush == 8w1)
                       ? ((meta.otagV == 8w1) ? hdr.ethernet.etherType : TPID_VLAN)
                       : ((meta.doPop == 8w1)
                           ? ((meta.otagV == 8w1) ? hdr.ethernet.etherType
                              : ((meta.vlanDepth == 4w1) ? hdr.etype.value : TPID_VLAN))
                           : hdr.ethernet.etherType);
        hdr.otag.nextType = (meta.doPush == 8w1)
                       ? ((meta.otagV == 8w1) ? TPID_VLAN : hdr.otag.nextType)
                       : ((meta.doPop == 8w1)
                           ? ((meta.otagV == 8w1)
                              ? ((meta.vlanDepth == 4w1) ? hdr.etype.value : TPID_VLAN)
                              : hdr.otag.nextType)
                           : hdr.otag.nextType);

        // ⑥ TTL 耗尽标记 —— ⚠️ 此处读到的 ttl 是**本 action 入口快照**（编辑前）
        meta.dropReason = (meta.rtHit == 8w1 && hdr.ipv4.ttl <= 8w1) ? DROP_TTL : DROP_NONE;
        // ⑦ 每端口字节统计：fwdType/outPortIdx 是上一相位（resolve）写出的，可安全读取
        portBytes.write(meta.outPortIdx[2:0],
            portBytes.read(meta.outPortIdx[2:0]) +
            ((meta.fwdType == FWD_L2 || meta.fwdType == FWD_L3)
                ? (bit<32>)meta.ipLen : 32w0));
    }

    // ---- TTL 耗尽：丢弃（静态表；resolve 之后才能看到编辑后的 TTL）----
    action drop_ttl() {
        meta.fwdType = FWD_DROP;
        meta.dropReason = DROP_TTL;
        fwdCnt.count(8w2);
    }

    action nop() { }

    // ---- 表 1：L2 目的 MAC 表（runtime：控制面可写）----
    // key 增加**内层 VID** ⇒ 二层转发按 VLAN 隔离（表项示例：
    //   (48w0x001122334455, 12w10) → l2_forward(4w1)）
    // p4c: table mac_table runtime size=8 latency=1-4
    table mac_table {
        key = {
            hdr.ethernet.dstAddr : exact;
            meta.innerVid        : exact;
        }
        actions = {
            l2_forward;
            l2_miss;
        }
        const entries = {
            default : l2_miss();     // 未命中 ⇒ resolve 判泛洪
        }
    }

    // ---- 表 2：L3 路由表（runtime；key 含预分类位 ⇒ 只有"上三层"的包才可能命中）----
    // p4c: table route_table runtime size=16 latency=2-8
    table route_table {
        key = {
            meta.isL3        : exact;   // 预分类门控（classify 写，不依赖任何表）
            hdr.ipv4.dstAddr : exact;   // /32 主机路由（子集无 LPM）
        }
        actions = {
            l3_forward;
            l3_miss;
        }
        const entries = {
            default : l3_miss();
        }
    }

    // ---- 表 3：TTL 守卫（静态融合：防御逻辑，控制面不写）----
    table ttl_guard {
        key = {
            meta.fwdType : exact;
            hdr.ipv4.ttl : exact;
        }
        actions = {
            drop_ttl;
            nop;
        }
        const entries = {
            8w2, 8w0 : drop_ttl();
            default  : nop();
        }
    }

    apply {
        classify();
        // 并行查找组：下面两张 runtime 表同拍发 key / 同拍收 rsp（工具链校验表间无依赖：
        // mac_table 的 key 只读报文+预分类出的 innerVid，route_table 的 key 只读
        // 报文+预分类位，互不依赖对方写集）
        // p4c: lookup-group g0 = mac_table, route_table
        mac_table.apply();     // ┐ 同拍发 key
        route_table.apply();   // ┘ 同拍收 rsp 并应用 —— 查找延时 2 拍（串行需 4 拍）
        resolve();             // 合并仲裁 + VLAN 增删决策（含层数边界）
        rewrite();             // 报文编辑（含 VLAN 搬移）+ 统计
        ttl_guard.apply();     // TTL 守卫
    }
}

// ---------------- 报文重组（deparser）----------------
// 按 emit 声明序把编辑后的 header 重组为对外报文（通道 pkt_out）。
// 子集语义：无条件 emit；metadata 不进报文（它是内部决策信息）。
// ⚠️ 不存在的可选层在入包侧已归一化为 0，出包侧同样是 0（外部按 tpid 判定存在）。
control Deparser(packet_out pkt, in headers_t hdr) {
    pkt.emit(hdr.ethernet);
    pkt.emit(hdr.otag);
    pkt.emit(hdr.vlan0);
    pkt.emit(hdr.vlan1);
    pkt.emit(hdr.vlan2);
    pkt.emit(hdr.vlan3);
    pkt.emit(hdr.etype);
    pkt.emit(hdr.ipv4);
    pkt.emit(hdr.udp);
    pkt.emit(hdr.tcp);
}
