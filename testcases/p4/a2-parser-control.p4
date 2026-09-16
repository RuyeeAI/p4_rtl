// A2-5 样本：parser + control 合并到同一个 proc
//
// ============================ 为什么需要这个样本 ============================
// A2-3（demo3-parser，纯 parser）与 A2-4（demo2-match，纯 control）各自跑通了，
// 但两条线**不能混在同一程序里**。合并要处理的唯一新问题是 **PHV 表示的对接**：
//   - parser 侧关心「这个 header 解析到了吗」→ 需要 valid 位；
//   - control 侧关心「哪个字段被改了」    → 需要字段级可写。
// 本样本把两者的最小组件拼起来，一次只引入这一个变量。
//
// ============================ 结构 ============================
// parser Top：start → parse_ethernet →(0x0800) parse_ipv4 → accept
//                                  →(default) accept
// control Ingress：1 张 const exact 表（key = etherType），2 个 action
//
// 预期相位（P = 3 个 parser 状态，S = 1 条 apply 语句）：
//   0 = start（兼收包）  1 = parse_ethernet  2 = parse_ipv4
//   3 = cls_table.apply()   4 = 发 PHV → 回 0

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

struct headers_t {
    ethernet_h ethernet;
    ipv4_h     ipv4;
}

struct metadata_t {
    bit<16> normPort;
    bit<8>  cls;
}

parser Top(packet_in pkt, out headers_t hdr) {
    state start {
        transition parse_ethernet;
    }
    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            0x0800: parse_ipv4;
            default: accept;
        }
    }
    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition accept;
    }
}

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    action set_cls(bit<8> c) {
        meta.cls = c;
        meta.normPort = 16w0;
    }
    action nop() { }

    table cls_table {
        key = {
            hdr.ethernet.etherType : exact;
        }
        actions = {
            set_cls;
            nop;
        }
        const entries = {
            0x0800 : set_cls(8w7);
            0x86dd : set_cls(8w9);
            default : nop();
        }
    }

    apply {
        cls_table.apply();
    }
}
