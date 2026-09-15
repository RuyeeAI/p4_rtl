#!/bin/sh
# @at_clifford_yosys//:yosys 的占位实现。
#
# 真实 Yosys 未引入（它需要 GB 级依赖与长时间编译，而本工程只做功能验证，
# 不走 FDO 综合）。本脚本只在被真正调用时报错，正常情况下它仅作为
# Bazel label 出现在数据流里，不会被执行。
echo "p4xls: @at_clifford_yosys//:yosys 是占位存根，未引入真实 Yosys。" >&2
echo "        若需要 FDO 综合流程，请把真正的 rules_hdl tools_extension 接回来。" >&2
exit 1
