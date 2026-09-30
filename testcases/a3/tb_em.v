`timescale 1ns/1ps
// ===========================================================================
// EM 模块（参数化）功能验证 —— Wrap3 存储 + OVFC 版
//
// 被验对象：out/a3/tb/ExactMatch.v（preset = tb）
//   key 16 位 / AD 26 位 / HT 32 桶 x 2 路 x 2 子表（容量 64）/
//   KT 128 / AD 128（全部 TpMemoryWrap3 / SpMemoryWrap3 封装）/
//   OVFC 深度 8（HT 溢出 TCAM）/ CRC-32 固化 / 自学习开 / 老化开
//
// 生成：sbt "em/runMain em.EmGen out/a3 tb"
// 仿真：iverilog -g2012 -o out/a3/tb_em.vvp testcases/a3/tb_em.v out/a3/tb/ExactMatch.v
//
// 用例：
//   1  插入 → 命中（hit=1 且 AD 正确）
//   2  未插入的 key → miss（hit=0）
//   3  第二条条目 → 命中
//   4  删除 → 再查 miss
//   5  批量插入 8 条 → 全部命中、insFail=0
//   6  OVFC：打满 HT（64 条）后继续插入 → 溢出条目进 OVFC（ovfcUse=8）且可命中
//   7  OVFC 打满后：第 73/74 条插入失败（insFail 增长）、OVFC 条目不受影响
//   8  删除 OVFC 条目 → miss 且 ovfcUse 回落
//   9  自学习：miss 自动插入，learnAd 端口值成为 AD，再查命中
//   10 老化：长期不访问的条目被 sweep 删除；持续命中的条目被刷新、存活
//
// 端口提示：io_rsp_bits = {hit, ad}，hit 在最高位（与 XLS proc 的 rsp 布局一致）
// ===========================================================================

module tb_em;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  // ---- 查表口 ----
  reg         key_valid = 0;
  reg  [15:0] key_bits  = 0;
  wire        rsp_valid;
  wire [26:0] rsp_bits;
  // ---- 维护口 ----
  reg         wr_valid = 0;
  reg  [1:0]  wr_op    = 0;
  reg  [15:0] wr_key   = 0;
  reg  [25:0] wr_ad    = 0;
  wire        wrBusy;
  // ---- 学习 / 老化 ----
  reg  [25:0] learnAd = 0;
  reg         learnEn = 0;
  reg         ageEn   = 0;
  // ---- 存储初始化 ----
  reg         memInit = 0;
  wire        memInitDone;
  // ---- CRC 配置（固化模式不用）----
  reg  [31:0] crcPoly = 32'h04C11DB7;
  reg  [31:0] crcInit = 32'hFFFFFFFF;
  reg  [31:0] crcXor  = 32'hFFFFFFFF;
  // ---- 状态 ----
  wire [6:0]  st_entries;
  wire [15:0] st_insert, st_insFail, st_delete, st_learn, st_ageDrop, st_keyDrop;
  wire [3:0]  st_ovfcUse;
  wire        st_lkBusy, st_mtBusy;

  ExactMatch dut (
    .clock(clk), .reset(rst),
    .io_key_valid(key_valid), .io_key_bits(key_bits),
    .io_rsp_valid(rsp_valid), .io_rsp_bits(rsp_bits),
    .io_wr_valid(wr_valid), .io_wr_bits_op(wr_op),
    .io_wr_bits_key(wr_key), .io_wr_bits_ad(wr_ad),
    .io_wrBusy(wrBusy),
    .io_learnAd(learnAd), .io_learnEn(learnEn), .io_ageEn(ageEn),
    .io_memInit(memInit), .io_memInitDone(memInitDone),
    .io_crcPoly(crcPoly), .io_crcInit(crcInit), .io_crcXor(crcXor),
    .io_status_entries(st_entries), .io_status_insert(st_insert),
    .io_status_insFail(st_insFail), .io_status_delete(st_delete),
    .io_status_learn(st_learn), .io_status_ageDrop(st_ageDrop),
    .io_status_keyDrop(st_keyDrop), .io_status_ovfcUse(st_ovfcUse),
    .io_status_lkBusy(st_lkBusy), .io_status_mtBusy(st_mtBusy)
  );

  integer errors = 0;
  reg        cap_hit;
  reg [25:0] cap_ad;

  // ------------------------------------------------------------------
  // 基础任务
  // ------------------------------------------------------------------
  task wait_idle;
    integer i;
    begin
      i = 0;
      // 必须连 wrBusy 一起等：它含 learnPend（自学习挂起期间维护 FSM 还没接手）
      while ((st_lkBusy || st_mtBusy || wrBusy) && i < 500) begin @(posedge clk); i = i + 1; end
      @(negedge clk);
    end
  endtask

  task do_wr(input [1:0] op, input [15:0] k, input [25:0] ad);
    begin
      @(negedge clk);
      wr_valid = 1; wr_op = op; wr_key = k; wr_ad = ad;
      @(negedge clk);
      wr_valid = 0;
      wait_idle;
    end
  endtask

  reg lk_timeout;
  task do_lookup(input [15:0] k);
    integer i;
    begin
      @(negedge clk);
      key_valid = 1; key_bits = k;
      @(negedge clk);
      key_valid = 0;
      i = 0;
      while (!rsp_valid && i < 100) begin @(negedge clk); i = i + 1; end
      lk_timeout = (i >= 100);
      if (lk_timeout) $display("        [warn] lookup(%h) 未收到 rsp", k);
      cap_hit = rsp_bits[26];
      cap_ad  = rsp_bits[25:0];
    end
  endtask

  task chk(input cond, input integer id, input [8*40-1:0] label);
    begin
      if (cond) begin
        $display("  [ok]   case %0d: %0s", id, label);
      end else begin
        $display("  [FAIL] case %0d: %0s", id, label);
        errors = errors + 1;
      end
    end
  endtask

  integer i;
  integer bad;
  integer waited;
  integer okN;
  reg [15:0] failPrev;
  reg [15:0] okKey [0:95];
  reg [25:0] okAd  [0:95];
  reg [15:0] ovfcKey;

  initial begin
    // 只 dump tb 一层：老化用例要跑几十万拍，全量 dump 会严重拖慢仿真
    $dumpfile("out/a3/tb_em.vcd");
    $dumpvars(1, tb_em);

    $display("======================================================");
    $display(" EM 模块功能验证（Wrap3 存储 + OVFC，preset=tb）");
    $display("======================================================");

    // ---- 存储初始化（Wrap3 dfx.init，替代手工清零）----
    // 注意：init FSM 本身受复位控制，**必须先在复位释放后**再发 init，
    // 否则 state 被复位钉在 sIdle，init 永远不开始。
    rst = 1;
    repeat (5) @(posedge clk);
    @(negedge clk); rst = 0;
    repeat (2) @(posedge clk);
    @(negedge clk); memInit = 1;
    @(negedge clk); memInit = 0;
    waited = 0;
    while (!memInitDone && waited < 20000) begin @(posedge clk); waited = waited + 1; end
    chk(memInitDone === 1'b1, 0, "memory init done");
    wait_idle;

    // ---- case 1：插入 → 命中 ----
    do_wr(2'd0, 16'h0800, 26'h00_00A5);
    do_lookup(16'h0800);
    chk(lk_timeout == 0 && cap_hit === 1'b1 && cap_ad === 26'h00_00A5, 1, "insert then hit, ad=A5");

    // ---- case 2：未插入 → miss ----
    do_lookup(16'h1234);
    chk(lk_timeout == 0 && cap_hit === 1'b0, 2, "unknown key -> miss");

    // ---- case 3：第二条条目 ----
    do_wr(2'd0, 16'h86dd, 26'h00_3BC7);
    do_lookup(16'h86dd);
    chk(cap_hit === 1'b1 && cap_ad === 26'h00_3BC7, 3, "second entry hit, ad=3BC7");

    // ---- case 4：删除 → miss ----
    do_wr(2'd1, 16'h0800, 26'h0);
    do_lookup(16'h0800);
    chk(cap_hit === 1'b0, 4, "delete then miss");

    // ---- case 5：批量插入 8 条 ----
    for (i = 0; i < 8; i = i + 1) do_wr(2'd0, 16'h1000 + i, 26'h10 + i);
    begin : blk5
      bad = 0;
      for (i = 0; i < 8; i = i + 1) begin
        do_lookup(16'h1000 + i);
        if (!(cap_hit === 1'b1 && cap_ad === (26'h10 + i))) bad = bad + 1;
      end
      chk(bad == 0 && st_insFail == 0, 5, "8 entries all hit, insFail=0");
    end

    // ---- case 6/7/8：OVFC（HT 溢出 TCAM）----
    // ⚠️ 溢出发生在**先到的**冲突 key 上，不是最后插入的那批 —— 所以校验口径必须是
    // "凡是插入成功的 key 都要能命中"，而不是"最后 8 个 key 在 OVFC 里"。
    okN = 0;
    for (i = 0; i < 80; i = i + 1) begin
      @(negedge clk);
      failPrev = st_insFail;
      wr_valid = 1; wr_op = 0; wr_key = 16'h3000 + i; wr_ad = 26'hB0 + i;
      @(negedge clk); wr_valid = 0;
      wait_idle;
      if (st_insFail == failPrev) begin okKey[okN] = 16'h3000 + i; okAd[okN] = 26'hB0 + i; okN = okN + 1; end
    end
    $display("        插入 80 条：成功 %0d 条，失败 %0d 次，OVFC 占用 %0d",
             okN, st_insFail, st_ovfcUse);
    chk(st_ovfcUse === 4'd8, 6, "OVFC filled (HT overflow)");
    $display("        OVFC 首条：key=%h ad=%h", dut.ovfcK_0, dut.ovfcP_0);

    begin : blk7
      bad = 0;
      for (i = 0; i < okN; i = i + 1) begin
        do_lookup(okKey[i]);
        if (!(cap_hit === 1'b1 && cap_ad === okAd[i])) begin
          bad = bad + 1;
          if (bad <= 3) $display("        miss: key=%h got hit=%b ad=%h want %h",
                                 okKey[i], cap_hit, cap_ad, okAd[i]);
        end
      end
      chk(bad == 0, 7, "every successfully inserted key hits (incl. OVFC)");
    end

    // ---- case 8：删除一条 OVFC 条目 ----
    // 直接取 DUT 里 OVFC 的首条 key（TB 允许用层次引用），确定性验证 OVFC 删除路径
    ovfcKey = dut.ovfcK_0;
    do_lookup(ovfcKey);
    chk(cap_hit === 1'b1, 8, "OVFC entry readable before delete");
    do_wr(2'd1, ovfcKey, 26'h0);
    chk(st_ovfcUse === 4'd7, 9, "OVFC entry deleted (use 8->7)");
    do_lookup(ovfcKey);
    chk(cap_hit === 1'b0, 10, "deleted OVFC key misses");

    // ---- case 11：自学习（此时表已满，先腾出空间）----
    do_wr(2'd1, okKey[0], 26'h0);
    do_wr(2'd1, okKey[1], 26'h0);
    do_wr(2'd1, okKey[2], 26'h0);
    learnEn = 1;
    learnAd = 26'h00_0123;
    do_lookup(16'hABCD);                                 // 第一次必然 miss，并触发学习
    chk(cap_hit === 1'b0, 11, "learn: first lookup misses");
    wait_idle;
    do_lookup(16'hABCD);
    chk(cap_hit === 1'b1 && cap_ad === 26'h00_0123, 12, "learn: auto-inserted with learnAd");
    learnEn = 0;

    // ---- case 13：老化（持续命中刷新 / 长期不访问被删除）----
    ageEn = 1;
    do_wr(2'd1, okKey[3], 26'h0);         // 再腾两个位置给 K1/K2
    do_wr(2'd1, okKey[4], 26'h0);
    do_wr(2'd0, 16'h2001, 26'h00_0011);   // K1：会持续查（保持新鲜）
    do_wr(2'd0, 16'h2002, 26'h00_0022);   // K2：插入后不再访问（应被老化）
    do_lookup(16'h2001);                  // 老化前先确认 K1 在位（含 OVFC 落点）
    $display("        aging 前 K1：hit=%b ad=%h（entries=%0d ovfc=%0d insFail=%0d）",
             cap_hit, cap_ad, st_entries, st_ovfcUse, st_insFail);
    chk(cap_hit === 1'b1 && cap_ad === 26'h00_0011, 14, "K1 present before aging");
    waited = 0;
    while (st_ageDrop == 0 && waited < 20000) begin
      do_lookup(16'h2001);                // 每轮查一次 K1（约 20 拍间隔，小于超时）
      repeat (10) @(posedge clk);
      waited = waited + 1;
      if (waited % 2000 == 0)
        $display("        ... 第 %0d 轮：ageDrop=%0d entries=%0d ovfc=%0d",
                 waited, st_ageDrop, st_entries, st_ovfcUse);
    end
    $display("        aging: 等待 %0d 轮后 ageDrop=%0d", waited, st_ageDrop);
    chk(st_ageDrop > 0, 15, "aging: stale entry swept");

    // sweep 再跑几轮，期间**持续查 K1**（保持时间戳新鲜），然后核对：
    //   K1 仍命中（命中刷新有效）、K2 已被老化删除
    i = 0;
    while (i < 300) begin
      do_lookup(16'h2001);
      repeat (10) @(posedge clk);
      i = i + 1;
    end
    do_lookup(16'h2001);
    chk(cap_hit === 1'b1 && cap_ad === 26'h00_0011, 16, "aging: refreshed entry survives");
    do_lookup(16'h2002);
    chk(cap_hit === 1'b0, 17, "aging: stale entry gone");

    $display("======================================================");
    $display(" 条目=%0d 插入=%0d 失败=%0d 删除=%0d 学习=%0d 老化=%0d OVFC占用=%0d key丢弃=%0d",
             st_entries, st_insert, st_insFail, st_delete, st_learn, st_ageDrop, st_ovfcUse, st_keyDrop);
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $display("======================================================");
    $finish;
  end

  initial begin
    #20000000;
    $display("[FAIL] 仿真超时");
    $finish;
  end

endmodule
