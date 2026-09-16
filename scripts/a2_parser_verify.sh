#!/usr/bin/env bash
# A2-3 一键验证：P4 parser → XLS IR → Verilog → iverilog 时序仿真
#
# 用法
#   scripts/a2_parser_verify.sh                       # 默认 demo3-parser
#   scripts/a2_parser_verify.sh <in.p4> [top] [tb.v]  # 换样本
#
# 默认样本：testcases/p4/demo3-parser.p4（纯 parser，2 个 header，
#   select 分支 —— A2 第一个端到端目标）
#
# 验收判据（见 testcases/a2/tb_demo3_parser.v 头部）
#   1. etherType=0x0800 → 解析 ipv4，两个 valid 都为 1
#   2. etherType=0x86dd → 走 default(accept)，ipv4 valid 必须为 0
#   3. ethernet 字段与输入一致
#
# 前置
#   - 已构建 XLS harness：scripts/bootstrap_xls_env.sh
#   - 已编译 Scala：sbt compile（脚本会顺带跑）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/third_party/xls/bazel-bin/p4xls_harness/verilog_codegen_main"
P4="${1:-$ROOT/testcases/p4/demo3-parser.p4}"
TOP="${2:-Top_parser}"
TB="${3:-$ROOT/testcases/a2/tb_demo3_parser.v}"
OUT="$ROOT/out/a2"

IR="$OUT/$(basename "${P4%.p4}").ir"
V="$OUT/$(basename "${P4%.p4}").v"

[ -x "$BIN" ] || {
  echo "❌ 找不到 codegen driver: $BIN" >&2
  echo "   先跑 scripts/bootstrap_xls_env.sh" >&2
  exit 1
}
mkdir -p "$OUT"
cd "$ROOT"

echo "========== 1/4 P4 → XLS IR =========="
sbt -batch "runMain p4xls.XlsBackendSelfTest $P4 $IR" 2>&1 \
  | grep -E "✅|^\[error\]" | head -20

echo
echo "========== 2/4 XLS IR → Verilog =========="
# stages=2：与 M0 一致（PDK 存根下的默认值，见 docs/A2-编排水法定案.md §5.4）
"$BIN" "$IR" --top="$TOP" --stages=2 --out="$V" 2>&1 \
  | grep -E "✅|❌" | head -10

echo
echo "========== 3/4 编译 testbench =========="
iverilog -g2012 -o "$OUT/tb_a2.vvp" "$V" "$TB"
echo "iverilog 编译通过"

echo
echo "========== 4/4 时序仿真 =========="
vvp "$OUT/tb_a2.vvp"
