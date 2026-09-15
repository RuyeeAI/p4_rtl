#!/usr/bin/env bash
# 用自建 codegen driver 把 XLS IR 编成 Verilog。
#
# 用法
#   scripts/xls_verilog.sh <input.ir> <top> <output.v> [driver 参数...]
#
# driver 参数（全部可选，直接透传）：
#   --stages=N           流水级数（不指定则 XLS 自行推导）
#   --wct=N              worst_case_throughput。坑：0=不约束，不设=必须为1
#   --clock-period-ps=N  显式时钟周期（不设则用关键路径 + 最小周期搜索路径）
#   --v=N                absl 日志级别
#
# 说明
#   - 驱动是 xls_harness/verilog_codegen_main.cc，链的是干净的
#     //xls/codegen_v_1_5:codegen（绕开官方 //xls/tools:codegen_main 沿链
#     拉入的 Yosys 与 delay_model）。
#   - 首次运行需要编译 driver（含 xls/passes、z3、protobuf 等），可能要十几分钟；
#     之后是增量，秒级。
#
# 例
#   scripts/xls_verilog.sh testcases/m0/m0_key_rsp_loop.ir key_rsp_loop out/m0/m0.v

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$ROOT/scripts/env.sh"

if [ $# -lt 3 ]; then
  sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

IN="$1"
TOP="$2"
OUT="$3"
shift 3        # 剩下的参数原样透传给 driver

case "$IN" in /*) ;; *) IN="$ROOT/$IN" ;; esac
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac

[ -f "$IN" ] || { echo "❌ 找不到输入: $IN" >&2; exit 1; }

cd "$XLS_SRC"
echo "== 构建 codegen driver =="
p4xls_bazel build //p4xls_harness:verilog_codegen_main \
  --downloader_config="$P4XLS_ROOT/config/bazel_downloader.cfg" \
  --noshow_progress

BIN="$(p4xls_bazel info bazel-bin --noshow_progress 2>/dev/null)/p4xls_harness/verilog_codegen_main"
[ -x "$BIN" ] || { echo "❌ driver 未产出: $BIN" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
echo "== 生成 Verilog =="
"$BIN" "$IN" --top="$TOP" --out="$OUT" "$@"
