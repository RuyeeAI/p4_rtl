// M4 demo：v1model 状态单元（Register / Counter）→ 时序状态模块
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
    bit<16> normPort;
    bit<8>  cls;
}

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    Register(bit<16>, 8) stats;
    Counter(bit<32>, 8) hits;

    action bump(bit<8> idx) {
        stats.write(idx, stats.read(idx) + 16w1);
        hits.count(idx);
    }

    apply {
        bump(8w3);
    }
}
