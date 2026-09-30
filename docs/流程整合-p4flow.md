# p4flow —— 一站式流程整合（单可执行文件）

> 把「P4 源码 → 仿真模型 / Verilog / Chisel BlackBox / 形式验证 / 资源报告」
> 收进一个入口。**一条命令跑完，产物与报告落到一个目录。**

```bash
scripts/p4flow testcases/p4/demo9-l3forwarder.p4 -o out/flow/demo9
# 或直接用单文件 jar（不依赖仓库 scripts/ 与 config/）：
P4XLS_HARNESS_DIR=<repo>/third_party/xls/bazel-bin/p4xls_harness \
  java -jar cli/target/scala-2.13/p4xls.jar flow <in.p4> -o <outDir>
```

---

## 1. 形态：单可执行文件 + 薄启动器

| 组件 | 说明 |
|---|---|
| `cli/target/scala-2.13/p4xls.jar` | **fat jar（≈6.8MB）**：Scala 侧 + **内嵌 `xls_ir_lint.py` / `gen_chisel_wrapper.py` / `config/op_contracts.json`** |
| `scripts/p4flow` | 薄启动器：**优先用 jar**（自包含），没有 jar 时回退 `scripts/p4xls`（classes 目录，开发态免打包） |
| 子命令 | `p4xls flow <args>` 与 `p4flow <args>` 等价（同一个 `p4xls.Flow`） |

**自包含性实测**：把 jar 拷到 `/tmp`、`P4XLS_ROOT` 指向空目录（脚本与 config 都找不到）→
流程仍 **7 步全绿**：Python 工具与 op 契约从 jar 里解到临时目录运行
（`git status` 里没有脚本依赖）。

**必须外置的两样**（体积/许可原因，且流程会显式探测）：

1. **XLS harness 二进制**：`ir_check_main`（36MB）、`verilog_codegen_main`
   —— 用 `P4XLS_HARNESS_DIR` 指定，或按仓库相对路径 `third_party/xls/bazel-bin/p4xls_harness/`。
   构建：`scripts/bootstrap_xls_env.sh`。
2. **系统工具**：`python3` / `iverilog` / `vvp` / `z3`。

> 缺哪个工具 → **只 SKIP 依赖它的那一步**，报告里把"跳过"与"通过"分开列，
> 避免出现"看起来跑过了其实没跑"。

---

## 2. 步骤表

| id | 步骤 | 依赖 | 产物 / 判据 |
|---|---|---|---|
| `frontend` | P4 前端解析 | 内置 | parser/control/table/action 清单 + 告警 |
| `ir` | XLS IR 生成（proc 编排）+ IR 画像 | 内置 | `ir/<stem>.ir`；state 项数/位宽、节点数、算子直方图、II |
| `dump-ir` | 函数级 IR dump | 内置（Chisel 线，**stages=1**） | `ir_fn/*.ir`，形式验证的输入 |
| `xls-verify` | **真 XLS parser** 的 parse + verify + 往返 | `ir_check_main` | 权威良构性判定 |
| `lint` | 内嵌静态 lint | 内置 py | 见 §5 的定级说明 |
| `formal` | **形式等价性**（两层） | `z3`（缺则降级） | 见 §3 |
| `verilog` | Verilog 生成 | `verilog_codegen_main` | `verilog/<stem>.v`；top 自动从 IR 的 `top proc` 取 |
| `chisel` | Chisel BlackBox + Shell | 内置 py | `chisel/<Name>.scala`（含自检） |
| `sim` | 仿真模型 | `iverilog`+`vvp` | `sim/`：RTL + TB + vvp + vcd + `run.sh` |

**TB 选择顺序**：`--tb` 指定 → 已知样本表（与 `a2_verify.sh` 同表）→ `testcases/a2/tb_<stem>.v` 命名扫描
→ **自动生成骨架**（接时钟复位、驱动输入、打输出、dump VCD、留 TODO 断言）。
用骨架时该步标 **WARN**（"未自检"），不会冒充通过。

**步骤裁剪**：`--only=ir,formal` / `--skip=sim`（`--k=v` 与 `--k v` 两种写法都支持）。

---

## 3. 形式验证（z3，两层）

同一套 IR 的 `Ir.Dag` 上做两个"应当等价"的比较：

