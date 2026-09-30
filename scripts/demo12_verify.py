#!/usr/bin/env python3
"""demo12（L2/L3 交换机 v2：并行查找 + 合并仲裁 + 报文编辑 + deparser）行为验证。

用 `p4xls eval-ir`（P4C.Interp，IR 级抽象求值）对每个 action 的 DAG 做断言。
本机无 iverilog，无法 RTL 仿真；IR 求值与 RTL 发射共用同一套语义。

关键原则：期望值**不抄运行结果** ——
  - 校验和由 Python 独立实现的 RFC 1624 增量公式给出；
  - TTL / 端口位图 / 统计直接计算。

场景覆盖：
  ① classify 预分类（isL3 门控位：目的 MAC == 交换机 MAC）
  ② l3_forward（route_table action）：决策字段 + Counter
  ③ l2_forward（mac_table action）：决策字段 + Counter
  ④ resolve 合并仲裁：L3 命中 / L2 命中 / 全 miss 泛洪 三条路径
  ⑤ resolve 报文编辑：TTL 递减 + RFC 1624 校验和 + MAC 重写 + ttl=0 防下溢
  ⑥ drop_ttl（ttl_guard）：丢弃标记

用法:
  python3 scripts/demo12_verify.py [--ir-dir out/flow/demo12/ir_fn] [-v]
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# demo12 常量（与 P4 源码一致；这里独立抄录，用于计算期望值）
SWITCH_MAC = 0x020000000001
NH_MAC_3 = 0x00DEADBEEF01
OLD_DST = 0x001122334455
OLD_SRC = 0x00AABBCCDDEE
PM_FLOOD = 0x000E
FWD_L2, FWD_L3, FWD_FLOOD, FWD_DROP = 1, 2, 3, 4


def rfc1624(hc: int, old_field: int, new_field: int, w: int = 16) -> int:
    """RFC 1624 增量式：HC' = ~(~HC + ~m + m')。独立参考实现。"""
    m = (1 << w) - 1
    return (~((~hc & m) + (~old_field & m) + new_field)) & m


def eval_ir(fn_ir: Path, inputs: dict[str, int], regs: dict[str, list[int]] | None = None) -> dict:
    cmd = [str(ROOT / "scripts" / "p4xls"), "eval-ir", str(fn_ir), "--json"]
    for k, v in inputs.items():
        cmd += ["--in", f"{k}={v}"]
    for k, vec in (regs or {}).items():
        cmd += ["--reg", f"{k}={','.join(str(x) for x in vec)}"]
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=str(ROOT))
    if p.returncode != 0:
        raise RuntimeError(f"eval-ir 失败（exit {p.returncode}）：{p.stderr.strip()[:300]}")
    return json.loads(p.stdout)


def outs(res: dict) -> dict[str, int]:
    return {k: v["value"] for k, v in res.get("outputs", {}).items()}


def fmt(v) -> str:
    if isinstance(v, int) and v > 9:
        return f"{v} (0x{v:x})"
    return str(v)


class Checker:
    def __init__(self):
        self.n_pass = 0
        self.n_fail = 0

    def case(self, name: str):
        print(f"\n■ {name}")

    def eq(self, what: str, got, want) -> None:
        if got == want:
            self.n_pass += 1
            print(f"  ✅ {what}: {fmt(got)}")
        else:
            self.n_fail += 1
            print(f"  ❌ {what}: 期望 {fmt(want)}，实际 {fmt(got)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ir-dir", default="out/flow/demo12/ir_fn")
    args = ap.parse_args()

    ir_dir = (ROOT / args.ir_dir) if not Path(args.ir_dir).is_absolute() else Path(args.ir_dir)
    if not ir_dir.is_dir():
        print(f"ERROR: 找不到 {ir_dir}，先跑 scripts/p4flow testcases/p4/demo12-l2l3-switch.p4", file=sys.stderr)
        return 2

    def fn(suffix: str) -> Path:
        hits = sorted(ir_dir.glob(f"*{suffix}.ir"))
        if not hits:
            raise RuntimeError(f"{ir_dir} 下找不到 *{suffix}.ir")
        return hits[0]

    c = Checker()

    # ================= ① 预分类 =================
    c.case("classify：预分类 isL3（目的 MAC == 交换机 MAC），并清决策字段")
    res = eval_ir(fn("action_classify"), {"hdr.ethernet.dstAddr": SWITCH_MAC, "hdr.ipv4.totalLen": 500})
    o = outs(res)
    c.eq("三层包（目的 MAC = 交换机 MAC）⇒ isL3 = 1", o.get("meta.isL3"), 1)
    c.eq("ipLen = IPv4 totalLen", o.get("meta.ipLen"), 500)
    c.eq("清 macHit / rtHit", (o.get("meta.macHit"), o.get("meta.rtHit")), (0, 0))
    c.eq("dropReason = DROP_NONE", o.get("meta.dropReason"), 0)
    res = eval_ir(fn("action_classify"), {"hdr.ethernet.dstAddr": 0x001122334455})
    c.eq("二层包 ⇒ isL3 = 0", outs(res).get("meta.isL3"), 0)

    # ================= ② route_table 的 action =================
    c.case("l3_forward（route_table）：只写决策字段 + Counter，不改报文")
    res = eval_ir(fn("rt_table_route_table_l3_forward"), {
        "__rtarg.port": 0, "__rtarg.nexthopMac": NH_MAC_3, "__rtarg.srcMac": SWITCH_MAC,
    })
    o = outs(res)
    c.eq("rtHit = 1", o.get("meta.rtHit"), 1)
    c.eq("rtPort = 0（上联口）", o.get("meta.rtPort"), 0)
    c.eq("rtDstMac = 下一跳 MAC", o.get("meta.rtDstMac"), NH_MAC_3)
    c.eq("rtSrcMac = 交换机 MAC", o.get("meta.rtSrcMac"), SWITCH_MAC)
    c.eq("fwdCnt[1] += 1（L3 类）", res.get("counterAdds", {}).get("fwdCnt[1]"), 1)
    c.eq("不改报文（无 hdr 输出）", any(k.startswith("hdr.") for k in o), False)

    c.case("l3_miss（route_table default）：rtHit = 0")
    c.eq("rtHit = 0", outs(eval_ir(fn("rt_table_route_table_l3_miss"), {})).get("meta.rtHit"), 0)

    # ================= ③ mac_table 的 action =================
    c.case("l2_forward（mac_table）：决策字段 + Counter")
    res = eval_ir(fn("rt_table_mac_table_l2_forward"), {"__rtarg.port": 3})
    o = outs(res)
    c.eq("macHit = 1", o.get("meta.macHit"), 1)
    c.eq("macPort = 3", o.get("meta.macPort"), 3)
    c.eq("fwdCnt[0] += 1（L2 类）", res.get("counterAdds", {}).get("fwdCnt[0]"), 1)

    c.case("l2_miss（mac_table default）：macHit = 0")
    c.eq("macHit = 0", outs(eval_ir(fn("rt_table_mac_table_l2_miss"), {})).get("meta.macHit"), 0)

    # ================= ④ resolve 合并仲裁（三条路径） =================
    c.case("resolve ①：L3 命中 ⇒ 决策 = 路由（上联口），L2 结果被优先级覆盖")
    res = eval_ir(fn("action_resolve"), {
        "meta.rtHit": 1, "meta.rtPort": 0, "meta.rtDstMac": NH_MAC_3, "meta.rtSrcMac": SWITCH_MAC,
        "meta.macHit": 1, "meta.macPort": 3,          # L2 同时命中 ⇒ 应被 L3 优先级覆盖
        "meta.ipLen": 100, "hdr.ipv4.ttl": 64, "hdr.ipv4.hdrChecksum": 0x1234,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("fwdType = FWD_L3(2)", o.get("meta.fwdType"), FWD_L3)
    c.eq("outPortIdx = 0", o.get("meta.outPortIdx"), 0)
    c.eq("outPort = 端口0 独热", o.get("meta.outPort"), 1 << 0)
    c.eq("resolve 只做决策（不改报文）", any(k.startswith("hdr.") for k in o), False)

    c.case("resolve ②：仅 L2 命中 ⇒ 决策 = 二层转发")
    res = eval_ir(fn("action_resolve"), {
        "meta.rtHit": 0, "meta.macHit": 1, "meta.macPort": 3,
        "meta.ipLen": 100, "hdr.ipv4.ttl": 64, "hdr.ipv4.hdrChecksum": 0x1234,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("fwdType = FWD_L2(1)", o.get("meta.fwdType"), FWD_L2)
    c.eq("outPortIdx = 3", o.get("meta.outPortIdx"), 3)
    c.eq("outPort = 端口3 独热", o.get("meta.outPort"), 1 << 3)

    c.case("resolve ③：全 miss ⇒ 泛洪（掩码 0x000e，无单一端口）")
    res = eval_ir(fn("action_resolve"), {
        "meta.rtHit": 0, "meta.macHit": 0,
        "meta.ipLen": 100, "hdr.ipv4.ttl": 64, "hdr.ipv4.hdrChecksum": 0x1234,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("fwdType = FWD_FLOOD(3)", o.get("meta.fwdType"), FWD_FLOOD)
    c.eq("outPort = 泛洪掩码", o.get("meta.outPort"), PM_FLOOD)
    c.eq("outPortIdx = 15（无单一端口）", o.get("meta.outPortIdx"), 15)

    # ================= ⑤ rewrite 报文编辑（核心；独立相位，读 resolve 的决策） =================
    c.case("rewrite ①：报文编辑 —— TTL 递减 + RFC1624 校验和 + MAC 重写（L3 命中）")
    HC, TTL, IPLEN = 0x1234, 64, 100
    res = eval_ir(fn("action_rewrite"), {
        "meta.rtHit": 1, "meta.rtDstMac": NH_MAC_3, "meta.rtSrcMac": SWITCH_MAC,
        "meta.fwdType": FWD_L3, "meta.outPortIdx": 0, "meta.ipLen": IPLEN,
        "hdr.ipv4.ttl": TTL, "hdr.ipv4.hdrChecksum": HC,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("TTL 递减 64 → 63", o.get("hdr.ipv4.ttl"), TTL - 1)
    c.eq("IP 校验和（独立 RFC1624 参考实现比对）",
         o.get("hdr.ipv4.hdrChecksum"), rfc1624(HC, TTL, TTL - 1))
    c.eq("目的 MAC → 下一跳", o.get("hdr.ethernet.dstAddr"), NH_MAC_3)
    c.eq("源 MAC → 交换机 MAC", o.get("hdr.ethernet.srcAddr"), SWITCH_MAC)
    c.eq("portBytes[0] += ipLen（L3 转发统计）",
         res.get("regWrites", {}).get("portBytes[0]"), IPLEN)

    c.case("rewrite ②：L2 转发不改报文，但字节统计计入端口 3")
    res = eval_ir(fn("action_rewrite"), {
        "meta.rtHit": 0, "meta.macHit": 1,
        "meta.fwdType": FWD_L2, "meta.outPortIdx": 3, "meta.ipLen": IPLEN,
        "hdr.ipv4.ttl": 64, "hdr.ipv4.hdrChecksum": HC,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("TTL 不变", o.get("hdr.ipv4.ttl"), 64)
    c.eq("校验和不变", o.get("hdr.ipv4.hdrChecksum"), HC)
    c.eq("MAC 不变", (o.get("hdr.ethernet.dstAddr"), o.get("hdr.ethernet.srcAddr")), (OLD_DST, OLD_SRC))
    c.eq("portBytes[3] += ipLen", res.get("regWrites", {}).get("portBytes[3]"), IPLEN)

    c.case("rewrite ③：泛洪不统计字节（增量 0），报文不变")
    res = eval_ir(fn("action_rewrite"), {
        "meta.rtHit": 0, "meta.macHit": 0,
        "meta.fwdType": FWD_FLOOD, "meta.outPortIdx": 15, "meta.ipLen": IPLEN,
        "hdr.ipv4.ttl": 64, "hdr.ipv4.hdrChecksum": HC,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("TTL 不变", o.get("hdr.ipv4.ttl"), 64)
    c.eq("portBytes[7]（15[2:0]）增量为 0", res.get("regWrites", {}).get("portBytes[7]"), 0)

    c.case("rewrite ④：TTL 边界 —— ttl=1 → 0 并标记丢弃；ttl=0 防下溢")
    res = eval_ir(fn("action_rewrite"), {
        "meta.rtHit": 1, "meta.rtDstMac": NH_MAC_3, "meta.rtSrcMac": SWITCH_MAC,
        "meta.fwdType": FWD_L3, "meta.outPortIdx": 0, "meta.ipLen": 100,
        "hdr.ipv4.ttl": 1, "hdr.ipv4.hdrChecksum": 0xFFFF,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("TTL 1 → 0", o.get("hdr.ipv4.ttl"), 0)
    c.eq("dropReason = DROP_TTL(1)", o.get("meta.dropReason"), 1)
    c.eq("校验和 = RFC1624(HC,1,0)", o.get("hdr.ipv4.hdrChecksum"), rfc1624(0xFFFF, 1, 0))
    res = eval_ir(fn("action_rewrite"), {
        "meta.rtHit": 1, "meta.rtDstMac": NH_MAC_3, "meta.rtSrcMac": SWITCH_MAC,
        "meta.fwdType": FWD_L3, "meta.outPortIdx": 0, "meta.ipLen": 100,
        "hdr.ipv4.ttl": 0, "hdr.ipv4.hdrChecksum": 0x1234,
        "hdr.ethernet.dstAddr": OLD_DST, "hdr.ethernet.srcAddr": OLD_SRC,
    })
    o = outs(res)
    c.eq("TTL=0 防下溢：保持 0（不减为 255）", o.get("hdr.ipv4.ttl"), 0)
    c.eq("TTL=0 时校验和不动", o.get("hdr.ipv4.hdrChecksum"), 0x1234)
    c.eq("TTL=0 标记 DROP_TTL", o.get("meta.dropReason"), 1)

    # ================= ⑥ ttl_guard =================
    c.case("drop_ttl（ttl_guard）：L3 包 TTL 归零 ⇒ 丢弃")
    res = eval_ir(fn("table_ttl_guard_drop_ttl"), {})
    o = outs(res)
    c.eq("fwdType = FWD_DROP(4)", o.get("meta.fwdType"), FWD_DROP)
    c.eq("dropReason = DROP_TTL(1)", o.get("meta.dropReason"), 1)
    c.eq("fwdCnt[2] += 1（丢弃类）", res.get("counterAdds", {}).get("fwdCnt[2]"), 1)
    c.case("ttl_guard nop：未命中 ⇒ 零输出")
    c.eq("无任何输出", len(eval_ir(fn("table_ttl_guard_nop"), {}).get("outputs", {})), 0)

    print()
    print(f"断言数: {c.n_pass + c.n_fail}  通过: {c.n_pass}  失败: {c.n_fail}")
    return 1 if c.n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
