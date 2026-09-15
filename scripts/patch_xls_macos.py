#!/usr/bin/env python3
"""XLS 的 macOS 可移植性补丁（源码级，改上游 C++ / Starlark 源码）。

本文件是 scripts/patch_xls_module.sh 的实现细节，一般不单独调用。

用法
    patch_xls_macos.py <target> <file>
    patch_xls_macos.py --list

target
    anchor-tu    给「只汇聚链接上下文、自己不编译源文件」的规则补锚点翻译单元。
                 适用于 xls/build_rules/xls_pass_rules.bzl 与
                 xls_estimator_rules.bzl。
    subprocess   让 xls/common/subprocess.cc 在 macOS 上可编译。

退出码
    0  已应用（或此前已应用过，幂等跳过）
    3  未找到目标代码模式 —— 上游实现可能已变更，需人工复核
    1  用法错误
"""

import pathlib
import sys

# ---------------------------------------------------------------------------
# 各补丁定义
#
# 统一结构：marker（幂等判据，只可能出现在打补丁后的文件里）
#          edits（(old, new) 列表，全部 old 都必须在文件里出现才动手）
# ---------------------------------------------------------------------------

ANCHOR_MARK = "p4xls_anchor"

# --- 补丁 1：macOS 的「空归档」缺口 ----------------------------------------
#
# 现象
#     Linking xls/passes/liboss_optimization_passes.lo failed:
#     libtool failed: ... libtool: no input file(s) specified
#
# 根因（读源码得到，非推测）
#     xls_pass_registry / xls_pass_registry（estimator 版）的实现是「只汇聚
#     各子项的链接上下文，自己不编译任何源文件」——上游原注释就是
#     "For now we don't compile anything."，配套的是空的
#     cc_common.create_compilation_outputs()。
#     但 create_linking_context_from_compilation_outputs(..., alwayslink=True)
#     仍会让 Bazel 声明一个静态库，而它的 .o 列表为空。
#     macOS 的归档器是 /usr/bin/libtool，收到 0 个输入文件直接报错退出；
#     Linux 用 GNU ar，接受空归档 —— 所以上游 CI 不暴露该问题。
#
# 修法
#     补一个空的「锚点翻译单元」，让归档至少有一个 .o。
ANCHOR_OLD = """    comp_out = cc_common.create_compilation_outputs()
    comp_ctx = cc_common.create_compilation_context()
"""

ANCHOR_NEW = """    # [p4xls] macOS 缺口修复：/usr/bin/libtool 拒绝创建**空归档**
    # （"libtool: no input file(s) specified"）。本规则的设计是「不编译任何
    # 源文件，只汇聚各子项的链接上下文」（上游原注释：For now we don't
    # compile anything.），于是 alwayslink 静态库的 CppArchive 动作收到 0 个
    # 输入文件。Linux 上 GNU ar 接受空归档，所以上游 CI 不暴露；macOS 上
    # 必然失败。这里补一个空的锚点翻译单元，使归档非空。
    # 打补丁的是 scripts/patch_xls_macos.py（由 patch_xls_module.sh 调用），
    # 详见 docs/A1-环境报告.md。
    _p4xls_anchor_src = ctx.actions.declare_file(ctx.label.name + ".p4xls_anchor.cc")
    ctx.actions.write(
        _p4xls_anchor_src,
        "// [p4xls] anchor translation unit (keeps the archive non-empty on macOS).\\n",
    )
    (comp_ctx, comp_out) = cc_common.compile(
        name = ctx.label.name + "_p4xls_anchor",
        actions = ctx.actions,
        feature_configuration = cc_features,
        cc_toolchain = cc_toolchain,
        srcs = [_p4xls_anchor_src],
    )
"""

# --- 补丁 2：subprocess.cc 的 Linux 专有实现 --------------------------------
#
# XLS 为了让内嵌的 subprocess_helper 不依赖构建产物是否还在，把它写进
# **memfd**，再用 /proc/self/fd/<fd> 去 posix_spawn。
# 这两样在 macOS 上都不存在：
#   - 无 memfd_create（<linux/memfd.h> 也找不到）
#   - 无 /proc，对应的虚拟文件系统是 /dev/fd/
#
# 修法：macOS 分支退化为「TMPDIR 下的临时文件 + /dev/fd/<fd>」。
# 两点必须注意，否则运行时才炸：
#   1. mkstemp 建出来的是 0600 且不可执行 → 必须 fchmod 加执行位，
#      否则 posix_spawn 以 EACCES 失败；
#   2. 不 unlink —— macOS 的 /dev/fd/N 对已 unlink 的文件不保证可执行。
#      helper 只有几 KB，留在临时目录里可以接受。
#
# 说明：M0 的 codegen 路径不会调用 RunSubprocess，所以本补丁在 M0 阶段
# 只需保证**能编译**；但这里给的是功能完整的实现，不是空壳。
SUBPROCESS_MARK = "p4xls_subprocess_helper"

