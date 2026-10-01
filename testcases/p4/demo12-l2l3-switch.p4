// demo12：二层/三层混合交换机 —— 并行查找 + 合并仲裁 + 报文编辑 + 报文重组
//          **v4：可选封装**（1 层 OpaqueTag + 最多 4 层 VLAN）
//
// 与 v3 的差异（本次新增）：报文格式支持**可选存在**的封装层：
//   Ethernet → [OpaqueTag] → [VLAN ×0..4] → IPv4 → (UDP | TCP)
// 并在 ingress 里真正用起来：
//   - classify 解析封装链：otagV / vlanDepth / innerVid / isIpv4
//   - mac_table 按 **(目的 MAC, 内层 VID)** 查找（二层转发的标准做法）
//   - rewrite 做 VLAN 编辑：L3 上联口**打外层 VLAN**（trunk）、
//     L2 用户口**剥外层 VLAN**（access）
//
// ⚠️ 子集限制与应对（无 header stack / setValid / 变长 extract）：
//   「可选」封装按**固定槽位**实现 —— 每个可选层占一个固定偏移的槽位，
//   不存在时槽位为全 0，存在与否由该槽位自身的 TPID 判定。这要求入包缓冲是
//   **归一化到最坏布局**的（缺的层补 0），也正是多数交换芯片内部报文总线的形态。
//   代价：不存在的层仍占带宽；收益：parser 偏移恒定（子集硬约束）。
//   每个可选层自带 `nextType`（下一层的 ethertype），使存在链可在固定偏移下判定。
//
// 处理流水（拍数按 proc 相位）：
//   parser(eth → otag → vlan0..3 → ipv4 → udp/tcp，固定槽位)
//   → classify()          封装链解析 + 预分类（isL3）+ 清理决策字段
//   → mac_table  ┐        runtime 表，并行查找组 g0：同拍发 key / 同拍收 rsp
//   → route_table┘        命中结果写入各自的决策字段
//   → resolve()           合并仲裁（L3 > L2 > 泛洪），只写决策字段
//   → rewrite()           报文编辑（MAC / TTL / 校验和 / **VLAN 增删**）+ 字节统计
//   → ttl_guard           静态表：路由后 TTL == 0 ⇒ 丢弃
//   → Deparser            emit 序重组报文
//
// 其余子集约束的应对（工具链边界见 docs/Demo12-L2L3交换机.md §2）：
//   - 无 if/else ⇒ 决策合并与报文编辑全部用三元表达式 + "meta 参与表 key" 门控
//   - 无 apply 内局部变量 ⇒ 旧 TTL 用表达式复用（先算校验和再减 TTL，顺序不能反）
//   - 表 key 只能是字段路径 ⇒ L3 是 /32 主机路由（无 LPM）
#include <core.p4>

// p4c: pkt-window 800
//   固定槽位最坏布局（按**字段字节对齐**累加，见下）= eth14B + otag8B + vlan×4 32B
//   + ipv4 22B + tcp 22B = 98 字节 = 784 位

// ---------------- 协议常量 ----------------
const bit<16> ET_IPV4   = 16w0x0800;
const bit<16> ET_ARP    = 16w0x0806;
const bit<8>  PROTO_UDP = 8w17;
const bit<8>  PROTO_TCP = 8w6;

const bit<16> TPID_VLAN = 16w0x8100;   // VLAN 槽位标识
const bit<16> TPID_OTAG = 16w0x8200;   // OpaqueTag 槽位标识（带内元数据：源端口等）

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
const bit<16> UPLINK_VID = 16w100;              // 上联口出方向打的外层 VLAN

