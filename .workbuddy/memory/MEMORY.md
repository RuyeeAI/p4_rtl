# 项目约定 — p4_rtl

> 项目目标：P4 → RTL 方向（调研/自研）。长期笔记，按主题维护。

## 目录约定

- 根目录：`/Users/haoyu/Documents/01-Work/Code-Repos/p4_rtl`
- **`docs/进度与计划.md` — 进度总览 + 已完成任务点 + 下一步计划（P0–P4）+ 技术债。
  接手/汇报先看这份。**
- `docs/` — 调研报告与说明文档
- `third_party/` — 第三方仓库源码（每个依赖一个子目录，独立 git 仓库，非 submodule）
- `.workbuddy/memory/` — 工作日志

## 工程结构（2026-09-15 起，p4_rtl 已是可运行 sbt 工程）

> 项目目标：P4 → XLS IR → RTL。**2026-09-15 起工程化**，不再是纯文档目录。

- 构建：sbt 1.12.6 + Scala 2.13.12，**纯 Scala、无 chisel3 依赖**，可打成轻量 fat jar。
- `src/main/scala/P4C/` — fork 自 `../P4C`（17 文件 / 5,381 行，基线 commit `8c7eaaf`），**包名保持 `P4C`** 便于与上游 diff。
- `src/main/scala/p4xls/Main.scala` — 顶层 CLI（`p4c` / `lint-ir` / `version`）。
- `scripts/gen_op_contracts.py` — 从 XLS `ir_parser.cc` 自动提取 op 参数契约 → `config/op_contracts.json`（84 条）。
- `scripts/xls_ir_lint.py` — XLS IR 文本静态校验器（A0 门禁核心工具）。
- `scripts/p4xls` — CLI 包装，用 `java -cp target/scala-2.13/classes:<scala-library>` 直跑，绕过 sbt 启动开销。
- `testcases/ir/` — 门禁基准（当前 P4C 产出的 25 个 `.ir`）；`testcases/ir_legacy_pre_a0/` — 修复前旧样本。
- 门禁命令：`sbt xlsIrLint` 或 `scripts/p4xls lint-ir <dir>`。
- **third_party/xls 已 gitignore**（155MB），拉取方式见 `docs/third-party-xls.md`。

## 环境限制

- **沙箱只能写工作区内**（`p4_rtl/` 之下）。写父目录 `Code-Repos/` 等会报 `Operation not permitted`，需提权。
- macOS 无 `timeout` 命令，长耗时网络操作需后台 + kill 轮询，或设 `GIT_HTTP_LOW_SPEED_TIME` / `GIT_HTTP_LOW_SPEED_LIMIT`。
- **Homebrew 安装在沙箱内必然失败**：`brew install bazelisk` 因 `/opt/homebrew/var/homebrew/locks/...`
  被拒（`Operation not permitted @ apply2files`）。需在沙箱外装。
- 本机**无 bazel / conda**，因此 XLS 无法构建；有 verilator / iverilog / cmake / ninja / clang。
- 坑：Scala 注释**可嵌套**，文档注释里出现 `/*`（如写 `out/ir/*.ir`）会导致 `unclosed comment`。
- 坑：macOS 自带 **bash 3.2**：紧跟 `$VAR` 之后的**全角标点**（如 `（` U+FF08）会被
  算进变量名 → `unbound variable`（报错信息还乱码，看不出是标点问题）。
  **脚本里变量一律写 `${VAR}`。**
- 坑：沙箱里 `sbt` 会因清理 `~/.sbt/**/classes.bak` 产生**非零退出码**（非编译失败）。
  脚本判产物存在（`[ -s "$OUT" ]`）而不是判 sbt 退出码。

## 第三方依赖拉取规范

1. 统一放 `third_party/<repo-name>/`
2. 网络受限环境走镜像 git 智能 HTTP（详见 skill `github-mirror-download`）
3. 每个依赖在 `docs/third-party-<name>.md` 写一份拉取记录：上游 URL、commit、日期、体积、校验结果、更新命令、构建要点
4. 远端固定配两个：`origin`（github 直连）、`mirror`（镜像优先）
5. 浅克隆后补 `--unshallow`，保证历史完整（便于查版本回溯）

