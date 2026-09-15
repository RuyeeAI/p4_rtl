#!/bin/sh
# @llvm//tools:clang-format 的占位实现。
#
# 真实的 clang-format 未引入（为一个默认值 Label 引入 GB 级 LLVM 不值得）。
# 本脚本只在被真正调用时提示；正常情况下它只作为 Bazel label 出现在
# 规则的 attrs 默认值里，不会被执行。
echo "p4xls: @llvm//tools:clang-format 是占位存根，未引入真实 clang-format。" >&2
exit 1
