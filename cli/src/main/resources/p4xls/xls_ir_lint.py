#!/usr/bin/env python3
"""XLS IR 文本静态校验器（A0 门禁工具）。

规则来源（全部取自 third_party/xls 的真实源码，非推测）：
  - 词法:        xls/ir/ir_scanner.h/.cc      (LexicalTokenType / Token::GetKeywords)
  - 顶层结构:    xls/ir/ir_parser.h           (ParseDerivedPackageNoVerify)
  - fn 签名:     xls/ir/ir_parser.cc          (ParseFunctionSignature / ParseType)
  - **proc 签名**: xls/ir/ir_parser.cc        (ParseProcSignature:2168
                   新式 proc `proc N<ch: T in, ...>(state..., init={...}, non_synth={...})`)
  - **chan_interface**: xls/ir/ir_parser.cc   (ParseChannelInterface:2986)
  - **通道枚举**:  xls/ir/channel.cc           (StringToChannelKind:202 / StringToFlowControl:285 /
                   ChannelStrictnessFromString:304 / StringToFlopKind:158)
  - **名字解析**:  xls/ir/ir_parser.cc         (ParseAndResolveIdentifier:461 —— 先定义后引用)
  - **节点名一致性**: xls/ir/ir_parser.cc      (SplitName:683 + 校验点 1524 / 1533
                   —— `名字.<N>` 的后缀必须等于该节点的 op 名与 `id=N`)
  - op 契约:     config/op_contracts.json     (由 gen_op_contracts.py 从 ir_parser.cc 提取)

校验器复现 XLS parser 的判定逻辑，用于在构建 XLS 之前快速定位不兼容点。

用法:
  python3 scripts/xls_ir_lint.py out/ir/demo9.ir
  python3 scripts/xls_ir_lint.py --recursive out/ir --json out/lint.json
  python3 scripts/xls_ir_lint.py --contracts config/op_contracts.json out/ir/*.ir
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------- 词法定义

# xls/ir/ir_scanner.h : Token::GetKeywords()
KEYWORDS = {
    "fn", "bits", "token", "ret", "package", "proc", "chan", "chan_interface",
    "reg", "next", "block", "clock", "instantiation", "top", "file_number",
    "proc_instantiation", "scheduled_proc", "scheduled_fn", "stage",
    "scheduled_block", "source", "controlled_stage", "active_inputs_valid",
    "active_outputs_ready",
}

# 多字符运算符（先于单字符匹配）
MULTI = [("->", "ARROW")]
# xls/ir/ir_scanner.h : LexicalTokenType —— 除 ident/number 外的全部标点
SINGLE = set("()[]{},:=+><.#!-")

RE_WS = re.compile(r"[ \t\r\n\f\v]+")
RE_COMMENT = re.compile(r"//[^\n]*")
RE_NUMBER = re.compile(
    r"(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*)"
)
RE_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
RE_TYPE_ANNOT = re.compile(r"bits\[\d+\]:")  # 值内嵌类型标注 bits[32]:0x42
RE_NODE_SUFFIX = re.compile(r"^(.*)\.([0-9]+)$")  # ir_parser.cc SplitName

# ------------------------------------------------ 通道 / proc 相关枚举
# 全部取自 xls/ir/channel.cc，逐字对齐（不要凭印象写）

CHANNEL_KINDS = {"streaming", "single_value"}                 # StringToChannelKind
FLOW_CONTROLS = {"none", "ready_valid", "valid_data"}          # StringToFlowControl
STRICTNESSES = {                                              # ChannelStrictnessFromString
    "proven_mutually_exclusive",
    "runtime_mutually_exclusive",
    "total_order",
    "runtime_ordered",
}
FLOP_KINDS = {"none", "flop", "skid", "zero_latency"}          # StringToFlopKind
CHAN_DIRECTIONS = {"receive", "send"}                          # chan_interface direction=
PROC_CHAN_DIRECTIONS = {"in", "out"}                           # proc <name: T in|out>
CHAN_OPS = {"send_receive", "receive_only", "send_only"}       # chan ops=（ParseChannel:2826）

# chan_interface 的关键字：direction/kind 必选，其余可选（ir_parser.cc:3061）
CHAN_INTERFACE_KW = {
    "direction": CHAN_DIRECTIONS,
    "kind": CHANNEL_KINDS,
    "flow_control": FLOW_CONTROLS,
    "strictness": STRICTNESSES,
    "flop_kind": FLOP_KINDS,
}
CHAN_INTERFACE_MANDATORY = {"direction", "kind"}

# 老式顶层 `chan` 声明的关键字（ir_parser.cc ParseChannel:2754 的 handlers）
# 必选 id / ops / kind；其余可选。值为 None 表示不做枚举校验。
CHAN_DECL_MANDATORY = {"id", "ops", "kind"}
CHAN_DECL_KW: dict[str, set[str] | None] = {
    "initial_values": None,
    "id": None,
    "kind": CHANNEL_KINDS,
    "ops": CHAN_OPS,
    "flow_control": FLOW_CONTROLS,
    "strictness": STRICTNESSES,
    "fifo_depth": None,
    "bypass": None,
    "register_push_outputs": None,
    "register_pop_outputs": None,
    "input_flop_kind": FLOP_KINDS,
    "output_flop_kind": FLOP_KINDS,
    "fifo_wrapper": None,
}
# 仅 streaming 通道允许出现的关键字（ParseChannel:2929 的校验）
CHAN_STREAMING_ONLY = {"flow_control", "fifo_depth", "strictness", "bypass"}

# 值位置出现的枚举量：做"名字引用"检查时要排除，避免误报
ENUM_VALUES = (
    CHANNEL_KINDS | FLOW_CONTROLS | STRICTNESSES | FLOP_KINDS
    | CHAN_DIRECTIONS | PROC_CHAN_DIRECTIONS | CHAN_OPS
    | {"true", "false", "truthy", "falsy", "negate", "identity"}
)

# 这些关键字的值是"名字 / 字符串 / 数字"，不是节点引用 —— 不做引用检查
NON_REF_KEYWORDS = {
    "channel", "state_element", "to_apply", "body", "label", "message", "rel",
    "tag", "source", "friendly_name", "trip_count", "stride", "index", "start",
    "width", "new_bit_count", "amount", "dimensions", "bit_count", "id", "pos",
    "direction", "kind", "strictness", "flow_control", "flop_kind", "ops",
}

# 列表形式的引用（sel / one_hot_sel / priority_sel 的 cases，array_index 的 indices）
REF_LIST_KEYWORDS = {"cases", "indices"}

# 所有 op 通用的属性关键字（ir_parser.cc ArgParser: id / pos；label 被大量 op 接受）
COMMON_KEYWORDS = {"id", "pos", "label"}


# ---------------------------------------------------------------- 词法器


@dataclass
class Token:
    kind: str  # IDENT / KEYWORD / NUMBER / STRING / PUNCT / ARROW / EOF
    value: str
    line: int
    col: int

    def __repr__(self) -> str:  # pragma: no cover
        return f"{self.kind}({self.value})@{self.line}:{self.col}"


class LexError(Exception):
    def __init__(self, msg: str, line: int, col: int):
        super().__init__(msg)
        self.line, self.col = line, col


def tokenize(src: str) -> list[Token]:
    """复现 xls/ir/ir_scanner.cc 的分词行为（含 // 注释跳过）。"""
    toks: list[Token] = []
    i, line, col = 0, 1, 1
    n = len(src)

    def bump(k: int) -> None:
        nonlocal i, line, col
        for _ in range(k):
            if i < n and src[i] == "\n":
                line, col = line + 1, 1
            else:
                col += 1
            i += 1

    while i < n:
        if m := RE_WS.match(src, i):
            bump(m.end() - i)
            continue
        if m := RE_COMMENT.match(src, i):
            bump(m.end() - i)
            continue
        # 值内嵌类型标注 bits[N]: 需整体吞掉，避免把 'bits' 当关键字分支
        if m := RE_TYPE_ANNOT.match(src, i):
            bump(m.end() - i)
            continue
        ch = src[i]
        if ch == '"' or ch == "`":
            # xls/ir/ir_scanner.cc MatchQuotedString:180
            #   `"` / 反引号 —— 单行（遇到换行即视为未闭合）
            #   `"""`        —— 多行（ffi_proto 等属性用）
            triple = src.startswith(ch * 3, i)
            quote = ch * 3 if triple else ch
            start_line, start_col = line, col
            j = i + len(quote)
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src.startswith(quote, j):
                    break
                if not triple and src[j] == "\n":
                    raise LexError("unterminated string (单行字符串跨行)", start_line, start_col)
                j += 1
            if j >= n or not src.startswith(quote, j):
                raise LexError("unterminated string", start_line, start_col)
            val = src[i : j + len(quote)]
            toks.append(Token("STRING", val, start_line, start_col))
            bump(j + len(quote) - i)
            continue
        matched = False
        for lit, kind in MULTI:
            if src.startswith(lit, i):
                toks.append(Token(kind, lit, line, col))
                bump(len(lit))
                matched = True
                break
        if matched:
            continue
        if m := RE_NUMBER.match(src, i):
            toks.append(Token("NUMBER", m.group(0), line, col))
            bump(m.end() - i)
            continue
        if m := RE_IDENT.match(src, i):
            v = m.group(0)
            kind = "KEYWORD" if v in KEYWORDS else "IDENT"
            toks.append(Token(kind, v, line, col))
            bump(m.end() - i)
            continue
        if ch in SINGLE:
            toks.append(Token("PUNCT", ch, line, col))
            bump(1)
            continue
        raise LexError(f"illegal character {ch!r}", line, col)

    toks.append(Token("EOF", "", line, col))
    return toks