## 已落地的第三方依赖

| 依赖 | 路径 | 版本 | 用途 |
|---|---|---|---|
| google/xls | `third_party/xls/` | `49c163e` (2026-09-11) | HLS 工具链，DSLX→Verilog，P4→RTL 后端候选 |

## 本地可复用资产（同机其他仓库）

p4_rtl 与以下两个仓库同属 `Code-Repos/`，**不是第三方依赖，是自家资产**，可直接复用：

| 仓库 | 路径 | 是什么 | 关键价值 |
|---|---|---|---|
| P4C | `../P4C` | 自研 P4→Chisel 编译器（Scala/sbt，main 5,381 行 / test 3,635 行，185 tests 绿） | 已把 IR 文本格式**逐语法对齐 google/xls 的 `.ir`**；已完成 X8–X14 的 XLS 对齐 |
| p4x | `../p4x` | 工具链统一入口（775 行 Python，stdlib-only，子命令 sim/chisel/ppal/all） | `dist/bin/` 有 macOS/arm64 构建好的官方 `p4c-bm2-ss` + bmv2 `simple_switch` + `p4include` + `p4chisel.jar` |

- **P4C 的 `.ir` 输出实测存在**：`P4C/build/demo{9,10,11}/ir/*.ir`，语法对照 XLS IR 合法。
- **唯一缺口 = Top 时序编排**：`IrText.dump` 只能 dump 单个 Dag → 单个 `fn`，无 proc/通道。但 Chisel 侧已做成（`generated/p4c/Demo5Pipeline.scala` 的 `_Ingress`/`_TopParser`/`_Top`）。
- 复用度：约 2,900 行（54%）直接复用；`ChiselBackend` 1,426 行需改写为 `XlsBackend`；`SchedulePass`+`DelayModel` 542 行在 XLS 路线下可暂缓。
- p4x 的 `dist/` 依赖 Homebrew 6 个 dylib 绝对路径，换机/CI 需收口；p4x 非 git 仓库。
- 详见 `docs/复用资产审计-本地P4C与p4x.md`。

### A1 环境 ✅ 通过（2026-09-15）

- XLS 环境**源码自建**（官方只发 linux-x64；conda litex-hub 只有 linux-64；本机无容器）。
  补丁共 **5 处**（`scripts/patch_xls_module.sh` + `patch_xls_macos.py`，绑 commit `49c163e`）：
  ① 轻量 `MODULE.bazel`（摘 OpenROAD/LLVM/PDK）② `.bazelrc`（注释悬空的
  `--@llvm-project//...` Starlark flag）③ 精简根 `BUILD`（去掉 hedron/fuzztest）
  ④ **空归档**：`xls_pass_rules.bzl`/`xls_estimator_rules.bzl` 补锚点 TU
  （macOS libtool 拒绝空归档，Linux GNU ar 接受 → 上游 CI 不暴露）
  ⑤ **`xls/common/subprocess.cc` 可移植化**：`memfd_create` → 临时文件 + `/dev/fd`
  外加三个存根 repo：`@rules_hdl`、`@at_clifford_yosys`（含真 json11）、`@llvm//tools:clang-format`。
  详见 `docs/A1-环境报告.md`。
- 真实 parser 复验：P4C 的 25 个 `.ir` **25/25** 且 round-trip 逐字节稳定；
  官方样本 745/768 = 97%；**proc + channel 4/4** → A2 语法前提成立。
- 三条环境约束：GitHub 直连不可用（走 gh-proxy 镜像）；本机透明代理会让 Bazel 报 502
  （**必须清空 `HTTP(S)_PROXY`**）；bazelisk 需固定 `USE_BAZEL_VERSION`（GCS 不可达）。
- **关键结论：LLVM 不是必需项**。`xls/ir`、`xls/common`、`xls/codegen`、`xls/interpreter`
  的 BUILD 里 llvm 出现 0 次；只有 `xls/jit`（供 `eval_ir_main`/`eval_proc_main` 加速求值）需要。
