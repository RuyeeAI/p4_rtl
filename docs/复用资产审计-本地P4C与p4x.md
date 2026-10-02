# 复用资产审计：本地 P4C 与 p4x

> 审计对象：`Code-Repos/P4C`（自研 P4→Chisel 编译器）、`Code-Repos/p4x`（工具链统一入口）
> 目的：评估在 `p4_rtl`（P4→RTL）方向上有多少现成代码可直接用
> 日期：2026-09-15
> **复审与合并：2026-10-01/02（见 §10，本节为最新结论）**

---

## 0. 结论

**有，而且是重资产。** P4C 是一个 5,381 行的自研 P4 编译器，且**已经主动把 IR 文本格式逐语法对齐了 google/xls 的 `.ir` 格式**。这意味着"P4C → XLS"这条链的**中间层已经建好**，比从官方 p4c 出发省掉整个 PIR + DSLX 生成器。

三条关键结论：

| # | 结论 | 证据 |
|---|---|---|
| 1 | **P4C 的 XLS IR 文本输出已实测存在**，语法可直接被 `xls/ir` 解析 | `build/demo{9,10,11}/ir/*.ir` 共 20+ 个文件，内容为 `package` / `fn` / `literal` / `sub` / `array_index` / `tuple` |
| 2 | **唯一缺口是 Top 时序编排**：`IrText` 只 dump 单个 Dag → 单个 `fn`，无 proc/通道抽象 | `IrText.dump(pkg, fnName, dag)` 签名；P4C 缺口清单已列"proc / 通道并发抽象：无 → 后续" |
| 3 | **p4x 提供现成黄金参考链**：官方 p4c + bmv2 已在 macOS/arm64 构建成功 | `p4x/dist/bin/` 有 `p4c-bm2-ss`(19.8MB)、`simple_switch`(4.5MB)、`p4test`、`p4include`、`p4chisel.jar`(6.6MB) |

**⚠️ 路线修正**：原方案（官方 p4c → PIR JSON → Python 生成 DSLX → XLS）应改为
**P4C → XLS IR 文本（已有）+ 新增 `XlsBackend` 生成 proc 编排 → XLS codegen → Verilog**。
可省掉 PIR 层、Python 生成器、DSLX 学习成本三层间接。

---

## 1. P4C 现状盘点

### 1.1 编译管线

```
P4 源码 → 前端（Lexer/Parser，P4-16 v1model 子集）
       → ActionDAG IR（Const/Zext/Trunc/Bin/Slice/Cat/Mux/Not/RegRead + Sink）
       → 优化 pass（constFold / simplify / balance / narrow / cse / dce）
       → 调度 pass（加权延时模型切拍 + SDC-lite 精化）
       → 后端（ChiselBackend：Emitter / StagedEmitter）
       → FIRRTL / Verilog
```

与方法论对齐 XLS：DSL → 核心数据流 IR → 优化 + 调度 → 后端。

### 1.2 已完成的 XLS 对齐（X8–X14，全绿）

| XLS 能力 | P4C 落地 |
|---|---|
| IR 文本格式（dump/parse/round-trip） | ✅ `IrText.scala`，**逐语法对齐 `.ir`** |
| 布尔树平衡 / 再结合 / 比较位缩减 | ✅ `Passes.balance` / `Passes.narrow` |
| SDC 寄存器最小化调度 | ✅ `Scheduler.refine`（SDC-lite 局部搜索） |
| SMT 形式等价（Z3） | ✅ `Smt.scala` + `Equivalence` 三级策略（穷尽/z3/采样） |
| `ir_stats` / `ir_minimizer` / `delay_info` | ✅ `Tools.scala`（IrStats / IrMinimizer） |
| BOM（`print_bom`） | ✅ Signature JSON + 报告 |
| 延迟模型特征化模板（FDO 前置） | ✅ `DelayModels.toJson` |

### 1.3 验证栈（已具备，可直接承接对拍）

