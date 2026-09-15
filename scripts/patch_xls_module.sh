#!/usr/bin/env bash
# 对 third_party/xls 打补丁（轻量依赖 + macOS 缺口修复）。
#
# 处理四组文件：
#   1. MODULE.bazel —— 换成本工程裁剪过的版本（config/xls_MODULE.bazel.minimal）
#   2. .bazelrc     —— 注释掉引用 @llvm-project 的 Starlark 命令行 flag
#   3. BUILD（根）  —— 去掉对已摘除依赖的引用（hedron_compile_commands / fuzztest）
#   4. xls/build_rules/{xls_pass_rules,xls_estimator_rules}.bzl
#                   —— 补空锚点翻译单元，规避 macOS libtool 的「空归档」报错
#
# ---------------------------------------------------------------------------
# 为什么需要（背景）
#
# XLS 原版 MODULE.bazel 是给「全功能 XLS」用的，包含大量与 IR 解析/代码生成
# 完全无关的重量级依赖。Bzlmod 是**全图解析**：只要某个 module 在依赖图里，
# Bazel 就必须拉它的源码才能读它的 BUILD 文件。于是即便我们只想编译
# //xls/ir:ir_parser，也会被迫下载：
#
#   - OpenROAD（git_override + init_submodules=True，含全部 submodule，GB 级）
#   - LLVM 源码（llvm_raw_ext → @llvm-project）
#   - 12 个 PDK（SkyWater sky130 六变体 + ASAP7 三个，每个 GB 级）
#   - Yosys / Icarus Verilog（rules_hdl tools extension）
#   - Perfetto、Verible、qt-bazel（OpenROAD GUI 预编译）
#   - Maven/JUnit（perfetto 连带）、rules_android、rules_closure
#
# 实测直连 GitHub 的 git clone 约 30KB/s，6 分钟只拉到 8.6MB 就卡在 OpenROAD。
#
# 而实测确认 xls/ir/BUILD、xls/common/BUILD、xls/codegen/BUILD 中
# openroad / rules_hdl / llvm-project / perfetto / verible 出现次数**均为 0** ——
# 这条闭包根本不需要它们。
#
# 摘掉 @llvm-project 之后，.bazelrc 第 83 行的这一行就会变成悬空引用：
#     build --@llvm-project//third-party:llvm_enable_zstd=false
# 它是 Bazel 的 Starlark 命令行 flag 语法（--@repo//pkg:flag），Bazel 在
# 装配命令行阶段就要解析该 repo，找不到就报：
#     ERROR: @llvm-project//third-party:llvm_enable_zstd :: Error loading option
#     @llvm-project//third-party:llvm_enable_zstd: No repository visible as
#     '@llvm-project' from main repository
# 注意这个错误**与构建哪个 target 无关**，哪怕构建一个零 XLS 依赖的
# cc_binary 也会报，因为它在命令行装配阶段就失败了。
#
# ---------------------------------------------------------------------------
# 第 4 组：macOS 的「空归档」缺口
#
# 现象：链接 //xls/passes:oss_optimization_passes 时
#     Linking xls/passes/liboss_optimization_passes.lo failed:
#     libtool failed: ... libtool: no input file(s) specified
#
# 根因（读 xls/build_rules/xls_pass_rules.bzl 得到，非推测）：
#   xls_pass_registry / xls_pass_registry 的实现里，上游写的是
#       # For now we don't compile anything.
#       comp_out = cc_common.create_compilation_outputs()   # ← 空
#       comp_ctx = cc_common.create_compilation_context()
#       cc_common.create_linking_context_from_compilation_outputs(
#           compilation_outputs = comp_out, alwayslink = True, ...)
#   规则的设计是「只汇聚各 pass 的链接上下文，自己不编译源文件」。
#   但 alwayslink=True 会让 Bazel 声明一个静态库，而它的 .o 列表为空。
#   macOS 的归档器是 /usr/bin/libtool，收到 0 个输入文件直接报错退出。
#   Linux 用的是 GNU ar，接受空归档 —— 所以上游 CI 不暴露该问题，
#   这是 XLS 在 macOS 上的真实缺口。
#
# 修复：在同一个 helper 里补一个空的「锚点翻译单元」，让归档至少有一个 .o。
# 影响面：xls_pass_rules.bzl 与 xls_estimator_rules.bzl 两处（全库仅此两处
# 使用空 compilation_outputs）。两处都改。
#
# ---------------------------------------------------------------------------
# 用法：
#   scripts/patch_xls_module.sh apply      # 应用补丁（原文件备份为 *.orig）
#   scripts/patch_xls_module.sh restore    # 还原上游原版
#   scripts/patch_xls_module.sh status     # 查看当前状态
#   scripts/patch_xls_module.sh diff       # 查看与上游原版的差异摘要

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XLS="$ROOT/third_party/xls"
MOD_SRC="$ROOT/config/xls_MODULE.bazel.minimal"
MOD_TARGET="$XLS/MODULE.bazel"
MOD_BACKUP="$XLS/MODULE.bazel.orig"
BAZELRC="$XLS/.bazelrc"
BAZELRC_BACKUP="$XLS/.bazelrc.orig"
LLVM_FLAG_LINE='build --@llvm-project//third-party:llvm_enable_zstd=false'
BUILD_SRC="$ROOT/config/xls_ROOT_BUILD.minimal"
BUILD_TARGET="$XLS/BUILD"
BUILD_BACKUP="$XLS/BUILD.orig"