- **依赖模式：不碰 `//xls/tools`**（那些面向用户的完整工具会拉 Yosys/LLVM/OpenROAD/PDK），
  **自己写最小驱动**（已验证有效：`xls_harness/ir_check_main.cc`）。
- 门禁命令：`scripts/xls_ir_verify.sh <file|dir>`（先 `source scripts/env.sh`）。

### A2 决策已定（2026-09-15 郝宇确认）

1. **起步方式**：先做 **M0 打样**（手工最小 proc 验证访存延迟的时序契约），再写 `XlsBackend`。
2. **面积/时序精度**：**继续用 PDK 空存根**；M4 前再评估是否引入真工艺库。
3. **双 RTL 路线**：**双线并存** —— 保留 `../P4C` 的 Chisel 线。
   → 产生维护面：两边**共享 P4 前端 + IR 约 2,900 行**，前端改动需同步，否则静默分叉。
   → 采用**「定期对齐」**方式（最低成本）：以 `p4_rtl` 为前端改动主战场，定期把前端/IR 改动
     同步回 `../P4C`，并在两边记录同步点 commit hash。
   → **同步锚点**：`p4_rtl` 的 fork 基线 = `../P4C` 的 `8c7eaaf`（2026-09-11），此后改动尚未回流。
4. **M1 回归语料**：**先实测自研 P4C 对官方 `p4c/p4_16_samples` 的通过率**，拿量化指标后再定语料策略。

详见 `docs/A2-启动前决策盘点.md`。

### M0 接口打样 ✅ 通过（2026-09-16）—— 时序契约成立

- 结论：`send key → 等 response 返回 → 分发` 在 XLS 里**成立**。
  已生成 Verilog（`out/m0/m0_key_rsp_loop.v`）并用 **iverilog 仿真实证**：
  等待 2 拍 → result 第 4 拍；等待 7 拍 → result 第 9 拍，差值恒为 2 拍（流水深度）
  → 延迟随 rsp 到达线性变化，**不是死等固定 L 拍**。
- 一键复现：`scripts/m0_verify.sh`（IR 校验 → 生成 Verilog → 仿真）。详见 `docs/M0-接口打样报告.md`。
- 自建 codegen driver：`xls_harness/verilog_codegen_main.cc`
  （链 `//xls/codegen_v_1_5:codegen` **+ `:passes`**，自实现 unit delay estimator）。
- 六个坑（详见报告 §3）：
  - **K3 最坑**：漏 `//xls/codegen_v_1_5:passes` → `default_pipeline is not registered`。
    该错误**只在调度成功之后才出现**，极易误判为调度问题；且报错列表里"看起来有"该项。
    官方 `xls/tools/BUILD:845` 有显式依赖，`//xls/codegen_v_1_5:codegen` 没有。
    → **教训：自建 driver 时要逐个对照官方 main 的 deps，特别是注册表/插件式 alwayslink 依赖。**
    → **判定法**：用官方最小 proc 样本 `xls/ir/testdata/ir_parser_round_trip_test_ParseIIProc.ir`
      复现同一错误 → 即为工具链问题，不是自己的 IR 问题。
  - **K1**：`token 链 + 阻塞通道` 的 proc **拿不到 II=1**
    （依据 `run_pipeline_schedule.cc:232-242`）。`--stages=2 --wct=2` 是当前可用配置。
    **对 PISA「一拍一包」是硬约束，A2 编排水法必须先决策**（拆 proc / 接受 II=2 / 不把 token 写回 state）。
  - **K2**：`worst_case_throughput` 语义反直觉 —— **0 = 不约束**；**不设 = 必须为 1**；N = 必须为 N。
  - **K4**：`clock_name` 必填（`scheduled_block_conversion_pass.cc:56-61`）。
  - **K5**：proc-scoped `chan_interface` 的 `strictness` 必填（老式 chan 有默认值，proc-scoped 没有）。
  - **K6**：IR 文本里节点名 `<op>.<N>` 的后缀 N 必须等于 `id=`；引用必须先于定义。
