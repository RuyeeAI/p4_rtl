#!/usr/bin/env bash
# M0 一键验证：XLS IR → Verilog → iverilog 时序仿真
#
# 用法
#   scripts/m0_verify.sh            # 全流程
#   scripts/m0_verify.sh --gen-only # 只生成 Verilog，不跑仿真
#
# 验收标准（见 docs/M0-接口打样报告.md）
#   1. key_out 上一次 key（数据 = 0xa5a5a5a5a5a5a5a5）
#   2. rsp_in_vld 未拉高之前，result_out_vld 必须一直为 0
#   3. 给出 rsp 后，result_out 上出现同一数据
#   4. 换不同等待拍数重跑，行为一致（证明不是死等固定 L 拍）

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

echo "========== 1/4 IR 语法校验（官方 parser + round-trip） =========="
"$ROOT/scripts/xls_ir_verify.sh" --quiet "$IR" | tail -3

echo
echo "========== 2/4 生成 Verilog =========="
# 必须带 --wct=2：见报告 F1 —— 「token 链 + 阻塞通道」的 proc 拿不到 II=1，
# 显式放宽到 2 才能过调度。（注意上游语义：0=不约束，不设=必须为 1）
"$BIN" "$IR" --top=key_rsp_loop --stages=2 --wct=2 --out="$V"

if $gen_only; then
  echo
  echo "（--gen-only，跳过仿真）Verilog: $V"
  exit 0
fi

echo
echo "========== 3/4 编译 testbench =========="
iverilog -g2012 -o "$OUT/tb_m0.vvp" "$V" "$TB"
echo "iverilog 编译通过"

echo
echo "========== 4/4 时序仿真 =========="
cd "$ROOT"          # VCD 是相对路径，必须从仓库根跑
vvp "$OUT/tb_m0.vvp"
