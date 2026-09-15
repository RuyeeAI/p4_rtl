"""最小存根，替代 @rules_hdl//pdk:build_defs.bzl。

为什么用存根而不是真正引入 rules_hdl：
  真实 rules_hdl 的 MODULE.bazel 依赖 openroad（git_override + init_submodules，
  含全部 submodule，GB 级）、verilator、abc、tcl_lang、rules_7zip、readline、
  libffi、bzip2、gperf 等一大堆。而且它的 git_override 对 openroad 在其作为
  非 root module 时**不生效**，会直接导致版本解析失败。

  而 XLS 侧用到 rules_hdl 的地方只有：本文件的 StandardCellInfo provider
  （被 FDO 构建规则 xls_ir_verilog_fdo 用作 attr 的 providers 约束），
  以及 synthesis:/verilog: 下几个综合相关的 rule。
  这些都在「用 Bazel 规则驱动 codegen + 综合」的路径上，
  A1 的 IR 解析校验路径**完全用不到**。

  所以只提供能让 load 与 provider 约束通过的最小定义即可。
"""

StandardCellInfo = provider(
    doc = "存根 provider（rules_hdl 未真实引入）。",
    fields = {
        "default_corner": "存根字段：真实实现为默认工艺角信息。",
        "open_road_configuration": "存根字段：真实实现为 OpenROAD 配置。",
        "default_input_driver_cell": "存根字段。",
        "default_output_load": "存根字段。",
    },
)
