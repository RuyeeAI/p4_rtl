#!/usr/bin/env python3
"""xls_ir_lint.py 的反向自测（negative self-test）。

目的：证明这个静态校验器**不是恒过**。
  - 每个用例构造一份最小 IR，只注入**一处**已知错误；
  - 断言 lint 恰好报出预期的 issue 码；
  - 另有若干"正向控制"用例（合法写法）必须 0 error。

全部规则都对应 third_party/xls 的真实源码判定，用例注释里标了出处。

用法:
  python3 scripts/xls_ir_lint_selftest.py
  python3 scripts/xls_ir_lint_selftest.py -v      # 打印每个用例的详情
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import json  # noqa: E402

import xls_ir_lint as L  # noqa: E402

# --------------------------------------------------------------------------
# 合法基线：proc（新式：<> 通道 + 数组 state + init={...}）
# --------------------------------------------------------------------------
PROC_HEAD = """package t

proc p<pkt: bits[8] in, out_ch: bits[8] out>(
    tok: token, s: bits[32], arr: bits[8][4], init={token, 0, [0, 0, 0, 0]}) {
  chan_interface pkt(direction=receive, kind=streaming, strictness=proven_mutually_exclusive, flow_control=valid_data, flop_kind=none)
  chan_interface out_ch(direction=send, kind=streaming, strictness=proven_mutually_exclusive, flow_control=ready_valid, flop_kind=none)
  t1: token = state_read(state_element=tok, id=1)
  s1: bits[32] = state_read(state_element=s, id=2)
  a0: bits[8] = array_index(arr, indices=[s1], id=3)
  literal.4: bits[32] = literal(value=7, id=4)
  add.5: bits[32] = add(s1, literal.4, id=5)
  next_value.6: () = next_value(state_element=s, value=add.5, id=6)
  next_value.7: () = next_value(state_element=tok, value=t1, id=7)
}
"""

FN_BASE = """package t