// ---------------- headers ----------------
header ethernet_h {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

// OpaqueTag：交换机内部带内标签（源端口 / 入 VLAN / 时间戳等，Demo 带 32 位数据）
// 固定槽位口径：tpid = TPID_OTAG 表示本层存在；nextType 指向下一层。
// ⚠️ 报文窗口按**字段**逐字节对齐累加（M3 子集：偏移以字节为单位），
//    故字段宽度都取 8 的倍数，避免槽位里出现无用填充字节。
header opaquetag_h {
    bit<16> tpid;
    bit<16> nextType;
    bit<32> tagData;
}

// VLAN 标签：tpid + nextType + pcp/dei/vid。nextType 是子集下判定"后面还有没有
// VLAN"的唯一手段（无法变长解析）。pcp/dei/vid 各占一字节（同上：字段字节对齐），
// 单槽 8 字节；这是本 Demo 为"可选封装 + 固定偏移"付出的带宽代价。
header vlan_h {
    bit<16> tpid;
    bit<16> nextType;
    bit<8>  pcp;
    bit<8>  dei;
    bit<16> vid;
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
    ipv4_h      ipv4;
    udp_h       udp;
    tcp_h       tcp;
}

// 决策信息（内部，不进对外报文 —— 见 Deparser 的 emit 清单）
struct metadata_t {
    // 预分类（classify 写）
    bit<8>  isL3;       // 门控位：目的 MAC == 交换机 MAC 且是 IPv4 ⇒ 该查路由表
    bit<8>  isIpv4;     // 封装链末端指向 IPv4
    bit<16> ipLen;      // IPv4 totalLen（非 IPv4 包该槽位为 0）
    bit<8>  dropReason;
    // 封装链解析（classify 写）
    bit<8>  otagV;      // OpaqueTag 存在
    bit<4>  vlanDepth;  // VLAN 层数 0..4
    bit<16> innerVid;   // 最内层 VLAN 的 VID（无 VLAN 时为 0）
    // mac_table 查找结果
    bit<8>  macHit;
    bit<4>  macPort;
    // route_table 查找结果
    bit<8>  rtHit;
    bit<4>  rtPort;
    bit<48> rtDstMac;   // 下一跳 MAC（编辑后的目的 MAC）
    bit<48> rtSrcMac;   // 重写后的源 MAC
    // 合并仲裁（resolve 写）
    bit<8>  fwdType;    // FWD_*
    bit<16> outPort;    // 出端口位图（单播 = 独热，泛洪 = 掩码）
    bit<4>  outPortIdx; // 出端口号（泛洪 = 15）
}

// ---------------- parser ----------------
// ⚠️ 固定槽位：每个可选层都在**同一偏移**上无条件 extract（子集不支持变长），
//    层是否存在由槽位自身的 tpid 在 ingress 里判定。
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
        // ① 封装链存在性：槽位恒在，存在 ⇔ 槽位自身的 tpid 命中
        meta.otagV = (hdr.otag.tpid == TPID_OTAG) ? 8w1 : 8w0;
        meta.vlanDepth = ((hdr.vlan0.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan1.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan2.tpid == TPID_VLAN) ? 4w1 : 4w0)
                       + ((hdr.vlan3.tpid == TPID_VLAN) ? 4w1 : 4w0);
        // ② 最内层 VLAN 的 VID（从内层往外找第一个存在的槽位）
        meta.innerVid = (hdr.vlan3.tpid == TPID_VLAN) ? hdr.vlan3.vid
                      : (hdr.vlan2.tpid == TPID_VLAN) ? hdr.vlan2.vid
                      : (hdr.vlan1.tpid == TPID_VLAN) ? hdr.vlan1.vid
                      : (hdr.vlan0.tpid == TPID_VLAN) ? hdr.vlan0.vid : 16w0;
        // ③ 封装链末端的 nextType（最内层存在的标签指向的类型；无标签则 etherType）
        meta.isIpv4 = ((((hdr.vlan3.tpid == TPID_VLAN) ? hdr.vlan3.nextType
                       : (hdr.vlan2.tpid == TPID_VLAN) ? hdr.vlan2.nextType
                       : (hdr.vlan1.tpid == TPID_VLAN) ? hdr.vlan1.nextType
                       : (hdr.vlan0.tpid == TPID_VLAN) ? hdr.vlan0.nextType
                       : (hdr.otag.tpid  == TPID_OTAG) ? hdr.otag.nextType
                       : hdr.ethernet.etherType)) == ET_IPV4) ? 8w1 : 8w0;
        // ④ 三层门控：目的 MAC == 交换机 MAC 且确实是 IPv4
        // ⚠️ 不能写 `meta.isIpv4 == 8w1` —— 同一 action 内读自己刚写的字段，
        //    读到的是**入口快照**（W1 顺序组合语义），恒为 0 ⇒ isL3 永远不成立。
        //    必须把封装链判定**内联**到本表达式里。
        meta.isL3 = ((hdr.ethernet.dstAddr == SWITCH_MAC)
                     && ((((hdr.vlan3.tpid == TPID_VLAN) ? hdr.vlan3.nextType
                         : (hdr.vlan2.tpid == TPID_VLAN) ? hdr.vlan2.nextType
                         : (hdr.vlan1.tpid == TPID_VLAN) ? hdr.vlan1.nextType
                         : (hdr.vlan0.tpid == TPID_VLAN) ? hdr.vlan0.nextType
                         : (hdr.otag.tpid  == TPID_OTAG) ? hdr.otag.nextType
                         : hdr.ethernet.etherType)) == ET_IPV4)) ? 8w1 : 8w0;
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

    // ---- 阶段 1：合并仲裁（只写决策字段）----
    // 决策优先级：L3 命中 > L2 命中 > 泛洪（两条查找并行发出，必须在此合并）。
    // ⚠️ 同一 action 内后一条语句读到的是**入口快照**，不是本 action 前面刚写的值
    //（W1 顺序组合语义）—— 所以统计/编辑必须放到下一个 action（rewrite）。
    action resolve() {
        meta.fwdType = (meta.rtHit == 8w1) ? FWD_L3
                     : ((meta.macHit == 8w1) ? FWD_L2 : FWD_FLOOD);
        meta.outPortIdx = (meta.rtHit == 8w1) ? meta.rtPort
                        : ((meta.macHit == 8w1) ? meta.macPort : 4w15);
        meta.outPort = (meta.rtHit == 8w1) ? (16w1 << meta.rtPort)
                     : ((meta.macHit == 8w1) ? (16w1 << meta.macPort) : PM_FLOOD);
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

        // ④ VLAN 编辑（固定槽位 ⇒ 增删 = 槽位内容搬移，全部用三元门控）
        //    L3 走**上联口**（trunk）⇒ 打外层 VLAN（UPLINK_VID）；
        //    L2 走**用户口**（access）⇒ 剥外层 VLAN（若存在）。
        //    ⚠️ 同一 action 内读的是入口快照 ⇒ 整槽搬移正是我们需要的语义：
        //       push：vlan3←vlan2、vlan2←vlan1、vlan1←vlan0、vlan0←新标签
        //       pop ：vlan0←vlan1、vlan1←vlan2、vlan2←vlan3、vlan3←0
        //    前者需要"上一层指向的类型"（prevType），后者需要"被剥层的 nextType"。
        hdr.vlan3.tpid = ((meta.fwdType == FWD_L3) ? hdr.vlan2.tpid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? 16w0 : hdr.vlan3.tpid));
        hdr.vlan3.nextType = ((meta.fwdType == FWD_L3) ? hdr.vlan2.nextType
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? 16w0 : hdr.vlan3.nextType));
        hdr.vlan3.pcp = ((meta.fwdType == FWD_L3) ? hdr.vlan2.pcp
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? 8w0 : hdr.vlan3.pcp));
        hdr.vlan3.dei = ((meta.fwdType == FWD_L3) ? hdr.vlan2.dei
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? 8w0 : hdr.vlan3.dei));
        hdr.vlan3.vid = ((meta.fwdType == FWD_L3) ? hdr.vlan2.vid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? 16w0 : hdr.vlan3.vid));

        hdr.vlan2.tpid = ((meta.fwdType == FWD_L3) ? hdr.vlan1.tpid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan3.tpid : hdr.vlan2.tpid));
        hdr.vlan2.nextType = ((meta.fwdType == FWD_L3) ? hdr.vlan1.nextType
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan3.nextType : hdr.vlan2.nextType));
        hdr.vlan2.pcp = ((meta.fwdType == FWD_L3) ? hdr.vlan1.pcp
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan3.pcp : hdr.vlan2.pcp));
        hdr.vlan2.dei = ((meta.fwdType == FWD_L3) ? hdr.vlan1.dei
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan3.dei : hdr.vlan2.dei));
        hdr.vlan2.vid = ((meta.fwdType == FWD_L3) ? hdr.vlan1.vid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan3.vid : hdr.vlan2.vid));

        hdr.vlan1.tpid = ((meta.fwdType == FWD_L3) ? hdr.vlan0.tpid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan2.tpid : hdr.vlan1.tpid));
        hdr.vlan1.nextType = ((meta.fwdType == FWD_L3) ? hdr.vlan0.nextType
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan2.nextType : hdr.vlan1.nextType));
        hdr.vlan1.pcp = ((meta.fwdType == FWD_L3) ? hdr.vlan0.pcp
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan2.pcp : hdr.vlan1.pcp));
        hdr.vlan1.dei = ((meta.fwdType == FWD_L3) ? hdr.vlan0.dei
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan2.dei : hdr.vlan1.dei));
        hdr.vlan1.vid = ((meta.fwdType == FWD_L3) ? hdr.vlan0.vid
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan2.vid : hdr.vlan1.vid));

        // vlan0：push 时写新标签（nextType = 原来上一层指向的类型，vid = UPLINK_VID）
        hdr.vlan0.tpid = ((meta.fwdType == FWD_L3) ? TPID_VLAN
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan1.tpid : hdr.vlan0.tpid));
        hdr.vlan0.nextType = ((meta.fwdType == FWD_L3)
                       ? ((hdr.otag.tpid == TPID_OTAG) ? hdr.otag.nextType : hdr.ethernet.etherType)
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan1.nextType : hdr.vlan0.nextType));
        hdr.vlan0.pcp = ((meta.fwdType == FWD_L3) ? 8w0
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan1.pcp : hdr.vlan0.pcp));
        hdr.vlan0.dei = ((meta.fwdType == FWD_L3) ? 8w0
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan1.dei : hdr.vlan0.dei));
        hdr.vlan0.vid = ((meta.fwdType == FWD_L3) ? UPLINK_VID
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0) ? hdr.vlan1.vid : hdr.vlan0.vid));

        // 最外层的"下一层类型"指针：push ⇒ 指向 VLAN；pop ⇒ 指向被剥层的 nextType
        hdr.ethernet.etherType = ((meta.fwdType == FWD_L3)
                       ? ((hdr.otag.tpid == TPID_OTAG) ? hdr.ethernet.etherType : TPID_VLAN)
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0)
                           ? ((hdr.otag.tpid == TPID_OTAG) ? hdr.ethernet.etherType : hdr.vlan0.nextType)
                           : hdr.ethernet.etherType));
        hdr.otag.nextType = ((meta.fwdType == FWD_L3)
                       ? ((hdr.otag.tpid == TPID_OTAG) ? TPID_VLAN : hdr.otag.nextType)
                       : ((meta.fwdType == FWD_L2 && meta.vlanDepth != 4w0)
                           ? ((hdr.otag.tpid == TPID_OTAG) ? hdr.vlan0.nextType : hdr.otag.nextType)
                           : hdr.otag.nextType));

        // ⑤ TTL 耗尽标记 —— ⚠️ 此处读到的 ttl 是**本 action 入口快照**（编辑前）
        meta.dropReason = (meta.rtHit == 8w1 && hdr.ipv4.ttl <= 8w1) ? DROP_TTL : DROP_NONE;
        // ⑥ 每端口字节统计：fwdType/outPortIdx 是上一相位（resolve）写出的，可安全读取
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
        resolve();             // 合并仲裁（决策字段）
        rewrite();             // 报文编辑（含 VLAN 增删）+ 统计
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
    pkt.emit(hdr.ipv4);
    pkt.emit(hdr.udp);
    pkt.emit(hdr.tcp);
}
