#!/usr/bin/env bash
# 收口 third_party/dist：写入 VERSION 与 toolchains.json，并检查可重定位性。
#
# 收编自 p4x/packaging（2026-10-01 合并）：去掉了对 p4xlib 版本的读取
# （p4x 不再有版本号来源），只记构建时间与工具清单。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TP="$(cd "$REPO/../third_party" && pwd)"
DIST="$TP/dist"
[[ -d "$DIST" ]] || { echo "[dist] 目录不存在: $DIST" >&2; exit 1; }

cat > "$DIST/VERSION" <<EOF
p4_rtl golden toolchain
built $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

python3 - "$DIST" <<'PY'
import json, pathlib, shutil, sys
dist = pathlib.Path(sys.argv[1])
info = {"dist": str(dist)}
for key, rel in (("p4c-bm2-ss", "bin/p4c-bm2-ss"), ("simple_switch", "bin/simple_switch"),
                 ("p4test", "bin/p4test")):
    p = dist / rel
    info[key] = str(p) if p.exists() else None
inc = dist / "share" / "p4c" / "p4include"
info["p4include"] = str(inc) if inc.is_dir() else None
(dist / "toolchains.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
print(json.dumps(info, indent=2))
PY

echo "[dist] VERSION:"
cat "$DIST/VERSION"

# 可重定位性：列出仍指向绝对路径的动态依赖（多则需 dylibbundler 收口）
echo
echo "[dist] 非 @rpath/@loader_path 的动态依赖（若多，需 dylibbundler 或 install_name_tool 收口）:"
for bin in "$DIST"/bin/*; do
  [[ -x "$bin" && ! "$bin" == *.jar ]] || continue
  otool -L "$bin" 2>/dev/null | tail -n +2 | awk '{print $1}' \
    | grep -E '^/' | grep -v '^/usr/lib' | grep -v '^/System' \
    | sed "s|^|  $(basename "$bin"): |" || true
done
