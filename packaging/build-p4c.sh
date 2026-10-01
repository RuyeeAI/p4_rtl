#!/usr/bin/env bash
# 构建官方 p4c 并安装 p4c-bm2-ss + p4include 到 third_party/dist（黄金对拍工具链）。
#
# 收编自 p4x/packaging（2026-10-01 合并），路径已适配工程外同级 third_party/ 布局。
#
# 关键约束（已核实）：
#   * ENABLE_BMV2 依赖 ENABLE_CONTROL_PLANE（p4c/CMakeLists.txt:37），
#     且 simple_switch/main.cpp 调用 serializeP4RuntimeIfRequired —— 因此
#     ENABLE_CONTROL_PLANE 必须 ON，p4runtime/protobuf/abseil 绕不开。
#   * p4runtime 无 CMakeLists，p4c 只读其 proto/ 目录 → 用镜像预置后经
#     FETCHCONTENT_SOURCE_DIR_P4RUNTIME 指过去，避免联网。
#   * 直接调 p4c-bm2-ss 即可，不需要 Python p4c 驱动（include 走
#     P4C_16_INCLUDE_PATH 环境变量，见 frontends/common/parser_options.cpp）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TP="$(cd "$REPO/../third_party" && pwd)"
SRC="$(cd "$TP/p4c" && pwd)"
DIST="$TP/dist"
BUILD="${P4C_BUILD_DIR:-$TP/.build/p4c}"
NPROC="$(sysctl -n hw.ncpu)"

BISON_BIN="$(brew --prefix bison)/bin"
export PATH="$BISON_BIN:$PATH"

P4RUNTIME_SRC="$TP/_deps/p4runtime"
if [[ ! -d "$P4RUNTIME_SRC/proto" ]]; then
  echo "[p4c] 缺少预置的 p4runtime，先跑 packaging/vendor-deps.sh" >&2
  exit 1
fi

echo "[p4c] source : $SRC"
echo "[p4c] prefix : $DIST"
echo "[p4c] bison  : $(bison --version | head -1)"

cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_INSTALL_PREFIX="$DIST" \
  -DCMAKE_INSTALL_RPATH='@loader_path/../lib' \
  -DCMAKE_INSTALL_RPATH_USE_LINK_PATH=TRUE \
  -DBISON_EXECUTABLE="$BISON_BIN/bison" \
  -DCMAKE_PREFIX_PATH="$(brew --prefix boost@1.85);$(brew --prefix);$(brew --prefix protobuf);$(brew --prefix abseil);$(brew --prefix bdw-gc)" \
  -DENABLE_CONTROL_PLANE=ON \
  -DENABLE_BMV2=ON \
  -DENABLE_P4TEST=ON \
  -DENABLE_EBPF=OFF \
  -DENABLE_UBPF=OFF \
  -DENABLE_DPDK=OFF \
  -DENABLE_P4TC=OFF \
  -DENABLE_TOFINO=OFF \
  -DENABLE_GTESTS=OFF \
  -DENABLE_WERROR=OFF \
  -DENABLE_PROTOBUF_STATIC=OFF \
  -DENABLE_ABSEIL_STATIC=OFF \
  -DP4C_USE_PREINSTALLED_PROTOBUF=ON \
  -DP4C_USE_PREINSTALLED_ABSEIL=ON \
  -DP4C_USE_PREINSTALLED_BDWGC=ON \
  -DFETCHCONTENT_SOURCE_DIR_P4RUNTIME="$P4RUNTIME_SRC"

cmake --build "$BUILD" -j "$NPROC"

mkdir -p "$DIST/bin"
# 只装我们需要的：后端二进制 + 架构头
cp -f "$BUILD/backends/bmv2/p4c-bm2-ss" "$DIST/bin/" 2>/dev/null || true
if [[ -x "$BUILD/backends/p4test/p4test" ]]; then
  cp -f "$BUILD/backends/p4test/p4test" "$DIST/bin/"
fi
mkdir -p "$DIST/share/p4c"
rsync -a --delete "$SRC/p4include/" "$DIST/share/p4c/p4include/"

echo "[p4c] 安装完成:"
ls -1 "$DIST/bin"
echo "[p4c] p4include: $(ls -1 "$DIST/share/p4c/p4include" | wc -l | tr -d ' ') 个文件"
