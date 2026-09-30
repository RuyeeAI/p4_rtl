#!/usr/bin/env python3
"""demo12（L2/L3 交换机）的行为验证 —— 用 IR 抽象求值做逐 action 断言。

为什么不用 Verilog 仿真：本机没有 iverilog/vvp，`p4flow` 的仿真步骤会被 SKIP。
`p4xls eval-ir` 走的是 `P4C.Interp`（与 RTL 发射逐条对齐的 IR 语义），
结论强度等价于"I R 级行为验证"——不依赖任何外部二进制。

关键点：期望值**不抄运行结果**，而是由独立参考实现给出：
  - TTL / 端口位图 / 统计：直接算
  - IP 校验和：在 Python 里独立实现 RFC 1624 增量更新公式，与 IR 求值结果比对
这样才构成"两条独立路径得到同一结论"。

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
MASK16 = (1 << 16) - 1


def rfc1624(hc: int, old_field: int, new_field: int, w: int = 16) -> int:
    """RFC 1624 增量式校验和更新：HC' = ~(~HC + ~m + m')。

    独立于 P4/IR 的参考实现（Python 任意精度整数，故每步显式取反截断）。
    """
    m = (1 << w) - 1
    return (~((~hc & m) + (~old_field & m) + new_field)) & m


def eval_ir(fn_ir: Path, inputs: dict[str, int], regs: dict[str, list[int]] | None = None) -> dict:
    """调 p4xls eval-ir 拿 JSON 结果。"""
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


def regs_of(res: dict) -> dict[str, int]:
    return dict(res.get("regWrites", {}))


def cnts_of(res: dict) -> dict[str, int]:
    return dict(res.get("counterAdds", {}))


class Checker:
    def __init__(self, verbose: bool):
        self.n_pass = 0
        self.n_fail = 0
        self.verbose = verbose
        self.group = ""

    def case(self, name: str):
        self.group = name
        print(f"\n■ {name}")

    def eq(self, what: str, got, want) -> None:
        ok = got == want
        if ok:
            self.n_pass += 1
            print(f"  ✅ {what}: {self.fmt(got)}")
        else:
            self.n_fail += 1
            print(f"  ❌ {what}: 期望 {self.fmt(want)}，实际 {self.fmt(got)}")

    @staticmethod
    def fmt(v) -> str:
        if isinstance(v, int) and v > 9:
            return f"{v} (0x{v:x})"
        return str(v)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ir-dir", default="out/flow/demo12/ir_fn")
    ap.add_argument("-v", "--verbose", action="store_true")
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

    c = Checker(args.verbose)

    # ================= ① L3 路由 + 报文编辑（核心） =================
    c.case("l3_forward：L3 路由 + 报文编辑（TTL / 校验和 / 下一跳 MAC）")
    HC, TTL, IPLEN = 0x1234, 64, 100
    res = eval_ir(
        fn("route_table_l3_forward"),
        {"hdr.ipv4.ttl": TTL, "hdr.ipv4.hdrChecksum": HC, "meta.ipLen": IPLEN},
    )
    o, rw, ca = outs(res), regs_of(res), cnts_of(res)

    c.eq("TTL 递减 64 → 63", o.get("hdr.ipv4.ttl"), TTL - 1)
    c.eq(
        "IP 校验和增量更新（独立 RFC1624 参考实现比对）",
        o.get("hdr.ipv4.hdrChecksum"),
        rfc1624(HC, TTL, TTL - 1),
    )
    c.eq("目的 MAC → 下一跳 MAC", o.get("hdr.ethernet.dstAddr"), 0x00DEADBEEF01)
    c.eq("源 MAC → 交换机 MAC", o.get("hdr.ethernet.srcAddr"), 0x020000000001)
    c.eq("fwdType = FWD_L3(2)", o.get("meta.fwdType"), 2)
    c.eq("出端口位图 = 端口0 独热", o.get("meta.outPort"), 1 << 0)
    c.eq("出端口号 = 0", o.get("meta.outPortIdx"), 0)
    c.eq("portBytes[0] += ipLen", rw.get("portBytes[0]"), IPLEN)
    c.eq("fwdCnt[1] += 1（L3 类）", ca.get("fwdCnt[1]"), 1)

    # 校验和与"从头重算"是否一致：用标准 16 位反码和独立验证
    #   IP 头为 12 字节固定字段 + src/dst + 校验和自身置 0 —— 这里只验证
    #   TTL 变更带来的增量，与 RFC1624 的定义式一致即算通过（上面已比对）。

    # 边界：TTL=1 → 递减后 0（供 ttl_guard 拦下）
    res1 = eval_ir(fn("route_table_l3_forward"), {"hdr.ipv4.ttl": 1, "hdr.ipv4.hdrChecksum": 0xFFFF})
    c.eq("边界 TTL=1 → 0", outs(res1).get("hdr.ipv4.ttl"), 0)

    # ================= ② L2 转发 =================
    c.case("l2_forward：按目的 MAC 从指定端口送出（表项实例为端口 3）")
    res = eval_ir(fn("mac_table_l2_forward"), {"meta.ipLen": IPLEN})
    o, rw, ca = outs(res), regs_of(res), cnts_of(res)
    c.eq("fwdType = FWD_L2(1)", o.get("meta.fwdType"), 1)
    c.eq("出端口位图 = 端口3 独热", o.get("meta.outPort"), 1 << 3)
    c.eq("出端口号 = 3", o.get("meta.outPortIdx"), 3)
    c.eq("portBytes[3] += ipLen", rw.get("portBytes[3]"), IPLEN)
    c.eq("fwdCnt[0] += 1（L2 类）", ca.get("fwdCnt[0]"), 1)
    c.eq("L2 转发不改 TTL（无该输出）", "hdr.ipv4.ttl" in o, False)

    # ================= ③ 未知单播泛洪 =================
    c.case("flood：未知单播泛洪到端口 1/2/3")
    res = eval_ir(fn("mac_table_flood"), {})
    o, ca = outs(res), cnts_of(res)
    c.eq("fwdType = FWD_FLOOD(3)", o.get("meta.fwdType"), 3)
    c.eq("出端口位图 = 0x000e（端口1/2/3）", o.get("meta.outPort"), 0x000E)
    c.eq("出端口号 = 15（无单一端口）", o.get("meta.outPortIdx"), 15)
    c.eq("fwdCnt[3] += 1（泛洪类）", ca.get("fwdCnt[3]"), 1)

    # ================= ④ 上三层（门控设计验证） =================
    c.case("to_l3：只置转发类型，出端口留给路由表（门控设计）")
    res = eval_ir(fn("mac_table_to_l3"), {})
    o = outs(res)
    c.eq("fwdType = FWD_L3(2)", o.get("meta.fwdType"), 2)
    c.eq("不写 outPort（交由 route_table 决定）", "meta.outPort" in o, False)
    c.eq("不写 outPortIdx", "meta.outPortIdx" in o, False)

    # ================= ⑤ TTL 耗尽丢弃 =================
    c.case("drop_ttl：TTL 耗尽丢弃")
    res = eval_ir(fn("ttl_guard_drop_ttl"), {})
    o, ca = outs(res), cnts_of(res)
    c.eq("fwdType = FWD_DROP(4)", o.get("meta.fwdType"), 4)
    c.eq("dropReason = DROP_TTL(1)", o.get("meta.dropReason"), 1)
    c.eq("fwdCnt[2] += 1（丢弃类）", ca.get("fwdCnt[2]"), 1)

    # ================= ⑥ 直通（不命中任何条目） =================
    c.case("nop：路由/ TTL 守卫未命中 ⇒ 零输出（直通，不改包）")
    for nm in ("route_table_nop", "ttl_guard_nop"):
        res = eval_ir(fn(nm), {})
        c.eq(f"{nm} 无任何输出", len(res.get("outputs", {})), 0)

    # ================= ⑦ 分类 =================
    c.case("classify：取 IPv4 长度 + 清丢弃原因")
    res = eval_ir(fn("action_classify"), {"hdr.ipv4.totalLen": 500})
    o = outs(res)
    c.eq("ipLen = IPv4 totalLen", o.get("meta.ipLen"), 500)
    c.eq("dropReason = DROP_NONE(0)", o.get("meta.dropReason"), 0)

    print()
    print(f"断言数: {c.n_pass + c.n_fail}  通过: {c.n_pass}  失败: {c.n_fail}")
    return 1 if c.n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