- **对 A3 接口规范的直接影响**：XLS 生成的 `rsp_in_rdy` 语义是「本拍完成一次接收」，
  **不是「我随时能收」**（无数据时也为 0）。外部存储器/表接口**不能把 rdy 当"可以发"的许可**。

### P0 II 编排水法定案 ✅（2026-09-16）—— II 从 2 降到 1

- **定案：并行 token 拓扑（通道操作不串链）**。
  依据：四个变体的 `stages × wct` 矩阵扫描（`scripts/ii_sweep.sh`）——
  只有 send = II 1；只有 receive = II 1；**并行 token = II 1**；
  串行 token 链 = II 2（且 stages=1 完全不可调度）。
  → **瓶颈不是通道操作本身，而是 token 串链形成的循环携带依赖。**
- 收益（功能不变）：吞吐翻倍、端到端延迟少 1 拍、
  **RTL 寄存器 9→4 个，规模 3812→2474 字节（−35%）**。
- **II 写进 IR**：`#[initiation_interval(N)]`（实测可用，生成物与 `--wct=N` 完全一致）。
  坑：命令行 `--wct` **优先**于属性。
- ⚠️ **A2 生成器必须遵守的两条**：
  1. 通道操作**不得串链**（从同一个 `state_read(tok)` 出发，末尾 `after_all` 汇聚）；
  2. **predicate 互斥性必须校验** —— 拆链的正确性完全依赖它；
     证明不了就退化为串行链（II=2）并**显式报诊断**。
     **静默拆链 = 产出"能编译、能过仿真、但真实硬件行为不确定"的 RTL。**
- 遗留：多通道 / 多表在同一 proc 内的 II 未验证 → **A3 第一个检查点就扫**。
- 详见 `docs/A2-编排水法定案.md`。

### Key/Response 接口裁剪：去掉 rdy（2026-09-16 郝宇确认）

- **决策**：Key/Response 通道用 `flow_control=valid_data`（**无背压**），
  即只有 `<chan>` + `<chan>_vld`，**不生成 rdy**。`result_out` 保留 `ready_valid`。
  IR 里就是一个词：`valid_data` ↔ `ready_valid`。
- **关键结论（最该记住的一条）**：**stall 与握手协议正交。**
  proc 的"等"由内部流水控制实现（`stage_outputs_ready_0` 链），
  **完全不含 rdy 信号** → 去掉 rdy 后"等 response 返回"依然成立（已实测）。
- **代价**：外部必须"随时可收 key / 随时会返回 rsp"，有不可用窗口需自行缓冲。
  风险从"协议复杂"转移到"外部必须保证不丢"。
- 附带收益：`valid_data` 下不存在"rdy 是'本次接收完成'还是'我随时能收'"的歧义。
- `FlowControl` 枚举有**三**个值（`channel.h:301-320`），不是两个：
  `kNone`（无流控）/ `kReadyValid` / **`kValidData`**（只有 valid，接收方假定永远 ready）。
  端口生成规则见 `channel_to_port_io_lowering_pass.cc:371`。
- **纪律**：改接口一律**改 IR 源再重新生成**，不要手改生成的 `.v`（会被覆盖且与源不一致）。

### A2 架构定案 + A2-1 完成（2026-09-16）

- **一个 P4 程序 → 一个 proc**（parser FSM + control FSM 合一）。
  跨 proc 互连需要 package 级 `chan` 声明 + 绑定（**尚未验证**），
  单 proc 才是已验证形态。代价：proc 较大。
- **通道数量不是 II 的瓶颈**：N=2/3/4/6/8 张表（最多 17 个通道）
  全部最小 II=1、stages 1..4 全可行（`scripts/gen_multichan_probe.py`）。
- **proc 内可 invoke 纯数据 fn**（fn 被内联展开）→ action 复用 `IrText.dump`。
- 三条硬规则（都是踩坑得来，见 `docs/A2-架构设计.md`）：
  1. **节点 id 必须 package 级全局唯一**（不是每个 fn/proc 各自从 1 编）；
  2. invoke 返回类型 = **被调函数的返回类型**：纯数据 fn 直接返回其值，
     只有接受 token 形参的 fn 才返回 `(token, ...)`；
  3. `receive` 返回 `(token, data)` 元组，取用必须先 `tuple_index`。
