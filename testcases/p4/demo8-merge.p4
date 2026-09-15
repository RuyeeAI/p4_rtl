// X3 demo：同一表达式的两条独立语句 → 跨 DAG 同值边界寄存器合并
// （XLS register_merge_strategy=identity 对标；切拍下两条语句各产生 2 个
// 跨级节点，文本相同 → 合并共享，RegEnable 由 4 个减为 2 个）
#include <core.p4>

header ethernet_h {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t {
    ethernet_h ethernet;
}

struct metadata_t {
    bit<16> a;
    bit<16> b;
    bit<16> c;
    bit<16> d;
    bit<16> acc;
}

// p4c: stages=2
control Ingress(inout headers_t hdr, inout metadata_t meta) {
    apply {
        meta.acc = (meta.a + meta.b) + (meta.c + meta.d);
        meta.acc = (meta.a + meta.b) + (meta.c + meta.d);
    }
}
