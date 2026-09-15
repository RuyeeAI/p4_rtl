// M1/M4 demo：16 项左结合加法链（Bin 链拓扑深度 15）——切拍功能的核心触发用例。
// 主目录以 N=1 正常管线编译为基线 Demo6DeepchainIngress；
// p4/demos/staged/demo6-deepchain.p4（同源程序）以 N=4 编译为 Demo6DeepchainStagedIngress，
// 供等价性测试配对（Demo6StagedSpec）。
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
    bit<16> f0;
    bit<16> f1;
    bit<16> f2;
    bit<16> f3;
    bit<16> f4;
    bit<16> f5;
    bit<16> f6;
    bit<16> f7;
    bit<16> f8;
    bit<16> f9;
    bit<16> f10;
    bit<16> f11;
    bit<16> f12;
    bit<16> f13;
    bit<16> f14;
    bit<16> f15;
    bit<16> acc;
}

control Ingress(inout headers_t hdr, inout metadata_t meta) {
    Register(bit<16>, 8) stats;
    Counter(bit<32>, 8) hits;

    action chain() {
        // 16 项左结合加法链 → Bin 链深度 15；预算 4 时按深度均匀切成 4 级
        meta.acc = meta.f0 + meta.f1 + meta.f2 + meta.f3
                 + meta.f4 + meta.f5 + meta.f6 + meta.f7
                 + meta.f8 + meta.f9 + meta.f10 + meta.f11
                 + meta.f12 + meta.f13 + meta.f14 + meta.f15;
        stats.write(8w0, meta.acc);
        hits.count(8w0);
    }

    apply { chain(); }
}
