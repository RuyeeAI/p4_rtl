#!/usr/bin/env python3
"""生成「N 张表争用同一 proc」的 II 探针（XLS IR 文本）。

## 为什么需要

P0 遗留事项：「多通道 / 多表在同一 proc 内的 II 未验证 —— A3 首个检查点就该扫。」
而 A2 的架构选择直接依赖它：若多对 Key/Response 通道能保持 II=1，
整个 P4 control 就可以放进**一个** proc（结构简单得多）。

## 结构（每张表 2 个相位：发 key / 收 rsp，末尾 1 个 dispatch 相位）

    phase:  IDLE(发key1) → WAIT1(收rsp1) → MID1(发key2) → WAIT2(收rsp2)
            → ... → DISPATCH(发result) → IDLE(0)

    相位总数 2N+1；通道数 2N+1（N 个 key_out + N 个 rsp_in + 1 个 result_out）

与 M0/P0 一致的做法：
  - 所有通道操作都直接接 state_read(tok__1)，**互不串链**（P0 定案，这是 II=1 的关键）；
  - 各操作的 predicate（FSM 相位）互斥；
  - 末尾用 after_all 汇聚（2N+1 个操作数）。

第 i 张表（i≥2）的 key 取自上张表的 response —— 复现真实的表级串联依赖。

## 用法

    scripts/gen_multichan_probe.py 4          # 写 testcases/a2/ii_probe_4tables.ir
    scripts/ii_sweep.sh testcases/a2/ii_probe_4tables.ir probe_Ntables

注意 ii_sweep.sh 只用现成的 codegen driver（不需要 bazel），几秒钟出结果。
"""

import math
import pathlib
import sys

W = 32  # 通道数据宽度（统一 bits[32]）


