# 第三方源码索引

四个仓库**均为独立 git 仓库（非 submodule）**，合计约 **470 MB**，放在
**工程外同级目录 `../third_party/`**（2026-10-01 从工程内 `third_party/` 外迁：
第三方源码 + 4 GB 级 bazel 输出基座留在工程内会让 IntelliJ 全量索引、卡到不可用）。
本文档记录其来源与版本，供重建与追溯。

| 目录 | 上游 | 基线 commit | 日期 | 体积 | 用途 |
|---|---|---|---|---|---|
| `xls/` | [google/xls](https://github.com/google/xls) | `49c163e42` | 2026-09-11 | 155 MB | **核心**：XLS IR parser / codegen，P4 → RTL 的后端 |
| `p4c/` | [p4lang/p4c](https://github.com/p4lang/p4c) | `ff5070480` | 2026-09-10 | 220 MB | 官方 p4c 参考实现（ir_parser.cc 是本工程 op 契约的来源） |
| `behavioral-model/` | [p4lang/behavioral-model](https://github.com/p4lang/behavioral-model) | `14e39e22` | 2026-09-09 | 44 MB | bmv2，A5 三级对拍的软件交换机 |
| `p4-spec/` | [p4lang/p4-spec](https://github.com/p4lang/p4-spec) | `ebfc6aa` | 2026-07-21 | 50 MB | P4 语言规范（AsciiDoc 源） |

## XLS 的详细拉取记录

见 [`third-party-xls.md`](third-party-xls.md) —— 含镜像选型、浅克隆参数、校验结果、
增量更新命令、构建要求（Bazel 8.7.0 + Bzlmod，无 submodule）。

## 重建方式

网络受限环境下走镜像 git 智能 HTTP（详见 skill `github-mirror-download`）。
以 XLS 为例：

```bash
mkdir -p ../third_party && cd ../third_party
git init -q -b main xls && cd xls
git remote add origin https://github.com/google/xls.git
git remote add mirror https://gh.monlor.com/https://github.com/google/xls.git
git -c http.version=HTTP/1.1 fetch --depth 1 --no-tags \
    mirror refs/heads/main:refs/remotes/mirror/main
git switch -c main refs/remotes/mirror/main
```

其余三个仓库同理（`origin` 直连 / `mirror` 镜像双远端）。

## 与工程的关系

- **`xls/` 是运行时依赖**：`config/op_contracts.json` 由 `scripts/gen_op_contracts.py`
  从其 `xls/ir/ir_parser.cc` 自动提取；后续 XLS codegen 也要用其构建产物。
- **`p4c/` 只做参考**：本工程的 P4 前端是自研的 `src/main/scala/P4C/`（fork 自 `../../P4C`），
  不依赖官方 p4c 的代码。
