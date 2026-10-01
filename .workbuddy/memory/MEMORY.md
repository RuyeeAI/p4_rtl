# 项目约定 — p4_rtl

> P4 → XLS IR → RTL。**接手/汇报先看 `docs/进度与计划.md`**。详细报告都在 `docs/`。

## 定位与范围（v2，郝宇确认）

- 产出的是「**P4 逻辑 → RTL 生成器**」，不是完整可编程数据面。
- **Match 阶段只生成 Key/Response 接口，不生成存储本体**：EM/LPM/TCAM 全部外置。
- 本期**不做** PISA ALU 阵列（不做 stage 内资源预算/多表打包/确定性流水划分）。

## 工程结构

- 根目录 `/Users/haoyu/Documents/01-Work/Code-Repos/p4_rtl`；`docs/` 文档、
  **第三方依赖在工程外同级 `../third_party/`（xls/p4c/behavioral-model/p4-spec，
  含 `.tools` bazel 基座；2026-10-01 外迁避免 IDE 索引）**、
  `scripts/` 脚本、`testcases/` 样本（p4/ir/tb）、`out/` 产物、`.workbuddy/memory/` 日志。
- sbt + Scala；`src/main/scala/P4C/` fork 自 `../P4C`（**包名保持 `P4C`** 便于 diff；
  fork 基线 = `../P4C` 的 `8c7eaaf`，此后改动**尚未回流**）。
- CLI `scripts/p4xls`：`p4c` / `xls` / `lint-ir` / `wrap-chisel` / `version`。
- 门禁：`sbt xlsIrLint`、`scripts/xls_ir_verify.sh`、`scripts/a2_verify.sh <in.p4>`（端到端）。

## 环境限制与坑

- **沙箱只能写工作区内**。Homebrew/沙箱内安装必失败。无 bazel/conda（**XLS 须用已建好的
  `scripts/env.sh` 环境**）；有 verilator/iverilog/cmake/ninja/clang。
- macOS **bash 3.2**：紧跟 `$VAR` 的**全角标点**会被算进变量名 → 变量一律写 `${VAR}`。
- WorkBuddy Bash 跑 **zsh，不做 word splitting**（`set -- $pair` 不分词）→ 显式函数传参。
- Scala 注释**可嵌套**：文档注释里写 `out/ir/*.ir` 会 `unclosed comment`。
- 沙箱 `sbt` 因清理 `classes.bak` 返回非零（非编译失败）→ **判产物存在而非退出码**。
- iverilog `-g2012`：`expect` 是保留字（改名 `exp_phv`），`%0s` 对中文乱码。
- chisel3：须 **scala 2.13.14 + chisel3 3.6.1 + plugin 3.6.1**（plugin 跳过 2.13.10-12）。

## XLS 工具链结论

- **依赖模式：不碰 `//xls/tools`**（会拉 Yosys/LLVM/OpenROAD/PDK），**自己写最小 driver**
  （`xls_harness/ir_check_main.cc`、`verilog_codegen_main.cc`）。LLVM 只有 `xls/jit` 需要。
- codegen driver 必须链 `//xls/codegen_v_1_5:codegen` **+ `:passes`**，漏后者报
  `default_pipeline is not registered`（**调度成功后才出现**，极易误判）。
  → 判别法：用官方样本 `ir_parser_round_trip_test_ParseIIProc.ir` 复现同一错误 = 工具链问题。
- `worst_case_throughput`：**0=不约束 / 不设=必须为1 / N=必须为N**；`clock_name` 必填；
  proc-scoped `chan_interface` 的 `strictness` 必填；节点名 `<op>.<N>` 的 N 必须 = `id=`。
- **II 编排水法（P0 定案）：并行 token 拓扑，通道操作不串链** → II=1
  （串行 token 链 = II=2）。`#[initiation_interval(N)]` 可用，但**命令行 `--wct` 优先**。
  ⚠️ 生成器守则：① 通道操作从同一 `state_read(tok)` 出发、末尾 `after_all` 汇聚；
  ② **predicate 互斥性必须校验**，证不了就退化串行链并**显式报诊断**（静默拆链 = 行为不确定）。
- **通道数不是 II 瓶颈**：2/3/4/6/8 张表（最多 17 通道）最小 II=1 全可行。
- **Key/Response 用 `flow_control=valid_data`（无背压，无 rdy）**，`result_out` 保留 ready_valid。
  关键：**stall 与握手协议正交**——「等」由内部流水控制实现，去掉 rdy 依然成立。
  代价：外部必须随时可收 key / 保证不丢 rsp。
- `FlowControl` 有三值：`kNone` / `kReadyValid` / `kValidData`。
- **纪律：改接口一律改 IR 源再重新生成**，绝不手改生成的 `.v`。

## A2 定案与实现要点（全部完成 ✅）