- **代码结构**：`IrText`（单 Dag → 单 `fn`，组合逻辑）+
  `XlsProc`（proc 形态，时序编排）。两者共用上层持有的 `IdGen`。
  `XlsProc` 内部登记已发节点名，引用未登记的名字立即抛错。
- **验证方法（值得复用）**：用 M0 当标尺 —— `p4xls.XlsProcSelfTest`
  重建 M0 的 proc，再用**同一个 testbench** 仿真，逐项对比结果。
- 第一个端到端目标定为 **demo3-parser**（纯 parser、无 extern、无表），
  而非 demo5（带 Register/Counter，extern 状态存放方式尚未验证）。
- 详见 `docs/A2-架构设计.md`。

### A2-2/A2-3 完成：parser → proc 端到端跑通（2026-09-16）

- **`XlsBackend`**：P4 parser → XLS proc。每个 header 一对 state
  （`h_<inst>` 数据 + `v_<inst>` valid），不拼大 PHV；相位 0..N-1 = parser
  状态、N = accept、N+1 = reject（处理完回 0 重新收包）；select 用倒序嵌套 `sel`。
- **复用** `ChiselBackend.layoutParser` 的字节偏移计算（可见性放宽到 `private[P4C]`，
  不改变行为）。**原则：同一套语义只写一份实现。**
- **端到端**：`scripts/a2_parser_verify.sh`（P4 → IR → Verilog → iverilog）
  demo3-parser 两个 case 全过，含 select 两个分支。
- **新坑 1：`sel` 的 default 规则** —— cases 覆盖 selector 全部取值时不许有 default。
  修法是**让工具承担**：Builder 记录节点类型，按 selector 位宽自动省略。
  不要在每个调用点手工判断（12 处里有 6 处会踩）。
- **新坑 2：`expect` 是 iverilog `-g2012` 的保留字**（SV 断言关键字），
  用作 TB 变量名会报指向声明行的莫名语法错。
- 范围：XlsBackend 第一版**只有 parser**；control 尚未支持（下一步 A2-4）。

### A2-4 完成：control + const 表 → proc 端到端跑通（2026-09-16）

- **`XlsBackend.emitControl`**：control → proc。
  - **字段级 state**（`<param>_<member>[_<field>]`），不是 header 级 —— action 是
    局部修改（`meta.cls = c`），字段级让「改哪个就更新哪个」最直接。
  - 相位：`0` 收 PHV → `1..S` apply 体每条语句一拍 → `S+1` 发 PHV → 回 0。
  - PHV 布局 = control 参数展平序（先声明者在高位，与 `concat` 左=MSB 一致）。
  - const 表**编译期内联**：逐表项算 `hit_i`（逐 key 元素比较后 `and`，与整体
    concat 比较等价），表项输出作候选值再按**声明序优先**倒序嵌套 `sel`；
    default 表项输出作基线（优先级最低）。
  - 每条语句一个相位，相位互斥 ⇒ 字段 next 值 = 逐相位 `sel` 链，
    天然实现「后写的覆盖先写的」。
- **决策修正：action 不用 `fn` + invoke，改为内联进 proc**（见 docs §3.4）。
  理由：单 proc 无复用收益（XLS 对 invoke 本就内联展开），而 fn 形参/返回值
  如何对接 proc 的 state **尚未验证**；内联让 `Ir.InputRef` 直接落到 state 读节点。
  内联仍**复用** `IrBuilder.ExprLowering`（宽度推断/位宽 fit/运算符一份实现）
  + `Passes.runAll`，逐节点映射到 `XlsProc.Builder`（与 `IrText.dump` 同一张表）。
- **端到端**：`scripts/a2_verify.sh <in.p4>`（样本名自动选 top/testbench）
  - demo2-match 4/4：0x0800→`set_cls(7)`；0x86dd→`set_cls(9)`；其他→`default(nop)`
    保持原值；**连发两包**都正确（验证 phase S+1 → 0 的回路）
  - demo3-parser 2/2 回归通过
