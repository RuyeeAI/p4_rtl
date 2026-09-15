# p4_rtl —— P4 → XLS IR → RTL 工具链

把 P4 程序编译为 XLS IR，再由 [google/xls](https://github.com/google/xls) 生成可综合 Verilog。

**本工程是 2026-09-15 从本地 P4C 工程 fork 出的干净工程**，专注 XLS 路线（不发射 Chisel）。

## 当前状态

**A0 ✅ / A1 ✅ / A2 决策 ✅ / M0 ✅** —— 整条路线的技术前提**已全部实测通过**，
可以从「调研验证」转入「实现」（A2 `XlsBackend`）。

| 检查项 | 结果 |
|---|---|
| P4 → XLS IR 文本生成 | ✅ 11 个 demo，产出 25 个 `.ir` |
| A0：XLS IR 语法兼容性（静态校验器） | ✅ **25/25 通过**，0 issue |
| 唯一不兼容点 | ✅ 已修：`array_index(arr, idx)` → `array_index(arr, indices=[idx])` |
| **A1：真实 XLS parser 复验** | ✅ **25/25 通过，且 round-trip 全部逐字节稳定** |
| A1：官方样本回归 | ✅ `xls/ir/testdata` 118/121；全集 744/768 |
| A1：`proc` + channel 语法 | ✅ **4/4 通过** |
| **M0：访存延迟的时序契约** | ✅ **契约成立**：生成的 Verilog + iverilog 仿真实证 |
| **P0：II 编排水法** | ✅ **定案：并行 token 拓扑，最小 II 从 2 降到 1**，功能不变 |

**M0 一句话结论**：`send key → 等 response 返回 → 分发` 这条闭环成立，
且延迟随 response 到达时刻线性变化（**不是死等固定 L 拍**）。
一键复现：`scripts/m0_verify.sh`

> 完整进度与下一步计划见 [`docs/进度与计划.md`](docs/进度与计划.md)。
> 各里程碑证据链见 [`docs/A0-门禁报告.md`](docs/A0-门禁报告.md)、
> [`docs/A1-环境报告.md`](docs/A1-环境报告.md)、
> [`docs/M0-接口打样报告.md`](docs/M0-接口打样报告.md)。

## 快速开始

```bash
# 1. 编译（Scala 侧）
sbt compile

# 2. P4 -> XLS IR（CLI 包装脚本，避免 sbt 启动开销）
scripts/p4xls p4c gen testcases/p4/demo9-l3forwarder.p4 out/gen --dump-ir out/ir

# 3. 静态校验 IR（A0 门禁，纯 Python）
scripts/p4xls lint-ir out/ir
# 或
sbt xlsIrLint

# 4. ★ 用真实 XLS parser 校验（A1 门禁）
source scripts/env.sh                      # 必须：含清代理的 bazel 包装
scripts/bootstrap_xls_env.sh               # 首次：装 bazelisk + bazel，打补丁，构建
scripts/xls_ir_verify.sh testcases/ir      # 之后：直接校验
scripts/xls_ir_verify.sh --roundtrip testcases/ir   # 加往返一致性检查
scripts/xls_ir_verify.sh --json <file.ir>  # JSON 输出
```

> XLS 环境是**自建**的：官方只发布 linux-x64 产物，本机无容器，因此从源码构建。
> 构建需给 XLS 打三个补丁（去掉 OpenROAD/LLVM/PDK 等无关重依赖），
> 细节与踩过的坑见 [`docs/A1-环境报告.md`](docs/A1-环境报告.md)。

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
| **A1** | 搭建 XLS 构建环境（macOS/arm64 源码自建 + 五处补丁） | ✅ 完成 |
| **A2 决策** | 启动前盘点 + 4 项决策（D1–D4） | ✅ 完成 |
| **M0** | 接口打样：访存延迟的时序契约 | ✅ **完成（契约成立）** |
| **P0** | II 编排水法定案（M0 发现的 II=2 问题） | ✅ **完成（II=1）** |
| A2 | `XlsBackend`：生成 `proc` 时序编排（**唯一缺口**） | 待开始（**前置已清空**） |
| A3 | 表接口：Key/Response 通道 | 待开始 |
| A4 | 组装 + CLI 收口 | 部分完成 |
| A5 | 三级对拍（C 模型 / bmv2 / XLS） | 待开始 |

**P0 的结论**（已定案）：M0 发现的 II=2 问题，**瓶颈不是通道操作本身，而是 token 串链**
——把三个通道操作改成都直连 `state_read(tok)`、末尾 `after_all` 汇聚后，
**最小 II = 1**（stages 1..4 全部可行），功能不变，RTL 寄存器 9→4 个、规模 −35%、延迟少 1 拍。
II 用 `#[initiation_interval(1)]` 写进 IR 自描述。
生成器必须遵守：**通道操作不得串链**、**predicate 互斥性必须校验**。
详见 [`docs/A2-编排水法定案.md`](docs/A2-编排水法定案.md)。

**A2 是核心工作量**：P4C 的 `IrText.dump` 只能输出单个 `fn`，尚无 `proc`/通道抽象。
映射关系已在 `docs/复用资产审计-本地P4C与p4x.md` 中给出。
语法前提（`proc` + `chan` + 状态读写）已由 A1/A2-决策/M0 三轮验证成立。

## 两项设计裁剪（v2 方案）

1. **表只生成 Key/Response 接口，不生成存储 RTL** —— 存储器/加速器由外部实现。
2. **不支持 PISA 的 ALU 阵列** —— 不做流水级资源分配调度。

净效果：MVP 从 7–11 人月降至 **4–7 人月**。
原头号风险（自研 TCAM/LPM RTL）已消失，**新头号风险（访存延迟的时序契约）已由 M0 关闭**。

## 相关文档

- [`docs/进度与计划.md`](docs/进度与计划.md) —— **进度总览、逐项任务点、下一步计划、技术债**
- [`docs/A2-编排水法定案.md`](docs/A2-编排水法定案.md) —— **P0：并行 token 拓扑（II=1）与对生成器的要求**
- [`docs/M0-接口打样报告.md`](docs/M0-接口打样报告.md) —— M0 结论、RTL 解读、六个坑
- [`docs/A1-环境报告.md`](docs/A1-环境报告.md) —— XLS 自建过程与全部踩坑记录
- [`docs/A0-门禁报告.md`](docs/A0-门禁报告.md) —— A0 门禁结论与证据链
- [`docs/A2-启动前决策盘点.md`](docs/A2-启动前决策盘点.md) —— 4 项决策
- [`docs/双线同步说明.md`](docs/双线同步说明.md) —— 双 RTL 路线的共享代码单一事实源
- [`docs/p4c-xls后端扩展方案.md`](docs/p4c-xls后端扩展方案.md) —— 完整方案（v2 + 路线修正）
- [`docs/复用资产审计-本地P4C与p4x.md`](docs/复用资产审计-本地P4C与p4x.md) —— 模块级复用度清单
- [`docs/P4转RTL开源工具调研.md`](docs/P4转RTL开源工具调研.md) —— 开源生态调研
- [`docs/third-party-xls.md`](docs/third-party-xls.md) —— XLS 拉取记录与更新方式
