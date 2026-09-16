`timescale 1ns/1ps
// demo3-parser 的功能验证：parser FSM → XLS proc
//
// ============================ 被验对象 ============================
// testcases/p4/demo3-parser.p4 经 XlsBackend 生成的 out/a2/demo3.v
//
//   parser Top(packet_in pkt, out headers_t hdr) {
//       state start          { transition parse_ethernet; }
//       state parse_ethernet { pkt.extract(hdr.ethernet);
//                              transition select(hdr.ethernet.etherType) {
//                                  0x0800 : parse_ipv4;
//                                  default: accept; }; }
//       state parse_ipv4     { pkt.extract(hdr.ipv4); transition accept; }
//   }
//
// ============================ 报文与 PHV 的位布局 ============================
// pkt_in 是 512 位窗口，报文按字节序**从高位开始**：
//   pkt[511:464] = ethernet.dstAddr(48)
//   pkt[463:416] = ethernet.srcAddr(48)
//   pkt[415:400] = ethernet.etherType(16)
//   pkt[399:240] = ipv4(160)
//   pkt[239:0]   = 未使用（补 0）
//
// phv_out 是 274 位，按 header 声明序拼 (valid, data)：
//   phv[273]     = ethernet valid
//   phv[272:161] = ethernet data(112)
//   phv[160]     = ipv4 valid
//   phv[159:0]   = ipv4 data(160)
//
// ============================ 验收判据 ============================
// 1. etherType = 0x0800 → 走 parse_ipv4 分支 → **两个 valid 都为 1**
// 2. etherType = 0x86dd → 走 default(accept) 分支 → **ipv4 valid 必须为 0**
//    （这一条同时验证了 select 分支与"未 extract 时 header 保持无效"）
// 3. 两次解析的 ethernet 字段都必须与输入一致
//
// 用法
//   iverilog -g2012 -o out/a2/tb_demo3.vvp out/a2/demo3.v testcases/a2/tb_demo3_parser.v
//   vvp out/a2/tb_demo3.vvp

module tb_demo3_parser;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [511:0] pkt_in      = 0;
  reg          pkt_in_vld  = 0;
  reg          phv_out_rdy = 1;   // 下游一直能收
  wire [273:0] phv_out;
  wire         phv_out_vld;

  Top_parser dut (
      .clk(clk),
      .rst(rst),
      .pkt_in(pkt_in),
      .pkt_in_vld(pkt_in_vld),
      .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out),
      .phv_out_vld(phv_out_vld)
  );

  integer errors = 0;

  // ---- 测试报文 ----
  localparam [47:0] DST = 48'h0011_2233_4455;
  localparam [47:0] SRC = 48'h6677_8899_aabb;
  localparam [15:0] ETYPE_IPV4 = 16'h0800;
  localparam [15:0] ETYPE_IPV6 = 16'h86dd;

  // ipv4 的 12 个字段共 160 位：
  //   version(4) ihl(4) diffserv(8) totalLen(16) identification(16)
  //   flags(3) fragOffset(13) ttl(8) protocol(8) hdrChecksum(16)
  //   srcAddr(32) dstAddr(32)
  localparam [159:0] IPV4 =
      {4'h4, 4'h5, 8'h00, 16'h003c, 16'h1234, 3'h0, 13'h0, 8'h40, 8'h06,
       16'hbeef, 32'h0a00_0001, 32'h0a00_0002};

  // ------------------------------------------------------------------
  task run_case(input [15:0] etype, input integer expect_ipv4, input integer case_id);
    reg [511:0] pkt;
    reg [273:0] exp_phv;   // 名字不用 expect：它在 -g2012 下是保留字
    integer i;
    begin
      $display("---- case %0d: etherType=%h, 期望 ipv4 valid=%0d ----",
               case_id, etype, expect_ipv4);

      pkt = {DST, SRC, etype, IPV4, 240'h0};

      // 复位
      rst = 1; pkt_in_vld = 0;
      repeat (3) @(posedge clk);
      @(negedge clk); rst = 0;

      // phase 0 需要 pkt_in_vld=1 才推进
      @(negedge clk); pkt_in = pkt; pkt_in_vld = 1;

      // 等 phv_out_vld（phase 3 = accept）
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);

      if (!phv_out_vld) begin
        $display("  [FAIL] 20 拍内未见 phv_out_vld");
        errors = errors + 1;
      end else begin
        exp_phv = {1'b1, DST, SRC, etype, (expect_ipv4 ? 1'b1 : 1'b0),
                  (expect_ipv4 ? IPV4 : 160'h0)};
        if (phv_out !== exp_phv) begin
          $display("  [FAIL] PHV 不符");
          $display("        得到 = %h", phv_out);
          $display("        期望 = %h", exp_phv);
          errors = errors + 1;
        end else begin
          $display("  [ok] PHV 正确（ethernet valid=1, ipv4 valid=%0d）", expect_ipv4);
        end
      end

      pkt_in_vld = 0;
      repeat (2) @(posedge clk);
    end
  endtask

  // ------------------------------------------------------------------
  initial begin
    $dumpfile("out/a2/tb_demo3.vcd");
    $dumpvars(0, tb_demo3_parser);

    $display("======================================================");
    $display(" demo3-parser：parser FSM → XLS proc 功能验证");
    $display("======================================================");

    run_case(ETYPE_IPV4, 1, 1);   // 0x0800 → 解析 ipv4
    run_case(ETYPE_IPV6, 0, 2);   // 其他 → default(accept)，不解析 ipv4

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