- **一个 P4 程序 → 一个 proc**（parser + control FSM 合一）。跨 proc 互连未验证。
- **`emitPipeline` 统一发射器**：字段级 state（`<inst>_<field>` / `<inst>_v` / `md_<member>`）；
  相位 `0..P-1` parser（相位 0 兼收包）→ `ctrlBase..` control → `phSend` 发 → 回 0。
- **IR 硬规则**：① 节点 id **package 级全局唯一**；② invoke 返回类型 = 被调 fn 返回类型
  （只有接受 token 形参的 fn 才返回 `(token,…)`）；③ `receive` 返回元组，取用先 `tuple_index`。
- **数组 state**（`bits[16][8]`）可行：init 字面量 = `[元素,…]` **不带类型前缀**；
  sel 可作用于数组；`array_update` 是全数组写回。
- ⚠️ **XLS 会 DCE 掉「只写不读」的 state**（IR 里在、codegen 后消失）→ 补观察通道
  `ex_<inst>`（valid_data、无谓词）。**每条 state 都要有对外可观测路径。**
- ⚠️ **相位 0 必须清所有槽位**（含 header 数据）——state 持久，回起点 ≠ 数据回起点。
- ⚠️ stages=2 流水化使相邻相位判据寄存器化、**永远错开一拍** → runtime 表
  **收 rsp 与应用 action 必须同拍**（2 拍：发 key → 收 rsp 同拍应用）。
- ⚠️ **extern（Register/Counter）写有相位陷阱**：runtime 表 action 里的 extern 写谓词
  必须 = **收 rsp 那一拍**（phRsp），不是发 key 那拍（phK）。XlsBackend 用
  `var extPhase` 处理（runtime 分支指 phRsp；静态表就是 phK）。症状：表命中、
  转发正常，但 Counter 永远为 0 —— **只做 IR 级断言看不出来，跑 RTL 仿真才暴露**。
- ⚠️ **extern 写必须带 hit 谓词门控**（`array_update` 是「效果」不是数据）。
- **runtime 表布局** `tbl_<名>_key`(send) + `tbl_<名>_rsp`(receive, `hit|actId|args`，hit 最高位)。
- 六样本 21 case 全绿（demo2/3/5/7/9 + a2-parser-control）。

## 路线 3：XLS 产物集成 Chisel ✅

- 解析 XLS Verilog 端口 → **BlackBox**（端口 1:1，desiredName=模块名）+ **Shell**
  （隐式时钟复位自动接线，数据 1:1 直连）。`p4xls wrap-chisel`、`scripts/gen_chisel_wrapper.py`。
- 实测：compile ✅、elaborate ✅、**46 端口对拍 0 不一致**。产物 `out/a2/chisel/*.scala`。

## A3 外部表模块（EM 打样）🟡

- **`em/` 子工程**：chisel 5.3.0 / scala 2.13.12（与 `../HardwareDesign` 对齐），
  `unmanagedSources` 直接引 BaseCbb 的 `GenBundle` / `Memory` / `MemInitCpuAccess`（**不拷贝**）。
  root 仍旧纯 Scala。产出：`sbt "em/runMain em.EmGen out/a3 <preset>"` →
  Chisel→CHIRRTL→**firtool --verilog**→iverilog。preset：basic/2left/inline/noad/crcrt/tb。
- **存储分层（郝宇定）**：HT/KT 用 `TpMemoryWrap3`，AD 用 `SpMemoryWrap3`；
  HT 每个 d-left 子表一个实例（并行读），KT 单实例（候选串行读），AD 单口读写互斥。
- **OVFC = HT 溢出时存 key 的小块 TCAM**（不是流控！），深度参数化，寄存器并行比较，
  命中优先于 HT 候选。桶满时落 OVFC，OVFC 满才 `insFail`。
- **d-left 并列仲裁**必须非 Leftmost（轻载下全堆 bank0，均衡失效）；默认 Random，验证用 RoundRobin。
- 验证 `testcases/a3/tb_em.v` 17 例 **15 通过**；未收敛：老化 sweep 与查找并发时 `hitReg` 读到 X。
- 详见 `docs/A3-EM模块设计.md`（含 12 条踩坑）。**接手前先读该文档 §9/§10。**

## 写 P4 之前必看：语法子集硬边界（2026-09-30 实测）