# 第 4/5 组：macOS 可移植性补丁涉及的文件（相对 XLS 根）
BUILD_RULES_FILES=(
  "xls/build_rules/xls_pass_rules.bzl"
  "xls/build_rules/xls_estimator_rules.bzl"
)
SUBPROCESS_CC="xls/common/subprocess.cc"
# 全部「target:相对路径」对，供 status / diff 遍历
MACOS_PATCHED=(
  "anchor-tu:${BUILD_RULES_FILES[0]}"
  "anchor-tu:${BUILD_RULES_FILES[1]}"
  "subprocess:${SUBPROCESS_CC}"
)

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[ $# -ge 1 ] || usage

if [ ! -d "$XLS" ]; then
  echo "❌ XLS 源码树不存在: $XLS" >&2
  exit 1
fi

patch_bazelrc() {
  # 把 LLVM flag 那行注释掉（幂等：已注释则跳过）
  if grep -qxF "$LLVM_FLAG_LINE" "$BAZELRC" 2>/dev/null; then
    [ -f "$BAZELRC_BACKUP" ] || cp "$BAZELRC" "$BAZELRC_BACKUP"
    local tmp
    tmp="$(mktemp)"
    while IFS= read -r line; do
      if [ "$line" = "$LLVM_FLAG_LINE" ]; then
        printf '# [p4xls] 已摘除 @llvm-project（随 LLVM 依赖一并移除），该 flag 无法解析:\n' >> "$tmp"
        printf '# %s\n' "$line" >> "$tmp"
      else
        printf '%s\n' "$line" >> "$tmp"
      fi
    done < "$BAZELRC"
    mv "$tmp" "$BAZELRC"
    echo "✅ .bazelrc: 已注释掉 @llvm-project flag"
  else
    echo "ℹ️  .bazelrc: 无待处理的 flag（已注释或上游已变更）"
  fi
}

# 第 4 组：给「只汇聚链接上下文、自己不编译源文件」的两个规则补锚点 TU。
# 第 5 组：让 xls/common/subprocess.cc 在 macOS 上可编译。
#
# 两组的具体替换内容都在 scripts/patch_xls_macos.py 里（可读性更好、也便于
# 上游升级时逐个复核）。本函数只负责备份原始文件与汇总结果。
MACOS_PATCHER="$ROOT/scripts/patch_xls_macos.py"

patch_macos_sources() {
  local target="$1" rel f out
  shift
  for rel in "$@"; do
    f="$XLS/$rel"
    if [ ! -f "$f" ]; then
      echo "❌ 找不到 $rel" >&2
      exit 1
    fi
    [ -f "$f.orig" ] || cp "$f" "$f.orig"
    out="$(python3 "$MACOS_PATCHER" "$target" "$f" 2>&1)"
    case "$out" in
      OK)   echo "✅ $rel: 已打补丁（${target}）" ;;
      SKIP) echo "ℹ️  $rel: 已打过（${target}）" ;;
      *)
        echo "❌ $rel: 打补丁失败（${target}）" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        exit 1
        ;;
    esac
  done
}

