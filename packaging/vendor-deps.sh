#!/usr/bin/env bash
# 预置 p4c 构建所需的离线依赖（p4runtime proto）。
#
# 收编自 p4x/packaging（2026-10-01 合并），路径已适配工程外同级 third_party/。
#
# 背景：p4c 的 ENABLE_BMV2 依赖 ENABLE_CONTROL_PLANE（CMakeLists.txt:37），
# 而 ENABLE_CONTROL_PLANE=ON 会在 configure 阶段用 FetchContent 从 GitHub
# 拉 p4runtime。本机直连 GitHub 极慢（<1KB/s，HTTP2 framing 报错），故改为
# 从镜像预置到 third_party/_deps/，再用 -DFETCHCONTENT_SOURCE_DIR_P4RUNTIME 指过去。
#
# 只需 proto 目录（p4c 只读 p4runtime_SOURCE_DIR/proto，不构建 p4runtime 本身）。
set -euo pipefail

P4RUNTIME_COMMIT="${P4RUNTIME_COMMIT:-ec4eb5ef70dbcbcbf2f8357a4b2b8c2f218845a5}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TP="$(cd "$REPO/../third_party" && pwd)"
DEST="$TP/_deps/p4runtime"
MIRROR="https://gh.monlor.com/https://github.com/p4lang/p4runtime.git"

export GIT_HTTP_LOW_SPEED_LIMIT=1000
export GIT_HTTP_LOW_SPEED_TIME=60

GIT=(git -c http.version=HTTP/1.1)

if "${GIT[@]}" -C "$DEST" cat-file -e "$P4RUNTIME_COMMIT" 2>/dev/null \
   && [[ "$("${GIT[@]}" -C "$DEST" rev-parse HEAD 2>/dev/null)" == "$P4RUNTIME_COMMIT" ]]; then
  echo "[vendor] p4runtime 已就绪 @ $P4RUNTIME_COMMIT"
else
  echo "[vendor] 拉取 p4runtime @ $P4RUNTIME_COMMIT（镜像）"
  rm -rf "$DEST"
  "${GIT[@]}" init -q "$DEST"
  "${GIT[@]}" -C "$DEST" remote add origin "$MIRROR"
  "${GIT[@]}" -C "$DEST" fetch --depth 1 origin "$P4RUNTIME_COMMIT"
  "${GIT[@]}" -C "$DEST" checkout -q FETCH_HEAD
fi

protos=$(find "$DEST/proto" -name '*.proto' 2>/dev/null | wc -l | tr -d ' ')
if [[ "$protos" -lt 4 ]]; then
  echo "[vendor] 错误：$DEST/proto 下 .proto 文件不足（找到 $protos，期望 4）" >&2
  exit 1
fi
echo "[vendor] OK: $DEST/proto（$protos 个 .proto）"
