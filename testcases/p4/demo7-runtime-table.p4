// M7 demo：表项运行时可配置（`// p4c: table <表名> runtime [size=N]`）
// 同一 control 内静态表（编译期融合）与运行时表共存。
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
    bit<8>  tag;
    bit<8>  stat;
}

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    action set_cls(bit<8> c) {
        meta.cls = c;
    }
    action set_port(bit<16> p, bit<8> t) {
        meta.normPort = p;
        meta.tag = t;
    }
    action set_stat(bit<8> s) {
        meta.stat = s;
    }
    action nop() { }

    // 静态表：条目编译期融合（与运行时表共存，行为不受影响）
    table static_table {
        key = {
            hdr.ethernet.srcAddr : exact;
        }
        actions = {
            set_stat;
            nop;
        }
        const entries = {
            0x02 : set_stat(8w5);
            default : nop();
        }
    }

    // p4c: table rt_table runtime size=6
    table rt_table {
        key = {
            hdr.ethernet.etherType : exact;
        }
        actions = {
            set_cls;
            set_port;
            nop;
        }
        const entries = {
            default : nop();
        }
    }

    apply {
        static_table.apply();
        rt_table.apply();
    }
}
