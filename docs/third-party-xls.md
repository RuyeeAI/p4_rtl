# 第三方依赖：google/xls（XLS - Accelerated HW Synthesis）

## 拉取记录

| 项 | 值 |
|---|---|
| 上游 | https://github.com/google/xls |
| 本地路径 | `../third_party/xls/` |
| 分支 | `main` |
| Commit | `49c163e427a54ad90dc70b7dd507212633630a21` |
| Commit 日期 | 2026-09-11 |
| 提交标题 | Merge pull request #4881 from mag-mga:mag-mga/select-to-xor |
| License | Apache-2.0 |
| 本地体积 | 155 MB（含完整 git 历史 10,677 commits） |
| 拉取日期 | 2026-09-14 |
| 校验 | `git fsck` 无错，工作树干净，非 shallow |

## 这是什么

Google 的 **HLS（高层次综合）工具链**：把 DSLX（类 Rust 的硬件描述语言）/ IR 综合成 **可综合的 Verilog / SystemVerilog**。

- 支持纯组合/流水线函数（pure-wire I/O）和带状态的 `proc` 并发进程
- 同一份设计既能当宿主软件跑（快速仿真），也能出硬件
- 目录结构：`xls/dslx`（前端）、`xls/ir`、`xls/passes`（优化）、`xls/codegen`（Verilog/SV 后端）、`xls/scheduling`、`xls/synthesis`
- 规模：1,424 个 C++ 文件，827 个 DSLX 文件

> 官方声明：实验性项目，非 Google 官方支持产品，DSLX 不保证向后兼容。

## 依赖方式

**不用 git submodule**。依赖通过 **Bzlmod**（`MODULE.bazel`，45 个 `bazel_dep`）+ `WORKSPACE` 声明，构建时由 Bazel 自行下载。

所以本次只拉了仓库源码，第三方依赖尚未落地——首次构建前需要能访问 Bazel 的下载源（`bazel_downloader.cfg` 可配置镜像）。

## 更新方法

远程已配好 `origin`（github 直连）与 `mirror`（镜像）。镜像通道优先：

```bash
cd ../third_party/xls
GIT_HTTP_LOW_SPEED_TIME=180 GIT_HTTP_LOW_SPEED_LIMIT=20000 \
  git -c http.version=HTTP/1.1 fetch --no-tags mirror refs/heads/main:refs/remotes/mirror/main
git merge --ff-only refs/remotes/mirror/main
```

## 构建（若要实际编译）

要求 **Bazel 8.7.0**。XLS 自建相当重（含 LLVM 等），本机首次构建以小时计。

**轻量替代**：上游提供 conda 预编译包，可跳过自建：

```bash
conda install -c litex-hub xls
```

构建入口：

```bash
bazel build //xls/dslx:interpreter_main      # DSLX 解释器
bazel build //xls/tools:codegen_main         # IR → Verilog 生成器
bazel build //xls/tools:opt_main             # IR 优化
```

## 与 P4 → RTL 的关系

XLS 本身不含 P4 前端。若要把它用作 P4 到 RTL 的后端，链路大致是：

```
P4 (p4c frontend/midend) → 自研 IR 降级 → XLS IR / DSLX → XLS codegen → Verilog/SV
```

它的价值在于**省掉自研 RTL 生成器**，代价是要做 P4 IR 到 XLS IR 的语义映射（parser 状态机、PHV、match-action 表都要表达成 XLS 的 proc/函数）。
