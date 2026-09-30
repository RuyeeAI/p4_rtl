// demo12：二层/三层混合交换机 —— L2 转发 + L3 路由 + 报文编辑
//
// 覆盖点：
//   - L2：目的 MAC 精确匹配 → 指定端口转发 / 上送三层 / 未知单播泛洪
//   - L3：目的 IP 精确匹配（/32 主机路由）→ 下一跳 + 出端口
//   - 报文编辑三件套：重写目的/源 MAC、TTL 递减、IP 头校验和增量更新（RFC 1624）
//   - 端口位图表达出端口（单播 = 独热，泛洪 = 掩码），出端口索引供状态单元寻址
//   - TTL 耗尽丢弃、每端口字节统计（Register 读改写）、每类转发计数（Counter）
//
// 子集约束下的两个设计要点（务必先读）：
//   1. **没有 if/else 语句** ⇒ 用"元数据参与表 key"来实现门控：
//      route_table 的 key 含 meta.fwdType，只有 L2 判定为"上三层"（FWD_L3）的包
//      才可能命中路由条目；L2/泛洪的包 key 不匹配，落到 default nop 直通。
//      这样下游表就不会误改报文，等价于"条件执行"。
//   2. **字段单一写者** ⇒ 每个字段只由一组互斥的 action 写：
//      meta.ipLen/dropReason 归 classify；fwdType/outPort/outPortIdx 由
//      mac_table 的三个 action（互斥）→ route_table 的 l3_forward（条件覆盖）
//      → ttl_guard 的 drop_ttl（条件覆盖）依次演进，形成流水状态机语义。
//
// 子集限制（本 demo 未使用，属工具链已知缺口）：
//   - 不支持 lpm/ternary ⇒ 路由只能是 /32 主机路由，无最长前缀匹配
//   - 不支持 header stack / setValid ⇒ 无法插入或剥离 VLAN tag（变长封装会
//     使同一 header 在不同路径上的字节偏移不同，工具链直接报错）
//   - 不支持 apply 内局部变量 ⇒ 旧 TTL 只能用表达式复用，不能存临时变量
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
const bit<48> SWITCH_MAC = 48w0x020000000001;   // 三层口/交换机 MAC，也是重写后的源 MAC
const bit<48> NH_MAC_1   = 48w0x001122334455;   // 10.0.0.1 的下一跳 MAC
const bit<48> NH_MAC_2   = 48w0x00aabbccddee;   // 10.0.0.2 的下一跳 MAC
const bit<48> NH_MAC_3   = 48w0x00deadbeef01;   // 10.0.0.3 的下一跳 MAC

// 端口 0 = 上联口（L3 出口），端口 1..3 = 用户接入口
const bit<16> PM_UPLINK  = 16w0x0001;           // 端口 0 的位图
const bit<16> PM_FLOOD   = 16w0x000e;           // 端口 1/2/3 的位图（不含上联）

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

// 转发决策与统计的载体（也是对外可观测的输出）
struct metadata_t {
    bit<8>  fwdType;      // FWD_*：本包走哪条转发路径
    bit<16> outPort;      // 出端口位图（单播 = 独热，泛洪 = 掩码）
    bit<4>  outPortIdx;   // 出端口号（泛洪时无意义），供状态单元寻址
    bit<8>  dropReason;   // DROP_*
    bit<16> ipLen;        // IPv4 totalLen（非 IPv4 包为 0），仅供字节统计
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
    Register(bit<32>, 8) portBytes;    // 每出端口累计字节数（按 meta.ipLen 累加）
    Counter(bit<32>, 4)  fwdCnt;       // 每类转发包计数：0=L2 1=L3 2=丢弃 3=泛洪

    // ---- 报文分类：只负责 ipLen / dropReason 两个字段 ----
    action classify() {
        meta.ipLen = hdr.ipv4.totalLen;   // 非 IPv4 包该槽位为 0
        meta.dropReason = DROP_NONE;
    }

