// demo10：多 Key 组合匹配 + 条件 Key 选择（表达式 key）
// 覆盖点：
//   - 多 key 表：key = { A : exact; B : exact; C : exact; } —— 组合键按声明序 Cat
//     拼接（A 在高位），表项逐 key 给值，全部命中才算命中（AND 语义）
//   - 条件 Key 选择（表达式 key）：key 位置写三元表达式，按协议条件在 UDP/TCP
//     字段间选择参与匹配的值。P4 表原生不支持 key 间接寻址，标准惯用法是
//     "先算选择子到 metadata 再匹配"；本子集表 key 读输入快照（W1 前的顺序
//     组合语义），同 control 内先写后用不生效——故以表达式 key 直接表达。
//   - 静态表与运行时表共用同一 key 发射口径（emitTableKey），运行时表布局的
//     keyBits 随表达式宽度推导
//   - 字段单一写者：cls 由 flow_cls 独占，dropFlag 由 l4_watch 独占
#include <core.p4>

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
    bit<16> window;
}

struct headers_t {
    ethernet_h ethernet;
    ipv4_h     ipv4;
    udp_h      udp;
    tcp_h      tcp;
}

struct metadata_t {
    bit<8>  cls;
    bit<8>  dropFlag;
}

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    action set_cls(bit<8> c) {
        meta.cls = c;
    }

    action drop_it() {
        meta.dropFlag = 8w1;
    }

    action nop() { }

    // 多 key 组合匹配（AND）：源/目的 IP + 条件选择的目的 L4 端口
    // 第三个 key 是三元表达式：UDP 报文取 udp.dstPort，其余取 tcp.dstPort
    table flow_cls {
        key = {
            hdr.ipv4.srcAddr : exact;
            hdr.ipv4.dstAddr : exact;
            ((hdr.ipv4.protocol == 8w17) ? hdr.udp.dstPort : hdr.tcp.dstPort) : exact;
        }
        actions = {
            set_cls;
            drop_it;
            nop;
        }
        const entries = {
            0x0a000001, 0x0a000002, 16w53 : set_cls(8w1);   // 内网 DNS 流
            0x0a000003, 0x0a000004, 16w80 : set_cls(8w2);   // 内网 HTTP 流
            default : nop();
        }
    }

    // 运行时表：单表达式 key（条件选择的源 L4 端口），控制面可写黑名单
    // p4c: table l4_watch runtime size=4
    table l4_watch {
        key = {
            ((hdr.ipv4.protocol == 8w17) ? hdr.udp.srcPort : hdr.tcp.srcPort) : exact;
        }
        actions = {
            drop_it;
            nop;
        }
        const entries = {
            default : nop();
        }
    }

    apply {
        flow_cls.apply();
        l4_watch.apply();
    }
}