| 约束 | 出处 | 应对 |
|---|---|---|
| **无 `if/else`**（`Ast.Stmt` 无条件语句） | — | 下游表无法被上游跳过 ⇒ **把上游判定结果塞进下游表的 key** 做门控 |
| **无 apply/action 内局部变量** | `XlsBackend:667`/`ChiselBackend:692` 抛错 | 临时值只能用表达式复用（注意赋值后读的是新值） |
| **表 key 只能是字段路径**，不能写 `hdr.x[31:8]` | `XlsBackend:327/616` | 做不了掩码前缀匹配 |
| **只支持 `exact`** | `Parser.scala:432` | L3 只能 /32 主机路由 |
| **每张表必须有 `const entries`** | `XlsBackend:611` | 空表编译失败 |
| **不能给切片赋值** | `Parser.scala:526` | 不能改半字节字段 |
| **同一 parser 状态在不同路径偏移必须相同** | `ChiselBackend:1103` | **变长封装（可选 VLAN）做不了** |
| 无 header stack / `setValid` / `push_front` / `checksum` / `hash` | — | VLAN 增删、库函数校验和都用不了 |

可用：顶层 `const`、多 key 表、`sel`/三元/`~`/`~~`、切片读、Cast `(bit<N>)x`、
`Register`/`Counter`（read 表达式 + write/count 语句）、`// p4c: table X runtime size=N`、
`// p4c: stages=N`、**deparser**（`control D(packet_out pkt, in hdr){pkt.emit(...);}`，
XLS 线出 pkt_out 通道；Chisel 线告警跳过）、
**`// p4c: lookup-group <组名> = 表1,表2,...`**（组内 runtime 表同拍发 key/收 rsp，
工具链校验：必须 runtime 表/至多一组/apply 相邻/写集互斥/先表写集∩后表 key 读集=∅）。
**样本已收敛（2026-10-01）：`testcases/p4/` 只剩 `demo12-l2l3-switch.p4`**（L2/L3
交换机：并行查找+deparser）；demo1–11 与 a2-parser-control 及其 TB、`testcases/ir/`
夹具已删（git 历史可查）。回归口径 = demo12 单样本。

**字段单一写者原则**：顺序组合语义下后写覆盖先写 ⇒ 每个字段只由一组互斥 action 写。
⚠️ 更强：**同一 action 内读不到自己刚写的值**（DAG 用入口快照）——中间结果必须拆成
两个 action 靠相位边界传递（demo12 的 resolve/rewrite 两级就是这么来的）。

## 工具链操作坑（2026-09-30）

- **`scripts/p4flow` / `scripts/p4xls` 是 fat jar 优先**：改完 Scala **必须先
  `sbt -batch cli/assembly`** 才生效，否则跑的还是旧 jar（本次踩过一次"改了没生效"）。
- `p4xls eval-ir <fn.ir> --in hdr.ipv4.ttl=64 [--json]`：对单个 IR 函数抽象求值
  （`P4C.Interp` 的 CLI 入口），用于 IR 级行为验证；路径口径见 IR 里的
  `// p4c-params:` / `// p4c-sinks:`，未给的输入自动填 0。
- 表项实例的 fn dump 只出一个（按 action 名），参数取自**某一个**表项实例。
- **仿真引擎已切 verilator**（2026-10-01）：`Flow.stepSim` = verilator 5.030
  `--binary --timing`（iverilog 回退保留）。**--top-module 必须给 TB**（给 DUT 名
  = TB 剔出例化树、0 时刻结束）；TB top 正则解析、优先 `tb_` 前缀；
  `xls_fifo_wrapper.sv` 已内嵌 jar 资源作 sim 附加源。
- **TB 收包协议硬规矩**：发包后**一拍撤 `pkt_in_vld`**（vld 挂着会让 parser 回 ph0
  重复收包 → 陈旧结果脉冲；iverilog 全绿可能只是调度运气）。

## 一站式入口 `p4flow`（2026-09-17）

- **一条命令**：`scripts/p4flow <in.p4> [-o dir] [--only=a,b] [--skip=...] [--k=v 或 --k v]`
  = P4 → 前端 / IR / 函数级 IR dump / **XLS parser 校验** / 静态 lint / **形式验证** / Verilog /
  Chisel BlackBox / 仿真模型，产物落 `<out>/{ir,ir_fn,verilog,chisel,sim}` + `report.md` + `report.json`。
- **单文件形态**：`cli/target/scala-2.13/p4xls.jar`（6.8MB）**自包含**——
  `xls_ir_lint.py` / `gen_chisel_wrapper.py` / `op_contracts.json` 都打进 jar 资源
  （`cli/src/main/resources/p4xls/`），运行时优先用仓库 `scripts/`（开发态），否则解到临时目录。
  外置只有 XLS harness 二进制（`P4XLS_HARNESS_DIR` 可覆盖）+ python3/iverilog/vvp/z3，**缺则 SKIP 该步**。
- **形式验证**（`P4C.Equivalence`，穷尽 ≤16 位 → z3 SMT → 固定种子采样）：
  ① 优化 pass 保语义 `check(dag, Passes.runAll(dag))`；② IR 文本往返保语义 `check(dag, parse(dump(dag)))`。
