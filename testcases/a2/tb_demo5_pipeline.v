`timescale 1ns/1ps
// demo5-pipeline 的功能验证：parser + control + **Register/Counter extern**
//
// ============================ 被验对象 ============================
// testcases/p4/demo5-pipeline.p4 经 XlsBackend 生成的 out/a2/demo5-pipeline.v
//
//   parser Top：start → parse_ethernet →(0x0800) parse_ipv4 → accept
//   control Ingress：
//     Register(bit<16>, 8) per_proto;  Counter(bit<32>, 8) total;
//     action count(bit<4> idx) {
//         per_proto.write(idx, per_proto.read(idx) + 16w1);   // 读改写
//         total.count(idx);                                   // 计数
//         meta.cls = idx + 8w1;
//     }
//     table proto_table { key = ipv4.protocol; const entries = { default: count(4w0); } }
//
// 表只有 default 条目 ⇒ 每包执行 count(0)：per_proto[0] += 1、total[0] += 1、
// cls = 1、normPort 保持 0（相位 0 清零后无人写）。
//
// ============================ 验收判据 ============================
// 1. 每包 PHV：eth_v=1、ipv4_v=1（0x0800 报文）、cls=1、normPort=0
// 2. **extern 跨包持久**：第 k 包处理后 ex_total[0] = k、ex_per_proto[0] = k
//    （Register/Counter 不参与相位 0 清零 —— 与 PHV 的本质区别）
// 3. 连发 3 包（不复位），计数 1→2→3
//
// ============================ 观察口布局 ============================
// ex_per_proto[127:0]：元素 0 在最高位（[127:112]），元素 i 占 [(7-i)*16 +: 16]
// ex_total[255:0]    ：元素 0 在最高位（[255:224]），元素 i 占 [(7-i)*32 +: 32]
//
// 用法（一键：scripts/a2_verify.sh testcases/p4/demo5-pipeline.p4）

module tb_demo5_pipeline;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [511:0] pkt_in      = 0;
  reg          pkt_in_vld  = 0;
  reg          phv_out_rdy = 1;
  wire [297:0] phv_out;
  wire         phv_out_vld;
  wire [127:0] ex_per_proto;
  wire         ex_per_proto_vld;
  wire [255:0] ex_total;
  wire         ex_total_vld;

  Ingress_pipeline dut (
      .clk(clk),
      .rst(rst),
      .pkt_in(pkt_in),
      .pkt_in_vld(pkt_in_vld),
      .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out),
      .phv_out_vld(phv_out_vld),
      .ex_per_proto(ex_per_proto),
      .ex_per_proto_vld(ex_per_proto_vld),
      .ex_total(ex_total),
      .ex_total_vld(ex_total_vld)
  );

  integer errors = 0;

  localparam [47:0] DST = 48'h0011_2233_4455;
  localparam [47:0] SRC = 48'h6677_8899_aabb;
  localparam [15:0] ETYPE_IPV4 = 16'h0800;
  localparam [159:0] IPV4 =
      {4'h4, 4'h5, 8'h00, 16'h003c, 16'h1234, 3'h0, 13'h0, 8'h40, 8'h06,
       16'hbeef, 32'h0a00_0001, 32'h0a00_0002};

  // ------------------------------------------------------------------
  task send_pkt;
    begin
      pkt_in = {DST, SRC, ETYPE_IPV4, IPV4, 240'h0};
      pkt_in_vld = 1;
    end
  endtask

  // 发一包，检查 PHV 与 extern 计数（expected = 本包处理后的计数值）
  task run_case(input [31:0] expect_cnt, input integer case_id);
    reg [297:0] exp_phv;
    integer i;
    begin
      // 等上一轮 vld 落，再等本轮起
      i = 0;
      while (phv_out_vld && i < 20) begin @(negedge clk); i = i + 1; end
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);

      if (!phv_out_vld) begin
        $display("  [FAIL] case %0d: no phv_out_vld within 20 cycles", case_id);
        errors = errors + 1;
      end else begin
        exp_phv = {1'b1, DST, SRC, ETYPE_IPV4, 1'b1, IPV4, 16'h0000, 8'h01};
        if (phv_out !== exp_phv) begin
          $display("  [FAIL] case %0d: PHV mismatch", case_id);
          $display("        got  = %h", phv_out);
          $display("        want = %h", exp_phv);
          errors = errors + 1;
        end else if (ex_total[255:224] !== expect_cnt || ex_per_proto[127:112] !== expect_cnt[15:0]) begin
          $display("  [FAIL] case %0d: extern mismatch (total=%0d per_proto=%0d, want %0d)",
                   case_id, ex_total[255:224], ex_per_proto[127:112], expect_cnt);
          errors = errors + 1;
        end else begin
          $display("  [ok] case %0d: cls=%h  per_proto[0]=%0d  total[0]=%0d",
                   case_id, phv_out[7:0], ex_per_proto[127:112], ex_total[255:224]);
        end
      end
    end
  endtask

  // ------------------------------------------------------------------
  integer i;
  initial begin
    $dumpfile("out/a2/tb_demo5.vcd");
    $dumpvars(0, tb_demo5_pipeline);

    $display("======================================================");
    $display(" demo5-pipeline：parser + control + Register/Counter");
    $display("======================================================");

    rst = 1; pkt_in_vld = 0;
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;

    // 连发 3 包（不复位）：验证 extern 跨包累计 1→2→3
    @(negedge clk); send_pkt;
    run_case(32'd1, 1);
    send_pkt;                 // vld 保持 1（连续流）
    run_case(32'd2, 2);
    send_pkt;
    run_case(32'd3, 3);
    pkt_in_vld = 0;

    $display("======================================================");
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $display("======================================================");
    $finish;
  end

  initial begin
    #20000;
    $display("[FAIL] 仿真超时");
    $finish;
  end

endmodule
