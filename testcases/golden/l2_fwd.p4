/* p4x 示例：最小 v1model L2 转发程序。
 *
 * 解析 ethernet 头，ingress 把报文定向到 port1，deparser 重新发出。
 * 用法：
 *   p4x sim   examples/l2_fwd.p4        # bmv2 仿真，观察 port1 输出
 *   p4x chisel examples/l2_fwd.p4       # 生成 Chisel（注：自研前端仅支持子集）
 */
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct metadata_t {
}

struct headers_t {
    ethernet_t ethernet;
}

parser MyParser(packet_in packet,
                out headers_t hdr,
                inout metadata_t meta,
                inout standard_metadata_t stdmeta) {
    state start {
        packet.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) {
    apply {
    }
}

control MyIngress(inout headers_t hdr,
                  inout metadata_t meta,
                  inout standard_metadata_t stdmeta) {
    apply {
        stdmeta.egress_spec = 1;
    }
}

control MyEgress(inout headers_t hdr,
                 inout metadata_t meta,
                 inout standard_metadata_t stdmeta) {
    apply {
    }
}

control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) {
    apply {
    }
}

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(),
         MyVerifyChecksum(),
         MyIngress(),
         MyEgress(),
         MyComputeChecksum(),
         MyDeparser()) main;