1. **优化 pass 保语义**：`check(dag, Passes.runAll(dag))`
   （`constFold → simplify → balance → cse → dce → narrow`）
2. **IR 文本往返保语义**：`check(dag, IrText.parse(IrText.dump(dag)).dag)`

判定三级策略（`P4C.Equivalence`）：

| 级别 | 条件 | 结论强度 |
|---|---|---|
| 穷尽枚举 | 两侧无 RegRead 且输入位宽合计 ≤16 | 完备，可给反例 |
| **z3 SMT** | z3 可用 | 完备（`unsat` = 等价） |
| 随机采样 | z3 不可用 | 200 组固定种子，**不完备**（记 Unknown） |

报告里对每个 fn 分别给出"优化 / 往返"的结论，并把**"未决"与"不等价"严格区分**
（未决 = 没证出来，不等于反例）。

---

## 4. 资源报告

| 侧 | 指标 | 来源 |
|---|---|---|
| IR | state 项数 + **位宽合计**、节点数、算子直方图、`initiation_interval`、receive/send/invoke/sel 计数 | IR 文本静态分析（不依赖 XLS 二进制） |
| IR | 端口表（名/位宽/方向），标注哪些是 runtime 表 key/rsp | 同上 |
| RTL | **寄存器个数 + 位宽合计**、线网、端口表、行数/字节 | 生成的 Verilog 静态扫描 |

> **不做门级面积**：本机无工艺库（PDK 空存根）。可比的**时序面积代理指标**是
> `state 位宽合计` 与 `RTL 寄存器位宽合计` —— 实测两者能对上
> （demo9：IR 1144 位 vs RTL 1149 位），所以拿它做优化前后/参数前后的对比是可信的。

报告同时出 `report.md`（给人看）与 `report.json`（给脚本消费，含每步状态与关键指标）。

---

## 5. `lint` 的定级（重要口径）

**2026-09-17 已修复**：`xls_ir_lint.py` 原先只实现 `fn` 形态，对 proc / 数组 state 会把体内节点行
当成顶层语句 —— 一份真 XLS parser 完全接受的 IR 会报上千条 issue（demo9：8262 条）。
该覆盖缺口已补全，详见 §5.1。

因此本流程现在的口径：

- **权威判定 = `xls-verify`（真 XLS parser 的 parse+verify+往返）**；
- **`lint` 是真门禁**：报错即 **FAIL**（不再降级 WARN）。
- 若 `lint` 报错而 `xls-verify` 通过，报告会标注"疑似 lint 覆盖缺口" —— 此时应补 lint 规则，
  而不是直接 `--skip=lint`。

### 5.1 lint 的 proc / 数组 state 补全（2026-09-17）

对齐对象是 `third_party/xls` 的真实源码，逐条标注出处：

| 补的能力 | 出处 |
|---|---|
| 新式 proc 签名 `proc N<ch: T in\|out>(state..., init={...}, non_synth={...})` | `ir_parser.cc:2168` ParseProcSignature |
| **数组 state** `bits[16][8]` 与任意层数组后缀（含 `(T,T)[3]`） | `ir_parser.cc:202/211` ParseType |
| 类型参数尾随属性 `id=N` / `pos=[...]` | `ir_parser.cc:134-138` |
| `init={...}` 必选（有 state 时）且初值个数 == state 数 | `ir_parser.cc:2279/2285` |
| `chan_interface name(direction=, kind=, [strictness], [flow_control], [flop_kind])` | `ir_parser.cc:2986` |
| 通道枚举合法性（kind / flow_control / strictness / flop_kind / direction） | `channel.cc:158/202/285/304` |
| 老式 `chan name(TYPE, kw=...)` 全套关键字 + 类型后必须有逗号 + 仅 streaming 允许 fifo/bypass | `ir_parser.cc:2754/2795/2929` |
| `proc_instantiation` | `ir_parser.cc:1592` |
| 三引号多行字符串 `"""..."""`（ffi_proto 等属性） | `ir_scanner.cc:180` MatchQuotedString |
| `file_number N "path"` | — |
| **先定义后引用**（含 state_element / channel= 的解析） | `ir_parser.cc:461` ParseAndResolveIdentifier |
| **节点名 `.<N>` 后缀必须等于 op 名与 `id=N`** | `ir_parser.cc:1524/1533` |
| 重名判据（Param / StateRead 豁免）、名字作用域按 fn/proc 隔离 | `ir_parser.cc:1486` |
| `next_value` 的 `param` / `state_element` 二选一 | `ir_parser.cc` kNext 分支 |
| `scheduled_fn/proc/block` 按**花括号配对**整段跳过（体内有内嵌 `source fn`） | — |

