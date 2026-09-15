// M2 demo：exact 表静态融合（表项固化进 RTL）
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
