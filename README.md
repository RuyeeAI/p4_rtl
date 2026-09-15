# p4_rtl —— P4 → XLS IR → RTL 工具链

把 P4 程序编译为 XLS IR，再由 [google/xls](https://github.com/google/xls) 生成可综合 Verilog。

**本工程是 2026-09-15 从本地 P4C 工程 fork 出的干净工程**，专注 XLS 路线（不发射 Chisel）。

## 当前状态

**A0 门禁已通过** —— 这是整条路线成立的前提。

| 检查项 | 结果 |
|---|---|
| P4 → XLS IR 文本生成 | ✅ 11 个 demo，产出 25 个 `.ir` |
| XLS IR 语法兼容性 | ✅ **25/25 通过**，0 issue |
| 唯一不兼容点 | ✅ 已修：`array_index(arr, idx)` → `array_index(arr, indices=[idx])` |

详见 [`docs/A0-门禁报告.md`](docs/A0-门禁报告.md)。

## 快速开始

```bash
# 1. 编译
sbt compile

# 2. P4 -> XLS IR（CLI 包装脚本，避免 sbt 启动开销）
scripts/p4xls p4c gen testcases/p4/demo9-l3forwarder.p4 out/gen --dump-ir out/ir

# 3. 校验 IR（A0 门禁）
scripts/p4xls lint-ir out/ir
# 或
sbt xlsIrLint
```

## 目录结构

```
p4_rtl/
├── build.sbt                      # sbt 工程（Scala 2.13.12，纯 Scala，无 chisel3）
├── src/main/scala/
│   ├── P4C/                       # fork 自本地 P4C 工程（5,381 行，包名保持便于 diff）
│   │   ├── Lexer / Parser / Ast   #   P4 前端
│   │   ├── Ir / IrBuilder         #   中间表示
│   │   ├── IrText                 #   ★ XLS IR 文本 dump / parse（本轮已修 array_index）
│   │   ├── Smt / Interp / Tools   #   形式化 / 解释器 / 工具
│   │   ├── ChiselBackend          #   Chisel 后端（XLS 路线下暂不启用）
│   │   └── Cli / Generate         #   命令行与生成管线
│   └── p4xls/Main.scala           # 本工程顶层 CLI
├── scripts/
│   ├── gen_op_contracts.py        # 从 XLS ir_parser.cc 提取 op 参数契约表
│   ├── xls_ir_lint.py             # XLS IR 文本静态校验器（A0 门禁）
│   └── p4xls                      # CLI 包装（java 直跑，绕过 sbt 启动开销）
├── config/op_contracts.json       # 84 条 op 契约（生成物）
├── testcases/
│   ├── p4/                        # 11 个 demo P4 源
│   ├── ir/                        # 门禁基准：当前 P4C 产出的 25 个 .ir
│   └── ir_legacy_pre_a0/          # A0 修复前的旧样本（回归对照）
├── third_party/xls/               # XLS 源码（未纳入版本管理，见 docs/third-party-xls.md）
└── docs/                          # 调研、方案、审计、门禁报告
```

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| **A0** | 前置门禁：XLS IR 语法兼容性验证 | ✅ 完成 |
| A1 | 搭建 XLS 构建环境（需 `brew install bazelisk`） | ⏸ 受阻 |
| A2 | `XlsBackend`：生成 `proc` 时序编排（**唯一缺口**） | 待开始 |
| A3 | 表接口：Key/Response 通道 | 待开始 |
| A4 | 组装 + CLI 收口 | 部分完成 |
| A5 | 三级对拍（C 模型 / bmv2 / XLS） | 待开始 |

**A2 是核心工作量**：P4C 的 `IrText.dump` 只能输出单个 `fn`，尚无 `proc`/通道抽象。
映射关系已在 `docs/复用资产审计-本地P4C与p4x.md` 中给出。

## 两项设计裁剪（v2 方案）

1. **表只生成 Key/Response 接口，不生成存储 RTL** —— 存储器/加速器由外部实现。
2. **不支持 PISA 的 ALU 阵列** —— 不做流水级资源分配调度。

净效果：MVP 从 7–11 人月降至 **4–7 人月**；头号风险从"自研 TCAM/LPM RTL"转为"访存延迟的时序契约"。

## 相关文档

- [`docs/A0-门禁报告.md`](docs/A0-门禁报告.md) —— 本轮门禁结论与证据链
- [`docs/p4c-xls后端扩展方案.md`](docs/p4c-xls后端扩展方案.md) —— 完整方案（v2 + 路线修正）
- [`docs/复用资产审计-本地P4C与p4x.md`](docs/复用资产审计-本地P4C与p4x.md) —— 模块级复用度清单
- [`docs/P4转RTL开源工具调研.md`](docs/P4转RTL开源工具调研.md) —— 开源生态调研
- [`docs/third-party-xls.md`](docs/third-party-xls.md) —— XLS 拉取记录与更新方式