> 附带修掉的**契约表**缺陷：`gen_op_contracts.py` 用 `to_snake(C++ 名)` 给契约挂键，
> 而 IR 文本 op 名以 `op_list.h` 为准，二者不总相等（`kNext` → IR 名是 `next_value` 而非 `next`），
> 导致 `next_value` 落到"无 case"分支被补成**空契约**（合法 IR 报一堆 `E_UNKNOWN_KW`）。
> 现在按 `op_list.h` 重映射键名（5 条：`next_value` / `smul` / `smulp` / `umul` / `umulp`）。

### 5.2 门禁可信度证据

| 证据 | 结果 |
|---|---|
| **反向自测** `scripts/xls_ir_lint_selftest.py`（33 个错误用例 × 9 个正向控制） | **42/42 通过** —— 既抓真错，也不误报 |
| 官方 XLS 全树 `.ir` 门禁实测 | **768/768 通过**（0 失败；A1 用真 XLS parser 测的是 745/768） |
| 本仓库门禁样本 `testcases/ir/**` | **25/25 通过**（0 issue） |

---

## 6. 实测（6 个样本，仓库内）

| 样本 | exit | OK | WARN | FAIL | 形式验证 | 仿真 |
|---|---|---|---|---|---|---|
| demo9-l3forwarder | 0 | 8 | 1 | 0 | 等价 12 · 不等价 0 · **未决 0** | ✅ 2/2 |
| demo7-runtime-table | 0 | 8 | 1 | 0 | 等价 12 · 0 · 0 | ✅ 4/4 |
| demo2-match | 0 | 8 | 1 | 0 | 等价 4 · 0 · 0 | ✅ 5/5 |
| a2-parser-control | 0 | 8 | 1 | 0 | 等价 4 · 0 · 0 | ✅ 5/5 |
| demo5-pipeline | 0 | 8 | 1 | 0 | 等价 2 · 0 · 0 | ✅ 3/3 |
| demo3-parser | 0 | 6 | 2 | 0 | n/a（纯 parser 无 action） | ✅ 2/2 |

- WARN 全部来自 `lint`（§5）。
- demo3-parser 的 1 个 SKIP 是 `formal`（没有 action DAG 可验）。

---

## 7. 本次顺带修掉的既有问题

1. **`Smt.exprOf` 的 `Bin` 编码有 bug**（X11 遗留）：只对齐右操作数、且把"比节点宽"当成"窄"处理
   ⇒ 生成 `((_ zero_extend -1) x)` ⇒ z3 直接报 `invalid zero_extend application`
   ⇒ 等价性证明退化成 Unknown。改成**两个操作数都对齐到节点宽（窄的零扩、宽的截断）**，
   demo9 的 2 项未决 → **0**。
2. **自动 TB 骨架两处语法错**：`module tb_demo2-match`（`-` 非法标识符）；
   三引号字符串里的 `\n` 是字面量（Scala 三引号不处理转义）⇒ 生成 `;\n $finish;` 一行。
3. `--k=v` 形式只在帮助里写了、没实现（只支持 `--k v`）。
4. 内嵌 py 的 op 契约路径：从 jar 解到临时目录后 `__file__/../config` 推不出来 ⇒ 显式传 `--contracts`。

---

## 8. 与既有脚本的关系

`scripts/a2_verify.sh` / `m0_verify.sh` / `xls_ir_verify.sh` **保留**（细粒度调试用，输出更聚焦）；
`p4flow` 是**收口层**：一条命令、统一产物布局、统一报告。两者共用同一批外部工具与参数约定。

命令速查：

```bash
scripts/p4flow <in.p4>                      # 全流程（默认输出到 <in 同目录>/flow_<stem>）
scripts/p4flow <in.p4> -o out/flow/x --only=ir,formal,report
scripts/p4flow <in.p4> --skip=sim --stages=4
scripts/p4flow <in.p4> --tb my_tb.v --top=Ingress_pipeline
scripts/p4flow -h                           # 步骤 id 与外部依赖说明
```