- **新坑：macOS bash 3.2 把紧跟 `$VAR` 的**全角标点**算进变量名**
  → `unbound variable`，错误信息还乱码。修法：变量一律写 `${VAR}`。
  中文注释密集的脚本极易踩。
- parser 与 control **尚不能混在同一程序**（A2-5 合并）；`emitProgram` 遇到即报错。

### A2-5a 完成：parser + control 合并到同一条 proc FSM（2026-09-16）

- **`emitParser`/`emitControl` 统一重写成 `emitPipeline`**（没保留两套 —— 三份代码必然漂移）：
  - **字段级 state 是 parser/control 的共同表示**：`<inst>_<field>`、`<inst>_v`、
    `md_<member>`。parser 的 extract 本就是按字段切片，control 的 action 本就是
    局部修改，字段级天然对齐。
  - **PHV 输出统一**：header 按声明序拼 `(valid, 字段…)` 再拼 meta，先声明者在高位。
  - **相位编码**：`0..P-1` parser（相位 0 兼收包）→ `ctrlBase..` control
    （ctrlBase = P>0 ? P : 1）→ `phSend` 发 → 回 0；`accept→ctrlBase`、
    `reject→phSend`（无独立 accept/reject 相位）。无 parser 时相位 0 专收 PHV，
    与 A2-4 编码一致（旧样本零迁移）。
- **端到端**：新样本 `a2-parser-control.p4`（demo3+demo2 合并体）5/5；
  demo2 5/5、demo3 2/2 回归（demo2 输出 136→137 位，多 valid 位，TB 已同步）。
- **真 bug：valid=0 的 header 数据跨包残留** —— state 是持久的，FSM 回到起点
  ≠ 数据回到起点。**修法：相位 0 清所有槽位（含 header 数据）**，让输出确定。
  教训：每包开始处必须显式重置，否则残留顺着"保持原值"的 sel 链流下去。
- **TB 坑**：① 连续发包时 `for(... && !vld) @(negedge)` 会在上一轮 vld 未落时
  立即返回、量到上一包 —— check 要**先等 vld 落再等它起**；
  ② iverilog 的 `%0s` 对多字节字符串乱码 —— task 标签用 ASCII。
- **交互命令坑：zsh 不做 word splitting**（`set -- $pair` 不分词）——
  WorkBuddy 的 Bash 工具跑 zsh，跨 shell 稳妥的写法是**显式函数传参**。

### A2-5b 完成：Register/Counter extern（数组 state）（2026-09-16）

- **§6 定案：方案 a 成立** —— proc 的 state 可以是数组 `bits[16][8]`。
  三个机制点：① init 的数组字面量 = `[元素,...]` 不带类型前缀（写 `type:{...}` 报
  `Expected token of type "["`）；② **sel 可作用于数组**（相位化写回靠它，XLS
  按元素 lowering）；③ RTL 是 `reg [W] __mem[0:N-1]` 寄存器组；`array_update`
  是全数组写回（每元素一个 mux，size 大时要另想形态）。
- 实现：每个 extern 一个数组 state（`reg_<inst>`/`cnt_<inst>`），**不参与相位 0
  清零**（跨包持久）；写回 = array_update 链 + 相位 sel；Counter.count = 读改写。
- **真·发现：XLS 把「只写不读」的 state 当死代码优化掉** —— demo5 的 counter
  在 IR 里全在、codegen 后整个消失。**修法：补观察通道 `ex_<inst>`**
  （对应 Chisel 线的 `io.ex_`）：valid_data 无背压、无谓词（绝不反压主通路）、
  元素 0 在最高位、数据取本拍处理后的值。
  **教训：每条 state 都必须有对外可观测路径，且要在 codegen 之后才能发现这种删除。**
- 端到端：demo5-pipeline 3/3（extern 跨包累计 1→2→3），四样本回归全绿。

### A2-5c 完成：runtime 表 + demo9 全特性端到端（2026-09-16）

