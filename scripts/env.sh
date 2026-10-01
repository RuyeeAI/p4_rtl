#!/usr/bin/env bash
# A1 环境变量：XLS 构建所需的镜像与隔离目录
#
# 用法（bash / zsh 均可）： source scripts/env.sh
#
# 设计要点
#  - bazelisk / bazel 二进制、Bazel 输出目录、第三方源码（third_party/）
#    全部落在**工程外**的同级目录 $P4XLS_ROOT/../third_party/ 下：
#    第三方仓库 + 4GB 级 bazel 输出基座留在工程内会让 IntelliJ 全量索引、
#    卡到不可用（2026-10-01 外迁）。不污染 ~/.cache，也便于整套删除重来。
#  - 所有走 GitHub 的下载（bazel 二进制、Bazel module 源）统一经 gh-proxy 镜像，
#    因为直连 GitHub 实测只有 ~30KB/s，镜像可达 ~3MB/s。

# ---- 定位本脚本所在目录（兼容 bash 与 zsh） ----
# bash：BASH_SOURCE[0]；zsh：${(%):-%x}
# 注意 zsh 的 ${(%):-%x} 在 bash 下是非法语法，故用 eval 延迟求值。
if [ -n "${ZSH_VERSION:-}" ]; then
  eval '_P4XLS_ENV_FILE="${(%):-%x}"'
else
  _P4XLS_ENV_FILE="${BASH_SOURCE[0]}"
fi
_P4XLS_ENV_DIR="$(cd "$(dirname "$_P4XLS_ENV_FILE")" && pwd)"
unset _P4XLS_ENV_FILE
export P4XLS_ROOT="$(cd "$_P4XLS_ENV_DIR/.." && pwd)"
unset _P4XLS_ENV_DIR

# ---- 工具目录（工程外：third_party/.tools，避免 IDE 索引） ----
export P4XLS_THIRD_PARTY="$P4XLS_ROOT/../third_party"
export P4XLS_TOOLS="$P4XLS_THIRD_PARTY/.tools"
case ":$PATH:" in
  *":$P4XLS_TOOLS/bin:"*) ;;
  *) export PATH="$P4XLS_TOOLS/bin:$PATH" ;;
esac

# ---- XLS 源码位置（工程外同级 third_party/，下面要用它读 .bazelversion） ----
export XLS_SRC="$P4XLS_THIRD_PARTY/xls"

# ---- bazelisk ----
# bazel 二进制缓存（bazelisk 下载 bazel 本身时用）
export BAZELISK_HOME="$P4XLS_TOOLS/bazelisk"
# bazel 二进制下载基址改走镜像；默认是直连 GitHub（实测 ~30KB/s）
export BAZELISK_BASE_URL="https://gh-proxy.com/https://github.com/bazelbuild/bazel/releases/download"

# 固定 bazel 版本。**不加这一条 bazelisk 会崩**：没有版本约束时它默认按
# "latest" 去查 GCS 的版本列表（www.googleapis.com/...），而该域名在本
# 网络环境不可达（实测 curl 超时，HTTP 000）。指定版本后 bazelisk 直接
# 按 BAZELISK_BASE_URL 拼 URL 下载，绕过 GCS。
if [ -f "$XLS_SRC/.bazelversion" ]; then
  export USE_BAZEL_VERSION="$(cat "$XLS_SRC/.bazelversion" | tr -d '[:space:]')"
else
  export USE_BAZEL_VERSION="8.7.0"
fi

# ---- bazel ----
# Bazel 的 output_user_root（构建缓存/产物），隔离到工程内
export P4XLS_BAZEL_OUTPUT_ROOT="$P4XLS_TOOLS/bazel-out"

# ---- 便捷函数：bazel 带隔离 output root ----
#
# 必须显式清空 HTTP(S)_PROXY：本机环境里存在 WorkBuddy 注入的透明代理
# （HTTP_PROXY=http://127.0.0.1:64321）。Bazel 的 Java HTTP 客户端会读取
# 这些变量并走 CONNECT 隧道，而该代理无法访问 github.com —— 表现为每个
# 归档下载都失败于：
#     Unable to tunnel through proxy. Proxy returns "HTTP/1.1 502 Bad Gateway"
# 实测：绕开代理时 gh-proxy 镜像与 bcr.bazel.build 均可直连（HTTP 200），
# 而 github.com 本身直连不可用（000）—— 所以下载必须同时依赖镜像重写规则，
# 见 config/bazel_downloader.cfg。
p4xls_bazel() {
  env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy \
      -u ALL_PROXY -u all_proxy -u NO_PROXY -u no_proxy \
    bazelisk --output_user_root="$P4XLS_BAZEL_OUTPUT_ROOT" "$@"
}
