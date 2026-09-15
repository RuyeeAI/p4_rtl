"""最小存根，替代 @rules_hdl//synthesis:build_defs.bzl。
见 pdk/build_defs.bzl 头部的说明。"""

def benchmark_synth(**kwargs):
    """存根：真实实现会调用综合工具跑基准。"""
    pass

def synthesize_rtl(**kwargs):
    """存根：真实实现会调用 Yosys/OpenROAD 做综合。"""
    pass