# ---------------------------------------------------------------- 结果结构


@dataclass
class Issue:
    severity: str  # error | warning
    code: str
    message: str
    line: int
    col: int

    def __str__(self) -> str:
        return f"{self.severity.upper()}:{self.code} @{self.line}:{self.col} {self.message}"


@dataclass
class LintResult:
    path: str
    package: str | None = None
    functions: list[str] = field(default_factory=list)
    procs: list[str] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    ops_used: set[str] = field(default_factory=set)
    fragment: bool = False

    @property
    def ok(self) -> bool:
        return not any(i.severity == "error" for i in self.issues)


# ---------------------------------------------------------------- 语法解析


class Parser:
    def __init__(self, toks: list[Token], contracts: dict, path: str):
        self.t = toks
        self.i = 0
        self.contracts = contracts
        self.res = LintResult(path=path)
        self.func_names: set[str] = set()
        # ---- 名字作用域（复现 parser 的 name_to_value：先定义后引用）----
        self.defined: dict[str, str] = {}   # 名字 -> 来源标记（node/param/state/reg）
        self.states: set[str] = set()       # 已声明的 state_element
        self.channels: set[str] = set()     # 已声明的通道（chan_interface / proc<> / chan）
        self.blocks: set[str] = set()
        self.procs_seen: set[str] = set()   # 已解析的 proc 名（供 proc_instantiation 校验）

    # -- 基础操作 --
    def peek(self, k: int = 0) -> Token:
        return self.t[min(self.i + k, len(self.t) - 1)]

    def next(self) -> Token:
        tok = self.t[self.i]
        if tok.kind != "EOF":
            self.i += 1
        return tok

    def at(self, value: str) -> bool:
        return self.peek().value == value

    def err(self, code: str, msg: str, tok: Token | None = None) -> None:
        tok = tok or self.peek()
        self.res.issues.append(Issue("error", code, msg, tok.line, tok.col))

    def warn(self, code: str, msg: str, tok: Token | None = None) -> None:
        tok = tok or self.peek()
        self.res.issues.append(Issue("warning", code, msg, tok.line, tok.col))

    def expect(self, value: str, what: str = "") -> Token:
        tok = self.peek()
        if tok.value != value:
            self.err(
                "E_EXPECT",
                f"期望 {value!r}{(' (' + what + ')') if what else ''}，实际 {tok.value!r}",
                tok,
            )
            # 错误恢复：不消费
            return tok
        return self.next()

    def expect_kind(self, kind: str, what: str = "") -> Token:
        tok = self.peek()
        if tok.kind != kind:
            self.err("E_EXPECT", f"期望 {kind}{(' (' + what + ')') if what else ''}，实际 {tok.kind}({tok.value})", tok)
            return tok
        return self.next()

    def skip_balanced(self, oc: str, cc: str) -> None:
        """跳过一对配对的括号（只按同种括号计数）。"""
        depth = 0
        while True:
            t = self.peek()
            if t.kind == "EOF":
                self.err("E_VALUE", f"{oc!r} 未闭合", t)
                return
            if t.value == oc:
                depth += 1
            elif t.value == cc:
                depth -= 1
            self.next()
            if depth == 0:
                return

    # -- 类型 --
    def parse_array_suffix(self) -> None:
        """数组维度后缀 —— ParseType:211 对 bits / tuple 一视同仁。"""
        while self.at("["):
            self.next()
            d = self.next()
            if d.kind != "NUMBER":
                self.err("E_TYPE", f"数组维度需要数字，实际 {d.value!r}", d)
            self.expect("]")

    def parse_type(self) -> bool:
        """解析一个类型；返回是否成功。"""
        tok = self.peek()
        if tok.kind == "KEYWORD" and tok.value == "bits":
            self.next()
            self.expect("[")
            w = self.next()
            if w.kind != "NUMBER":
                self.err("E_TYPE", f"bits[...] 需要位宽数字，实际 {w.value!r}", w)
            self.expect("]")
            self.parse_array_suffix()
            return True
        if tok.kind == "KEYWORD" and tok.value == "clock":
            self.next()
            return True
        if tok.kind == "KEYWORD" and tok.value == "token":
            self.next()
            return True
        if tok.value == "(":
            # 元组类型 (T, T, ...) / 空元组 ()
            self.next()
            if self.at(")"):
                self.next()
                self.parse_array_suffix()
                return True
            while True:
                self.parse_type()
                if self.at(","):
                    self.next()
                    continue
                break
            self.expect(")")
            self.parse_array_suffix()   # (bits[32], bits[1])[3]
            return True
        self.err("E_TYPE", f"无法识别的类型，起始 token {tok.value!r}", tok)
        return False

    # -- 值 / 引用 --
    def parse_value(self, brace_ok: bool = False) -> Token | None:
        """解析一个值。返回"裸标识符"token（供引用检查），其它情形返回 None。"""
        if self.at("-"):  # 负数字面量（LexicalTokenType::kMinus）
            self.next()
            t = self.next()
            if t.kind != "NUMBER":
                self.err("E_VALUE", f"'-' 后应为数字，实际 {t.value!r}", t)
            return None
        tok = self.peek()
        if tok.kind in ("NUMBER", "STRING"):
            self.next()
            return None
        if tok.kind == "IDENT":
            self.next()
            return tok
        if tok.kind == "KEYWORD":  # token / bits[...]:0x1 —— 值位置合法，但不是引用
            self.next()
            return None
        if tok.value == "[":
            self.skip_balanced("[", "]")
            return None
        if tok.value == "(":
            self.skip_balanced("(", ")")
            return None
        if brace_ok and tok.value == "{":   # init={...} / non_synth={...}
            self.skip_balanced("{", "}")
            return None
        self.err("E_VALUE", f"无法解析的值，起始 token {tok.value!r}", tok)
        return None

    def check_ref(self, tok: Token, what: str = "") -> None:
        """引用检查：裸名字必须在之前已定义（ir_parser.cc:461）。"""
        name = tok.value
        if name in ENUM_VALUES:
            return
        if name in self.defined or name in self.states or name in self.blocks:
            return
        self.err(
            "E_UNDEF",
            f"引用了未定义的名字 {name!r}{what}"
            f"（XLS 要求先定义后引用：Referred to a name that was not previously defined）",
            tok,
        )

    def check_state_ref(self, tok: Token) -> None:
        if tok.kind != "IDENT":
            return
        if tok.value not in self.states:
            self.err(
                "E_STATE_UNDEF",
                f"state_element={tok.value} 未在 proc 签名中声明为 state",
                tok,
            )

    def check_channel_ref(self, tok: Token) -> None:
        if tok.kind != "IDENT":
            return
        if self.channels and tok.value not in self.channels:
            self.err(
                "E_CHAN_UNDEF",
                f"channel={tok.value} 未声明（本文件已声明的通道：{sorted(self.channels)}）",
                tok,
            )

    def parse_ref_list(self) -> None:
        """解析 cases=[...] / indices=[...]：元素是节点引用，逐个检查。"""
        self.expect("[")
        while not self.at("]") and self.peek().kind != "EOF":
            t = self.parse_value()
            if t is not None:
                self.check_ref(t)
            if self.at(","):
                self.next()
                continue
            break
        self.expect("]")

    # -- 关键字参数（含枚举校验与引用检查）--
    def parse_kw_list(
        self,
        end: str,
        allowed_kw: dict[str, set[str] | None],
        mandatory: set[str],
        where: str,
    ) -> dict[str, Token]:
        """解析 `kw=value, ...` 直到遇到 end。返回值名 -> 值 token。

        allowed_kw: 关键字 -> 允许的枚举取值集合（None = 任意值）
        """
        seen: dict[str, Token] = {}
        while not self.at(end) and self.peek().kind != "EOF":
            key_tok = self.peek()
            if key_tok.kind not in ("IDENT", "KEYWORD"):
                self.err("E_EXPECT", f"{where} 期望关键字参数，实际 {key_tok.value!r}", key_tok)
                break
            key = self.next().value
            if not self.at("="):
                self.err("E_EXPECT", f"{where} 关键字 {key} 后应为 '='", self.peek())
                break
            self.next()
            if key in seen:
                self.err("E_DUP_KW", f"{where} 重复的关键字参数 {key}=", key_tok)
            seen[key] = self.peek()
            if key not in allowed_kw:
                self.err(
                    "E_UNKNOWN_KW",
                    f"{where} 不支持关键字参数 {key}=（合法：{sorted(allowed_kw)}）",
                    key_tok,
                )
                self.parse_value(brace_ok=True)
            else:
                vals = allowed_kw[key]
                vt = self.peek()
                if vals is not None and vt.kind == "IDENT" and vt.value not in vals:
                    self.err(
                        "E_ENUM",
                        f"{where} 的 {key}= 取值非法：{vt.value!r}（合法：{sorted(vals)}）",
                        vt,
                    )
                self.parse_value(brace_ok=True)
            if self.at(","):
                self.next()
                continue
            break
        for req in sorted(mandatory - set(seen)):
            self.err(
                "E_MISSING_KW",
                f"{where} 缺少必选关键字参数 {req}=（XLS 标记为 mandatory）",
                self.peek(),
            )
        return seen

    # -- 表达式：op(args) --
    def parse_expr(self) -> tuple[str | None, list[tuple[str, Token]]]:
        """解析一个表达式；返回 (op 名, 关键字参数列表)。"""
        tok = self.peek()
        if tok.kind not in ("IDENT", "KEYWORD"):
            # 允许裸值（如 ret 后直接跟数字）
            t = self.parse_value()
            if t is not None:
                self.check_ref(t)
            return None, []
        op_name = tok.value
        self.next()
        if not self.at("("):
            # 纯标识符引用（如 ret x 或位置参数）
            self.check_ref(tok)
            return op_name, []
        kws = self.parse_op_args(op_name, tok)
        self.res.ops_used.add(op_name)
        return op_name, kws

    def parse_op_args(self, op_name: str, op_tok: Token) -> list[tuple[str, Token]]:
        """解析 op 的实参：按契约校验，并对节点引用做"先定义"检查。

        返回关键字参数列表（名, 值 token），供节点名/id 一致性检查复用。
        """
        self.expect("(")
        # 以 op 名（去掉 node 序号后缀）查契约
        base = op_name.split(".")[0]
        contract = self.contracts.get(base)

        n_positional = 0
        kw_seen: list[str] = []
        kw_values: dict[str, str] = {}
        kw_toks: list[tuple[str, Token]] = []

        if not self.at(")"):
            while True:
                if self.at(")"):
                    break
                # 关键字参数：ident '='
                if (
                    self.peek().kind in ("IDENT", "KEYWORD")
                    and self.peek(1).value == "="
                ):
                    key_tok = self.peek()
                    key = self.next().value
                    self.next()  # '='
                    if key in kw_values:
                        self.err(
                            "E_DUP_KW",
                            f"{base}() 重复的关键字参数 {key}="
                            f"（XLS ir_parser ParseKeywordArguments:92）",
                            key_tok,
                        )
                    kw_seen.append(key)
                    kw_values[key] = self.peek().value
                    kw_toks.append((key, self.peek()))
                    if key in REF_LIST_KEYWORDS:
                        self.parse_ref_list()
                    elif key == "channel":
                        self.check_channel_ref(self.peek())
                        self.parse_value()
                    elif key == "state_element":
                        self.check_state_ref(self.peek())
                        self.parse_value()
                    else:
                        t = self.parse_value()
                        if t is not None and key not in NON_REF_KEYWORDS:
                            self.check_ref(t, f"（{key}=）")
                else:
                    n_positional += 1
                    t = self.parse_value()
                    if t is not None:
                        self.check_ref(t)
                if self.at(","):
                    self.next()
                    continue
                break
        self.expect(")")

        if contract is None:
            self.warn("W_UNKNOWN_OP", f"未知 op {base!r}（不在 ir_parser.cc 契约表中）", op_tok)
            return kw_toks

        arity = contract["arity"]
        if arity is not None and arity >= 0 and n_positional != arity:
            # 位置参数多于 arity 且存在必选关键字，是典型的写法错误
            hint = ""
            if "indices[]" in contract["mandatory_keywords"]:
                hint = " 提示: 数组索引必须写作 indices=[...]（XLS 要求关键字形式）"
            elif "cases[]" in contract["mandatory_keywords"]:
                hint = " 提示: 分支必须写作 cases=[...]（XLS 要求关键字形式）"
            self.err(
                "E_ARITY",
                f"{base}() 期望 {arity} 个位置参数，实际 {n_positional} 个。{hint}",
                op_tok,
            )

        # 语义上必选、但 op_list.h 反射不出来的（BinaryOrUnary 默认 arity 坑）
        extra_mandatory = {
            "next_value": ("value",),
        }.get(base, ())
        for req in tuple(contract["mandatory_keywords"]) + extra_mandatory:
            plain = req.rstrip("[]")
            if plain not in kw_seen:
                self.err(
                    "E_MISSING_KW",
                    f"{base}() 缺少必选关键字参数 {plain}=（XLS ir_parser.cc 标记为 mandatory）",
                    op_tok,
                )
        # next_value 的 param / state_element 二选一（ir_parser.cc kNext 分支）
        if base == "next_value":
            has_param = "param" in kw_seen
            has_state = "state_element" in kw_seen
            if has_param and has_state:
                self.err(
                    "E_NEXT_ARG",
                    "next_value() 不能同时指定 param=（StateRead）与 state_element=",
                    op_tok,
                )
            elif not has_param and not has_state:
                self.err(
                    "E_NEXT_ARG",
                    "next_value() 必须指定 param=（StateRead）或 state_element= 之一",
                    op_tok,
                )
        allowed = (
            {k.rstrip("[]") for k in contract["mandatory_keywords"]}
            | {k.rstrip("[]") for k in contract["optional_keywords"]}
            | COMMON_KEYWORDS
        )
        for k in kw_seen:
            if k not in allowed:
                self.err("E_UNKNOWN_KW", f"{base}() 不支持关键字参数 {k}=", op_tok)
        return kw_toks

    # -- 语句 --
    def parse_statement(self) -> None:
        tok = self.peek()
        if tok.kind == "KEYWORD" and tok.value == "ret":
            self.next()
            # 可选 "name: type ="
            if (
                self.peek().kind in ("IDENT", "KEYWORD")
                and self.peek(1).value == ":"
            ):
                name = self.next()  # name
                self.next()  # ':'
                self.parse_type()
                self.expect("=")
                op_name, kws = self.parse_expr()
                self.define_node(name.value, op_name, name)
                self.check_node_id(name.value, kws, name)
            else:
                self.parse_expr()
            return
        if tok.kind == "KEYWORD" and tok.value == "next":
            self.next()
            self.expect("(")
            while not self.at(")") and self.peek().kind != "EOF":
                t = self.parse_value()
                if t is not None:
                    self.check_ref(t)
                if self.at(","):
                    self.next()
            self.expect(")")
            return
        # 普通节点: name: type = expr
        if tok.kind in ("IDENT", "KEYWORD") and self.peek(1).value == ":":
            name = self.next()  # name
            self.next()  # ':'
            self.parse_type()
            self.expect("=")
            op_tok = self.peek()
            op_name, kws = self.parse_expr()
            self.define_node(name.value, op_name or op_tok.value, name)
            self.check_node_id(name.value, kws, name)
            return
        self.err("E_STMT", f"无法识别的语句，起始 token {tok.value!r}", tok)
        # 跳过至下一行
        while self.peek().kind != "EOF" and not self.at("}"):
            self.next()

    def define_node(self, name: str, op_name: str | None, name_tok: Token) -> None:
        """登记节点名，并按 ir_parser.cc 校验重名与"名字里的 op 名"。

        重名判据（ir_parser.cc:1486）：只有**新节点**的 op 不是 Param / StateRead
        时才算错 —— 签名阶段已把参数/状态塞进 name_to_value，`param(name=x)` 与
        `state_read(state_element=x)` 是有意复用同名。
        """
        if not name:
            return
        if name in self.defined and (op_name or "") not in ("param", "state_read"):
            self.err("E_DUP_NAME", f"节点名 {name!r} 重复定义（XLS ir_parser.cc:1488）", name_tok)
        self.defined[name] = op_name or "node"

        m = RE_NODE_SUFFIX.match(name)
        if m and op_name:
            prefix = m.group(1)
            base = op_name.split(".")[0]
            if prefix and prefix != base:
                self.err(
                    "E_NODE_OP",
                    f"节点名 {name!r} 中的 {prefix!r} 与该节点的 op {base!r} 不一致"
                    f"（XLS ir_parser.cc:1533）",
                    name_tok,
                )

    def check_node_id(
        self, name: str, kws: list[tuple[str, Token]], name_tok: Token
    ) -> None:
        """`名字.<N>` 的后缀 N 必须等于 `id=N`（ir_parser.cc:1524）。"""
        m = RE_NODE_SUFFIX.match(name)
        if not m:
            return
        for key, val_tok in kws:
            if key == "id" and val_tok.value != m.group(2):
                self.err(
                    "E_NODE_ID",
                    f"节点名 {name!r} 的后缀 {m.group(2)} 与 id={val_tok.value} 不一致"
                    f"（XLS ir_parser.cc:1524）",
                    name_tok,
                )
                return

    def parse_trailing_attrs(self) -> None:
        """类型参数后的可选属性 `id=N` / `pos=[...]`（ir_parser.cc:134-138）。

        注意：这是**类型参数**的一部分，不是 proc 签名的关键字参数 ——
        例 `proc p(__state: () pos=[(0,8,6)], init={()})`。
        """
        while (
            self.peek().kind == "IDENT"
            and self.peek(1).value == "="
            and self.peek().value in ("id", "pos")
        ):
            self.next()  # id / pos
            self.next()  # '='
            self.parse_value()

    def parse_typed_args(self) -> list[str]:
        """`(name: type, ...)`；返回参数名列表。"""
        names: list[str] = []
        self.expect("(")
        if not self.at(")"):
            while True:
                t = self.expect_kind("IDENT", "参数名")
                names.append(t.value)
                self.expect(":")
                self.parse_type()
                self.parse_trailing_attrs()
                if self.at(","):
                    self.next()
                    continue
                break
        self.expect(")")
        return names

    # ---- 通道声明 ----

    def parse_proc_chan_angle(self) -> None:
        """proc 签名里的 `<name: T in, name2: T out>`（ParseProcSignature:2195）。"""
        if not self.at("<"):
            return
        self.next()
        if self.at(">"):
            self.next()
            return
        while True:
            cname = self.expect_kind("IDENT", "通道名")
            self.channels.add(cname.value)
            self.expect(":")
            self.parse_type()
            d = self.expect_kind("IDENT", "通道方向 in/out")
            if d.kind == "IDENT" and d.value not in PROC_CHAN_DIRECTIONS:
                self.err(
                    "E_ENUM",
                    f"proc 通道方向非法：{d.value!r}（合法：in / out）",
                    d,
                )
            if self.at(","):
                self.next()
                continue
            break
        self.expect(">")

    def parse_chan_interface(self) -> None:
        """chan_interface name(direction=..., kind=..., ...)（ParseChannelInterface:2986）。"""
        self.next()  # 'chan_interface'
        cname = self.expect_kind("IDENT", "channel interface 名")
        self.channels.add(cname.value)
        self.expect("(")
        seen = self.parse_kw_list(
            ")", CHAN_INTERFACE_KW, CHAN_INTERFACE_MANDATORY,
            f"chan_interface {cname.value}",
        )
        self.expect(")")
        # 工程约定：proc-scoped 通道接口显式写 strictness（老式 chan 才有默认值）
        if "strictness" not in seen:
            self.warn(
                "W_CHAN_STRICTNESS",
                f"chan_interface {cname.value} 未显式声明 strictness=（proc-scoped 通道无默认值）",
                cname,
            )

    def parse_chan_decl(self) -> None:
        """`chan name(TYPE, kw=...)`（ParseChannel:2754）。

        注意：类型后**必须**有逗号（ParseChannel:2795 直接 Drop kComma）。
        """
        self.next()  # 'chan'
        cname = self.expect_kind("IDENT", "chan 名")
        self.channels.add(cname.value)
        self.expect("(")
        self.parse_type()
        if self.at(","):
            self.next()
        else:
            self.err(
                "E_EXPECT",
                f"chan {cname.value} 的类型后必须跟 ','（XLS ParseChannel:2795）",
                self.peek(),
            )
        seen = self.parse_kw_list(
            ")", CHAN_DECL_KW, CHAN_DECL_MANDATORY, f"chan {cname.value}"
        )
        self.expect(")")
        kind_tok = seen.get("kind")
        if kind_tok is not None and kind_tok.value != "streaming":
            for k in sorted(CHAN_STREAMING_ONLY & set(seen)):
                self.err(
                    "E_CHAN_KIND",
                    f"chan {cname.value} 的 kind={kind_tok.value} 不允许 {k}="
                    f"（只有 streaming 通道支持，ParseChannel:2936）",
                    seen[k],
                )

    def parse_proc_instantiation(self) -> None:
        """`proc_instantiation NAME(ch_refs..., proc=other)`（ParseProcInstantiation:1592）。"""
        self.next()  # 'proc_instantiation'
        self.expect_kind("IDENT", "instantiation 名")
        self.expect("(")
        seen_proc: Token | None = None
        while not self.at(")") and self.peek().kind != "EOF":
            if self.peek().kind in ("IDENT", "KEYWORD") and self.peek(1).value == "=":
                key = self.next().value
                self.next()  # '='
                if key != "proc":
                    self.err("E_UNKNOWN_KW", f"proc_instantiation 不支持关键字参数 {key}=", self.peek())
                elif self.peek().kind == "IDENT":
                    seen_proc = self.peek()
                self.parse_value()
            else:
                self.parse_value()
            if self.at(","):
                self.next()
        self.expect(")")
        if seen_proc is None:
            self.err(
                "E_MISSING_KW",
                "proc_instantiation 缺少必选关键字参数 proc=（ParseProcInstantiation:1620）",
                self.peek(),
            )
        elif self.procs_seen and seen_proc.value not in self.procs_seen:
            self.err(
                "E_PROC_UNDEF",
                f"proc_instantiation 引用了未定义的 proc {seen_proc.value!r}"
                f"（XLS 要求该 proc 已在前面声明）",
                seen_proc,
            )

    # ---- fn / proc / block ----

    def parse_function(self) -> None:
        self.expect("fn")
        name = self.expect_kind("IDENT", "函数名")
        if name.value:
            self.res.functions.append(name.value)
            self.func_names.add(name.value)
        saved = self.defined
        self.defined = dict(saved)
        for p in self.parse_typed_args():
            self.defined[p] = "param"
        self.expect("->", "函数返回类型")
        self.parse_type()
        self.expect("{", "函数体起始")
        self.parse_body()
        self.defined = saved

    def parse_proc(self) -> None:
        """proc 签名 + 体（新式 proc：<通道> / 数组 state / init={...}）。"""
        self.expect("proc")
        name = self.expect_kind("IDENT", "proc 名")
        if name.value:
            self.res.functions.append(name.value)
            self.res.procs.append(name.value)
            self.procs_seen.add(name.value)

        # 名字作用域按 proc 隔离（每个 proc 有自己的 name_to_value / state 集）
        saved_defined, saved_states = self.defined, self.states
        self.defined, self.states = dict(saved_defined), set()
        self.channels = set(self.channels)

        self.parse_proc_chan_angle()

        # 状态元素：(name: type, ...)
        state_params: list[str] = []
        self.expect("(", "proc 参数表")
        if not self.at(")"):
            while True:
                # proc 参数里混着 `init={...}` / `non_synth={...}` 这类关键字项
                if (
                    self.peek().kind in ("IDENT", "KEYWORD")
                    and self.peek(1).value == "="
                ):
                    break
                t = self.expect_kind("IDENT", "state 名")
                state_params.append(t.value)
                self.states.add(t.value)
                self.expect(":")
                self.parse_type()
                self.parse_trailing_attrs()   # id=N / pos=[...]
                if self.at(","):
                    self.next()
                    continue
                break

        # init={VALUES} / non_synth={names}（ParseProcSignature:2238/2259）
        init_tok: Token | None = None
        init_count = 0
        while not self.at(")") and self.peek().kind != "EOF":
            if self.at(","):
                self.next()
                continue
            key_tok = self.peek()
            if key_tok.kind not in ("IDENT", "KEYWORD") or self.peek(1).value != "=":
                self.err("E_EXPECT", f"proc 参数表期望 init=/non_synth=，实际 {key_tok.value!r}", key_tok)
                break
            key = self.next().value
            self.next()  # '='
            if key == "init":
                init_tok = key_tok
                if not self.at("{"):
                    self.err("E_EXPECT", "init= 后应为 '{'（形如 init={token, 0, [0,0]}）", self.peek())
                    self.parse_value(brace_ok=True)
                else:
                    init_count = self.count_brace_values()
            elif key == "non_synth":
                # non_synth={state1, state2}
                if not self.at("{"):
                    self.err("E_EXPECT", "non_synth= 后应为 '{'", self.peek())
                else:
                    self.skip_balanced("{", "}")
            else:
                # pos=/id= 已在 parse_trailing_attrs 里被类型参数吃掉，走到这里是真错
                self.err(
                    "E_UNKNOWN_KW",
                    f"proc 签名不支持关键字参数 {key}=（合法：init / non_synth）",
                    key_tok,
                )
                self.parse_value(brace_ok=True)
            if self.at(","):
                self.next()
        self.expect(")", "proc 参数表结束")

        # init 必选（有 state 时），且个数必须等于 state 数（ParseProcSignature:2279/2285）
        if state_params:
            if init_tok is None:
                self.err(
                    "E_MISSING_KW",
                    f"proc {name.value} 声明了 {len(state_params)} 个 state，但缺少 init={{...}}"
                    f"（XLS 标记为 mandatory）",
                    name,
                )
            elif init_count != len(state_params):
                self.err(
                    "E_INIT_COUNT",
                    f"proc {name.value} 的 init 提供 {init_count} 个初值，"
                    f"但声明了 {len(state_params)} 个 state（XLS ir_parser.cc:2285）",
                    init_tok,
                )

        self.expect("{", "proc 体起始")
        for p in state_params:
            self.defined[p] = "state"
        self.parse_body(chan_interfaces_ok=True)
        self.defined, self.states = saved_defined, saved_states

    def count_brace_values(self) -> int:
        """数一数 `{v, v, ...}` 里有多少个顶层值（跳过嵌套括号/方括号）。"""
        self.expect("{")
        n = 0
        while not self.at("}") and self.peek().kind != "EOF":
            self.parse_value(brace_ok=True)
            n += 1
            if self.at(","):
                self.next()
                continue
            break
        self.expect("}")
        return n

    def parse_block(self) -> None:
        """block 声明：解析签名后按花括号配对跳过 body。"""
        self.expect("block")
        bname = self.expect_kind("IDENT", "block 名")
        self.blocks.add(bname.value)
        if self.at("("):
            self.parse_typed_args()
        if not self.at("{"):
            self.err("E_BLOCK", "block 签名后应为 '{'")
            return
        depth = 0
        while self.peek().kind != "EOF":
            if self.at("{"):
                depth += 1
            elif self.at("}"):
                depth -= 1
                self.next()
                if depth == 0:
                    return
                continue
            self.next()

    def parse_body(self, chan_interfaces_ok: bool = False) -> None:
        depth = 1
        while depth > 0 and self.peek().kind != "EOF":
            if self.at("{"):
                depth += 1
                self.next()
                continue
            if self.at("}"):
                depth -= 1
                self.next()
                continue
            tok = self.peek()
            if tok.kind == "KEYWORD" and tok.value == "chan_interface":
                if not chan_interfaces_ok:
                    self.err("E_STMT", "chan_interface 只能出现在 proc 体内", tok)
                    self.next()
                    continue
                self.parse_chan_interface()
                continue
            if tok.kind == "KEYWORD" and tok.value == "chan":
                if not chan_interfaces_ok:
                    self.err("E_STMT", "chan 只能出现在 proc 体内", tok)
                    self.next()
                    continue
                self.parse_chan_decl()
                continue
            if tok.kind == "KEYWORD" and tok.value == "proc_instantiation":
                if not chan_interfaces_ok:
                    self.err("E_STMT", "proc_instantiation 只能出现在 proc 体内", tok)
                    self.next()
                    continue
                self.parse_proc_instantiation()
                continue
            self.parse_statement()

    # -- 顶层 --
    def parse(self) -> LintResult:
        if self.peek().kind == "KEYWORD" and self.peek().value == "package":
            self.next()
            pkg = self.expect_kind("IDENT", "package 名")
            self.res.package = pkg.value
        else:
            # 片段模式：XLS 的 ParseFunction/ParseProc/ParseBlock API 输入
            # （xls/ir/testdata/*.ir）不含 package 头，直接以 fn/proc/block 开头。
            self.res.fragment = True
        return self.parse_decls()

    def parse_decls(self) -> LintResult:
        while self.peek().kind != "EOF":
            tok = self.peek()
            if tok.kind == "PUNCT" and tok.value == "#":
                # 属性 #[...] / #![...]：整段跳过到匹配的 ]
                depth = 0
                while self.peek().kind != "EOF":
                    if self.at("["):
                        depth += 1
                    elif self.at("]"):
                        depth -= 1
                        self.next()
                        if depth == 0:
                            break
                        continue
                    self.next()
                continue
            if tok.kind == "KEYWORD" and tok.value == "top":
                self.next()
                continue
            if tok.kind == "KEYWORD" and tok.value == "fn":
                self.parse_function()
                continue
            if tok.kind == "KEYWORD" and tok.value == "proc":
                self.parse_proc()
                continue
            if tok.kind == "KEYWORD" and tok.value == "block":
                self.parse_block()
                continue
            if tok.kind == "KEYWORD" and tok.value == "chan":
                self.parse_chan_decl()
                continue
            if tok.kind == "KEYWORD" and tok.value == "file_number":
                # 形态：`file_number 0 "proc_iota.x"`（编号 + 文件名，两个 token）
                self.next()
                self.parse_value()
                self.parse_value()
                continue
            if tok.kind == "KEYWORD" and tok.value in (
                "scheduled_fn", "scheduled_proc", "scheduled_block",
            ):
                self.parse_scheduled()
                continue
            self.err(
                "E_TOP",
                f"顶层不允许出现 {tok.value!r}（应为 fn/proc/block/chan/file_number/属性）",
                tok,
            )
            self.next()
        return self.res

    def skip_to_next_decl(self) -> None:
        """跳到下一个顶层声明关键字。"""
        top_kws = {
            "fn", "proc", "block", "chan", "file_number", "top",
            "scheduled_fn", "scheduled_proc", "scheduled_block",
        }
        self.next()
        while self.peek().kind != "EOF":
            t2 = self.peek()
            if t2.kind == "KEYWORD" and t2.value in top_kws:
                return
            self.next()

    def parse_scheduled(self) -> None:
        """`scheduled_fn` / `scheduled_proc` / `scheduled_block`：只跳过，不深检。

        ⚠️ 不能用"跳到下一个顶层关键字"来实现 —— 这些实体的**体内**会内嵌
        `source fn|proc|block ...` 声明（见 codegen_v_1_5/testdata/*.ir），
        逐个跳会被内嵌的 `fn` 骗到，导致后续整段被误判为顶层垃圾（实测 14 个
        官方样本因此各报上千条 E_TOP）。正确做法：跳过签名后按花括号配对整段跳过。
        """
        kw = self.next()
        self.warn("W_TOP_UNSUPPORTED", f"{kw.value} 暂不做深检（跳过签名与实体体）", kw)
        if self.peek().kind == "IDENT":
            self.next()
        if self.at("<"):     # 可选通道表
            depth = 0
            while self.peek().kind != "EOF":
                if self.at("<"):
                    depth += 1
                elif self.at(">"):
                    depth -= 1
                    self.next()
                    if depth == 0:
                        break
                    continue
                self.next()
        if self.at("("):     # 参数表
            depth = 0
            while self.peek().kind != "EOF":
                if self.at("("):
                    depth += 1
                elif self.at(")"):
                    depth -= 1
                self.next()
                if depth == 0:
                    break
        if self.at("->"):    # 可选返回类型
            self.next()
            self.parse_type()
        if not self.at("{"):
            self.err("E_EXPECT", f"{kw.value} 签名后应为 '{{'", self.peek())
            return
        self.skip_balanced("{", "}")


