// M1 demo：action 体（位操作 / 算术）→ 组合 Chisel 模块
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
    action rewrite(bit<16> newType) {
        hdr.ethernet.etherType = newType ^ 0x00ff;
        meta.normPort = (bit<16>)(hdr.ethernet.srcAddr[15:0] + 1) << 1;
        meta.cls = 8w3;
    }

    apply {
        rewrite(16w0x0800);
    }
}