SUBPROCESS_EDITS = [
    (
        """#include <fcntl.h>
#include <linux/memfd.h>
""",
        """#include <fcntl.h>
#if defined(__linux__)
#include <linux/memfd.h>
#else
// [p4xls] macOS 没有 memfd；退化为「临时文件 + /dev/fd」实现，
// 见下方 GetSubprocessHelperFd()。fchmod 需要 <sys/stat.h>。
#include <sys/stat.h>
#endif
""",
    ),
    (
        """  int raw_fd = memfd_create("subprocess_helper", MFD_CLOEXEC);
""",
        """#if defined(__linux__)
  int raw_fd = memfd_create("subprocess_helper", MFD_CLOEXEC);
#else
  // [p4xls] macOS 没有 memfd_create：把内嵌的 helper 落到 TMPDIR 下的临时
  // 文件，返回其 fd（下方用 /dev/fd/<fd> 执行）。
  const char* p4xls_tmpdir = std::getenv("TMPDIR");
  std::string p4xls_tmpl =
      absl::StrCat((p4xls_tmpdir != nullptr && p4xls_tmpdir[0] != '\\0')
                       ? p4xls_tmpdir
                       : "/tmp",
                   "/p4xls_subprocess_helper.XXXXXX");
  std::vector<char> p4xls_tmpl_buf(p4xls_tmpl.begin(), p4xls_tmpl.end());
  p4xls_tmpl_buf.push_back('\\0');
  int raw_fd = mkstemp(p4xls_tmpl_buf.data());
  if (raw_fd != -1) {
    // 与 MFD_CLOEXEC 语义对齐：fd 不泄漏到子进程。
    fcntl(raw_fd, F_SETFD, FD_CLOEXEC);
    // mkstemp 默认 0600 不可执行，必须先补执行位。
    fchmod(raw_fd, 0700);
  }
#endif
""",
    ),
    (
        """  std::string subprocess_helper = absl::StrCat("/proc/self/fd/", fd);
""",
        """#if defined(__linux__)
  std::string subprocess_helper = absl::StrCat("/proc/self/fd/", fd);
#else
  // [p4xls] macOS 上是 /dev/fd/<fd>（等价于 Linux 的 /proc/self/fd/<fd>）。
  std::string subprocess_helper = absl::StrCat("/dev/fd/", fd);
#endif
""",
    ),
]

TARGETS = {
    "anchor-tu": (ANCHOR_MARK, [(ANCHOR_OLD, ANCHOR_NEW)]),
    "subprocess": (SUBPROCESS_MARK, SUBPROCESS_EDITS),
}


def main(argv):
    if len(argv) == 2 and argv[1] == "--list":
        for name in TARGETS:
            print(name)
        return 0
    if len(argv) != 3:
        sys.stderr.write(__doc__)
        return 1

    target, path_str = argv[1], argv[2]
    if target not in TARGETS:
        sys.stderr.write(f"未知 target: {target}\n可选: {', '.join(TARGETS)}\n")
        return 1

    marker, edits = TARGETS[target]
    path = pathlib.Path(path_str)
    if not path.is_file():
        sys.stderr.write(f"文件不存在: {path}\n")
        return 1

    text = path.read_text(encoding="utf-8")

    if marker in text:
        print("SKIP")  # 幂等：已打过
        return 0

    # 全有或全无：任何一处对不上都不动文件，避免留下半截补丁。
    missing = [old for old, _ in edits if old not in text]
    if missing:
        sys.stderr.write(
            f"{path.name}: 有 {len(missing)}/{len(edits)} 处目标代码未找到"
            "（上游实现可能已变更，需人工复核）:\n"
        )
        for old in missing:
            first = old.splitlines()[0] if old.splitlines() else old
            sys.stderr.write(f"  - {first}\n")
        return 3

    for old, new in edits:
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