# ---------------------------------------------------------------- 入口


def lint_text(src: str, contracts: dict, path: str = "<string>") -> LintResult:
    try:
        toks = tokenize(src)
    except LexError as e:
        r = LintResult(path=path)
        r.issues.append(Issue("error", "E_LEX", str(e), e.line, e.col))
        return r
    return Parser(toks, contracts, path).parse()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("files", nargs="+", help="待校验的 .ir 文件或目录")
    ap.add_argument("--recursive", action="store_true", help="递归扫描目录")
    ap.add_argument("--contracts", default="config/op_contracts.json")
    ap.add_argument("--json", help="把结果写入 JSON 文件")
    ap.add_argument("--quiet", action="store_true", help="只输出汇总")
    ap.add_argument("--max-issues", type=int, default=40, help="单文件最多打印多少条 issue")
    args = ap.parse_args()

    cpath = Path(args.contracts)
    if not cpath.is_absolute():
        # 先按当前工作目录找，再回退到 scripts/ 上一级（开发态默认）
        cwd_cand = Path.cwd() / args.contracts
        root_cand = Path(__file__).resolve().parent.parent / args.contracts
        cpath = cwd_cand if cwd_cand.exists() else root_cand
    if not cpath.exists():
        print(f"ERROR: 缺少 {cpath}，先运行 scripts/gen_op_contracts.py", file=sys.stderr)
        return 2
    contracts = json.loads(cpath.read_text(encoding="utf-8"))["contracts"]

    targets: list[Path] = []
    for f in args.files:
        p = Path(f)
        if p.is_dir():
            targets.extend(sorted(p.rglob("*.ir") if args.recursive else p.glob("*.ir")))
        elif p.exists():
            targets.append(p)

    if not targets:
        print("没有找到 .ir 文件", file=sys.stderr)
        return 2

    results: list[LintResult] = []
    for p in targets:
        r = lint_text(p.read_text(encoding="utf-8", errors="replace"), contracts, str(p))
        results.append(r)

    n_err = sum(1 for r in results if not r.ok)
    n_issues = sum(len(r.issues) for r in results)

    if not args.quiet:
        for r in results:
            mark = "✅" if r.ok else "❌"
            print(f"{mark} {Path(r.path).name}  ({len(r.issues)} issue)")
            for it in r.issues[: args.max_issues]:
                print(f"     {it}")
            if len(r.issues) > args.max_issues:
                print(f"     ... 另有 {len(r.issues) - args.max_issues} 条未显示（--max-issues 调整）")

    print()
    print(f"文件: {len(results)}  通过: {len(results) - n_err}  失败: {n_err}  问题总数: {n_issues}")

    if args.json:
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                [
                    {
                        "path": r.path,
                        "package": r.package,
                        "fragment": r.fragment,
                        "functions": r.functions,
                        "procs": r.procs,
                        "ok": r.ok,
                        "ops_used": sorted(r.ops_used),
                        "issues": [vars(i) for i in r.issues],
                    }
                    for r in results
                ],
                indent=2,
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"JSON 结果 -> {args.json}")

    return 1 if n_err else 0


if __name__ == "__main__":
    sys.exit(main())
