#!/usr/bin/env bash
# 把工程内的 xls_harness/ 注入到 XLS 源码树，使其可被 Bazel 构建。
#
# 为什么需要注入
#   Bazel 的 label（如 //xls/ir:ir_parser）只在**同一个 workspace 内**可见。
#   harness 的源文件保存在工程内（进版本控制），但 BUILD 必须出现在
#   third_party/xls/ 之下才能引用那些 label。所以用本脚本做一次性注入。
#
#   选择「复制」而非「软链」：Bazel 对 workspace 内指向 workspace 之外的
#   符号链接处理不一致（部分版本会报 symlink cycle 或直接忽略），复制虽
#   需要重新执行，但行为完全确定。
#
# 用法： scripts/setup_xls_harness.sh [--check]
#   --check  只检查是否已同步，不写文件（CI 用）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/xls_harness"
DST="$ROOT/third_party/xls/p4xls_harness"

check_only=false
[ "${1:-}" = "--check" ] && check_only=true

if [ ! -d "$ROOT/third_party/xls" ]; then
  echo "❌ XLS 源码树不存在: $ROOT/third_party/xls" >&2
  echo "   先拉取：见 docs/third-party-INDEX.md" >&2
  exit 1
fi

if [ ! -f "$SRC/BUILD.bazel" ]; then
  echo "❌ harness 源不存在: $SRC/BUILD.bazel" >&2
  exit 1
fi

# 计算差异（递归，因为 harness 里现在有子目录：rules_hdl_stub/）
changed=()
while IFS= read -r rel; do
  [ -z "$rel" ] && continue
  if [ ! -f "$DST/$rel" ] || ! cmp -s "$SRC/$rel" "$DST/$rel"; then
    changed+=("$rel")
  fi
done < <(cd "$SRC" && find . -type f -not -name '.DS_Store' | sed 's|^\./||' | sort)

if [ "$check_only" = true ]; then
  if [ "${#changed[@]}" -eq 0 ]; then
    echo "✅ harness 已同步"
    exit 0
  fi
  echo "⚠️  harness 未同步，需重新运行 scripts/setup_xls_harness.sh"
  printf '   待更新: %s\n' "${changed[@]}"
  exit 1
fi

# 整体复制（含子目录），**仅在有差异时进行**。
# 这里必须做差异判断：无条件 cp 会刷新文件 mtime，Bazel 据此认为源文件变了，
# 于是每次 xls_ir_verify.sh 都会触发 ir_check_main 重编译（实测从 20 秒级
# 变成数分钟级）。
if [ "${#changed[@]}" -gt 0 ]; then
  mkdir -p "$DST"
  cp -R "$SRC"/. "$DST"/
  find "$DST" -name '.DS_Store' -delete 2>/dev/null || true
fi

# 注入目录整体不进主仓库（third_party/ 已在 .gitignore 中，这里做双保险）
if ! grep -qx "third_party/xls/p4xls_harness/" "$ROOT/.gitignore" 2>/dev/null; then
  printf 'third_party/xls/p4xls_harness/\n' >> "$ROOT/.gitignore"
fi

if [ "${#changed[@]}" -eq 0 ]; then
  echo "✅ harness 已是最新（$(ls -1 "$DST" | wc -l | tr -d ' ') 个文件）"
else
  echo "✅ harness 已注入到 $DST"
  printf '   更新: %s\n' "${changed[@]}"
fi
