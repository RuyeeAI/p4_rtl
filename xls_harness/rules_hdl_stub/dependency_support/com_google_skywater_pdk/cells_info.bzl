"""最小存根，替代 @rules_hdl//dependency_support/com_google_skywater_pdk:cells_info.bzl。

这个宏在 rules_hdl 里会遍历 SkyWater sky130 标准单元、为每个单元生成
面积/延迟数据文件（供 xls/estimators/{delay_model,area_model} 用）。

本工程只做「P4 逻辑 → RTL」的功能验证，不做时序收敛，因此不需要真实
工艺库数据。实测 XLS 侧只 load 了这个宏、**从未调用**，所以空实现足够。

将来若要做真实面积/时序基线（方案里的 M4），必须换成真 PDK。
"""

def for_each_sky130_cells(*args, **kwargs):
    """存根：真实实现会为每个 sky130 标准单元生成数据文件。"""
    pass