| 设施 | 用途 |
|---|---|
| `Interp.scala` | IR 解释器 —— 三级对拍的中间一级 |
| `Smt.scala` | 形式等价（SMT-LIB2 + 外部 z3） |
| `Signature.scala` | 生成代码签名回归（`generated/p4c_signature/*.json`） |
| `CrossEngineFuzzSpec` | 跨引擎模糊测试（Interp vs 生成 RTL 固定 seed 比对） |
| 测试规模 | 25+ 套件 / **185 tests 全绿**（含 chiseltest 交叉仿真） |

### 1.4 能力边界（as-built）

| 维度 | 已支持 | 未支持 |
|---|---|---|
| 类型 | `bit<W>`、header/struct、带宽字面量 | `int<W>`、`varbit`、typedef/enum |
| 表达式 | `+ - & \| ^ << >> ++`、切片、六种比较、`&& \|\|`、`~`、`!`、三元 | `* / %` |
| 语句 | 顺序直行、赋值、action 调用、extern 调用、表 apply | **if/else、switch、局部变量**（W1/W2 已立项） |
| 表 | exact；静态融合表 / 运行时表 | **lpm / ternary / range**（W3 已立项） |
| parser | 固定偏移 extract、transition、select | 表达式 select、变长 extract |
| header | — | **valid 位、header stack、deparser**（W6 已立项） |
| extern | `Register`、`Counter` | hash / meter / direct_* （W4 已立项） |
| 架构 | v1model ingress 切片，1 parser + 1 control | 多级管线、standard_metadata |

---

## 2. 关键实证：XLS IR 文本已经产出

`build/demo9/ir/Demo9L3forwarder__table_l2_fwd_forward.ir` 实际内容：

```
package Demo9L3forwarder

fn table_l2_fwd_forward(in_hdr_ipv4_ttl: bits[8], in_hdr_ipv4_totalLen: bits[16],
                        reg_portBytes: bits[16][8]) -> (bits[16], bits[8], ...) {
  n0: bits[16] = literal(value=2)
  n1: bits[8] = literal(value=2)
  n3: bits[8] = sub(in_hdr_ipv4_ttl, n2)
  n7: bits[16] = array_index(reg_portBytes, n6)
  n8: bits[16] = add(n7, in_hdr_ipv4_totalLen)
  ret sinks: (...) = tuple(n0, n1, n3, n4, n5, n6, n8)
}
```

**逐项对照 XLS IR 语法，全部合法**：`package` / `fn(p: bits[N]) -> (...)` / `literal(value=N)` /
`sub` / `array_index` / `tuple` / 命名返回值 / `//` 注释。

> 待实测确认：需要 XLS 的 IR 解析器实跑一次（`xls/ir` 的 `ParseAndTypecheck`）确认无误。
> 这一步是整条路线成立的**前提**，成本极低，建议第一件事就做。

---

## 3. 唯一缺口：Top 时序编排

| | 现状 | 缺什么 |
|---|---|---|
| 单 function 级 | ✅ `IrText.dump` 可输出（每个 action / table entry 一个 `fn`） | — |
| **程序级编排** | ❌ 无 | **一个 `proc`**：串起 parser FSM + control 逻辑 + 表访问 + 状态更新 |

而这件事**在 Chisel 侧已经做成了**：

```
generated/p4c/Demo5Pipeline.scala（157 行）
├── Demo5Pipeline_ethernet_h / _ipv4_h / _headers_t / _metadata_t   ← 类型 Bundle
├── Demo5PipelineIngress        ← control（含 Reg/Counter 状态 + 表逻辑）
├── Demo5PipelineTopParser      ← parser FSM
└── Demo5PipelineTop            ← 顶层组装（parser → control 端到端）
```

**所以映射关系是清晰的**：