patch_build_rules() {
  patch_macos_sources "anchor-tu" "${BUILD_RULES_FILES[@]}"
}

patch_subprocess() {
  patch_macos_sources "subprocess" "$SUBPROCESS_CC"
}

# status 用：查单个文件是否已打指定的补丁。
# 复用 patcher 自身的幂等判据（输出 SKIP 即已打过），避免在 shell 里重复维护 marker。
report_patch_state() {
  local target="$1" rel="$2" out
  out="$(python3 "$MACOS_PATCHER" "$target" "$XLS/$rel" 2>/dev/null || true)"
  case "$out" in
    SKIP) echo "  ✅ $rel: 已打（${target}）" ;;
    OK)   echo "  ⚠️  $rel: 未打补丁（${target}）" ;;
    *)    echo "  ❓ $rel: 无法判定（${target}）—— 文件缺失或模式已变更" ;;
  esac
}

case "$1" in
  apply)
    [ -f "$MOD_SRC" ] || { echo "❌ 找不到 $MOD_SRC" >&2; exit 1; }

    if [ ! -f "$MOD_BACKUP" ]; then
      cp "$MOD_TARGET" "$MOD_BACKUP"
      echo "✅ MODULE.bazel: 已备份上游原版 -> MODULE.bazel.orig"
    else
      echo "ℹ️  MODULE.bazel: 备份已存在，保留原备份"
    fi
    cp "$MOD_SRC" "$MOD_TARGET"
    echo "✅ MODULE.bazel: 已应用轻量版本"
    echo "   上游原版 $(wc -l < "$MOD_BACKUP" | tr -d ' ') 行  →  轻量版 $(wc -l < "$MOD_TARGET" | tr -d ' ') 行"

    patch_bazelrc

    # 根 BUILD：必须处理，因为 //:license 被所有 cc_library 隐式依赖，
    # 导致根包必然被加载，而原版根 BUILD 引用了已摘除的
    # @hedron_compile_commands 与 @com_google_fuzztest。
    [ -f "$BUILD_SRC" ] || { echo "❌ 找不到 $BUILD_SRC" >&2; exit 1; }
    if [ ! -f "$BUILD_BACKUP" ]; then
      cp "$BUILD_TARGET" "$BUILD_BACKUP"
      echo "✅ BUILD(根): 已备份上游原版 -> BUILD.orig"
    else
      echo "ℹ️  BUILD(根): 备份已存在，保留原备份"
    fi
    cp "$BUILD_SRC" "$BUILD_TARGET"
    echo "✅ BUILD(根): 已替换为精简版（保留 //:license，去掉 hedron/fuzztest 引用）"

    patch_build_rules
    patch_subprocess
    ;;

  restore)
    if [ -f "$MOD_BACKUP" ]; then
      cp "$MOD_BACKUP" "$MOD_TARGET"
      echo "✅ MODULE.bazel: 已还原上游原版"
    else
      git -C "$XLS" checkout -- MODULE.bazel && echo "✅ MODULE.bazel: 已用 git 还原"
    fi
    if [ -f "$BAZELRC_BACKUP" ]; then
      cp "$BAZELRC_BACKUP" "$BAZELRC"
      echo "✅ .bazelrc: 已还原上游原版"
    else
      git -C "$XLS" checkout -- .bazelrc 2>/dev/null && echo "✅ .bazelrc: 已用 git 还原" || true
    fi
    if [ -f "$BUILD_BACKUP" ]; then
      cp "$BUILD_BACKUP" "$BUILD_TARGET"
      echo "✅ BUILD(根): 已还原上游原版"
    else
      git -C "$XLS" checkout -- BUILD 2>/dev/null && echo "✅ BUILD(根): 已用 git 还原" || true
    fi
    for rel in "${BUILD_RULES_FILES[@]}"; do
      if [ -f "$XLS/$rel.orig" ]; then
        cp "$XLS/$rel.orig" "$XLS/$rel"
        echo "✅ $rel: 已还原上游原版"
      else
        git -C "$XLS" checkout -- "$rel" 2>/dev/null && echo "✅ $rel: 已用 git 还原" || true
      fi
    done
    if [ -f "$XLS/xls/common/subprocess.cc.orig" ]; then
      cp "$XLS/xls/common/subprocess.cc.orig" "$XLS/xls/common/subprocess.cc"
      echo "✅ xls/common/subprocess.cc: 已还原上游原版"
    else
      git -C "$XLS" checkout -- xls/common/subprocess.cc 2>/dev/null \
        && echo "✅ xls/common/subprocess.cc: 已用 git 还原" || true
    fi
    ;;

  status)
    echo "== MODULE.bazel =="
    if [ -f "$MOD_BACKUP" ]; then
      if cmp -s "$MOD_TARGET" "$MOD_SRC"; then
        echo "  状态: 已应用轻量补丁"
      elif cmp -s "$MOD_TARGET" "$MOD_BACKUP"; then
        echo "  状态: 上游原版（未打补丁）"
      else
        echo "  状态: ⚠️ 与两者都不同（被手工修改过？）"
      fi
    else
      echo "  状态: 无备份，未打过补丁"
    fi
    echo "  行数: $(wc -l < "$MOD_TARGET" | tr -d ' ')  bazel_dep 数: $(grep -c '^bazel_dep(' "$MOD_TARGET" || true)"
    echo "  use_extension 数: $(grep -c 'use_extension(' "$MOD_TARGET" || true)"

    echo "== .bazelrc =="
    if grep -qxF "$LLVM_FLAG_LINE" "$BAZELRC" 2>/dev/null; then
      echo "  状态: ⚠️ 仍含悬空的 @llvm-project flag（会阻塞所有构建）"
    else
      echo "  状态: 已处理（无悬空 @llvm-project flag）"
    fi

    echo "== BUILD(根) =="
    if [ -f "$BUILD_BACKUP" ]; then
      if cmp -s "$BUILD_TARGET" "$BUILD_SRC"; then
        echo "  状态: 已应用精简版"
      elif cmp -s "$BUILD_TARGET" "$BUILD_BACKUP"; then
        echo "  状态: 上游原版（未打补丁）—— 会导致根包加载时报 hedron/fuzztest 缺失"
      else
        echo "  状态: ⚠️ 与两者都不同（被手工修改过？）"
      fi
    else
      echo "  状态: 无备份，未打过补丁"
    fi
    if grep -q "hedron_compile_commands\|com_google_fuzztest" "$BUILD_TARGET" 2>/dev/null; then
      echo "  ⚠️  仍引用已摘除的依赖（hedron_compile_commands / fuzztest）"
    fi

    echo "== build_rules（macOS 空归档修复） =="
    for rel in "${BUILD_RULES_FILES[@]}"; do
      report_patch_state "anchor-tu" "$rel"
    done

    echo "== xls/common/subprocess.cc（macOS 可移植化） =="
    report_patch_state "subprocess" "xls/common/subprocess.cc"
    ;;

  diff)
    if [ -f "$MOD_BACKUP" ]; then
      echo "=== 被删除的依赖声明 ==="
      comm -23 <(grep -oE 'bazel_dep\(name = "[^"]+"' "$MOD_BACKUP" | sed 's/.*"\(.*\)"/\1/' | sort -u) \
               <(grep -oE 'bazel_dep\(name = "[^"]+"' "$MOD_TARGET" | sed 's/.*"\(.*\)"/\1/' | sort -u) \
        | sed 's/^/  - /'
      echo
      echo "=== 依赖数 ==="
      echo "  原版 $(grep -c '^bazel_dep(' "$MOD_BACKUP" || true)  /  轻量 $(grep -c '^bazel_dep(' "$MOD_TARGET" || true)"
    else
      echo "⚠️  无备份可比对"
    fi
    echo
    echo "=== .bazelrc 中的 Starlark flag ==="
    grep -nE '^\s*#?\s*build .*--@' "$BAZELRC" | sed 's/^/  /' || echo "  （无）"

    echo
    echo "=== macOS 可移植性补丁（源码级） ==="
    for entry in "${MACOS_PATCHED[@]}"; do
      rel="${entry#*:}"
      if [ -f "$XLS/$rel.orig" ]; then
        added="$(diff "$XLS/$rel.orig" "$XLS/$rel" 2>/dev/null | grep -c '^>' || true)"
        echo "  $rel: 新增 $added 行"
      else
        echo "  $rel: 无备份（未打过补丁）"
      fi
    done
    ;;

  *) usage ;;
esac
