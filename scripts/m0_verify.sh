#!/usr/bin/env bash
# M0 一键验证：XLS IR → Verilog → iverilog 时序仿真
#
# 用法
#   scripts/m0_verify.sh            # 全流程
#   scripts/m0_verify.sh --gen-only # 只生成 Verilog，不跑仿真
#
# 接口形态（2026-09-16 起）
#   key_out     : send,    flow_control=valid_data → 只有 key_out / key_out_vld
#   rsp_in      : receive, flow_control=valid_data → 只有 rsp_in  / rsp_in_vld
#   result_out  : send,    flow_control=ready_valid → result_out / _vld / _rdy
#   Key/Response 通路为**无背压**接口：vld 单独成立即视为一次传输。
#
# 验收标准（见 docs/M0-接口打样报告.md）
#   1. key_out_vld 上出现一次 key（数据 = 0xa5a5a5a5a5a5a5a5），且只出现一次
#   2. rsp_in_vld 未拉高之前，result_out_vld 必须一直为 0
#      —— 这也是"去掉 rdy 后 receive 是否仍然会等"的回归点
#   3. 给出 rsp 后，result_out 上出现同一数据
#   4. 换不同等待拍数重跑，行为一致（证明不是死等固定 L 拍）
#   5. result_out 背压不丢数据（key/rsp 已无背压，不适用）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/third_party/xls/bazel-bin/p4xls_harness/verilog_codegen_main"
IR="$ROOT/testcases/m0/m0_key_rsp_loop.ir"
TB="$ROOT/testcases/m0/tb_m0_key_rsp_loop.v"
OUT="$ROOT/out/m0"
V="$OUT/m0_key_rsp_loop.v"

gen_only=false
[ "${1:-}" = "--gen-only" ] && gen_only=true

[ -x "$BIN" ] || {
  echo "❌ 找不到 codegen driver: $BIN" >&2
  echo "   先跑 scripts/bootstrap_xls_env.sh" >&2
  exit 1
}

mkdir -p "$OUT"

echo "========== 1/5 IR 语法校验（官方 parser + round-trip） =========="
"$ROOT/scripts/xls_ir_verify.sh" --quiet "$IR" | tail -3

echo
echo "========== 2/5 生成 Verilog =========="
# 配置说明（scripts/ii_sweep.sh 实测）：
#   本样本用**并行 token**（三个通道操作都直连 state_read(tok)，末尾 after_all 汇聚）
#   → 最小可行 II = 1，且在 IR 里用 #[initiation_interval(1)] 显式声明，
#     所以这里**不传 --wct**（II 由 IR 自描述；若传了选项会优先于属性）。
#   对照样本 m0_key_rsp_loop_serial_token.ir 用串行 token 链，最小 II = 2。
#   注意上游的 wct 语义坑：0 = 不约束；不设 = 必须为 1；N = 必须恰好为 N。
"$BIN" "$IR" --top=key_rsp_loop --stages=2 --out="$V"

if $gen_only; then
  echo
  echo "（--gen-only，跳过仿真）Verilog: $V"
  exit 0
fi

echo
echo "========== 3/5 编译 testbench =========="
iverilog -g2012 -o "$OUT/tb_m0.vvp" "$V" "$TB"
echo "iverilog 编译通过"

echo
echo "========== 4/5 时序仿真 =========="
cd "$ROOT"          # VCD 是相对路径，必须从仓库根跑
vvp "$OUT/tb_m0.vvp"

echo
echo "========== 5/5 II 属性核对（并行 token 应为 1） =========="
"$ROOT/scripts/ii_sweep.sh" "$IR" key_rsp_loop 2 1 | tail -6
