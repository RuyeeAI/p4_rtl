// demo11：多拍 Action（声明级切拍指示 `// p4c: stages=4`）
// 覆盖点：
//   - 指示把 Ingress 的拍数预算设为 4：深度 11 的左结合混合运算链按深度切成
//     4 级，单次调用需 4 拍完成（发起间隔 ≥ 4，valid 链逐级推进）
//   - 与 demo6（staged 目录 + 全局 --stages 管线）不同，本 demo 在主管线内以
//     声明级指示生效，单文件自描述，无需第二个 sourceGenerator
//   - 运算链 + / ^ 交替并用显式括号固定左结合形状，避免解析器优先级改变拓扑
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
    bit<16> acc;
}

// p4c: stages=4
control Ingress(inout headers_t hdr, inout metadata_t meta) {
    // 深度 11 左结合链：12 个操作数、11 个运算（+ / ^ 交替）→ 预算 4 时按深度
    // 均匀切成 4 级，mix() 需 4 拍完成
    action mix() {
        meta.acc = ((((((((((meta.f0 + meta.f1) ^ meta.f2) + meta.f3) ^ meta.f4)
                     + meta.f5) ^ meta.f6) + meta.f7) ^ meta.f8) + meta.f9) ^ meta.f10)
                 + meta.f11;
    }

    apply { mix(); }
}