fn f(x: bits[32] id=4, y: bits[32] id=5) -> bits[32] {
  ret add.1: bits[32] = add(x, y, id=1)
}
"""

# --------------------------------------------------------------------------
# 用例：(名字, IR 文本, 期望出现的 issue 码，注释/出处)
#   expect=None 表示"必须完全干净"（正向控制）
# --------------------------------------------------------------------------
CASES: list[tuple[str, str, str | None, str]] = [
    # ---------------- 正向控制 ----------------
    ("ok:proc", PROC_HEAD, None, "合法新式 proc（数组 state / init / chan_interface）"),
    ("ok:fn", FN_BASE, None, "合法 fn"),
    (
        "ok:tuple_array_type",
        "package t\n\nfn f(x: (bits[32], bits[1]) id=4) -> (bits[32], bits[1])[3] {\n"
        "  ret array.1: (bits[32], bits[1])[3] = array(x, x, x, id=1)\n}\n",
        None,
        "元组数组类型 (T,T)[3]（ParseType:211 数组后缀对 tuple 同样适用）",
    ),
    (
        "ok:param_redefines_name",
        "package t\n\nfn f(x: bits[2] id=2) -> bits[2] {\n"
        "  ret x: bits[2] = param(name=x, id=2)\n}\n",
        None,
        "param 复用签名里的同名参数（ir_parser.cc:1486 明确豁免 Param）",
    ),
    (
        "ok:state_read_redefines_name",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        None,
        "state_read 复用 state 名（同上豁免 StateRead）",
    ),
    (
        "ok:chan_streaming_full",
        'package t\n\nchan c(bits[32], id=0, kind=streaming, ops=send_receive, '
        "flow_control=ready_valid, strictness=proven_mutually_exclusive, fifo_depth=4, "
        "bypass=true, register_push_outputs=true, register_pop_outputs=false)\n",
        None,
        "老式 chan 全套关键字（ParseChannel:2754）",
    ),
    (
        "ok:triple_quoted_attr",
        'package t\n\n#[ffi_proto("""code_template: "x {fn} ({a});"\n""")]\n'
        "fn f(a: bits[8] id=2) -> bits[8] {\n  ret a: bits[8] = param(name=a, id=2)\n}\n",
        None,
        '三引号多行字符串（ir_scanner MatchQuotedString:180，"`;` 不算非法字符）',
    ),
    (
        "ok:file_number",
        'package t\n\nfile_number 0 "foo.x"\n\nchan c(bits[8], id=0, kind=streaming, '
        "ops=send_only, flow_control=none)\n",
        None,
        'file_number 声明（编号 + 文件名两个 token）',
    ),
    (
        "ok:multi_proc_scope",
        "package t\n\nproc p1(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n\n"
        "proc p2(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=3)\n"
        "  next_value.4: () = next_value(state_element=s, value=s, id=4)\n}\n",
        None,
        "同名跨 proc 不冲突（名字作用域按 proc 隔离）",
    ),
    (
        "ok:proc_instantiation",
        "package t\n\nproc leaf(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n\n"
        "proc wrapper(s: bits[32], init={0}) {\n"
        "  proc_instantiation i0(proc=leaf)\n"
        "  s: bits[32] = state_read(state_element=s, id=3)\n"
        "  next_value.4: () = next_value(state_element=s, value=s, id=4)\n}\n",
        None,
        "proc_instantiation（ParseProcInstantiation:1592）",
    ),
    # ---------------- 词法 / 结构 ----------------
    ("err:lex", "package t\n\nfn f(x: bits[32]) -> bits[32] {\n  ret %\n}\n", "E_LEX", "非法字符"),
    ("err:top", "package t\n\n)\n", "E_TOP", "顶层出现垃圾 token"),
    ("err:stmt", "package t\n\nfn f(x: bits[32]) -> bits[32] {\n  1234\n}\n", "E_STMT", "函数体内无法识别的语句"),
    ("err:type", "package t\n\nfn f(x: bits[]) -> bits[8] {\n  ret x\n}\n", "E_TYPE", "bits[] 缺位宽"),
    # ---------------- op 契约 ----------------
    (
        "err:arity",
        "package t\n\nfn f(c: bits[1] id=4, a: bits[8] id=5, b: bits[8] id=6) -> bits[8] {\n"
        "  ret s.1: bits[8] = sel(c, [a, b], id=1)\n}\n",
        "E_ARITY",
        "sel 的位置参数给多了（cases 必须写关键字形式）",
    ),
    (
        "err:missing_kw:bit_slice",
        "package t\n\nfn f(x: bits[32] id=4) -> bits[8] {\n"
        "  ret s.1: bits[8] = bit_slice(x, id=1)\n}\n",
        "E_MISSING_KW",
        "bit_slice 缺 start/width",
    ),
    (
        "err:missing_kw:indices",
        "package t\n\nfn f(a: bits[8][4] id=4, i: bits[2] id=5) -> bits[8] {\n"
        "  ret v.1: bits[8] = array_index(a, i, id=1)\n}\n",
        "E_MISSING_KW",
        "array_index 必须写 indices=[...]",
    ),
    (
        "err:unknown_kw",
        "package t\n\nfn f() -> bits[8] {\n"
        "  ret l.1: bits[8] = literal(value=3, bogus=1, id=1)\n}\n",
        "E_UNKNOWN_KW",
        "literal 不存在的关键字",
    ),
    (
        "err:dup_kw",
        "package t\n\nfn f() -> bits[8] {\n"
        "  ret literal.1: bits[8] = literal(value=3, value=4, id=1)\n}\n",
        "E_DUP_KW",
        "重复关键字（ir_parser ParseKeywordArguments:92）",
    ),
    (
        "err:unknown_op",
        "package t\n\nfn f() -> bits[8] {\n  ret frobnicate.1: bits[8] = frobnicate(id=1)\n}\n",
        "W_UNKNOWN_OP",
        "未知 op（警告级：不在契约表）",
    ),
    # ---------------- 名字解析（ir_parser.cc:461） ----------------
    (
        "err:undef_ref",
        "package t\n\nfn f() -> bits[8] {\n  ret a.1: bits[8] = add(nosuch, nosuch2, id=1)\n}\n",
        "E_UNDEF",
        "引用未定义名字（先定义后引用）",
    ),
    (
        "err:forward_ref",
        "package t\n\nfn f() -> bits[8] {\n"
        "  a.1: bits[8] = add(b.2, c.3, id=1)\n"
        "  b.2: bits[8] = literal(value=1, id=2)\n"
        "  c.3: bits[8] = literal(value=2, id=3)\n  ret a.1\n}\n",
        "E_UNDEF",
        "前向引用（XLS 要求拓扑序）",
    ),
    (
        "err:dup_name",
        "package t\n\nfn f(x: bits[8] id=4) -> bits[8] {\n"
        "  a.1: bits[8] = literal(value=1, id=1)\n"
        "  a.1: bits[8] = literal(value=2, id=2)\n  ret x\n}\n",
        "E_DUP_NAME",
        "节点名重复（ir_parser.cc:1488）",
    ),
    (
        "err:node_op_mismatch",
        "package t\n\nfn f(x: bits[8] id=4, y: bits[8] id=5) -> bits[8] {\n"
        "  ret nibble.1: bits[8] = add(x, y, id=1)\n}\n",
        "E_NODE_OP",
        "名字里的 op 片段与实际 op 不一致（ir_parser.cc:1533）",
    ),
    (
        "err:node_id_mismatch",
        "package t\n\nfn f(x: bits[8] id=4, y: bits[8] id=5) -> bits[8] {\n"
        "  ret add.9: bits[8] = add(x, y, id=1)\n}\n",
        "E_NODE_ID",
        "名字后缀 .9 与 id=1 不一致（ir_parser.cc:1524）",
    ),
    # ---------------- proc 签名 ----------------
    (
        "err:init_missing",
        "package t\n\nproc p(s: bits[32]) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_MISSING_KW",
        "有 state 却缺 init={...}（ParseProcSignature:2279）",
    ),
    (
        "err:init_count",
        "package t\n\nproc p(s: bits[32], init={0, 0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_INIT_COUNT",
        "init 初值个数 != state 数（ParseProcSignature:2285）",
    ),
    (
        "err:proc_chan_direction",
        "package t\n\nproc p<in_ch: bits[8] inward>(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_ENUM",
        "proc 通道方向只能是 in/out（ParseProcSignature:2210）",
    ),
    (
        "err:state_undef",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  x.1: bits[32] = state_read(state_element=nosuch, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=x.1, id=2)\n}\n",
        "E_STATE_UNDEF",
        "state_element 未声明（ir_parser.cc:866）",
    ),
    (
        "err:next_arg_both",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, param=s, value=s, id=2)\n}\n",
        "E_NEXT_ARG",
        "next_value 同时给 param 与 state_element（ir_parser kNext 分支）",
    ),
    (
        "err:next_arg_neither",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(value=s, id=2)\n}\n",
        "E_NEXT_ARG",
        "next_value 既无 param 也无 state_element",
    ),
    (
        "err:chan_interface_missing_kind",
        "package t\n\nproc p<>(ch: bits[8] in)(s: bits[32], init={0}) {\n"
        "  chan_interface ch(direction=receive)\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_MISSING_KW",
        "chan_interface 缺 kind（ParseChannelInterface:3061）",
    ),
    (
        "err:chan_interface_enum",
        "package t\n\nproc p<ch: bits[8] in>(s: bits[32], init={0}) {\n"
        "  chan_interface ch(direction=recv, kind=streaming, strictness=total_order)\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_ENUM",
        "direction 取值非法（channel.cc:3005）",
    ),
    (
        "err:chan_interface_unknown_kw",
        "package t\n\nproc p<ch: bits[8] in>(s: bits[32], init={0}) {\n"
        "  chan_interface ch(direction=receive, kind=streaming, weird=none)\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_UNKNOWN_KW",
        "chan_interface 不存在的关键字（ParseChannelInterface:3061）",
    ),
    (
        "err:chan_interface_outside_proc",
        "package t\n\nfn f(x: bits[8] id=2) -> bits[8] {\n"
        "  chan_interface ch(direction=receive, kind=streaming)\n  ret x\n}\n",
        "E_STMT",
        "chan_interface 出现在 fn 体内（ir_parser.cc:1910）",
    ),
    (
        "err:chan_kind_streaming_only",
        'package t\n\nchan c(bits[32], id=0, kind=single_value, ops=send_only, fifo_depth=4)\n',
        "E_CHAN_KIND",
        "非 streaming 通道不能带 fifo_depth（ParseChannel:2936）",
    ),
    (
        "err:chan_missing_comma",
        "package t\n\nchan c(bits[32] id=0, kind=streaming, ops=send_only)\n",
        "E_EXPECT",
        "chan 类型后必须有逗号（ParseChannel:2795）",
    ),
    (
        "err:chan_missing_ops",
        "package t\n\nchan c(bits[32], id=0, kind=streaming)\n",
        "E_MISSING_KW",
        "chan 缺 ops=（ParseChannel:2923）",
    ),
    (
        "err:chan_ops_enum",
        "package t\n\nchan c(bits[32], id=0, kind=streaming, ops=both)\n",
        "E_ENUM",
        "ops 取值非法（ParseChannel:2829）",
    ),
    (
        "err:chan_undef",
        "package t\n\nproc p<ch: bits[8] in>(tok: token, s: bits[32], init={token, 0}) {\n"
        "  chan_interface ch(direction=receive, kind=streaming, strictness=total_order)\n"
        "  s1: bits[32] = state_read(state_element=s, id=1)\n"
        "  sd.2: token = send(tok, s1, channel=nosuch_channel, id=2)\n"
        "  next_value.3: () = next_value(state_element=s, value=s1, id=3)\n"
        "  next_value.4: () = next_value(state_element=tok, value=sd.2, id=4)\n}\n",
        "E_CHAN_UNDEF",
        "channel= 引用了未声明的通道",
    ),
    (
        "err:proc_instance_undef",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  proc_instantiation i0(proc=nosuch)\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_PROC_UNDEF",
        "proc_instantiation 引用了未定义的 proc（ir_parser.cc:1625）",
    ),
    (
        "err:proc_instance_missing_kw",
        "package t\n\nproc p(s: bits[32], init={0}) {\n"
        "  proc_instantiation i0()\n"
        "  s: bits[32] = state_read(state_element=s, id=1)\n"
        "  next_value.2: () = next_value(state_element=s, value=s, id=2)\n}\n",
        "E_MISSING_KW",
        "proc_instantiation 缺 proc=（ParseProcInstantiation:1620）",
    ),
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--contracts", default="config/op_contracts.json")
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    cpath = Path(args.contracts)
    if not cpath.is_absolute():
        cpath = (Path.cwd() / args.contracts) if (Path.cwd() / args.contracts).exists() else root / args.contracts
    if not cpath.exists():
        print(f"ERROR: 缺少契约表 {cpath}", file=sys.stderr)
        return 2
    contracts = json.loads(cpath.read_text(encoding="utf-8"))["contracts"]

    n_pass = n_fail = 0
    with tempfile.TemporaryDirectory() as td:
        for name, src, expect, why in CASES:
            fp = Path(td) / f"{name.replace(':', '_')}.ir"
            fp.write_text(src, encoding="utf-8")
            res = L.lint_text(src, contracts, str(fp))
            codes = [i.code for i in res.issues]
            errs = [i.code for i in res.issues if i.severity == "error"]

            if expect is None:
                ok = not res.issues
                detail = "要求完全干净" if not ok else ""
            elif expect.startswith("W_"):
                ok = expect in codes and not errs
                detail = f"期望警告 {expect} 且无 error" if not ok else ""
            else:
                ok = expect in codes
                detail = f"期望 {expect}，实际 errors={errs}" if not ok else ""

            if ok:
                n_pass += 1
                print(f"  ✅ {name:38s} {expect or '(干净)':16s} {why}")
            else:
                n_fail += 1
                print(f"  ❌ {name:38s} {detail}")
                for i in res.issues[:5]:
                    print(f"       {i}")

            if args.verbose and ok and res.issues:
                for i in res.issues:
                    print(f"       ↳ {i}")

    print()
    print(f"自测用例: {len(CASES)}  通过: {n_pass}  失败: {n_fail}")
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
