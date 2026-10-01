#!/usr/bin/env bash
# 构建 behavioral-model (bmv2) 并安装到 third_party/dist（黄金对拍工具链）。
#
# 收编自 p4x/packaging（2026-10-01 合并，见 docs/复用资产审计-本地P4C与p4x.md），
# 路径已适配工程外同级 third_party/ 布局。
#
# 只做无头仿真需要的部分：simple_switch（--use-files 模式不需要 root/NIC）。
# 关掉 PI / nanomsg，避免引入 protobuf+gRPC 与 nanomsg 版本耦合。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TP="$(cd "$REPO/../third_party" && pwd)"
SRC="$(cd "$TP/behavioral-model" && pwd)"
DIST="$TP/dist"
BUILD="${BMV2_BUILD_DIR:-$TP/.build/bmv2}"

BOOST="$(brew --prefix boost@1.85)"
JSONCPP="$(brew --prefix jsoncpp)"
PCAP="$(brew --prefix libpcap)"
THRIFT="$(brew --prefix thrift)"
BREW="$(brew --prefix)"
GMP="$(brew --prefix gmp)"
SHIM="$HERE/compat/bmv2_apple_portability.h"
NPROC="$(sysctl -n hw.ncpu)"

# bmv2 的 bmsim 是 OBJECT 库，PUBLIC 依赖不会传递到 bmall 共享库；macOS 的
# ld 不允许 dylib 有未定义符号 → 显式把这些库挂到共享库/可执行文件链接行。
DEPS="-L$BREW/lib -L$GMP/lib -L$PCAP/lib -lxxhash -ljsoncpp -lgmp -lpcap"

echo "[bmv2] source: $SRC"
echo "[bmv2] prefix: $DIST"
echo "[bmv2] shim  : $SHIM"

cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_INSTALL_PREFIX="$DIST" \
  -DCMAKE_INSTALL_RPATH='@loader_path/../lib' \
  -DCMAKE_INSTALL_RPATH_USE_LINK_PATH=TRUE \
  -DCMAKE_PREFIX_PATH="$BOOST;$JSONCPP;$PCAP;$GMP;$THRIFT;$BREW" \
  -DCMAKE_CXX_FLAGS="-I$PCAP/include -I$GMP/include -I$BOOST/include -I$BREW/include -include $SHIM" \
  -DCMAKE_SHARED_LINKER_FLAGS="$DEPS" \
  -DCMAKE_EXE_LINKER_FLAGS="$DEPS -L$BOOST/lib" \
  -DWITH_PI=OFF \
  -DWITH_THRIFT=ON \
  -DWITH_NANOMSG=OFF \
  -DENABLE_ELOGGER=OFF

cmake --build "$BUILD" -j "$NPROC"
cmake --install "$BUILD"

echo "[bmv2] 安装到 $DIST/bin:"
ls -1 "$DIST/bin" 2>/dev/null || true
