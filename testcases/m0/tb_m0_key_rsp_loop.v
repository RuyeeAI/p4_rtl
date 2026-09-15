`timescale 1ns/1ps
// M0 时序契约的 iverilog testbench
//
// ============================ 验收标准 ============================
// 1. key_out 上必须出现一次 key（vld & rdy 同拍），数据 = 0xa5a5a5a5a5a5a5a5
// 2. **rsp_in_vld 从未拉高之前，result_out_vld 必须一直为 0**
//    —— 这一条就是"后续逻辑必须等 response 返回才开始工作"
// 3. 给出 rsp_in_vld + 数据后，result_out 上必须出现**同一数据**
// 4. **换一个不同的等待拍数重跑，行为一致** —— 证明不是"死等固定 L 拍"
// 5. **背压不丢数据** —— key_out_rdy / result_out_rdy 为 0 时不得发生传输，
//    放开后数据必须完好（case 3）
//
// 用法
//   iverilog -o out/m0/tb.vvp out/m0/m0_key_rsp_loop.v testcases/m0/tb_m0_key_rsp_loop.v
//   vvp out/m0/tb.vvp
//
// 端口命名来自 XLS codegen 的约定：<chan> / <chan>_vld / <chan>_rdy

module tb_m0_key_rsp_loop;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;   // 100MHz

  // ---- 激励（角色：外部世界 + 外部存储器模型 + 下游消费者）----
  reg         key_out_rdy    = 1;   // 外部存储器一直能收 key
  reg  [31:0] rsp_in         = 0;   // 外部存储器返回的数据
  reg         rsp_in_vld     = 0;   // 外部存储器返回的 valid
  reg         result_out_rdy = 1;   // 下游一直能收

  // ---- DUT ----
  wire [63:0] key_out;
  wire        key_out_vld;
  wire        rsp_in_rdy;
  wire [31:0] result_out;
  wire        result_out_vld;

  key_rsp_loop dut (
      .clk(clk),
      .rst(rst),
      .key_out_rdy(key_out_rdy),
      .key_out(key_out),
      .key_out_vld(key_out_vld),
      .rsp_in(rsp_in),
      .rsp_in_vld(rsp_in_vld),
      .rsp_in_rdy(rsp_in_rdy),
      .result_out_rdy(result_out_rdy),
      .result_out(result_out),
      .result_out_vld(result_out_vld)
  );

  localparam [63:0] KEY_EXPECTED = 64'ha5a5_a5a5_a5a5_a5a5;

  integer errors = 0;

  // ------------------------------------------------------------------
  // 一个 case：复位 → 等 wait_cycles 拍（期间不给 rsp）→ 给 rsp → 等 result
  // ------------------------------------------------------------------
  task run_case(input integer wait_cycles, input [31:0] data,
                input integer case_id);
    integer i;
    integer key_seen;
    integer result_seen;
    integer key_cyc;
    integer result_cyc;
    integer rsp_hs_seen;
    reg [31:0] got;
    begin
      // ---- 复位 ----
      rst         = 1;
      rsp_in_vld  = 0;
      rsp_in      = 0;
      repeat (3) @(posedge clk);
      @(negedge clk);
      rst = 0;

      key_seen    = 0;
      result_seen = 0;
      key_cyc     = 0;
      result_cyc  = 0;
      rsp_hs_seen = 0;
      got         = 0;

      // **关键采样点**：rst 撤销的当拍，组合逻辑立刻给出 key_out_vld=1，
      // 而 __key_out_already_done_reg 会在下一个 posedge 把它清掉 ——
      // 也就是"只发一次"。所以必须在这里（同一仿真时刻）采样，
      // 用 #1 等组合逻辑稳定。
      #1;
      if (key_out_vld && key_out_rdy) begin
        key_seen = 1;
        key_cyc  = -1;
        if (key_out !== KEY_EXPECTED) begin
          $display("  [FAIL] key 数据错误: 得到 %h，期望 %h", key_out, KEY_EXPECTED);
          errors = errors + 1;
        end
      end

      $display("---- case %0d: 计划等待 %0d 拍后才给 rsp ----", case_id, wait_cycles);

      // ---- 阶段 1：等待期内不给 rsp，禁止出现 result ----
      for (i = 0; i < wait_cycles; i = i + 1) begin
        @(negedge clk);
        if (key_out_vld && key_out_rdy) begin
          key_seen = 1;
          key_cyc  = i;
          if (key_out !== KEY_EXPECTED) begin
            $display("  [FAIL] key 数据错误: 得到 %h，期望 %h", key_out, KEY_EXPECTED);
            errors = errors + 1;
          end
        end
        // 注意：XLS 生成的 rsp_in_rdy 语义是"本拍完成一次接收"，
        // 而不是"我随时能收"。所以无数据时 rdy=0 是正常的，
        // 真正要验的是 rsp_in_vld=1 的那拍 rdy 也必须为 1（握手成立）。
        if (result_out_vld && result_out_rdy) begin
          $display("  [FAIL] 第 %0d 拍：rsp 还没给，result_out_vld 就拉高了！", i);
          errors = errors + 1;
        end
      end

      // ---- 阶段 2：给 response ----
      @(negedge clk);
      rsp_in     = data;
      rsp_in_vld = 1;
      #1;
      if (rsp_in_rdy) begin
        rsp_hs_seen = 1;
      end
      $display("  → 第 %0d 拍给出 rsp_in_vld=1, data=%h (rsp_in_rdy=%b)",
               wait_cycles, data, rsp_in_rdy);

      // 保持 rsp_in_vld，等 result（最多 20 拍）
      for (i = 0; i < 20 && !result_seen; i = i + 1) begin
        @(negedge clk);
        if (result_out_vld && result_out_rdy) begin
          result_seen = 1;
          result_cyc  = wait_cycles + i + 1;
          got         = result_out;
        end
      end

      // ---- 判定 ----
      if (!key_seen) begin
        $display("  [FAIL] 全程未见 key_out 发出");
        errors = errors + 1;
      end else begin
        $display("  [ok] key 在第 %0d 拍发出", key_cyc);
      end

      if (!rsp_hs_seen) begin
        $display("  [WARN] 给出 rsp 的当拍 rsp_in_rdy 未拉高（握手未在同拍成立）");
      end

      if (!result_seen) begin
        $display("  [FAIL] 给 rsp 后 20 拍内未见 result_out_vld");
        errors = errors + 1;
      end else if (got !== data) begin
        $display("  [FAIL] result 数据错: 得到 %h，期望 %h", got, data);
        errors = errors + 1;
      end else begin
        $display("  [ok] result 在第 %0d 拍出现，数据 = %h（与 rsp 一致）",
                 result_cyc, got);
      end

      // 撤掉 rsp，为下一个 case 做准备
      @(negedge clk);
      rsp_in_vld = 0;
      repeat (2) @(posedge clk);
      $display("");
    end
  endtask

  // ------------------------------------------------------------------
  // case 3：**背压测试**（key_out_rdy / result_out_rdy 先为 0 再放开）
  //
  // 为什么必须测：II=1 版本的 state 更新条件是 stage_outputs_ready_0，
  // 与 II=2 版本不同。若背压路径有问题，key 或 result 会在下游没准备好时
  // 被"吃掉"（vld 拉高但传输未被接收，而 phase 仍然推进）。
  // 这直接决定 A3 的接口契约怎么写。
  // ------------------------------------------------------------------
  task run_backpressure_case;
    integer i;
    integer key_seen;
    integer pending_seen;
    integer result_taken;
    reg [31:0] got;
    begin
      rst            = 1;
      rsp_in_vld     = 0;
      rsp_in         = 0;
      key_out_rdy    = 0;   // 外部存储器尚未准备好
      result_out_rdy = 0;   // 下游尚未准备好
      repeat (3) @(posedge clk);
      @(negedge clk);
      rst = 0;

      key_seen     = 0;
      pending_seen = 0;
      result_taken = 0;
      got          = 0;

      $display("---- case 3: 背压测试（两个 rdy 先拉 0 再放开）----");

      // 阶段 A：key_out_rdy=0 保持 3 拍 —— 不允许发生 key 传输
      for (i = 0; i < 3; i = i + 1) begin
        @(negedge clk); #1;
        if (key_out_vld && key_out_rdy) begin
          $display("  [FAIL] key_out_rdy=0 时却发生了 key 传输");
          errors = errors + 1;
        end
      end

      // 阶段 B：放开 key_out_rdy —— key 应被接收且数据正确
      @(negedge clk); key_out_rdy = 1; #1;
      if (key_out_vld && key_out_rdy) begin
        key_seen = 1;
        if (key_out !== KEY_EXPECTED) begin
          $display("  [FAIL] key 数据错误: 得到 %h，期望 %h", key_out, KEY_EXPECTED);
          errors = errors + 1;
        end
      end

      // 阶段 C：给 rsp，但 result_out_rdy 仍为 0
      repeat (2) @(negedge clk);
      rsp_in     = 32'hcafe_0001;
      rsp_in_vld = 1;
      for (i = 0; i < 6; i = i + 1) begin
        @(negedge clk); #1;
        if (result_out_vld && !result_out_rdy) pending_seen = 1;
        if (result_out_vld && result_out_rdy) begin
          $display("  [FAIL] result_out_rdy=0 时却发生了 result 传输");
          errors = errors + 1;
        end
      end

      // 阶段 D：放开 result_out_rdy —— result 应被接收且数据正确
      @(negedge clk); result_out_rdy = 1; #1;
      if (result_out_vld && result_out_rdy) begin
        result_taken = 1;
        got          = result_out;
      end
      for (i = 0; i < 5 && !result_taken; i = i + 1) begin
        @(negedge clk); #1;
        if (result_out_vld && result_out_rdy) begin
          result_taken = 1;
          got          = result_out;
        end
      end

      if (!key_seen) begin
        $display("  [FAIL] 放开 key_out_rdy 后未见 key 传输（key 被丢或 phase 提前推进）");
        errors = errors + 1;
      end
      if (!pending_seen) begin
        $display("  [WARN] rdy=0 期间未观察到 result_out_vld 悬挂");
      end
      if (!result_taken) begin
        $display("  [FAIL] 放开 result_out_rdy 后未见 result 传输");
        errors = errors + 1;
      end else if (got !== 32'hcafe_0001) begin
        $display("  [FAIL] result 数据错: 得到 %h，期望 cafe0001", got);
        errors = errors + 1;
      end else if (key_seen) begin
        $display("  [ok] 背压下 key 与 result 均未丢失，数据正确 = %h", got);
      end

      key_out_rdy    = 1;
      result_out_rdy = 1;
      rsp_in_vld     = 0;
      repeat (2) @(posedge clk);
      $display("");
    end
  endtask

  // ------------------------------------------------------------------
  initial begin
    $dumpfile("out/m0/tb_m0.vcd");
    $dumpvars(0, tb_m0_key_rsp_loop);

    $display("======================================================");
    $display(" M0 时序契约验证：send key -> 等 response -> 分发");
    $display("======================================================");
    $display("");

    // 两个 case 用**不同的等待拍数**，用来排除"死等固定 L 拍"的可能
    run_case(2, 32'hdead_beef, 1);
    run_case(7, 32'h1234_5678, 2);
    run_backpressure_case;

    $display("======================================================");
    if (errors == 0) begin
      $display(" 结果：全部通过 —— 时序契约成立");
    end else begin
      $display(" 结果：%0d 处失败", errors);
    end
    $display("======================================================");
    $finish;
  end

  // 全局超时保护
  initial begin
    #20000;
    $display("[FAIL] 仿真超时（20000ns）");
    $finish;
  end

endmodule
