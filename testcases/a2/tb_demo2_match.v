`timescale 1ns/1ps
// demo2-match 的功能验证：control + const 表 → XLS proc
//
// ============================ 被验对象 ============================
// testcases/p4/demo2-match.p4 经 XlsBackend 生成的 out/a2/demo2.v
//
//   control Ingress(inout headers_t hdr, inout metadata_t meta) {
//       action set_cls(bit<8> c) { meta.cls = c; meta.normPort = 16w0; }
//       action nop() { }
//       table cls_table {
//           key = { hdr.ethernet.etherType : exact; }
//           actions = { set_cls; nop; }
//           const entries = { 0x0800: set_cls(8w7); 0x86dd: set_cls(8w9); default: nop(); }
//       }
//       apply { cls_table.apply(); }
//   }
//
// ============================ PHV 的位布局（136 位） ============================
// 字段序 = control 参数展平序（params 按声明序，struct 成员按声明序）：
//   phv[135:88] = hdr.ethernet.dstAddr(48)
//   phv[87:40]  = hdr.ethernet.srcAddr(48)
//   phv[39:24]  = hdr.ethernet.etherType(16)
//   phv[23:8]   = meta.normPort(16)
//   phv[7:0]    = meta.cls(8)
//
// ============================ 验收判据 ============================
// 1. etherType = 0x0800 → 表项 0 → set_cls(8w7)：cls=7、normPort=0
// 2. etherType = 0x86dd → 表项 1 → set_cls(8w9)：cls=9、normPort=0
// 3. etherType = 0x1234 → 都不命中 → default(nop)：**cls/normPort 保持输入值**
//    （这一条同时验证了 default 语义与"命中才改"）
// 4. 同一次运行里连发两包，第二包必须同样正确
//    （验证 phase S+1 发完 → 回 phase 0 的回路）
//
// 用法
//   iverilog -g2012 -o out/a2/tb_demo2.vvp out/a2/demo2.v testcases/a2/tb_demo2_match.v
//   vvp out/a2/tb_demo2.vvp

module tb_demo2_match;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [135:0] phv_in      = 0;
  reg          phv_in_vld  = 0;
  reg          phv_out_rdy = 1;   // 下游一直能收
  wire [135:0] phv_out;
  wire         phv_out_vld;

  Ingress_control dut (
      .clk(clk),
      .rst(rst),
      .phv_in(phv_in),
      .phv_in_vld(phv_in_vld),
      .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out),
      .phv_out_vld(phv_out_vld)
  );

  integer errors = 0;

  localparam [47:0] DST = 48'h0011_2233_4455;
  localparam [47:0] SRC = 48'h6677_8899_aabb;
  localparam [15:0] ETYPE_IPV4 = 16'h0800;
  localparam [15:0] ETYPE_IPV6 = 16'h86dd;
  localparam [15:0] ETYPE_OTHER = 16'h1234;

  // 报文 CLS0/NP0 = 输入侧初值（用于验证 default 时"保持原值"）
  localparam [7:0]  CLS0 = 8'haa;
  localparam [15:0] NP0  = 16'hbbbb;

  // ------------------------------------------------------------------
  // 发一包并检查 phv_out
  // ------------------------------------------------------------------
  task run_case(input [15:0] etype,
                input [7:0]  cls_exp, input [15:0] np_exp,
                input integer case_id, input [8*40-1:0] label);
    reg [135:0] pin;
    reg [135:0] exp_phv;   // 名字不用 expect：它在 -g2012 下是保留字
    integer i;
    begin
      pin = {DST, SRC, etype, NP0, CLS0};

      // phase 0 需要 phv_in_vld=1 才推进
      @(negedge clk); phv_in = pin; phv_in_vld = 1;

      // 等 phv_out_vld（phase 2 = 发送相位）
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);

      if (!phv_out_vld) begin
        $display("  [FAIL] case %0d（%0s）：20 拍内未见 phv_out_vld", case_id, label);
        errors = errors + 1;
      end else begin
        exp_phv = {DST, SRC, etype, np_exp, cls_exp};
        if (phv_out !== exp_phv) begin
          $display("  [FAIL] case %0d（%0s）：PHV 不符", case_id, label);
          $display("        得到 = %h", phv_out);
          $display("        期望 = %h", exp_phv);
          errors = errors + 1;
        end else begin
          $display("  [ok] case %0d（%0s）：cls=%h normPort=%h", case_id, label, cls_exp, np_exp);
        end
      end

      phv_in_vld = 0;
      repeat (2) @(posedge clk);
    end
  endtask

  // ------------------------------------------------------------------
  integer i;
  initial begin
    $dumpfile("out/a2/tb_demo2.vcd");
    $dumpvars(0, tb_demo2_match);

    $display("======================================================");
    $display(" demo2-match：control + const 表（编译期内联）→ XLS proc");
    $display("======================================================");

    rst = 1; phv_in_vld = 0;
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;

    // case 1：命中表项 0（0x0800 → set_cls(7)）
    run_case(ETYPE_IPV4, 8'h07, 16'h0000, 1, "hit entry0  0x0800");
    // case 2：命中表项 1（0x86dd → set_cls(9)）
    run_case(ETYPE_IPV6, 8'h09, 16'h0000, 2, "hit entry1  0x86dd");
    // case 3：都不命中 → default(nop)，cls/normPort 保持输入值
    run_case(ETYPE_OTHER, CLS0, NP0, 3, "default     nop");

    // case 4：**不复位**连发两包，验证 phase2 → phase0 的回路
    $display("---- case 4：连发两包（不复位）----");
    begin
      reg [135:0] p1, p2, e1, e2;
      p1 = {DST, SRC, ETYPE_IPV4, NP0, CLS0};
      p2 = {DST, SRC, ETYPE_IPV6, NP0, CLS0};
      e1 = {DST, SRC, ETYPE_IPV4, 16'h0000, 8'h07};
      e2 = {DST, SRC, ETYPE_IPV6, 16'h0000, 8'h09};

      @(negedge clk); phv_in = p1; phv_in_vld = 1;
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);
      if (phv_out !== e1) begin
        $display("  [FAIL] 第 1 包：得到 %h 期望 %h", phv_out, e1);
        errors = errors + 1;
      end else $display("  [ok] 第 1 包（0x0800）");

      @(negedge clk); phv_in = p2;   // vld 保持 1（连续流）
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);
      if (phv_out !== e2) begin
        $display("  [FAIL] 第 2 包：得到 %h 期望 %h", phv_out, e2);
        errors = errors + 1;
      end else $display("  [ok] 第 2 包（0x86dd）");

      phv_in_vld = 0;
    end

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