def gen(n: int) -> str:
    assert n >= 1, "至少 1 张表"
    nph = 2 * n + 1  # 相位总数
    pw = max(1, math.ceil(math.log2(nph)))  # 相位位宽

    nid = 0

    def nxt() -> int:
        nonlocal nid
        nid += 1
        return nid

    # 相位语义（注释用）
    def phase_desc(k: int) -> str:
        if k == 0:
            return "IDLE：发 key1"
        if k == nph - 1:
            return "DISPATCH：发 result"
        if k % 2 == 0:
            return f"MID{k // 2}：发 key{k // 2 + 1}"
        return f"WAIT{(k + 1) // 2}：收 rsp{(k + 1) // 2}"

    chan_decl = []
    ports = []
    for i in range(1, n + 1):
        ports.append(f"    key{i}_out: bits[{W}] out")
        ports.append(f"    rsp{i}_in: bits[{W}] in")
    ports.append(f"    result_out: bits[{W}] out")

    for i in range(1, n + 1):
        chan_decl.append(
            f"  chan_interface key{i}_out(direction=send, kind=streaming, "
            f"strictness=proven_mutually_exclusive, flow_control=valid_data, flop_kind=none)")
        chan_decl.append(
            f"  chan_interface rsp{i}_in(direction=receive, kind=streaming, "
            f"strictness=proven_mutually_exclusive, flow_control=valid_data, flop_kind=none)")
    chan_decl.append(
        f"  chan_interface result_out(direction=send, kind=streaming, "
        f"strictness=proven_mutually_exclusive, flow_control=ready_valid, flop_kind=none)")

    # state 形参
    state_params = ["    tok: token", f"    phase: bits[{pw}]"]
    for i in range(1, n + 1):
        state_params.append(f"    hold{i}: bits[{W}]")
    init_vals = ["token"] + ["0"] * (1 + n)

    body = []
    body.append("  // ---- 读状态 ----")
    body.append(f"  tok__1: token = state_read(state_element=tok, id={nxt()})")
    body.append(f"  phase__1: bits[{pw}] = state_read(state_element=phase, id={nxt()})")
    for i in range(1, n + 1):
        body.append(f"  hold{i}__1: bits[{W}] = state_read(state_element=hold{i}, id={nxt()})")

    body.append("")
    body.append("  // ---- 相位判定 ----")
    for k in range(nph):
        body.append(f"  // phase {k} = {phase_desc(k)}")
        body.append(f"  p_{k}: bits[{pw}] = literal(value={k}, id={nxt()})")
    for k in range(nph):
        body.append(f"  is_{k}: bits[1] = eq(phase__1, p_{k}, id={nxt()})")

    body.append("")
    body.append("  // ---- 通道操作：全部直接接 tok__1，互不串链（P0 定案）----")
    chan_ops = []  # after_all 的操作数（按序）
    for i in range(1, n + 1):
        key_phase = 2 * (i - 1)  # 发 key_i 的相位
        rsp_phase = 2 * i - 1    # 收 rsp_i 的相位
        if i == 1:
            key_expr = f"key{i}"
            body.append(f"  key{i}: bits[{W}] = literal(value={1000 + i}, id={nxt()})")
        else:
            # 表级串联：第 i 张表的 key 用上一张表的 response（真实依赖）
            key_expr = f"hold{i - 1}__1"
        sid = nxt()
        body.append(f"  s_key{i}: token = send(tok__1, {key_expr}, predicate=is_{key_phase}, "
                    f"channel=key{i}_out, id={sid})")
        chan_ops.append(f"s_key{i}")
        rid = nxt()
        body.append(f"  recv{i}: (token, bits[{W}]) = receive(tok__1, predicate=is_{rsp_phase}, "
                    f"channel=rsp{i}_in, id={rid})")
        body.append(f"  t{i}: token = tuple_index(recv{i}, index=0, id={nxt()})")
        body.append(f"  rsp{i}: bits[{W}] = tuple_index(recv{i}, index=1, id={nxt()})")
        chan_ops.append(f"t{i}")

    disp_phase = nph - 1
    body.append(f"  s_out: token = send(tok__1, hold{n}__1, predicate=is_{disp_phase}, "
                f"channel=result_out, id={nxt()})")
    chan_ops.append("s_out")

    body.append("")
    body.append("  // ---- 相位推进（k → k+1；末相位 → 0）----")
    for k in range(nph):
        body.append(f"  n_{k}: bits[{pw}] = literal(value={k}, id={nxt()})")
    cases = [f"n_{k + 1}" for k in range(nph - 1)] + ["n_0"]
    body.append("  next_phase: bits[%d] = sel(" % pw)
    body.append("      phase__1,")
    body.append("      cases=[%s]," % ", ".join(cases))
    body.append("      default=n_0,")
    body.append(f"      id={nxt()})")

    body.append("")
    body.append("  // ---- 保存各自 response（只在对应 WAIT 相位采样）----")
    for i in range(1, n + 1):
        rsp_phase = 2 * i - 1
        cs = [f"rsp{i}" if k == rsp_phase else f"hold{i}__1" for k in range(nph)]
        body.append("  next_hold%d: bits[%d] = sel(" % (i, W))
        body.append("      phase__1,")
        body.append("      cases=[%s]," % ", ".join(cs))
        body.append(f"      default=hold{i}__1,")
        body.append(f"      id={nxt()})")

    body.append("")
    body.append(f"  // ---- token 汇聚（{len(chan_ops)} 个操作数）----")
    body.append(f"  next_tok_all: token = after_all({', '.join(chan_ops)}, id={nxt()})")

    body.append("")
    body.append("  // 语法要点：next_value 要写 `next_value.<id>: () = ` 前缀（M0 K6）")
    body.append(f"  next_value.{nxt()}: () = next_value(state_element=tok, value=next_tok_all, id={nid})")
    nid_v = nid
    body.append(f"  next_value.{nxt()}: () = next_value(state_element=phase, value=next_phase, id={nid})")
    for i in range(1, n + 1):
        body.append(f"  next_value.{nxt()}: () = next_value(state_element=hold{i}, value=next_hold{i}, id={nid})")

    header = f"""// A2 前置实验：同一 proc 内 **{n} 张表**（{2 * n + 1} 个通道）时的最小 II
//
// **本文件由 scripts/gen_multichan_probe.py {n} 生成，请勿手改。**
//
// 目的：回答「一个 proc 里放多少对 Key/Response 通道后 II 会退化？」
//       A2 架构（整个 control 放进一个 proc）直接依赖这个结论。
//
// 结构：{' → '.join(phase_desc(k) for k in range(nph))}
//
// 与 M0/P0 一致：通道操作都直连 state_read(tok__1)、互不串链；
// predicate(FSM 相位) 互斥；末尾 after_all 汇聚（{2 * n + 1} 个操作数）。
// 第 i 张表（i≥2）的 key 取自上张表的 response —— 复现真实表级串联依赖。
//
// 判定：最小 II 是否仍为 1。
//   scripts/ii_sweep.sh testcases/a2/ii_probe_{n}tables.ir probe_{n}tables

package a2_ii_probe_{n}tbl
"""

    return "\n".join([
        header,
        "#[initiation_interval(1)]",
        f"top proc probe_{n}tables<",
        ",\n".join(ports),
        ">(",
        # 注意：state 形参列表后面**必须留逗号** —— 后面还跟着 init=... 参数。
        # 用 ",\n".join() 时最后一个元素不带逗号，直接接 init 会导致
        # `Invalid parameter keyword argument \`init\``（踩过）。
        ",\n".join(state_params) + ",",
        f"    init={{{', '.join(init_vals)}}}",
        ") {",
        "\n".join(chan_decl),
        "",
        "\n".join(body),
        "}",
        "",
    ])


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    n = int(sys.argv[1])
    root = pathlib.Path(__file__).resolve().parent.parent
    out = root / "testcases" / "a2" / f"ii_probe_{n}tables.ir"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(gen(n), encoding="utf-8")
    print(f"✅ 已生成 {out.relative_to(root)}  "
          f"({n} 张表 / {2 * n + 1} 个通道 / {2 * n + 1} 个相位)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
