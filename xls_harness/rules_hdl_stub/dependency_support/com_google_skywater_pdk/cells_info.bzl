"""最小存根，替代 @rules_hdl//dependency_support/com_google_skywater_pdk:cells_info.bzl。

真实实现会遍历 SkyWater sky130 标准单元，产出 (cell_name, cell_target) 列表，
调用方在 BUILD 里这样用：

    for cell_name, cell_target in for_each_sky130_cells("sc_hd"):
        ...

为每个单元生成延迟/面积数据 target。

本工程只做「P4 逻辑 → RTL」的功能验证，不做时序收敛，所以不需要真实工艺库。
返回**空列表**即可：调用方的 for 循环不执行，不生成任何工艺库 target。

⚠️ 踩坑记录：最初误判成"只 load、从未调用"（因为只 grep 到 load 行），
结果第一次构建才暴露 —— 调用点在 BUILD 的 for 语句里：

    Error: type 'NoneType' is not iterable

所以这里必须 `return []`，不能写成 `pass`（那样返回 None）。

将来若要做真实面积/时序基线（方案里的 M4），必须换成真 PDK。
"""

def for_each_sky130_cells(flavor, *args, **kwargs):
    """存根：不产出任何标准单元。

    Args:
      flavor: 工艺角 flavor（如 "sc_hd"），真实实现据此挑选单元集。
      *args: 占位，兼容真实实现的额外位置参数。
      **kwargs: 占位，兼容真实实现的额外关键字参数。

    Returns:
      空列表，使调用方的 for 循环不执行。
    """
    _ = (flavor, args, kwargs)  # 显式引用，避免 lint 报"未使用"
    return []
