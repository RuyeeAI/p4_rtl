#!/usr/bin/env bash
# 一键搭建 XLS 验证环境（A1 环境复现脚本）。
#
# 适用场景：新机器 / 新同事 / 环境被清掉后重建。
#
# 做四件事：
#   1. 下载 bazelisk 二进制（走 gh-proxy 镜像，直连 GitHub 实测 ~30KB/s）
#   2. 让 bazelisk 拉取 XLS 要求的 bazel 版本（走镜像 + 固定版本绕过 GCS）
#   3. 给 third_party/xls 打「轻量 MODULE.bazel」补丁（摘掉 OpenROAD/LLVM/PDK）
#   4. 把 xls_harness 注入 XLS 源码树，并构建 ir_check_main
#
# 前置条件：
#   - macOS（脚本按 darwin/arm64 取 bazelisk，其他架构会自动判断）
#   - 已装 Xcode CLT（clang）、JDK 21+
#   - third_party/xls 已拉取（见 docs/third-party-INDEX.md）
#
# 用法：
#   scripts/bootstrap_xls_env.sh              # 全流程
#   scripts/bootstrap_xls_env.sh --no-build   # 只搭环境不构建

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"

BUILD=true
[ "${1:-}" = "--no-build" ] && BUILD=false

step() { printf '\n\033[1m[%s/4] %s\033[0m\n' "$1" "$2"; }

# ------------------------------------------------------------------ 0 前置检查
step 0 "前置检查"
missing=0
for t in java clang curl; do
  if command -v "$t" >/dev/null 2>&1; then
    printf '  ✅ %s\n' "$t"
  else
    printf '  ❌ %s 缺失\n' "$t"; missing=1
  fi
done
if [ ! -d "$XLS_SRC" ]; then
  echo "  ❌ XLS 源码缺失: $XLS_SRC" >&2
  echo "     拉取方式见 docs/third-party-INDEX.md" >&2
  exit 1
fi
printf '  ✅ XLS 源码 %s\n' "$XLS_SRC"
[ "$missing" = 1 ] && { echo "缺少前置工具，请先安装" >&2; exit 1; }

# ------------------------------------------------------------- 1 bazelisk 二进制
step 1 "bazelisk 二进制"
if [ -x "$P4XLS_TOOLS/bin/bazelisk" ]; then
  echo "  ✅ 已存在: $("$P4XLS_TOOLS/bin/bazelisk" --version 2>/dev/null | head -1 || echo '?')"
else
  mkdir -p "$P4XLS_TOOLS/bin"
  case "$(uname -m)" in
    arm64) ARCH=arm64 ;;
    x86_64) ARCH=amd64 ;;
    *) ARCH=amd64 ;;
  esac
  VER=v1.26.0
  GH="github.com/bazelbuild/bazelisk/releases/download/${VER}/bazelisk-darwin-${ARCH}"
  got=false
  for M in "https://gh-proxy.com/https://${GH}" \
           "https://ghproxy.net/https://${GH}" \
           "https://ghfast.top/https://${GH}" \
           "https://${GH}"; do
    echo "  尝试 $M"
    rm -f "$P4XLS_TOOLS/bin/bazelisk"
    if curl -sSL --max-time 180 -o "$P4XLS_TOOLS/bin/bazelisk" "$M" 2>/dev/null; then
      sz=$(stat -f%z "$P4XLS_TOOLS/bin/bazelisk" 2>/dev/null || echo 0)
      if [ "$sz" -gt 5000000 ]; then
        chmod +x "$P4XLS_TOOLS/bin/bazelisk"
        echo "  ✅ 下载成功 ($sz bytes)"
        got=true
        break
      fi
    fi
  done
  if [ "$got" != true ]; then
    echo "  ❌ 所有镜像均失败。请手工下载 bazelisk 放到 $P4XLS_TOOLS/bin/" >&2
    exit 1
  fi
fi

# ------------------------------------------------------------ 2 bazel 本体
step 2 "bazel $USE_BAZEL_VERSION"
echo "  镜像: $BAZELISK_BASE_URL"
if bazelisk --version >/dev/null 2>&1; then
  echo "  ✅ $(bazelisk --version 2>&1 | head -1)"
else
  echo "  ❌ bazel 拉取失败。常见原因："
  echo "     - www.googleapis.com 不可达（本脚本已用 USE_BAZEL_VERSION 绕过）"
  echo "     - BAZELISK_BASE_URL 镜像失效，换一个再试"
  exit 1
fi

# --------------------------------------------------- 3 轻量 MODULE.bazel 补丁
step 3 "轻量 MODULE.bazel 补丁"
"$ROOT/scripts/patch_xls_module.sh" status
if cmp -s "$XLS_SRC/MODULE.bazel" "$ROOT/config/xls_MODULE.bazel.minimal"; then
  echo "  ✅ 已是轻量版本"
else
  "$ROOT/scripts/patch_xls_module.sh" apply
fi
echo "  说明: 摘掉 OpenROAD/LLVM/PDK/Yosys 等无关重依赖，"
echo "        Bzlmod 全图解析否则会强制 clone 它们（GB 级、直连 GitHub 极慢）"

# ------------------------------------------------------- 4 注入并构建 harness
step 4 "注入 harness 并构建"
"$ROOT/scripts/setup_xls_harness.sh"
if [ "$BUILD" = true ]; then
  echo "  开始构建（首次需下载依赖，可能较久）..."
  ( cd "$XLS_SRC" && p4xls_bazel build //p4xls_harness:ir_check_main \
      --downloader_config="$ROOT/config/bazel_downloader.cfg" )
  BIN="$(cd "$XLS_SRC" && p4xls_bazel info bazel-bin 2>/dev/null)/p4xls_harness/ir_check_main"
  if [ -x "$BIN" ]; then
    echo "  ✅ 构建成功: $BIN"
  else
    echo "  ❌ 构建产物不存在" >&2
    exit 1
  fi
else
  echo "  ⏭  跳过构建（--no-build）"
fi

echo
echo "环境就绪。试运行： scripts/xls_ir_verify.sh testcases/ir"
