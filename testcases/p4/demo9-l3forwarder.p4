// demo9：L2/L3 混合转发器 —— 综合特性覆盖最全的 demo（parser + control 单文件组装 Top）
// 覆盖点：
//   - 3 级 parser 状态机：Ethernet → select(IPv4/ARP) → select(UDP/TCP)，5 状态 2 select
//     （M3 子集要求各状态固定字节偏移，VLAN 等变路径封装不可线性串联——见缺口分析 §1）
//   - W0 顶层 const：协议常量声明，parser select 分支与表项内按名引用
//   - 静态融合表（L2 目的 MAC）+ 运行时表（L3 目的 IP，size=8）共存
//   - Register（每端口字节统计）/ Counter（每类别包计数）状态单元
//   - 表达式全家桶：切片、++ 拼接字节交换、移位、三元、一元 !、一元负号、读改写
#include <core.p4>

// W0：顶层 const（协议常量表），select 分支 / 表项 / 表达式均按名引用
const bit<16> ET_IPV4  = 16w0x0800;
const bit<16> ET_ARP   = 16w0x0806;
const bit<8>  PROTO_UDP = 8w17;
const bit<8>  PROTO_TCP = 8w6;

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

struct metadata_t {
    bit<16> normPort;
    bit<8>  cls;
    bit<8>  dropFlag;
    bit<8>  badVer;
    bit<8>  ecnQ;
    bit<16> flowHash;
    bit<16> swapId;
}

parser Top(packet_in pkt, out headers_t hdr) {
    state start {
        transition parse_ethernet;
    }

    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            ET_IPV4 : parse_ipv4;
            ET_ARP  : accept;          // const 引用（W0），ARP 直接上交
            default : accept;
        }
    }

    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition select(hdr.ipv4.protocol) {
            PROTO_UDP : parse_udp;     // const 引用（W0）
            PROTO_TCP : parse_tcp;
            default : accept;
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

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    Register(bit<16>, 8) portBytes;    // 每出端口字节统计（低 3 位端口索引）
    Counter(bit<32>, 4) classPkts;     // 每转发类别包计数

    // 字段单一写者原则（W1 值环境前的顺序组合语义：后写语句/表的 default 直通
    // 读输入快照，会覆盖先写值——见缺口分析）：classify/l2_fwd/acl 各自独占字段。
    //
    // 分类：版本校验（W0 一元!）+ ECN 标记提取（切片 + 三元）+ 流哈希混淆
    //（W0 一元负号，模 2^16 加法逆元）+ ID 字节交换（++ 拼接）
    action classify() {
        meta.badVer = !(hdr.ipv4.version == 4w4);
        meta.ecnQ = (hdr.ipv4.diffserv[5:4] == 2w3) ? 8w1 : 8w0;
        meta.flowHash = -((hdr.ipv4.srcAddr[15:0] ^ hdr.ipv4.dstAddr[15:0])
                        + hdr.ipv4.identification);
        meta.swapId = hdr.ipv4.identification[7:0] ++ hdr.ipv4.identification[15:8];
    }

    // 转发（l2_fwd 独占 normPort/cls/ttl）：改写出口 + TTL 递减 + 状态统计（读改写 Register / Counter）
    action forward(bit<16> port, bit<8> c) {
        meta.normPort = port;
        meta.cls = c;
        hdr.ipv4.ttl = hdr.ipv4.ttl - 8w1;
        classPkts.count(c);
        portBytes.write(port[2:0], portBytes.read(port[2:0]) + hdr.ipv4.totalLen);
    }

    // 丢弃标记（acl 独占 dropFlag）：置丢弃位，出端口选择交由下游
    action trap() {
        meta.dropFlag = 8w1;
    }

    action nop() { }

    // L2 静态融合表：目的 MAC 精确匹配，条目编译期展开
    table l2_fwd {
        key = {
            hdr.ethernet.dstAddr : exact;
        }
        actions = {
            forward;
            nop;
        }
        const entries = {
            48w0x001122334455 : forward(16w1, 8w1);
            48w0x00aabbccddee : forward(16w2, 8w2);
            default : nop();
        }
    }

    // L3 ACL 运行时表：目的 IP 精确匹配，条目控制面可写
    // p4c: table acl runtime size=8
    table acl {
        key = {
            hdr.ipv4.dstAddr : exact;
        }
        actions = {
            trap;
            nop;
        }
        const entries = {
            default : nop();
        }
    }

    apply {
        classify();
        l2_fwd.apply();
        acl.apply();
    }
}
