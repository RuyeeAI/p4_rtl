#!/usr/bin/env python3
"""把 EM（精确匹配表）模块的源码打成 zip，并输出发信所需的 base64 / size / sha1。

只收 `em/src/main/scala/em/*.scala`（不含 target/ 编译产物、不含 .DS_Store）。

用法:
  python3 scripts/pack_em_src.py [out.zip]
"""

from __future__ import annotations

import base64
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "em" / "src" / "main" / "scala" / "em"
ARC_PREFIX = "p4xls-em/src/main/scala/em"


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "out" / "pkg" / "p4xls-em-src.zip"
    out.parent.mkdir(parents=True, exist_ok=True)

    files = sorted(p for p in SRC_DIR.glob("*.scala") if p.name != ".DS_Store")
    if not files:
        print(f"ERROR: {SRC_DIR} 下没有 .scala", file=sys.stderr)
        return 2

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for p in files:
            z.write(p, f"{ARC_PREFIX}/{p.name}")

    raw = out.read_bytes()
    print(f"✅ {out}")
    for p in files:
        print(f"   + {ARC_PREFIX}/{p.name}  ({p.stat().st_size} B)")
    print(f"   大小 {len(raw)} B   文件数 {len(files)}")
    print(f"   sha1 {hashlib.sha1(raw).hexdigest()}")

    # 给发信工具用的载荷
    env = ROOT / "out" / "pkg" / "em_attach.json"
    env.write_text(
        json.dumps(
            {
                "filename": out.name,
                "size": len(raw),
                "sha1": hashlib.sha1(raw).hexdigest(),
                "b64": base64.b64encode(raw).decode(),
            }
        )
    )
    print(f"   发信载荷 -> {env}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
