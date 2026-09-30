// demo12：二层/三层混合交换机 —— 并行查找 + 合并仲裁 + 报文编辑 + 报文重组
//
// 与 v1 对比的三个升级（对应郝宇的三条要求）：
//   1. **报文编辑/重组显式化**：新增 `control Deparser(packet_out pkt, in hdr)`，
//      按 emit 声明序把编辑后的 header 重组为对外报文（通道 pkt_out）。
//      字段级编辑集中在 resolve action 的「报文编辑」段（改下一跳/源 MAC、
//      TTL 递减、IP 校验和 RFC 1624 增量更新），读表决策结果、三元门控改包。
//   2. **查找表外置**：mac_table / route_table 声明为 runtime 表 ⇒ 生成
//      tbl_<表名>_key / tbl_<表名>_rsp 通道，存储与匹配在外部表模块（控制面可写）。
//      ttl_guard 是防御逻辑不是配置表，保持静态融合（两种形态共存）。
//   3. **并行查找分组**：`// p4c: lookup-group g0 = mac_table, route_table`
//      两张表**同拍**发出 key、同拍收回 rsp —— 查找延时从 2×2 拍降到 2 拍。
//      前提是表间无依赖：classify 预先算好门控位 meta.isL3（只看报文字段），
//      两表的 key 都不依赖对方的写集（工具链会校验，违反即报错）。
//
// 处理流水（拍数按 proc 相位）：
//   parser(Ethernet→IPv4→UDP/TCP)
//   → classify()          预分类（isL3 门控位）+ 清理决策字段
//   → mac_table  ┐        runtime 表，并行查找组 g0：同拍发 key / 同拍收 rsp
//   → route_table┘        命中结果写入各自的决策字段（macHit/macPort、rtHit/rtPort/…）
//   → resolve()           合并仲裁（L3 > L2 > 泛洪），只写决策字段
//   → rewrite()           **报文编辑**（MAC 重写/TTL 递减/校验和增量）+ 字节统计
//   → ttl_guard           静态表：路由后 TTL == 0 ⇒ 丢弃
//   → Deparser            emit 序重组报文（ethernet → ipv4 → udp → tcp）
//
// 子集约束的应对（工具链边界见 docs/Demo12-L2L3交换机.md §2）：
//   - 无 if/else ⇒ 决策合并与报文编辑全部用三元表达式 + "meta 参与表 key" 门控
//   - 无 apply 内局部变量 ⇒ 旧 TTL 用表达式复用（先算校验和再减 TTL，顺序不能反）
//   - 表 key 只能是字段路径 ⇒ L3 是 /32 主机路由（无 LPM）
//   - 无 header stack / setValid ⇒ 无 VLAN 增删；emit 无条件（invalid header 输出全 0）
#include <core.p4>

// ---------------- 协议常量 ----------------
const bit<16> ET_IPV4   = 16w0x0800;
const bit<16> ET_ARP    = 16w0x0806;
const bit<8>  PROTO_UDP = 8w17;
const bit<8>  PROTO_TCP = 8w6;

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

// 端口 0 = 上联口（L3 出口），端口 1..3 = 用户接入口；outPort 统一用位图表达
const bit<16> PM_FLOOD = 16w0x000e;             // 泛洪掩码：端口 1/2/3（不含上联）

// ---------------- headers ----------------
header ethernet_h {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
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

struct headers_t {
    ethernet_h ethernet;
    ipv4_h     ipv4;
    udp_h      udp;
    tcp_h      tcp;
}

// 决策信息（内部，不进对外报文 —— 见 Deparser 的 emit 清单）
struct metadata_t {
    // 预分类（classify 写）
    bit<8>  isL3;       // 门控位：目的 MAC == 交换机 MAC ⇒ 该查路由表
    bit<16> ipLen;      // IPv4 totalLen（非 IPv4 包为 0）
    bit<8>  dropReason;
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
parser Top(packet_in pkt, out headers_t hdr) {
    state start {
        transition parse_ethernet;
    }

    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            ET_IPV4 : parse_ipv4;
            ET_ARP  : accept;          // ARP 不做三层处理，按目的 MAC 走二层转发
            default : accept;
        }
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

    // ---- 预分类：只看报文字段，不依赖任何表 ⇒ 并行查找的前提 ----
    action classify() {
        meta.isL3 = (hdr.ethernet.dstAddr == SWITCH_MAC) ? 8w1 : 8w0;
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
    // 下一跳 MAC 作为决策数据带出来，编辑统一在 resolve 做（报文编辑显式化）
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
    //（W1 顺序组合语义）—— 所以统计/编辑必须放到下一个 action（rewrite），
    // 靠相位边界更新快照，才能读到这里写出的 fwdType/outPortIdx。
    action resolve() {
        meta.fwdType = (meta.rtHit == 8w1) ? FWD_L3
                     : ((meta.macHit == 8w1) ? FWD_L2 : FWD_FLOOD);
        meta.outPortIdx = (meta.rtHit == 8w1) ? meta.rtPort
                        : ((meta.macHit == 8w1) ? meta.macPort : 4w15);
        meta.outPort = (meta.rtHit == 8w1) ? (16w1 << meta.rtPort)
                     : ((meta.macHit == 8w1) ? (16w1 << meta.macPort) : PM_FLOOD);
    }

    // ---- 阶段 2：报文编辑 + 统计（读 resolve 的决策结果）----
    // 编辑门控：仅 L3 路由命中才改包；ttl=0 时不编辑（防 8 位下溢），由 ttl_guard 拦下。
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
        // ④ TTL 耗尽标记 —— ⚠️ 此处读到的 ttl 是**本 action 入口快照**（编辑前），
        //    旧 TTL ≤ 1 ⇔ 减后为 0 或已耗尽 ⇒ 交给 ttl_guard 拦下
        meta.dropReason = (meta.rtHit == 8w1 && hdr.ipv4.ttl <= 8w1) ? DROP_TTL : DROP_NONE;
        // ⑤ 每端口字节统计：fwdType/outPortIdx 是上一相位（resolve）写出的，可安全读取
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
    // ⚠️ runtime 表只固化结构（key/actId/args 位宽），表项由控制面经写接口下发
    //（示例条目：48w0x001122334455 → l2_forward(4w1)，见 docs/Demo12-L2L3交换机.md §5）
    // p4c: table mac_table runtime size=8
    table mac_table {
        key = {
            hdr.ethernet.dstAddr : exact;
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
    // 控制面下发表项示例：meta.isL3=1 + 目的 IP + 下一跳 MAC/端口
    // p4c: table route_table runtime size=16
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
        // mac_table 的 key 只读报文、route_table 的 key 只读报文+预分类位，互不依赖对方写集）
        // p4c: lookup-group g0 = mac_table, route_table
        mac_table.apply();     // ┐ 同拍发 key
        route_table.apply();   // ┘ 同拍收 rsp 并应用 —— 查找延时 2 拍（串行需 4 拍）
        resolve();             // 合并仲裁（决策字段）
        rewrite();             // 报文编辑 + 统计
        ttl_guard.apply();     // TTL 守卫
    }
}

// ---------------- 报文重组（deparser）----------------
// 按 emit 声明序把编辑后的 header 重组为对外报文（通道 pkt_out）。
// 子集语义：无条件 emit；metadata 不进报文（它是内部决策信息）。
control Deparser(packet_out pkt, in headers_t hdr) {
    pkt.emit(hdr.ethernet);
    pkt.emit(hdr.ipv4);
    pkt.emit(hdr.udp);
    pkt.emit(hdr.tcp);
}
