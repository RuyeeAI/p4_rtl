#!/usr/bin/env bash
# II 扫描：测一个 proc 的**最小可行启动间隔（II / worst_case_throughput）**
#
# 用法
#   scripts/ii_sweep.sh <input.ir> <top> [max_stages] [max_wct]
#
# 原理
#   上游语义（run_pipeline_schedule.cc:456-463）：
#     不设 wct = 必须 II=1；wct=0 = 不约束；wct=N = 必须恰好为 N
#   所以「最小的能成功的 N」就是该结构在当前 stage 数下的最小可行 II。
#
# 输出：一张 stages × wct 的可行性矩阵 + 每个 stage 数下的最小 II + 全局最小 II。
#
# 为什么需要它
#   M0 已发现「token 链 + 阻塞通道」的 proc 拿不到 II=1，而 PISA 要求一拍一包。
#   A2 的编排水法（拆 proc / 接受 II=2 / 不把 token 写回 state）取决于
#   **II 的瓶颈到底是"通道操作本身"还是"token 串链"** —— 本脚本就是那个判别工具。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/third_party/xls/bazel-bin/p4xls_harness/verilog_codegen_main"

if [ $# -lt 2 ]; then
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

IR="$1"
TOP="$2"
MAX_STAGES="${3:-4}"
MAX_WCT="${4:-4}"

case "$IR" in /*) ;; *) IR="$ROOT/$IR" ;; esac
[ -f "$IR" ] || { echo "❌ 找不到 $IR" >&2; exit 1; }
[ -x "$BIN" ] || { echo "❌ 找不到 driver: $BIN" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "======================================================================"
echo " II 扫描： $(basename "$IR")   top=$TOP"
echo " stages 1..$MAX_STAGES   wct 1..$MAX_WCT"
echo "======================================================================"
echo

# 表头
printf '%-8s' "stages\\wct"
for w in $(seq 1 "$MAX_WCT"); do printf '%5s' "$w"; done
printf '   %s\n' "最小 II"
printf '%s\n' "----------------------------------------------------------------------"

global_min=""
for s in $(seq 1 "$MAX_STAGES"); do
  printf '%-10s' "$s"
  min_for_s=""
  for w in $(seq 1 "$MAX_WCT"); do
    if "$BIN" "$IR" --top="$TOP" --stages="$s" --wct="$w" \
         --out="$TMP/s${s}_w${w}.v" >/dev/null 2>&1; then
      printf '%5s' "✓"
      [ -z "$min_for_s" ] && min_for_s="$w"
      [ -z "$global_min" ] && global_min="$w"
    else
      printf '%5s' "✗"
    fi
  done
  if [ -n "$min_for_s" ]; then
    printf '   %s\n' "$min_for_s"
  else
    printf '   %s\n' "> $MAX_WCT"
  fi
done

echo
if [ -z "$global_min" ]; then
  echo "结论：在 stages<=$MAX_STAGES / wct<=$MAX_WCT 范围内**完全不可调度**"
  exit 1
fi

echo "======================================================================"
echo " **最小可行 II = $global_min**"
if [ "$global_min" = "1" ]; then
  echo " → 该结构可达一拍一迭代（PISA「一拍一包」在此结构上可行）"
else
  echo " → 该结构每 $global_min 拍才能启动一次迭代；若用于 PISA 流水线，"
  echo "   吞吐将降至 1/$global_min 包/拍，需靠「双实例交替」或改结构补偿"
fi
echo "======================================================================"