| Chisel 侧（已有） | XLS 侧（要生成） |
|---|---|
| `emitBundles` → Bundle 类 | XLS `struct` 类型定义 |
| `emitControl` → Module | `proc` 的 `next(state)`（SSA let 链 + 状态更新） |
| `emitParser` → Module（FSM） | 同一 `proc` 的状态机段（cursor + state id） |
| `emitStaticTable` → 比较器 + Mux | 内联 `sel` / `priority_sel`（**无存储**，零外部端口） |
| `emitRuntimeTable` → `Vec[Reg]` + 写端口 | **Key/Response 通道**（裁剪 ① 的直接收益） |
| `StagedEmitter` → 切拍寄存器 | **不需要**（裁剪 ②，交给 XLS 调度器） |
| — | 新增：`.ir` 文件拼接（多 fn + proc 组装成一个 package） |

---

## 4. p4x 提供什么

775 行 Python（stdlib-only），四个子命令：`sim` / `chisel` / `ppal` / `all`。

**真正的价值在 `dist/` 和 `packaging/`**：

| 资产 | 说明 |
|---|---|
| `dist/bin/p4c-bm2-ss` | 官方 p4c，编译到 bmv2 JSON —— **三级对拍第一级现成** |
| `dist/bin/simple_switch` + `simple_switch_CLI` | bmv2 行为模型 —— golden 输出 |
| `dist/bin/p4test` | 官方 p4c 的 parser/前端校验 |
| `dist/share/p4c/p4include/` | 完整架构头（v1model.p4 / core.p4 / psa.p4 等） |
| `dist/bin/p4chisel.jar` | 自研 P4C 打包（6.6MB） |
| `packaging/*.sh` | 5 个构建脚本，含 macOS/arm64 上游缺陷规避（`compat/bmv2_apple_portability.h`、dylib 链接行显式挂依赖） |

**这意味着**：验证环境不用重建，`p4x sim` 现在就能产出 golden 包。

> ⚠️ 一个限制：`dist/` 依赖 Homebrew 的 6 个 dylib（绝对路径），换机需重装依赖或用 `dylibbundler` 收口。
> `p4_rtl` 若复用，建议先把这一层收口，否则 CI 不可移植。

---

## 5. 可复用代码清单（模块级）

主代码 5,381 行 / 测试 3,635 行。

| 模块 | 行数 | 复用度 | 说明 |
|---|---|---|---|
| `Parser.scala` | 710 | ✅ **全复用** | P4 前端（词法/语法） |
| `Ir.scala` | 490 | ✅ **全复用** | ActionDAG IR 定义 + Passes |
| `IrText.scala` | 346 | ✅ **全复用** | 就是 XLS IR 文本 dump/parse |
| `Smt.scala` | 329 | ✅ **全复用** | Z3 形式等价 |
| `IrBuilder.scala` | 202 | ✅ **全复用** | P4 AST → IR 降级 |
| `Signature.scala` | 199 | ✅ **全复用** | 签名回归 |
| `Lexer.scala` | 159 | ✅ **全复用** | — |
| `Directive.scala` | 144 | ✅ **全复用** | `// p4c:` 编译指示 |
| `Ast.scala` | 109 | ✅ **全复用** | — |
| `Tools.scala` | 107 | ✅ **全复用** | IrStats / IrMinimizer |
| `Interp.scala` | 96 | ✅ **全复用** | IR 解释器（对拍中间级） |
| `Repro.scala` | 18 | ✅ **全复用** | — |
| **小计** | **≈2,900** | | **占 main 的 54%** |
| `ChiselBackend.scala` | 1,426 | 🔄 **替换** | 改写为 `XlsBackend`，编排逻辑照搬 |
| `SchedulePass.scala` | 368 | ⏸ **XLS 路线下可暂缓** | XLS 自带调度器；保留给 Chisel 路线 |
| `DelayModel.scala` | 174 | ⏸ **同上** | 可作为 XLS 的 delay model 输入 |
| `Cli.scala` + `Generate.scala` | 504 | 🟡 **部分改造** | 加 `--xls` 输出路径 |