    // ---- 二层转发：按目的 MAC 从指定端口送出 ----
    action l2_forward(bit<4> port) {
        meta.fwdType = FWD_L2;
        meta.outPortIdx = port;
        meta.outPort = 16w1 << port;
        portBytes.write(port[2:0], portBytes.read(port[2:0]) + meta.ipLen);
        fwdCnt.count(8w0);
    }

    // ---- 未知单播泛洪：送到 mask 指定的全部端口 ----
    action flood(bit<16> mask) {
        meta.fwdType = FWD_FLOOD;
        meta.outPortIdx = 4w15;           // 15 = 无单一出端口（位图代表多个）
        meta.outPort = mask;
        fwdCnt.count(8w3);
    }

    // ---- 上送三层：只置转发类型，出端口交由路由动作决定 ----
    action to_l3() {
        meta.fwdType = FWD_L3;
    }

    // ---- 三层路由 + 报文编辑 ----
    // 编辑三件套：目的 MAC 换成下一跳、源 MAC 换成本机、TTL 递减且增量更新校验和
    action l3_forward(bit<4> port, bit<48> nexthopMac, bit<48> srcMac) {
        meta.fwdType = FWD_L3;
        meta.outPortIdx = port;
        meta.outPort = 16w1 << port;

        // ① 先更新校验和 —— 此刻 hdr.ipv4.ttl 还是旧值
        //    RFC 1624 增量式：HC' = ~(~HC + ~m + m')，m = 旧 TTL 零扩到 16 位
        hdr.ipv4.hdrChecksum = ~(~hdr.ipv4.hdrChecksum
                               + ~((bit<16>)hdr.ipv4.ttl)
                               + ((bit<16>)hdr.ipv4.ttl - 16w1));
        // ② 再递减 TTL（顺序不能反：反了上面读到的就是新值）
        hdr.ipv4.ttl = hdr.ipv4.ttl - 8w1;
        // ③ 重写二层地址
        hdr.ethernet.dstAddr = nexthopMac;
        hdr.ethernet.srcAddr = srcMac;

        portBytes.write(port[2:0], portBytes.read(port[2:0]) + meta.ipLen);
        fwdCnt.count(8w1);
    }

    // ---- TTL 耗尽：丢弃（在三层编辑之后判定，故 TTL=1 的包在此被拦下）----
    action drop_ttl() {
        meta.fwdType = FWD_DROP;
        meta.dropReason = DROP_TTL;
        fwdCnt.count(8w2);
    }

    action nop() { }

    // ---- 表 1：L2 目的 MAC 表（静态融合，编译期展开）----
    table mac_table {
        key = {
            hdr.ethernet.dstAddr : exact;
        }
        actions = {
            l2_forward;
            to_l3;
            flood;
        }
        const entries = {
            48w0x001122334455 : l2_forward(4w1);
            48w0x00aabbccddee : l2_forward(4w2);
            48w0x00deadbeef01 : l2_forward(4w3);
            SWITCH_MAC        : to_l3();          // 目的为本机 MAC ⇒ 上三层
            default           : flood(PM_FLOOD);  // 未知单播 ⇒ 泛洪
        }
    }

    // ---- 表 2：L3 路由表（静态融合；key 含 fwdType 起门控作用）----
    // 只有 mac_table 判为"上三层"的包（fwdType == FWD_L3）才可能命中路由条目，
    // 其余包的 key 第二段是合法 IP 但第一段不匹配，落到 default nop 直通。
    table route_table {
        key = {
            meta.fwdType     : exact;
            hdr.ipv4.dstAddr : exact;
        }
        actions = {
            l3_forward;
            nop;
        }
        const entries = {
            8w2, 32w0x0a000001 : l3_forward(4w0, NH_MAC_1, SWITCH_MAC);
            8w2, 32w0x0a000002 : l3_forward(4w0, NH_MAC_2, SWITCH_MAC);
            8w2, 32w0x0a000003 : l3_forward(4w0, NH_MAC_3, SWITCH_MAC);
            default            : nop();
        }
    }

    // ---- 表 3：TTL 守卫（路由后 TTL 归零 ⇒ 丢弃）----
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
        mac_table.apply();
        route_table.apply();
        ttl_guard.apply();
    }
}
