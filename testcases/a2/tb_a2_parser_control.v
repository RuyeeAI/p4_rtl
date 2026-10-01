`timescale 1ns/1ps
// a2-parser-control 的功能验证：**parser 与 control 接在同一条 proc FSM 上**
//
// ============================ 被验对象 ============================
// testcases/p4/a2-parser-control.p4 经 XlsBackend 生成的 out/a2/a2-parser-control.v
//
//   parser Top：start → parse_ethernet →(0x0800) parse_ipv4 → accept
//                                     →(default) accept
//   control Ingress：1 张 const exact 表（key = etherType），2 个 action
//
// 这个样本是 A2-3（纯 parser）与 A2-4（纯 control）的**合并**，只引入一个变量：
// PHV 表示的对接 —— parser 产生 valid 位，control 按字段改写。
//
// 相位（P=3 个 parser 状态，S=1 条 apply 语句）：
//   0 = start（兼收包） 1 = parse_ethernet 2 = parse_ipv4 3 = cls_table.apply() 4 = 发 PHV
//
// ============================ 报文布局（512 位窗口，高位在前） ============================
//   pkt[511:464] = ethernet.dstAddr(48)
//   pkt[463:416] = ethernet.srcAddr(48)
//   pkt[415:400] = ethernet.etherType(16)
//   pkt[399:240] = ipv4(160)
//   pkt[239:0]   = 未使用
//
// ============================ PHV 布局（298 位） ============================
//   按 header 声明序拼 (valid, 字段…)（先声明者在高位），再拼 meta：
//   [297]     ethernet valid
//   [296:249] ethernet.dstAddr(48) | [248:201] srcAddr(48) | [200:185] etherType(16)
//   [184]     ipv4 valid
//   [183:24]  ipv4 的 12 个字段共 160 位
//   [23:8]    meta.normPort(16)    | [7:0] meta.cls(8)
//
// ============================ 验收判据 ============================
// 1. etherType=0x0800 → parse_ipv4：**两个 valid 都为 1**，ipv4 字段与报文一致；
//    表命中 0x0800 → cls=7、normPort=0
// 2. etherType=0x86dd → default(accept)：**ipv4 valid 必须为 0**、ipv4 字段全 0；
//    表命中 0x86dd → cls=9、normPort=0
// 3. etherType=0x1234 → 都不命中 → default(nop)：ipv4 valid=0，
//    **cls/normPort 保持相位 0 的清零值 0**（meta 每包从 0 起，control 不写就为 0）
// 4. 连发两包（不复位）：第二包同样正确 —— 验证「phase4 发完 → 回 phase0」
//    以及 **valid 不跨包残留**（第 2 包若走 default，ipv4 valid 必须回到 0，
//    而这正是 phase 0 清零要解决的问题）
//
// 用法
//   iverilog -g2012 -o out/a2/tb_a2_parser_control.vvp \
//       out/a2/a2-parser-control.v testcases/a2/tb_a2_parser_control.v
//   vvp out/a2/tb_a2_parser_control.vvp

module tb_a2_parser_control;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [511:0] pkt_in      = 0;
  reg          pkt_in_vld  = 0;
  reg          phv_out_rdy = 1;   // 下游一直能收
  wire [297:0] phv_out;
  wire         phv_out_vld;

  Ingress_pipeline dut (
      .clk(clk),
      .rst(rst),
      .pkt_in(pkt_in),
      .pkt_in_vld(pkt_in_vld),
      .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out),
      .phv_out_vld(phv_out_vld)
  );

  integer errors = 0;

  localparam [47:0] DST = 48'h0011_2233_4455;
  localparam [47:0] SRC = 48'h6677_8899_aabb;
  localparam [15:0] ETYPE_IPV4  = 16'h0800;
  localparam [15:0] ETYPE_IPV6  = 16'h86dd;
  localparam [15:0] ETYPE_OTHER = 16'h1234;

  // ipv4 的 12 个字段共 160 位
  localparam [159:0] IPV4 =
      {4'h4, 4'h5, 8'h00, 16'h003c, 16'h1234, 3'h0, 13'h0, 8'h40, 8'h06,
       16'hbeef, 32'h0a00_0001, 32'h0a00_0002};

  // ------------------------------------------------------------------
  // 发一包并检查 phv_out
  //   协议（跨仿真器确定性，iverilog/verilator 均可）：
  //   ① proc 停在相位 0 等包；send_pkt 在 negedge 置数并拉高 vld，
  //      **下一拍 negedge 立刻撤 vld** —— 一个 vld 脉冲只覆盖一个 posedge，
  //      proc 必收且只收一次（若 vld 一直挂着，proc 转完一圈回 ph0 会把
  //      同一个包再收一遍，产生一条陈旧结果脉冲，TB 就会采错包）。
  //   ② check 等本轮 phv_out_vld 上升后采样；采样后 proc 早已回到 ph0，
  //      补两拍停等再发下一包即可（不复位，验证状态跨包不残留）。
  // ------------------------------------------------------------------
  task send_pkt(input [15:0] etype);
    begin
      pkt_in = {DST, SRC, etype, IPV4, 240'h0};
      pkt_in_vld = 1;
    end
  endtask

  // 发包 + 一拍撤 vld（收包脉冲化）
  task send_one(input [15:0] etype);
    begin
      send_pkt(etype);
      @(negedge clk); pkt_in_vld = 0;
    end
  endtask

  // 标签只用 ASCII：iverilog 的 %0s 对多字节字符串会乱码
  task check(input [297:0] exp_phv, input integer case_id, input [8*40-1:0] label);
    reg [297:0] got;
    integer i;
    begin
      // 先等**上一轮**的 phv_out_vld 落下 —— 否则连续发包时会量到上一包的结果
      i = 0;
      while (phv_out_vld && i < 20) begin @(negedge clk); i = i + 1; end
      // 再等本轮的 phv_out_vld
      for (i = 0; i < 20 && !phv_out_vld; i = i + 1) @(negedge clk);
      got = phv_out;
      if (!phv_out_vld) begin
        $display("  [FAIL] case %0d (%0s): 20 拍内未见 phv_out_vld", case_id, label);
        errors = errors + 1;
      end else if (got !== exp_phv) begin
        $display("  [FAIL] case %0d (%0s): PHV mismatch", case_id, label);
        $display("        got  = %h", got);
        $display("        want = %h", exp_phv);
        $display("        eth_v=%b ipv4_v=%b cls=%h np=%h",
                 got[297], got[184], got[7:0], got[23:8]);
        errors = errors + 1;
      end else begin
        $display("  [ok] case %0d (%0s): eth_v=%b ipv4_v=%b cls=%h",
                 case_id, label, got[297], got[184], got[7:0]);
      end
    end
  endtask

  integer i;
  initial begin
    $dumpfile("out/a2/tb_a2_parser_control.vcd");
    $dumpvars(0, tb_a2_parser_control);

    $display("============================================================");
    $display(" a2-parser-control：parser + control 合并到同一条 proc FSM");
    $display("============================================================");

    rst = 1; pkt_in_vld = 0;
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;

    // case 1：0x0800 → 解析 ipv4 + 表命中 0x0800（set_cls(7)）
    send_one(ETYPE_IPV4);
    check({1'b1, DST, SRC, ETYPE_IPV4, 1'b1, IPV4, 16'h0000, 8'h07},
          1, "0x0800: parse_ipv4 + set_cls(7)");
    repeat (2) @(negedge clk);   // 等 proc 回 ph0 停稳

    // case 2：0x86dd → default(accept)，不解析 ipv4；表命中 0x86dd（set_cls(9)）
    send_one(ETYPE_IPV6);
    check({1'b1, DST, SRC, ETYPE_IPV6, 1'b0, 160'h0, 16'h0000, 8'h09},
          2, "0x86dd: accept + set_cls(9)");
    repeat (2) @(negedge clk);

    // case 3：0x1234 → accept + 表不命中 → default(nop)，meta 保持清零值
    send_one(ETYPE_OTHER);
    check({1'b1, DST, SRC, ETYPE_OTHER, 1'b0, 160'h0, 16'h0000, 8'h00},
          3, "0x1234: accept + nop, meta stays 0");
    repeat (2) @(negedge clk);

    // case 4/5：连发两包（不复位）—— 第 1 包解析 ipv4，第 2 包走 default，
    //           ipv4 valid 必须回到 0（验证 phase 0 的清零，无跨包残留）
    $display("---- case 4/5：连发两包（0x0800 后接 0x86dd，不复位）----");
    send_one(ETYPE_IPV4);
    check({1'b1, DST, SRC, ETYPE_IPV4, 1'b1, IPV4, 16'h0000, 8'h07},
          4, "pkt1 0x0800");
    repeat (2) @(negedge clk);
    send_one(ETYPE_IPV6);
    check({1'b1, DST, SRC, ETYPE_IPV6, 1'b0, 160'h0, 16'h0000, 8'h09},
          5, "pkt2 0x86dd, ipv4_v must be 0");

    $display("============================================================");
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $display("============================================================");
    $finish;
  end

  initial begin
    #20000;
    $display("[FAIL] 仿真超时");
    $finish;
  end

endmodule
