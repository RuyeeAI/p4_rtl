#!/usr/bin/env bash
# A2 一键验证：P4 → XLS IR → Verilog → iverilog 时序仿真
#
# 用法
#   scripts/a2_verify.sh                       # 默认 demo3-parser
#   scripts/a2_verify.sh <in.p4>               # 按样本名自动选 top / testbench
#   scripts/a2_verify.sh <in.p4> <top> [tb.v]  # 全部显式指定
#
# 已知样本（自动匹配 top 与 testbench）
#   demo3-parser.p4       → Top_parser        + testcases/a2/tb_demo3_parser.v
#   demo2-match.p4        → Ingress_control   + testcases/a2/tb_demo2_match.v
#   a2-parser-control.p4  → Ingress_pipeline  + testcases/a2/tb_a2_parser_control.v
#
# 验收判据（各 testbench 头部有完整说明）
#   demo3-parser      ：0x0800 → 解析 ipv4（两 valid=1）；0x86dd → default(accept)，ipv4 valid=0
#   demo2-match       ：0x0800 → set_cls(7)；0x86dd → set_cls(9)；其他 → default(nop) 保持原值
#   a2-parser-control ：parser 与 control 接在同一条 FSM 上；valid 不跨包残留
#
# 前置
#   - 已构建 XLS harness：scripts/bootstrap_xls_env.sh
#   - 已编译 Scala：sbt compile（脚本会顺带跑）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/third_party/xls/bazel-bin/p4xls_harness/verilog_codegen_main"
P4="${1:-$ROOT/testcases/p4/demo3-parser.p4}"
STEM="$(basename "${P4%.p4}")"

# 样本 → (top, testbench) 默认值；显式参数优先
case "$STEM" in
  demo3-parser)       DEF_TOP="Top_parser";       DEF_TB="$ROOT/testcases/a2/tb_demo3_parser.v" ;;
  demo2-match)        DEF_TOP="Ingress_control";  DEF_TB="$ROOT/testcases/a2/tb_demo2_match.v" ;;
  a2-parser-control)  DEF_TOP="Ingress_pipeline"; DEF_TB="$ROOT/testcases/a2/tb_a2_parser_control.v" ;;
  *)                  DEF_TOP="Top";              DEF_TB="" ;;
esac

TOP="${2:-$DEF_TOP}"
TB="${3:-$DEF_TB}"
[ -n "$TB" ] || { echo "❌ 样本 '$STEM' 无默认 testbench，请显式传第 3 个参数" >&2; exit 2; }

OUT="$ROOT/out/a2"
IR="$OUT/$STEM.ir"
V="$OUT/$STEM.v"
VVP="$OUT/tb_$STEM.vvp"

[ -x "$BIN" ] || {
  echo "❌ 找不到 codegen driver: $BIN" >&2
  echo "   先跑 scripts/bootstrap_xls_env.sh" >&2
  exit 1
}
mkdir -p "$OUT"
cd "$ROOT"

# ⚠️ 变量一律写 ${VAR}：macOS 自带的 bash 3.2 会把紧跟在 $VAR 后的**全角标点**
#    （如 `（` U+FF08）算进标识符 ⇒ 报 "unbound variable"。见 docs 里的踩坑记录。
echo "########## 样本：${STEM}（top=${TOP}） ##########"
echo
echo "========== 1/4 P4 → XLS IR =========="
# 判「产物」而非 sbt 退出码：sbt 会因清理 ~/.sbt 下的 .bak 缓存产生非零退出
# （沙箱里必现），那不是编译失败。
sbt -batch "runMain p4xls.XlsBackendSelfTest $P4 $IR" 2>&1 \
  | grep -E "✅|^\[error\]" | head -20 || true
[ -s "$IR" ] || { echo "❌ IR 未生成：$IR" >&2; exit 1; }

echo
echo "========== 2/4 XLS IR → Verilog =========="
# stages=2：与 M0 一致（PDK 存根下的默认值，见 docs/A2-编排水法定案.md §5.4）
"$BIN" "$IR" --top="$TOP" --stages=2 --out="$V" 2>&1 \
  | grep -E "✅|❌" | head -10
[ -s "$V" ] || { echo "❌ Verilog 未生成：$V" >&2; exit 1; }

echo
echo "========== 3/4 编译 testbench =========="
iverilog -g2012 -o "$VVP" "$V" "$TB"
echo "iverilog 编译通过"

echo
echo "========== 4/4 时序仿真 =========="
vvp "$VVP"
