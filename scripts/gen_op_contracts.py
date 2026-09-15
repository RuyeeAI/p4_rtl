#!/usr/bin/env python3
"""从 XLS 的 ir_parser.cc 源码提取 IR 指令的参数契约表。

XLS 的 IR parser 对每个 op 用 ArgParser 声明参数：
  - AddKeywordArg<T>("k")            -> 必选关键字参数
  - AddOptionalKeywordArg<T>("k")    -> 可选关键字参数
  - arg_parser.Run(/*arity=*/N)      -> 位置参数个数（kVariadic = -1 表示可变）

本脚本解析 `case Op::kXXX: { ... break; }` 的代码块，产出 JSON 契约表，
供 scripts/xls_ir_lint.py 做静态语法校验。

用法:
  python3 scripts/gen_op_contracts.py [--xls-dir third_party/xls] [-o config/op_contracts.json]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# 模板实参可含嵌套尖括号（如 std::vector<BValue>），故用 (?:[^<>]|<[^<>]*>)+ 匹配
_INNER = r'((?:[^<>]|<[^<>]*>)+)'
RE_KW_MANDATORY = re.compile(rf'AddKeywordArg<{_INNER}>\s*\(\s*"([^"]+)"')
RE_KW_OPTIONAL = re.compile(rf'AddOptionalKeywordArg<{_INNER}>\s*\(\s*"([^"]+)"')
# 位置参数个数：Run(/*arity=*/N) / Run(ArgParser::kVariadic) / Run(N)
RE_ARITY = re.compile(r'\.Run\(\s*(?:/\*arity=\*/\s*)?([A-Za-z0-9_:]+)\s*\)')
RE_BINARY_OR_UNARY = re.compile(r'BuildBinaryOrUnaryOp\(')
RE_VARIADIC = re.compile(r'BuildVariadicOp\(')
# case 分支起始（部分 op 用单行 case，无花括号，故 '{' 可选）
RE_CASE = re.compile(r'case Op::k([A-Za-z0-9_]+)\s*:')

# 位置参数的语义名（用于生成可读文档）
POSITIONAL_NAMES: dict[str, list[str]] = {
    "kArrayIndex": ["array"],
    "kArrayUpdate": ["array", "update_value"],
    "kSel": ["selector"],
    "kOneHotSel": ["selector"],
    "kPrioritySel": ["selector"],
    "kTupleIndex": ["operand"],
    "kBitSlice": ["operand"],
    "kBitSliceUpdate": ["operand", "start", "update_value"],
    "kZeroExt": ["operand"],
    "kSignExt": ["operand"],
    "kTruncate": ["operand"],
    "kTuple": ["element*"],
    "kConcat": ["operand*"],
    "kLiteral": [],
    "kAfterAll": ["token*"],
    "kMinDelay": ["token"],
    "kGate": ["condition", "operand"],
}


def extract_op_blocks(src: str) -> dict[str, str]:
    """按 `case Op::kXXX:` ... `break;` 切分源码，返回 op -> 代码块。

    注意 C++ 允许 case 标签 fallthrough：
        case Op::kZeroExt:
        case Op::kSignExt: {
          ... 共用实现 ...
        }
    此时 kZeroExt 切出的块只含下一行 case 标签，需继承后继 case 的实现。
    """
    blocks: dict[str, str] = {}
    matches = list(RE_CASE.finditer(src))
    for i, m in enumerate(matches):
        op = m.group(1)
        end = matches[i + 1].start() if i + 1 < len(matches) else len(src)
        blocks[op] = src[m.end() : end]

    # fallthrough 修正：块内容实质为空或仅剩下一个 case 标签 -> 继承后继实现
    ordered = [m.group(1) for m in matches]
    for i, op in enumerate(ordered):
        body = blocks[op].strip()
        if body.startswith("case Op::k") or len(body) < 8:
            for j in range(i + 1, len(ordered)):
                nxt = blocks[ordered[j]].strip()
                if not nxt.startswith("case Op::k") and len(nxt) >= 8:
                    blocks[op] = blocks[ordered[j]]
                    break
    return blocks


def parse_block(op: str, block: str) -> dict:
    """从单个 op 的代码块提取契约。"""
    mandatory: list[str] = []
    optional: list[str] = []

    for type_str, name in RE_KW_MANDATORY.findall(block):
        mandatory.append(f"{name}[]" if "vector" in type_str else name)

    for type_str, name in RE_KW_OPTIONAL.findall(block):
        entry = f"{name}[]" if "vector" in type_str else name
        if entry not in mandatory and entry not in optional:
            optional.append(entry)

    arity: int | None = None
    if m := RE_ARITY.search(block):
        raw = m.group(1)
        if raw.endswith("kVariadic"):
            arity = -1
        elif re.fullmatch(r"-?\d+", raw):
            arity = int(raw)
    elif RE_BINARY_OR_UNARY.search(block):
        arity = 2
    elif RE_VARIADIC.search(block):
        arity = -1

    return {
        "op": op,
        "positional": POSITIONAL_NAMES.get(f"k{op}", []),
        "arity": arity,
        "mandatory_keywords": mandatory,
        "optional_keywords": optional,
    }


def to_snake(op: str) -> str:
    """CamelCase -> snake_case，匹配 IR 文本里的 op 名。"""
    s = re.sub(r"(?<!^)(?=[A-Z])", "_", op).lower()
    return s


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--xls-dir", default="third_party/xls", help="XLS 源码根目录")
    ap.add_argument("-o", "--out", default="config/op_contracts.json")
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    parser_cc = root / args.xls_dir / "xls" / "ir" / "ir_parser.cc"
    if not parser_cc.exists():
        print(f"ERROR: 找不到 {parser_cc}", file=sys.stderr)
        return 2

    src = parser_cc.read_text(encoding="utf-8", errors="replace")
    blocks = extract_op_blocks(src)

    contracts: dict[str, dict] = {}
    for op, block in sorted(blocks.items()):
        # 收录所有 case 分支；未声明 arg_parser 的（如用 BuildBinaryOrUnaryOp
        # 的 add/sub/shll...）arity 由 builder 调用形式推断。
        if not block.strip():
            continue
        c = parse_block(op, block)
        # op 名以源码为准，IR 文本用小写下划线下划线形式
        contracts[to_snake(op)] = c

    # 补全：ir_parser.cc 的 switch 有 default 分支走 BuildBinaryOrUnaryOp，
    # 因此大量二元/一元 op（add/sub/and/or/xor/not/eq/lt/shll...）没有显式
    # case。它们的 op 名全集来自 xls/ir/op_list.h。这些 op 的 arity 不做静态
    # 校验（arity=None），仅确认 op 名合法。
    op_list = parser_cc.parent / "op_list.h"
    n_default = 0
    if op_list.exists():
        lst = op_list.read_text(encoding="utf-8", errors="replace")
        for cxx, enum, name in re.findall(
            r'F\((k[A-Za-z0-9]+),\s*(OP_[A-Z0-9_]+),\s*"([a-z0-9_]+)"', lst
        ):
            if name in contracts:
                continue
            contracts[name] = {
                "op": cxx[1:],
                "positional": POSITIONAL_NAMES.get(cxx, []),
                "arity": None,  # default 分支，不做 arity 静态校验
                "mandatory_keywords": [],
                "optional_keywords": [],
                "source": "op_list.h/default(BinaryOrUnary)",
            }
            n_default += 1

    out_path = root / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "_source": str(parser_cc.relative_to(root)),
        "_note": "由 gen_op_contracts.py 从 XLS ir_parser.cc 提取；arity=-1 表示可变参数",
        "contracts": contracts,
    }
    out_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    n_kw = sum(1 for c in contracts.values() if c["mandatory_keywords"])
    print(f"✅ 提取 {len(contracts)} 条 op 契约 -> {args.out}")
    print(f"   显式 case（含参数契约）: {len(contracts) - n_default} 条")
    print(f"   来自 op_list.h（arity 不校验）: {n_default} 条")
    print(f"   其中 {n_kw} 条含必选关键字参数")
    for name in ("array_index", "sel", "zero_ext", "bit_slice", "literal", "concat"):
        if name in contracts:
            c = contracts[name]
            print(
                f"   {name:14s} arity={c['arity']} "
                f"mandatory={c['mandatory_keywords']} optional={c['optional_keywords']}"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
