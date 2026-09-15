"""最小存根，替代 @rules_hdl//dependency_support/org_theopenroadproject_asap7_pdk_r1p7:cells_info.bzl。

见 com_google_skywater_pdk/cells_info.bzl 的说明：只做功能验证时不需要
真工艺库；将来做面积/时序基线（M4）时必须换成真 PDK。
"""

def for_each_asap7_cells(*args, **kwargs):
    """存根：真实实现会为每个 ASAP7 标准单元生成数据文件。"""
    pass