- **资源报告**：IR 侧 state 位宽合计/节点/算子直方图/II ＋ RTL 侧寄存器位宽合计/线网/端口。
  **无 PDK 不做门级面积**；「IR state 位宽合计 vs RTL 寄存器位宽合计」是可信的时序面积代理（能对上）。
- **实测 6 样本全绿**（exit=0/0 FAIL/仿真全通过）；jar 拷到 /tmp + 空 root 仍 7 步全绿。
- ✅ **`lint` 已是真门禁**（2026-09-17 补齐 proc/数组 state 后，报错即 FAIL，不再降级 WARN）。
  `p4xls lint-ir` 也走同一套"仓库 scripts/ → jar 内嵌"回退，单 jar + 空 root 可用。
- 改 lint 后**必跑** `python3 scripts/xls_ir_lint_selftest.py`（42 例反向自测）；
  正样本回归口径：官方全树 `../third_party/xls/xls` 下 768 个 `.ir` 必须 **768/768**。
- 详见 `docs/流程整合-p4flow.md`（§5.1 补的能力↔源码出处对照表）。

## 工具链坑（Chisel5 → firtool → iverilog，2026-09-16）

- firtool 1.62 在 `~/Library/Caches/org.chipsalliance.llvm-firtool/1.62.0/bin/firtool`，
  也有 `~/.local/bin/firtool` 包装。chisel5 只有 SV 目标 ⇒ **两步**：ChiselStage `--target chirrtl`
  出 `.fir`，再 `firtool x.fir --verilog -o x.v --disable-all-randomization --strip-debug-info
  --lowering-options=disallowLocalVariables`。
- **必须加 `disallowLocalVariables`**：否则 firtool 生成 `automatic` 变量，iverilog 视为
  unsupported（`sorry:`）并以 **非零码退出、不产出 vvp**。
- **`iverilog ... | head -N` 是假成功陷阱**：警告超 N 行触发 SIGPIPE 掐死 iverilog，
  管道退出码来自 head(0)。**编译日志一律重定向到文件。**
- `Mem`/SimMemory 无复位值（X）；表存储必须**先初始化**（Wrap3 的 `dfx.init`），
  且 **init FSM 受复位控制 ⇒ 必须在复位释放后再发 init**。
- 调试探针**别在 `@(posedge clk)` 打**：与 DUT 的 NBA 竞争会读到上一拍的值，
  容易把"晚一拍"误判成"读延迟 2 拍"。用 negedge。

## EM 迁出（2026-10-01，郝宇迁移）

- **EM 整体迁至 `../HardwareDesign`**：主代码 `src/main/scala/BaseCbb/em/`
  （ExactMatch/EmGen/EmParams/Crc + 演进出 AgeSched/AgeTable/SvcEngine/OvfTable/ForwardCam）；
  测试 `src/test/scala/em/` 用 svsim + `TraceKind.Fst` 出 **FST 波形**
  （chisel 7.15.0 起 svsim Verilator 后端支持 FST；`EmWaveSpec` 替代手写 TB，
  波形落 `<workspace>/workdir-verilator/trace.fst`；`simulate()` 异常被吞，
  **必须取 `.result`**）。chisel 版本对齐问题随之消解，本工程不再持有 chisel 依赖。
- 本工程已删：`em/` 子工程（build.sbt em 块 + 5 个 scala）、`testcases/a3/tb_em.v`、
  `out/a3`；root/cli 纯 Scala 不变。

## 黄金对拍工具链（A5 第一级，2026-10-01/02 自 ../p4x 合并）

- `../p4x`（同级非 git 原型工程）的**官方 p4c-bm2-ss + bmv2 simple_switch** 是
  三级对拍的 golden 源；预编译 `../p4x/dist`（75MB，**不进本工程**，绝对路径 dylib）。
- `scripts/golden_sim.sh <p4> -o <dir>`：p4c-bm2-ss → bmv2 `--use-files` 无头仿真
  → pcap 输出帧；`env.sh` 的 `P4X_HOME` 定位工具链（默认 `../p4x`）。
- **`packaging/`（本工程版本管理）**：官方 p4c/bmv2 的 macOS-arm64 构建配方，
  源=`../third_party/{p4c,behavioral-model}`、产物=`../third_party/dist`、
  构建=`../third_party/.build/`；坑清单见 `packaging/README.md`。
- ⚠️ **本工程样本过不了官方 p4c**：demo12 报 `syntax error ... Register`
  （自研子集顶层 extern 非 v1model 语法）⇒ A5 对拍需另备官方可编译变体样本。
- ⚠️ p4x 的 bug：`sim` 以输出目录为 cwd 却传相对 bmv2 JSON → 打不开；
  golden_sim.sh 已把 `-o` 统一转绝对路径规避。

## 下一步

1. demo12 TB 补激励　2. 前端改动回灌 `../P4C`　3. EM 后续（LPM/TCAM）在 HardwareDesign 侧推进
