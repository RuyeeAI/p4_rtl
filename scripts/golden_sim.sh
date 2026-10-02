#!/usr/bin/env bash
# golden 对拍（A5 第一级）：官方 p4c-bm2-ss 编译 + bmv2 simple_switch 无头仿真。
#
# 产出 golden 参考：同一报文进出 bmv2 后的实际帧（作为 XLS/RTL 侧的对拍基准）。
# 实现已收编为本工程自包含的 scripts/golden_sim.py（原 ../p4x 的 p4xlib，
# 2026-10-02 p4x 目录删除前并入）；工具链在 ../third_party/dist
# （scripts/env.sh 的 P4X_GOLDEN_DIST，packaging/ 配方可重建）。
#
# ⚠️ 输入必须是**官方 v1model 语法可编译**的 P4 —— 本工程 testcases/p4/ 下的
#    样本是自研子集（顶层 Register/Counter extern 等），官方 p4c 会报错。
#    自检样本：testcases/golden/l2_fwd.p4。
#
# 用法：
#   scripts/golden_sim.sh <in.p4> [-o outdir] [--packet <hex>] [--ports N] [--wait N]
#   scripts/golden_sim.sh --check        # 只检查工具链完整性
# 示例：
#   scripts/golden_sim.sh testcases/golden/l2_fwd.p4 -o out/golden/l2_fwd
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/golden_sim.py" "$@"