**净账**：约 **2,900 行直接复用**，约 **1,400 行需要改写**（XlsBackend，且因两项裁剪会比 ChiselBackend 小），约 **500 行小改**。

---

## 6. 落地工作项

| # | 工作项 | 产出 | 估算 |
|---|---|---|---|
| **A0** | **验证 `IrText` 输出能被 XLS 解析**（把 `build/demo9/ir/*.ir` 喂给 `xls/ir` 的 parser） | 一个能跑通的判据 | **0.1–0.2 人月**（前置门禁） |
| A1 | 搭 XLS 侧环境（conda 预编译包优先，或自建） | `codegen_main` 可用 | 0.3–1 |
| A2 | 写 `XlsBackend.scala`：类型 → struct；control → proc；parser → FSM | 单 demo 端到端 | 1.5–2.5 |
| A3 | 表接口：静态表内联 `sel`/`priority_sel`；运行时表 → Key/Response 通道 | 表可用 | 1–1.5 |
| A4 | `.ir` package 组装（多 fn + proc 拼一个文件）+ `--xls` CLI 通道 | 一键出 `.ir` | 0.3–0.5 |
| A5 | 三级对拍接入（复用 p4x 的 bmv2 + P4C 的 Interp + XLS 仿真） | CI 门禁 | 1–1.5 |

**A0 是整条路线的成立前提，成本极低，建议立刻做。**
估算合计（A0–A5）≈ **4–7 人月**，比原方案的 5.5–8.5 人月更低，且复用度更高。

---

## 7. 风险与注意

| # | 风险 | 应对 |
|---|---|---|
| 1 | **`IrText` 输出语法合法但语义未必被 XLS 接受**（如 `array_index` 的数组参数、多返回值 tuple 的 `ret` 命名） | A0 立刻验；有差异就在 `IrText` 里改 dump 格式（改动面小） |
| 2 | **P4C 的 IR 是纯组合 DAG，无时序概念**。XLS 的 `proc` 需要 `state`/`next` 语义，编排层要从零设计 | 照搬 `Demo5PipelineTop` 的组装逻辑；先只做单 control |
| 3 | **P4C 子集小**（不支持 if/else、局部变量、lpm/ternary） | 这些在 Chisel 路线也是缺口（W1–W6 已立项），两路线共享同一批波次工作 |
| 4 | **两条 RTL 路线并存**（Chisel vs XLS）造成维护负担 | 明确取舍：Chisel 为交付主线，XLS 为对照/研究线；共享 2,900 行前端与 IR |
| 5 | P4C 主仓 4 个 commit、`p4x` 非 git 仓库 | 纳入统一版本管理，否则复用时对不上基线 |

---

## 8. 与两项裁剪的契合度

| 裁剪决策 | 在 P4C 上的效果 |
|---|---|
| ① Match 只做 Key/Response 接口，不生成存储 | P4C 当前运行时表是 `Vec[Reg]` + 独立写端口。改成"发 key / 收 response 的通道"**代码量更少**（省掉存储阵列、写口协议、地址宽度逻辑） |
| ② 不做 PISA ALU 阵列 | `SchedulePass` + `DelayModel`（542 行）在 XLS 路线下**可整体暂缓** —— XLS 自带调度器。这直接砍掉一块工作量 |

即两项裁剪在 P4C 路线下**同时减少了后端改写量与调度模块依赖**，契合度比原方案更高。

---

## 9. 建议的下一步

1. **立刻做 A0**：把 `build/demo9/ir/*.ir` 喂给 XLS 的 IR parser，确认能解析。这是所有判断的前提。
2. **收口 p4x/dist 的 dylib 依赖**，让验证环境可移植。
3. 把 `p4x` 纳入 git，与 `P4C` 统一版本管理。
4. A0 通过后，按 A2 → A3 顺序推进 `XlsBackend`。

---