- **架构定案：proc 是查表客户端** —— 表存储与匹配在外部表模块（M0 已验证的
  key→rsp 契约），接口 `tbl_<名>_key`(send) + `tbl_<名>_rsp`(receive，
  布局 `hit|actId|args`，hit 最高位，proc 不回传 key）。
- **2 拍：发 key → 收 rsp 并同拍应用 action**。⚠️ 不能放下一拍：stages=2 流水化
  把相邻相位判据寄存器化（p0_is_phX）**永远错开一拍**，而 receive 数据只在
  p0_is_phRsp 拍有效（其余拍被门控清零）—— 错拍应用时数据已没了。
- **parser select 的顶层 const（W0）零改动**：Parser 解析期就按名替换成带宽字面量。
- **两个真 bug**：① 上述错拍；② **extern 写必须带 hit 谓词门控** ——
  array_update 是「效果」不是数据，PHV 字段靠数据 sel 选择的方式对它无效，
  miss 条目的写会照样生效；default 表项谓词 = ~anyHit。
- 端到端：demo7 4/4、demo9 2/2（flowHash/swapId 逐位核对），**六样本 21 case 全绿**。
  A2 功能面已齐：parser + control + const/runtime 表 + Register/Counter。

## 技术结论备忘

### A0 门禁 ✅ 通过（2026-09-15）

- P4C 产出的 25 个 `.ir`（11 个 demo）语法校验 **25/25 通过**，整条路线成立。
- **唯一不兼容点（已修）**：`array_index(arr, idx)` 必须写成 `array_index(arr, indices=[idx])`。
  根因：`ir_parser.cc` 的 `kArrayIndex` 用 `AddKeywordArg("indices")` + `Run(arity=1)`，
  而 `ArgParser::Run`（`:329-376`）把位置参数与关键字参数**分开解析**，`indices` 是 mandatory keyword。
  修复位置：`IrText.scala:128`（生成）/ `:304`（解析）。
- 已确认兼容：`//` 行注释（`ir_scanner.cc:156`）、`ret name: (T,) = tuple(...)`、
  `sel(cond, cases=[])` 无 default、`zero_ext(x, new_bit_count=N)`、`bits[16][8]` 数组类型。
- 校验器可信度：用 XLS 自带 768 个 `.ir` 做正样本，纯 `fn` 子集 **468/493 = 94.9%**。
- 报告：`docs/A0-门禁报告.md`。
- **未覆盖**：A0 只验证了 `fn` 形态；`proc`/通道编排（A2）的语法正确性待 A2 首检。

- 详见 `docs/P4转RTL开源工具调研.md`：GitHub 无活跃开源全流程 P4→RTL 工具；Match-Action 单元 RTL 生成是完全空白。
- 详见 `docs/p4c-xls后端扩展方案.md`（**v2**）：p4c → DSLX → XLS codegen 路线的工作分解。三条硬结论：
  1. XLS IR 仅 79 个 op，**无存储器/CAM 原语**。
  2. 推荐 p4c 只做 frontend+midend 输出 **PIR JSON**，DSLX 生成器用 Python 写（解耦、迭代快、PIR 可复用）。
  3. XLS 产出的是通用数据流调度结果，与 PISA "一表一 stage" 结构不对齐。

## 项目范围约定（v2，2026-09-15 郝宇确认）

- **Match 阶段只生成 Key/Response 接口，不生成存储本体**。表存储与匹配算法（EM/LPM/TCAM）全部外置，XLS 侧只输出 key、接收 response。
- **本期不支持 PISA ALU 阵列**：不做 stage 内 ALU 资源预算、多表同 stage 打包、确定性流水划分；接受 XLS 自动调度结果。
- 因此头号风险从"表存储 RTL 自研"变为**访存延迟的时序契约**（XLS proc 每拍迭代 vs 外部存储器 1–4 拍延迟）。接口按握手定义、实现先走计数状态，**M0 必须验证此闭环**。
- 工作量基线：MVP 5.5–8.5 人月；到基线评测 8–12.5 人月；ALU 阵列 + 真实存储 IP 预留 +4–8 人月。
- 项目定位 0：产出的是"**P4 逻辑 → RTL 生成器**"，不是"P4 → 完整可编程数据面"。存储、状态、调度全部外置。
