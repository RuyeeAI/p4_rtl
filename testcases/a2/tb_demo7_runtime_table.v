`timescale 1ns/1ps
// demo7-runtime-table 的功能验证：**runtime 表**（key/rsp 通道对）
//
// ============================ 被验对象 ============================
// testcases/p4/demo7-runtime-table.p4 经 XlsBackend 生成的 out/a2/demo7-runtime-table.v
//
//   静态表 static_table（key=srcAddr，const 条目编译期融合）
//   runtime 表 rt_table（key=etherType，size=6，表项运行时可配）
//
// ============================ runtime 表的接口协议 ============================
// proc 是查表客户端（存储与匹配在外部表模块；M0 已验证此时序契约，A4：无背压）：
//   tbl_rt_table_key[15:0]  + _key_vld   ：phase 2 发出（etherType）
//   tbl_rt_table_rsp[26:0]  + _rsp_vld   ：phase 3 必须有效
// rsp 布局：hit(1) | actId(2) | args(24)，hit 在最高位
//   actId: 0=set_cls(c@args[7:0])  1=set_port(p@args[23:8], t@args[7:0])  2=nop
// 本 TB 用打一拍的 mock 表模块：看到 key_vld → 查 mock → 下一拍给 rsp。
//
// 相位（无 parser ⇒ ctrlBase=1）：1=静态表 2=发key 3=收rsp 4=应用 5=发PHV
//
// ============================ PHV 布局（153 位） ============================
//   [152] ethernet_v | [151:104] dstAddr | [103:56] srcAddr | [55:40] etherType
//   [39:24] normPort | [23:16] cls | [15:8] tag | [7:0] stat
//
// ============================ 验收判据 ============================
// 1. etherType=0x0800 → mock 命中 set_cls(0x33) → cls=33
// 2. etherType=0x86dd → mock 命中 set_port(0x64, 0x07) → normPort=0064、tag=07
// 3. etherType=0x1234 → mock miss（hit=0）→ default(nop) → meta 全 0（相位 0 清零）
// 4. srcAddr=0x02 → **静态表同时命中** set_stat(5) → stat=05（两表共存互不干扰）
//
// 用法（一键：scripts/a2_verify.sh testcases/p4/demo7-runtime-table.p4）

module tb_demo7_runtime_table;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [152:0] phv_in      = 0;
  reg          phv_in_vld  = 0;
  reg          phv_out_rdy = 1;
  wire [152:0] phv_out;
  wire         phv_out_vld;
  wire [15:0]  tbl_rt_table_key;
  wire         tbl_rt_table_key_vld;
  reg  [26:0]  tbl_rt_table_rsp;
  reg          tbl_rt_table_rsp_vld;

  Ingress_control dut (
      .clk(clk), .rst(rst),
      .phv_in(phv_in), .phv_in_vld(phv_in_vld),
      .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out), .phv_out_vld(phv_out_vld),
      .tbl_rt_table_key(tbl_rt_table_key), .tbl_rt_table_key_vld(tbl_rt_table_key_vld),
      .tbl_rt_table_rsp(tbl_rt_table_rsp), .tbl_rt_table_rsp_vld(tbl_rt_table_rsp_vld)
  );

  // ------------------------------------------------------------------
  // mock 表模块（打一拍）：key_vld 时查表，下一拍给 rsp
  // ------------------------------------------------------------------
  always @(posedge clk) begin
    if (tbl_rt_table_key_vld) begin
      case (tbl_rt_table_key)
        16'h0800: tbl_rt_table_rsp <= {1'b1, 2'd0, 16'h0000, 8'h33};  // set_cls(0x33)
        16'h86dd: tbl_rt_table_rsp <= {1'b1, 2'd1, 16'h0064, 8'h07};  // set_port(0x64, 0x07)
        default:  tbl_rt_table_rsp <= 27'd0;                          // hit=0
      endcase
      tbl_rt_table_rsp_vld <= 1'b1;
    end else begin
      tbl_rt_table_rsp_vld <= 1'b0;
    end
  end

  integer errors = 0;

  // 波形文本监视：定位 key/rsp 握手时序
  always @(negedge clk) begin
    if (tbl_rt_table_key_vld)
      $display("[%0t] KEY out: etherType=%h (vld)", $time, tbl_rt_table_key);
    if (tbl_rt_table_rsp_vld)
      $display("[%0t] RSP in : %h (vld)  hit=%b act=%0d args=%h",
               $time, tbl_rt_table_rsp, tbl_rt_table_rsp[26], tbl_rt_table_rsp[25:24],
               tbl_rt_table_rsp[23:0]);
  end

  localparam [47:0] DST = 48'h0011_2233_4455;
  localparam [15:0] ET_IPV4 = 16'h0800;
  localparam [15:0] ET_IPV6 = 16'h86dd;
  localparam [15:0] ET_OTHER = 16'h1234;
  localparam [47:0] SRC_MISS = 48'h01;
  localparam [47:0] SRC_HIT  = 48'h02;   // 静态表条目（set_stat(5)）

  task send_pkt(input [15:0] etype, input [47:0] src);
    begin
      phv_in = {1'b1, DST, src, etype, 16'h0000, 8'h00, 8'h00, 8'h00};
      phv_in_vld = 1;
    end
  endtask

  // 期望值：np/cls/tag/stat
  task check(input [15:0] np, input [7:0] cls, input [7:0] tag, input [7:0] stat,
             input integer case_id, input [8*36-1:0] label);
    reg [152:0] exp;
    integer i;
    begin
      i = 0;
      while (phv_out_vld && i < 20) begin @(negedge clk); i = i + 1; end
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);
      if (!phv_out_vld) begin
        $display("  [FAIL] case %0d (%0s): no phv_out_vld", case_id, label);
        errors = errors + 1;
      end else begin
        exp = {1'b1, DST, phv_in[103:56], phv_in[55:40], np, cls, tag, stat};
        if (phv_out !== exp) begin
          $display("  [FAIL] case %0d (%0s): PHV mismatch", case_id, label);
          $display("        got  = %h", phv_out);
          $display("        want = %h", exp);
          errors = errors + 1;
        end else begin
          $display("  [ok] case %0d (%0s): cls=%h np=%h tag=%h stat=%h",
                   case_id, label, phv_out[23:16], phv_out[39:24], phv_out[15:8], phv_out[7:0]);
        end
      end
    end
  endtask

  integer i;
  initial begin
    $dumpfile("out/a2/tb_demo7.vcd");
    $dumpvars(0, tb_demo7_runtime_table);

    $display("======================================================");
    $display(" demo7-runtime-table：静态表 + runtime 表（key/rsp 通道）");
    $display("======================================================");

    rst = 1; phv_in_vld = 0;
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;

    @(negedge clk); send_pkt(ET_IPV4, SRC_MISS);
    check(16'h0000, 8'h33, 8'h00, 8'h00, 1, "rt hit set_cls(33)");
    phv_in_vld = 0; repeat (2) @(posedge clk);

    @(negedge clk); send_pkt(ET_IPV6, SRC_MISS);
    check(16'h0064, 8'h00, 8'h07, 8'h00, 2, "rt hit set_port");
    phv_in_vld = 0; repeat (2) @(posedge clk);

    @(negedge clk); send_pkt(ET_OTHER, SRC_MISS);
    check(16'h0000, 8'h00, 8'h00, 8'h00, 3, "rt miss -> default nop");
    phv_in_vld = 0; repeat (2) @(posedge clk);

    @(negedge clk); send_pkt(ET_IPV4, SRC_HIT);
    check(16'h0000, 8'h33, 8'h00, 8'h05, 4, "static hit + rt hit");
    phv_in_vld = 0; repeat (2) @(posedge clk);

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