## 10. 2026-10-01/02 复审与合并结论

P4C 的 2,900 行早已 fork 进本工程（`src/main/scala/P4C/`，§5 的复用已全部落地）。
本轮重看 `../p4x`，按「对 `p4_rtl` 还有无价值」重新定性：

| p4x 资产 | 判定 | 处置 |
|---|---|---|
| `packaging/*.sh` + `compat/bmv2_apple_portability.h` | ✅ **收编** | 官方 p4c + bmv2 在 macOS/arm64 的构建配方（含 6 处上游缺陷规避），A5 黄金链唯一可复现路径 → `p4_rtl/packaging/`，路径改指 `../third_party/`、产物落 `../third_party/dist` |
| `p4x sim`（`p4xlib/sim.py`：pcap 进/出 + bmv2 `--use-files` 无头仿真） | ✅ **接入** | 新增 `scripts/golden_sim.sh`（A5 第一级 golden 入口），工具链经 `P4X_HOME` 引用 `../p4x/dist` |
| `dist/` 预编译工具链（75MB：p4c-bm2-ss / simple_switch / p4include） | 🔗 **引用不拷贝** | 二进制 + 绝对路径 dylib，进工程会让 IDE/仓库退化；保留 `../p4x/dist`，需时用 packaging 配方重建 |
| `p4x chisel` | ❌ 取代 | 本工程 `p4flow` + fat jar（`p4xls.jar`）已覆盖 |
| `p4x ppal` | ⏸ 暂缓 | 依赖旧 `p4chisel.jar` 的 Tier1 估算模型；本工程已有 IR/RTL 资源报告口径 |
| `p4xlib/{config,cli}.py`、`doctor` | ❌ 取代 | `p4flow` 自带外部依赖探测与分步报告 |
| `examples/l2_fwd.p4` | ❌ 无价值 | 仅作 golden 链路自检样本 |

**实测结论（重要）**：

1. 黄金链本身可用 —— `scripts/golden_sim.sh ../p4x/examples/l2_fwd.p4` 正常产出
   bmv2 JSON + port0→port1 的转发帧（内容与输入一致）。
2. **本工程样本过不了官方 p4c**：`testcases/p4/demo12-l2l3-switch.p4` 在
   `p4c-bm2-ss` 下报 `syntax error, unexpected IDENTIFIER ... Register`（自研子集的
   顶层 `Register`/`Counter` extern 写法非 v1model 语法）。即 A5 对拍**必须先补
   「官方可编译变体样本」**——这是 A5 剩余工作量的一部分，不是工具链问题。
3. p4x 侧踩到一个真 bug：`sim` 以输出目录为 cwd 启动 `simple_switch`，但 bmv2 JSON
   传的是调用方相对路径 → `JSON input file cannot be opened`（相对 `-o` 必现）。
   `scripts/golden_sim.sh` 已统一把 `-o` 转绝对路径规避（注释里记了出处）。

### 10.1 终局：p4x 目录删除（2026-10-02）

可取之处全部并入后 `../p4x` 已删除：

| 资产 | 去向 |
|---|---|
| `p4xlib`（sim 驱动 + 工具链定位） | **收编为 `scripts/golden_sim.py`**（自包含，修掉输入 P4/输出目录两处相对路径 bug），`golden_sim.sh` 变薄壳 |
| `dist/` 预编译工具链 | **迁至 `../third_party/dist`**，`env.sh` 的 `P4X_HOME` 改为 `P4X_GOLDEN_DIST` |
| `examples/l2_fwd.p4` | `testcases/golden/l2_fwd.p4`（golden 链自检样本，进版本管理） |
| `packaging/` | 早已收编（见 §10 表） |
| `p4chisel.jar` / `ppal` / `doctor` | 不收编（被 fat jar 取代 / 依赖旧 jar；`../P4C` 可随时重建） |
| `build/`（368MB 构建树） | 随目录删除（packaging 配方可重建） |
