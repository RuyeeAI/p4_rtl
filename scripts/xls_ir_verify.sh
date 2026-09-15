#!/usr/bin/env bash
# A1 统一入口：用 XLS 官方 parser 校验 IR 文本。
#
# 首次运行会触发 Bazel 构建（下载依赖 + 编译 ir_parser 链路），耗时约 10 分钟。
# 之后是增量构建，通常几秒到二十几秒。
#
# 用法：
#   scripts/xls_ir_verify.sh <file.ir|dir>...        # 校验
#   scripts/xls_ir_verify.sh --json <file.ir>...     # JSON 输出
#   scripts/xls_ir_verify.sh --roundtrip <file.ir>   # 加往返一致性检查
#   scripts/xls_ir_verify.sh --build-only            # 只构建不运行
#   scripts/xls_ir_verify.sh --info                  # 打印环境与产物路径
#
# 实现注意（都是踩过的坑）：
#   1. 目录参数会被展开成其中的 .ir 文件列表，**并且转成绝对路径** —— 因为
#      下面要 cd 到 XLS 源码树才能跑 bazel，相对路径到那里就失效了。
#   2. 必须把 flag 与文件分开收集：早先的写法把原始参数直接透传给校验器，
#      于是 `testcases/ir` 这个目录名被当成文件路径传了进去，报
#      "读取文件失败"，而汇总行显示 "校验 25 个文件 ... 通过 0/1"。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"

TARGET="//p4xls_harness:ir_check_main"

if [ "${1:-}" = "--info" ]; then
  echo "P4XLS_ROOT              = $P4XLS_ROOT"
  echo "XLS_SRC                 = $XLS_SRC"
  echo "bazelisk                = $(command -v bazelisk || echo '(未找到)')"
  echo "USE_BAZEL_VERSION       = ${USE_BAZEL_VERSION:-<未设置>}"
  echo "BAZELISK_BASE_URL       = ${BAZELISK_BASE_URL:-<未设置>}"
  echo "BAZELISK_HOME           = $BAZELISK_HOME"
  echo "output_user_root        = $P4XLS_BAZEL_OUTPUT_ROOT"
  exit 0
fi

if ! command -v bazelisk >/dev/null 2>&1; then
  echo "❌ 找不到 bazelisk。先运行： scripts/bootstrap_xls_env.sh" >&2
  exit 1
fi

_abs() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s\n' "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")" ;;
  esac
}

# ---- 解析参数：flag 与输入分开 ----
flags=()
inputs=()
build_only=false
for a in "$@"; do
  case "$a" in
    --build-only) build_only=true ;;
    --json | --quiet | --roundtrip) flags+=("$a") ;;
    -*) flags+=("$a") ;;
    *) inputs+=("$a") ;;
  esac
done

# ---- 展开输入为绝对路径的文件列表 ----
files=()
if [ "${#inputs[@]}" -gt 0 ]; then
  for a in "${inputs[@]}"; do
    [ -z "$a" ] && continue
    if [ -d "$a" ]; then
      while IFS= read -r f; do
        [ -z "$f" ] && continue
        files+=("$(_abs "$f")")
      done < <(find "$a" -name '*.ir' | sort)
    else
      files+=("$(_abs "$a")")
    fi
  done
fi

# ---- 注入 harness ----
"$ROOT/scripts/setup_xls_harness.sh" >/dev/null

# ---- 构建 ----
cd "$XLS_SRC"
echo "[1/2] 构建 $TARGET ..." >&2
p4xls_bazel build "$TARGET" \
  --downloader_config="$ROOT/config/bazel_downloader.cfg" >/dev/null

BIN="$(p4xls_bazel info bazel-bin 2>/dev/null)/p4xls_harness/ir_check_main"
if [ ! -x "$BIN" ]; then
  echo "❌ 构建产物不存在: $BIN" >&2
  exit 1
fi

if [ "$build_only" = true ]; then
  echo "✅ 构建完成: $BIN"
  exit 0
fi

if [ "${#files[@]}" -eq 0 ]; then
  echo "❌ 未指定 .ir 文件" >&2
  echo "   用法: scripts/xls_ir_verify.sh <file.ir|dir>..." >&2
  exit 2
fi

# ---- 运行（文件已是绝对路径，工作目录不影响） ----
echo "[2/2] 校验 ${#files[@]} 个文件 ..." >&2
set +e
"$BIN" ${flags[@]+"${flags[@]}"} "${files[@]}"
rc=$?
set -e
exit $rc
