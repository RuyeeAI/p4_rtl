"""最小存根，替代 @rules_hdl//dependency_support/org_theopenroadproject_asap7_pdk_r1p7:cells_info.bzl。

与 com_google_skywater_pdk/cells_info.bzl 同理：真实实现用
`for cell_name, cell_target in for_each_asap7_cells(...)` 遍历 ASAP7 标准单元
（7nm 预测性工艺库）生成延迟/面积数据 target。

本工程只做功能验证，不需要真实工艺库 → 返回空列表让调用方的 for 循环不执行。

⚠️ 同样必须 `return []` 而非 `pass`（后者返回 None，会导致
   Error: type 'NoneType' is not iterable）。

将来做面积/时序基线（M4）时必须换成真 PDK。
"""

def for_each_asap7_cells(*args, **kwargs):
    """存根：不产出任何标准单元。

    Args:
      *args: 占位，兼容真实实现的位置参数。
      **kwargs: 占位，兼容真实实现的关键字参数。

    Returns:
      空列表，使调用方的 for 循环不执行。
    """
    _ = (args, kwargs)  # 显式引用，避免 lint 报"未使用"
    return []
