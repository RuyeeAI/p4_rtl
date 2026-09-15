#!/usr/bin/env python3
"""XLS IR 文本静态校验器（A0 门禁工具）。

规则来源（全部取自 third_party/xls 的真实源码，非推测）：
  - 词法:      xls/ir/ir_scanner.h  (LexicalTokenType / Token::GetKeywords)
  - 注释:      xls/ir/ir_scanner.cc (DropEndOfLineComment, 支持 //)
  - 顶层结构:  xls/ir/ir_parser.h   (ParseDerivedPackageNoVerify)
  - 函数签名:  xls/ir/ir_parser.cc  (ParseFunctionSignature / ParseTupleType)
  - 语句/ret:  xls/ir/ir_parser.cc  (ParseBody)
  - op 契约:   config/op_contracts.json (由 gen_op_contracts.py 从 ir_parser.cc 提取)

校验器复现 XLS parser 的判定逻辑，用于在构建 XLS 之前快速定位不兼容点。

用法:
  python3 scripts/xls_ir_lint.py testcases/ir/demo9/*.ir
  python3 scripts/xls_ir_lint.py --recursive testcases/ir --json out/lint.json
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
SINGLE = set("()[]{},:=+><.#!")

RE_WS = re.compile(r"[ \t\r\n\f\v]+")
RE_COMMENT = re.compile(r"//[^\n]*")
RE_NUMBER = re.compile(
    r"(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*)"
)
RE_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
RE_TYPE_ANNOT = re.compile(r"bits\[\d+\]:")  # 值内嵌类型标注 bits[32]:0x42


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
            quote = ch
            start_line, start_col = line, col
            j = i + 1
            while j < n and src[j] != quote:
                if src[j] == "\\":
                    j += 1
                j += 1
            if j >= n:
                raise LexError("unterminated string", start_line, start_col)
            val = src[i : j + 1]
            toks.append(Token("STRING", val, start_line, start_col))
            bump(j + 1 - i)
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


# ---------------------------------------------------------------- 语法解析

# 类型语法：bits[N] / bits[N][M] / (T, T) / token / ()
RE_BITS = re.compile(r"bits\[(\d+)\]")


# 所有 op 通用的属性关键字（ir_parser.cc ParseNode 统一处理）
COMMON_KEYWORDS = {"id", "pos"}


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
    issues: list[Issue] = field(default_factory=list)
    ops_used: set[str] = field(default_factory=set)
    fragment: bool = False

    @property
    def ok(self) -> bool:
        return not any(i.severity == "error" for i in self.issues)


class Parser:
    def __init__(self, toks: list[Token], contracts: dict, path: str):
        self.t = toks
        self.i = 0
        self.contracts = contracts
        self.res = LintResult(path=path)
        self.func_names: set[str] = set()

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

    # -- 类型 --
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
            # 数组维度 bits[16][8]
            while self.at("["):
                self.next()
                d = self.next()
                if d.kind != "NUMBER":
                    self.err("E_TYPE", f"数组维度需要数字，实际 {d.value!r}", d)
                self.expect("]")
            return True
        if tok.kind == "KEYWORD" and tok.value in ("token", "clock"):
            self.next()
            return True
        if tok.value == "(":
            self.next()
            if self.at(")"):
                self.next()
                return True
            while True:
                self.parse_type()
                if self.at(","):
                    self.next()
                    continue
                break
            self.expect(")")
            return True
        self.err("E_TYPE", f"无法识别的类型，起始 token {tok.value!r}", tok)
        return False

    # -- 值 / 表达式的实参 --
    def parse_value(self) -> None:
        """解析一个值：ident / 数字 / true|false / [..] / (..) / 字符串。"""
        tok = self.peek()
        if tok.kind in ("IDENT", "NUMBER", "STRING") or (
            tok.kind == "KEYWORD" and tok.value in ("true", "false")
        ):
            self.next()
            return
        if tok.value == "[":
            depth = 0
            while True:
                t2 = self.peek()
                if t2.kind == "EOF":
                    self.err("E_VALUE", "值列表未闭合 '['", t2)
                    return
                if t2.value == "[":
                    depth += 1
                elif t2.value == "]":
                    depth -= 1
                self.next()
                if depth == 0:
                    return
        if tok.value == "(":
            depth = 0
            while True:
                t2 = self.peek()
                if t2.kind == "EOF":
                    self.err("E_VALUE", "值元组未闭合 '('", t2)
                    return
                if t2.value == "(":
                    depth += 1
                elif t2.value == ")":
                    depth -= 1
                self.next()
                if depth == 0:
                    return
        self.err("E_VALUE", f"无法解析的值，起始 token {tok.value!r}", tok)

    # -- 表达式：op(args) --
    def parse_expr(self) -> None:
        tok = self.peek()
        if tok.kind not in ("IDENT", "KEYWORD"):
            # 允许裸值（如 ret 后直接跟数字）
            self.parse_value()
            return
        op_name = tok.value
        self.next()
        if not self.at("("):
            # 纯标识符引用（如 ret x 或位置参数）
            return
        self.parse_op_args(op_name, tok)
        self.res.ops_used.add(op_name)

    def parse_op_args(self, op_name: str, op_tok: Token) -> None:
        """解析 op 的实参，并按契约校验。"""
        self.expect("(")
        # 以 op 名（去掉 node 序号后缀）查契约
        base = op_name.split(".")[0]
        contract = self.contracts.get(base)

        n_positional = 0
        kw_seen: list[str] = []
        kw_values: dict[str, str] = {}

        if not self.at(")"):
            while True:
                if self.at(")"):
                    break
                # 关键字参数：ident '='
                if (
                    self.peek().kind in ("IDENT", "KEYWORD")
                    and self.peek(1).value == "="
                ):
                    key = self.next().value
                    self.next()  # '='
                    kw_seen.append(key)
                    kw_values[key] = self.peek().value
                    self.parse_value()
                else:
                    n_positional += 1
                    self.parse_value()
                if self.at(","):
                    self.next()
                    continue
                break
        self.expect(")")

        if contract is None:
            self.warn("W_UNKNOWN_OP", f"未知 op {base!r}（不在 ir_parser.cc 契约表中）", op_tok)
            return

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

        for req in contract["mandatory_keywords"]:
            plain = req.rstrip("[]")
            if plain not in kw_seen:
                self.err(
                    "E_MISSING_KW",
                    f"{base}() 缺少必选关键字参数 {plain}=（XLS ir_parser.cc 标记为 mandatory）",
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
                self.next()  # name
                self.next()  # ':'
                self.parse_type()
                self.expect("=")
            self.parse_expr()
            return
        if tok.kind == "KEYWORD" and tok.value == "next":
            self.next()
            self.expect("(")
            while not self.at(")") and self.peek().kind != "EOF":
                self.parse_value()
                if self.at(","):
                    self.next()
            self.expect(")")
            return
        # 普通节点: name: type = expr
        if tok.kind in ("IDENT", "KEYWORD") and self.peek(1).value == ":":
            self.next()  # name
            self.next()  # ':'
            self.parse_type()
            self.expect("=")
            self.parse_expr()
            return
        self.err("E_STMT", f"无法识别的语句，起始 token {tok.value!r}", tok)
        # 跳过至下一行
        while self.peek().kind != "EOF" and not self.at("}"):
            self.next()

    def parse_typed_args(self) -> list[str]:
        names: list[str] = []
        self.expect("(")
        if not self.at(")"):
            while True:
                t = self.expect_kind("IDENT", "参数名")
                names.append(t.value)
                self.expect(":")
                self.parse_type()
                # 可选 id=N（函数参数可显式指定 node id）
                if self.at("id") and self.peek(1).value == "=":
                    self.next()
                    self.next()
                    self.next()
                if self.at(","):
                    self.next()
                    continue
                break
        self.expect(")")
        return names

    def parse_function(self) -> None:
        self.expect("fn")
        name = self.expect_kind("IDENT", "函数名")
        if name.value:
            self.res.functions.append(name.value)
            self.func_names.add(name.value)
        self.parse_typed_args()
        self.expect("->", "函数返回类型")
        self.parse_type()
        self.expect("{", "函数体起始")
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
            self.parse_statement()

    def parse_proc(self) -> None:
        self.expect("proc")
        name = self.expect_kind("IDENT", "proc 名")
        if name.value:
            self.res.functions.append(name.value)
        # 通道接口 <...>
        if self.at("<"):
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
        if self.at("("):
            self.parse_typed_args()
        self.expect("{")
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
                while self.peek().kind != "EOF" and not self.at("]"):
                    self.next()
                if self.at("]"):
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
            if tok.kind == "KEYWORD" and tok.value in (
                "scheduled_fn", "scheduled_proc", "scheduled_block",
                "chan", "file_number",
            ):
                self.warn("W_TOP_UNSUPPORTED", f"顶层声明 {tok.value!r} 暂不做深检，跳过")
                self.skip_to_next_decl()
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

    def parse_block(self) -> None:
        """block 声明：解析签名后按花括号配对跳过 body。"""
        self.expect("block")
        self.expect_kind("IDENT", "block 名")
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
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    cpath = root / args.contracts
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
            for it in r.issues:
                print(f"     {it}")

    print()
    print(f"文件: {len(results)}  通过: {len(results) - n_err}  失败: {n_err}  问题总数: {n_issues}")

    if args.json:
        out = root / args.json
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                [
                    {
                        "path": r.path,
                        "package": r.package,
                        "fragment": r.fragment,
                        "functions": r.functions,
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
