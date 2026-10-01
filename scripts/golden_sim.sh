#!/usr/bin/env bash
# golden 对拍（A5 第一级）：官方 p4c-bm2-ss 编译 + bmv2 simple_switch 无头仿真。
#
# 产出 golden 参考：同一报文进出 bmv2 后的实际帧（作为 XLS/RTL 侧的对拍基准）。
# 工具链来自工程外 ../p4x（scripts/env.sh 的 P4X_HOME），无需 root/NIC
# （bmv2 --use-files 模式：port N@X 读写 X_in.pcap / X_out.pcap）。
#
# ⚠️ 输入必须是**官方 v1model 语法可编译**的 P4 —— 本工程 testcases/p4/ 下的
#    样本是自研子集（顶层 Register/Counter extern、`// p4c:` 指示等），
#    官方 p4c 会报错（demo12 实测 syntax error at `Register`）。
#    对拍时须另备官方可编译变体（A5 待办）。
#
# 用法：
#   scripts/golden_sim.sh <in.p4> [-o outdir] [--packet <hex>] [--ports N] [--wait N]
# 示例：
#   scripts/golden_sim.sh ../p4x/examples/l2_fwd.p4 -o out/golden/l2_fwd
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
[[ -f "$ROOT/scripts/env.sh" ]] && source "$ROOT/scripts/env.sh"

P4X="${P4X_HOME:-$ROOT/../p4x}"
if [[ ! -x "$P4X/bin/p4x" ]]; then
  echo "❌ 未找到黄金工具链入口: $P4X/bin/p4x" >&2
  echo "   P4X_HOME 可覆盖；配方见 packaging/README.md（可重建到 ../third_party/dist）" >&2
  exit 1
fi

# ⚠️ ../p4x 的坑：sim 以输出目录为 cwd 启动 simple_switch，但 bmv2 JSON 传的是
# 调用方的相对路径 → "JSON input file ... cannot be opened"（相对 -o 必现，
# 绝对 -o 才碰巧可用）。本包装统一把 -o 转绝对路径规避。
args=()
have_out=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--out)
      shift
      [[ $# -gt 0 ]] || { echo "❌ -o 缺少参数" >&2; exit 1; }
      mkdir -p "$1"
      args+=("-o" "$(cd "$1" && pwd)")
      have_out=true
      shift
      ;;
    *) args+=("$1"); shift ;;
  esac
done
if [[ "$have_out" != true ]]; then
  mkdir -p "$PWD/out/golden"
  args+=("-o" "$PWD/out/golden")
fi

exec "$P4X/bin/p4x" sim "${args[@]}"
