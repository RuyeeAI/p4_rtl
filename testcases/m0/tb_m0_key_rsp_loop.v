`timescale 1ns/1ps
// M0 时序契约的 iverilog testbench
//
// ============================ 接口形态 ============================
// key_out     : send,    flow_control=valid_data → 只有 key_out / key_out_vld
// rsp_in      : receive, flow_control=valid_data → 只有 rsp_in  / rsp_in_vld
// result_out  : send,    flow_control=ready_valid → result_out / result_out_vld / result_out_rdy
//
// 即 Key/Response 通路为**无背压**接口（2026-09-16 郝宇要求去掉两个 rdy）：
//   传输条件：key_out_vld 单独成立即为一次 key 传输；
//             rsp_in_vld  单独成立即为一次 rsp 接收。
//
// ============================ 验收标准 ============================
// 1. key_out_vld 上必须出现一次 key，数据 = 0xa5a5a5a5a5a5a5a5，且**只出现一次**
// 2. **rsp_in_vld 从未拉高之前，result_out_vld 必须一直为 0**
//    —— 这一条就是"后续逻辑必须等 response 返回才开始工作"
//    —— 也是本轮最关键的回归点：去掉 rdy 之后 receive 是否**仍然会等**？
//       （valid_data 下没有 rdy 可供 proc 反向表达"我没准备好"，
//         若 XLS 因此不再 stall，时序契约就被破坏了）
// 3. 给出 rsp_in_vld + 数据后，result_out 上必须出现**同一数据**
// 4. **换一个不同的等待拍数重跑，行为一致** —— 证明不是"死等固定 L 拍"
// 5. **背压不丢数据（仅 result_out）** —— result_out_rdy 为 0 期间不得发生
//    传输，放开后数据必须完好（case 3）。
//    注意 key/rsp 已无背压，无法也不应做此项。
//
// 用法
//   iverilog -g2012 -o out/m0/tb_m0.vvp out/m0/m0_key_rsp_loop.v testcases/m0/tb_m0_key_rsp_loop.v
//   vvp out/m0/tb_m0.vvp
//
// 端口命名来自 XLS codegen 的约定：<chan> / <chan>_vld [/ <chan>_rdy]

module tb_m0_key_rsp_loop;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;   // 100MHz

  // ---- 激励（角色：外部存储器模型 + 下游消费者）----
  // 注意：没有 key_out_rdy —— key_out 是 valid_data 通道，外部被约定"随时可收"
  reg  [31:0] rsp_in         = 0;   // 外部存储器返回的数据
  reg         rsp_in_vld     = 0;   // 外部存储器返回的 valid
  reg         result_out_rdy = 1;   // 下游一直能收

  // ---- DUT ----
  wire [63:0] key_out;
  wire        key_out_vld;
  wire [31:0] result_out;
  wire        result_out_vld;

  key_rsp_loop dut (
      .clk(clk),
      .rst(rst),
      .key_out(key_out),
      .key_out_vld(key_out_vld),
      .rsp_in(rsp_in),
      .rsp_in_vld(rsp_in_vld),
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
    integer key_count;
    integer result_seen;
    integer key_cyc;
    integer result_cyc;
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
      key_count   = 0;
      result_seen = 0;
      key_cyc     = 0;
      result_cyc  = 0;
      got         = 0;

      // 采样点说明：key_out_vld 由寄存器驱动（p1_inputs_valid & ... & is_idle），
      // 在 rst 撤销的**当拍仍是复位值 0**，key 实际出现在撤销后的第一拍。
      // 所以 key 的采样统一放在下面的逐拍循环里 ——
      // 若在当拍额外采一次，同一个 key 会被计成两次而误报。

      $display("---- case %0d: 计划等待 %0d 拍后才给 rsp ----", case_id, wait_cycles);

      // ---- 阶段 1：等待期内不给 rsp，禁止出现 result ----
      for (i = 0; i < wait_cycles; i = i + 1) begin
        @(negedge clk);
        if (key_out_vld) begin
          key_seen  = 1;
          key_count = key_count + 1;
          key_cyc   = i;
          if (key_out !== KEY_EXPECTED) begin
            $display("  [FAIL] key 数据错误: 得到 %h，期望 %h", key_out, KEY_EXPECTED);
            errors = errors + 1;
          end
        end
        // **本 case 的核心断言**：rsp 没给，result 就不能出现。
        // 去掉 rdy 之后 receive 是否仍然会等，全靠这一条来证。
        if (result_out_vld) begin
          $display("  [FAIL] 第 %0d 拍：rsp 还没给，result_out_vld 就拉高了！", i);
          errors = errors + 1;
        end
      end

      // ---- 阶段 2：给 response ----
      @(negedge clk);
      rsp_in     = data;
      rsp_in_vld = 1;
      $display("  → 第 %0d 拍给出 rsp_in_vld=1, data=%h", wait_cycles, data);

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
        $display("  [FAIL] 全程未见 key_out_vld");
        errors = errors + 1;
      end else if (key_count > 1) begin
        $display("  [FAIL] key_out_vld 出现了 %0d 次（应只发一次）", key_count);
        errors = errors + 1;
      end else begin
        $display("  [ok] key 在第 %0d 拍发出（key_out_vld，无 rdy，只发一次）", key_cyc);
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
  // case 3：**result_out 背压测试**
  //
  // 为什么还要测：result_out 仍是 ready_valid 通道，其背压路径与 II=1 改版
  // 时不同（state 更新条件是 stage_outputs_ready_0）。若背压路径有问题，
  // result 会在下游没准备好时被"吃掉"（vld 拉高但传输未被接收，而 phase
  // 仍然推进）。这直接决定 A3 的接口契约怎么写。
  //
  // 注意：key_out / rsp_in 已成为 valid_data 通道，没有 rdy，本项对它们
  // 不适用 —— 那正是"无背压"的代价（见 docs/M0-接口打样报告.md）。
  // ------------------------------------------------------------------
  task run_result_backpressure_case;
    integer i;
    integer key_seen;
    integer pending_seen;
    integer result_taken;
    reg [31:0] got;
    begin
      rst            = 1;
      rsp_in_vld     = 0;
      rsp_in         = 0;
      result_out_rdy = 0;   // 下游尚未准备好
      repeat (3) @(posedge clk);
      @(negedge clk);
      rst = 0;

      key_seen     = 0;
      pending_seen = 0;
      result_taken = 0;
      got          = 0;

      $display("---- case 3: result_out 背压测试（rdy 先拉 0 再放开）----");

      // 阶段 A：rst 撤销后的第一拍应见到 key_out_vld
      //（无 rdy，vld=1 即视为传输完成 —— 这是 valid_data 的约定）
      @(negedge clk); #1;
      if (key_out_vld) begin
        key_seen = 1;
        if (key_out !== KEY_EXPECTED) begin
          $display("  [FAIL] key 数据错误: 得到 %h，期望 %h", key_out, KEY_EXPECTED);
          errors = errors + 1;
        end
      end

      // 阶段 B：给 rsp（此时应已进入 WAIT 相位）
      @(negedge clk); #1;
      rsp_in     = 32'hcafe_0001;
      rsp_in_vld = 1;

      // 阶段 C：result_out_rdy 保持 0 共 6 拍 —— 不允许发生 result 传输
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
        $display("  [FAIL] 未见 key_out_vld（key 未发出）");
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
      end else begin
        $display("  [ok] result_out 背压下未丢失，放开后数据正确 = %h", got);
      end

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
    $display(" 接口：key_out/rsp_in = valid_data（无 rdy）");
    $display("       result_out      = ready_valid（有 rdy）");
    $display("======================================================");
    $display("");

    // 两个 case 用**不同的等待拍数**，用来排除"死等固定 L 拍"的可能
    run_case(2, 32'hdead_beef, 1);
    run_case(7, 32'h1234_5678, 2);
    run_result_backpressure_case;

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
