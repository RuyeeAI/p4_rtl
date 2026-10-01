# 在 p4c 中扩展 XLS 后端：工作分解

> 目标：`P4-16 源程序 → p4c → DSLX/XLS IR → 可综合 Verilog`
> 结论基于本地源码核对：`../third_party/xls`（commit `49c163e`，2026-09-11）+ p4c main 分支实际文件结构。

**v2 修订（2026-09-15）——两项范围裁剪：**

| 裁剪项 | 决策 | 影响 |
|---|---|---|
| ① Match 阶段 | **只生成 Key/Response 接口，不生成存储本体**。表存储由外部存储器/加速器提供 | 原"最大阻塞点"消失；转为**接口契约定义**工作 |
| ② PISA ALU 阵列 | **本期不支持**。不做 stage 内 ALU 资源预算、多表同 stage 打包、确定性流水划分 | 资源调度相关工作全部推迟；接受 XLS 自动调度的非确定性流水深度 |

净效应：MVP 从 7–11 人月降到 **5.5–8.5 人月**；到基线评测 **8–12.5 人月**。核心工作量重心从"硬件资源建模"移到**语义映射 + 接口规范**。

### ⚠️ 路线修正（2026-09-15 晚）——本方案前半部分的前提已变

审计本地 `Code-Repos/P4C`（自研 P4→Chisel 编译器，5,381 行）与 `p4x`（工具链封装）后发现：
**不必从官方 p4c 出发**。P4C 已把 IR 文本格式逐语法对齐 google/xls 的 `.ir`，且实测有产出
（`build/demo{9,10,11}/ir/*.ir`）。

**推荐路线改为**：

```
P4C（自研）→ XLS IR 文本（IrText，已有）
          → 新增 XlsBackend 生成 proc 编排（缺口）
          → XLS codegen → Verilog
```

相对原方案的收益：省掉 PIR 层、Python DSLX 生成器、DSLX 学习成本；约 **2,900 行直接复用**。
本方案中仍有价值的部分：**第 4 节 Key/Response 接口规范**（裁剪 ① 的核心交付，与后端选谁无关）、
第 5 节难点 1 的时序契约、第 7 节验证策略。

详见 `docs/复用资产审计-本地P4C与p4x.md`。

---

## 0. 摘要

**要做的不是"写一个 backend"，而是造一条 "P4 语义 → XLS 可综合数据流" 的降级链路。**
后端骨架只占 ~10% 工作量，剩下 90% 在语义映射。

| # | 结论 | 依据 |
|---|---|---|
| 1 | p4c 侧改造是**机械工作**，有现成模板（`backends/p4test`、`backends/bmv2`），约 1–2 人月 | p4c `Backend` 基类只要求 `virtual void convert(const IR::ToplevelBlock*)` |
| 2 | 真正的工作量在 **DSLX 代码生成器**，不在 p4c | p4c 输出一个中间 IR 后，映射规则有 ~40 条 |
| 3 | Match 走 Key/Response 接口后，**XLS 侧彻底不碰存储**——这符合软硬件分工，也回避了 XLS 无存储原语的硬伤 | XLS IR 79 个 op 中无任何存储器/CAM 抽象（`xls/ir/op_list.h`） |
| 4 | **新的头号风险：访存延迟的时序契约**。XLS `proc` 是"每拍迭代一次"的模型，外部存储器有 1–4 拍（TCAM 更多）延迟，必须用握手或显式计数状态吸收 | 见第 5 节难点 1，**建议在 M0 就验证** |
| 5 | 建议**不把生成器写进 p4c**：p4c 只做 frontend+midend 并序列化精简 IR（JSON），生成器用 Python | p4c 构建慢（CMake+LLVM 级依赖），C++ 迭代成本高 |
| 6 | 两边都是 **Apache-2.0**，商用无 license 障碍 | `../third_party/xls/LICENSE`；p4c SPDX 头 |

**量级估算**（基于同类项目的公开经验，非承诺）：跑通 MVP **5.5–8.5 人月**；到基线评测 **8–12.5 人月**；再补 ALU 阵列与真实存储对接 **+4–8 人月**（见第 6 节）。

---

## 1. 能力对齐矩阵

### 1.1 XLS 侧实际有什么

| 能力 | 状态 | 证据（本地源码） |
|---|---|---|
| 纯函数数据流（组合逻辑） | ✅ 完整 | IR `add/and/sel/concat/array/...` |
| 状态机（跨时钟状态） | ✅ 完整 | `kStateRead` / `kNextValue` + DSLX `proc{config,init,next}` |
| 进程间通信 | ✅ 完整 | `chan<T>` / `send` / `recv` / `join` / `spawn` |
| proc → Verilog | ✅ 有正式测试 | `xls/codegen_v_1_5/codegen_proc_test.cc` |
| 自动插流水寄存器 | ✅ | `pipeline_register_insertion_pass.cc` |
| ready/valid 握手插入 | ✅ | `flow_control_insertion_pass.cc` |
| 自动调度 | ✅ | `scheduling_pass.cc` + delay model |
| 形式化验证（LEC） | ✅ | `xls/solvers`（Z3） |
| **存储器 / RAM 原语** | ❌ **无用户可见抽象** | 只有低层 `ram_rewrite.proto`（`RAM_ABSTRACT/1RW/1R1W`）+ 一个改写 pass |
| **CAM / TCAM / LPM** | ❌ 无 | op 列表里不存在 |
| **变长数据 / 动态分配** | ❌ 无 | DSLX 是固定大小对象（这是设计前提） |
| 字节流帧边界 | ❌ 无 | 只有固定位宽 channel |

**IR op 全量 79 个**（`xls/ir/op_list.h`）：
`add, after_all, and, and_reduce, array, array_concat, array_index, array_slice, array_update, assert, bit_slice, bit_slice_update, concat, counted_for, cover, decode, dynamic_bit_slice, dynamic_counted_for, encode, eq, gate, identity, input_port, instantiation_input, instantiation_output, invoke, literal, map, min_delay, nand, ne, neg, new_channel, next_value, nor, not, one_hot, one_hot_sel, or, or_reduce, output_port, param, priority_sel, receive, recv_channel_end, register_read, register_write, reverse, sdiv, sge, sgt, sle, slt, smod, smul, smulp, sel, send, send_channel_end, shll, shra, shrl, sign_ext, state_read, sub, trace, tuple, tuple_index, udiv, uge, ugt, ule, ult, umod, umul, umulp, xor, xor_reduce, zero_ext`

→ 纯计算 + 状态 + 通道的指令集，没有任何"内存层级"概念。**裁剪 ① 之后，这不再是问题**——存储本来就在 XLS 之外。

### 1.2 P4 构造 → XLS 对应物

| P4 构造 | XLS 对应物 | 差距 |
|---|---|---|
| `header` 字段 | `bits<N>` / `struct` | 🟢 直映 |
| `header.valid` | 与结构体配对的 `bool` | 🟡 需显式建模 |
| `header stack` | `(struct, bool)[MAX]` + 索引 | 🟡 需按最大值实例化 |
| `varbit<N>` | `bits<N>` + 长度字段 | 🟡 需显式携带长度 |
| parser 状态 | `proc` 的 state（cursors + state id） | 🔴 需降级 FSM |
| `extract()` | 从 `chan<bytes>` 读 + 动态切片 | 🔴 位偏移 + 背压 |
| **`table.apply()`** | **Key/Response 接口 + `sel`/`priority_sel` 分发** | 🟡 **接口规范（本期核心交付）** |
| `action` | `fn`（返回新状态元组） | 🟡 SSA 改写 |
| control 顺序语句 | `let` 绑定链 | 🟢 SSA 天然顺序 |
| `if/else` / `switch` | `sel` / `priority_sel` | 🟢 直映 |
| `register/counter/meter` | 外部 RAM 端口（复用同一套接口基建） | 🟡 extern 语义 |
| `deparser.emit()` | `concat` 拼装输出流 | 🟡 长度对齐 |
| packet in/out | `chan<bits<W>>`（SOP/EOP 需自定义） | 🔴 帧边界 |
| **PISA stage 内 ALU 阵列** | **本期不做** | ⚪ 已裁剪 |
| `clone/multicast/resubmit` | **无对应物** | 🔴 属架构级 RTL |
| 饱和算术 `|+|` `|-|` | 需手写溢出检测 | 🟡 语义差异 |
| 除零 / 负位移位行为 | 与 DSLX 语义不同 | 🟡 需显式规范化 |

图例：🟢 直映　🟡 需适配　🔴 需自研算法或硬件资源　⚪ 已移出范围

---

## 2. p4c 侧改造清单（机械工作）

### 2.1 新增目录

用 `backends/p4test` + `backends/bmv2` 当模板。`Backend` 基类契约极简
（`backends/bmv2/common/backend.h`）：

```cpp
class Backend {
 public:
  const IR::ToplevelBlock *toplevel = nullptr;
  virtual void convert(const IR::ToplevelBlock *block) = 0;   // 唯一必须实现的虚函数
};
```

```
backends/xls/
├── CMakeLists.txt            # 注册目标 p4c-xls；链接 P4C_LIBRARIES + P4C_LIB_DEPS；依赖 genIR
├── xls_options.h/.cpp        # XlsOptions : CompilerOptions（--xls-out、--arch、--phv-width…）
├── xls_backend.h/.cpp        # class XlsBackend : public Backend { void convert(...) override; }
├── p4c-xls.cpp               # main()：FrontEnd → MidEnd → XlsBackend → 输出
├── ir/
│   ├── pir.h/.cpp            # 自定义中间表示（见第 3 节）
│   └── pir_serialize.cpp     # PIR → JSON
├── p4include/                # 目标架构定义（v1model_xls.p4 / psa_xls.p4）
└── driver/p4c.xls.cfg        # 内容：run.xls = p4c-xls
```

另需在 `backends/CMakeLists.txt` 加 `add_subdirectory(xls)`。

### 2.2 midend 策略（最容易被低估的一步）

p4c 默认 midend 是**为 bmv2 定制的**，会把 P4 改写成不便于映射硬件的形式。必须写自定义
midend（参考 `backends/p4test/midend.cpp`）做 pass 取舍：

| Pass | 处置 | 原因 |
|---|---|---|
| `FrontEnd`（parse/resolve/typecheck） | ✅ 保留 | 复用官方语义检查，价值最大 |
| `SynthesizeActions` | ✅ 保留 | 把 inline 动作块提取成具名 action，正是硬件需要的形态 |
| `InstantiateDirectCalls` / `EliminateControlFlow` | ✅ 保留 | 展平嵌套 control |
| `SimplifyControlFlow` | ✅ 保留 | 把表应用显式化 |
| `ConvertEnums` | ⚠️ 慎用 | 会把枚举转成 `bit<>`，丢失 table 的语义标签 |
| `RemoveComplexExpressions` | ⚠️ 按需 | 会引入大量临时变量，需控制策略 |
| `FlattenInterfaceStructs` / header 展平相关 | ❌ 建议关 | P4 的 header struct 结构要保留，否则 offset 信息丢失 |
| `CompileTimeOps` / 位宽推断 | ✅ 保留 | 位宽必须显式，这是硬件刚需 |
| bmv2 专属（`LowerActionProfileOptions`、extern 打桩等） | ❌ 替换 | 换成 XLS 的 extern 映射 |

> 关键动作：显式 dump 出 `--top4` 各 pass 后的 IR，人工过一遍，判断哪些 pass 让映射变难。
> 这一步的效果通常比后端代码本身更大。

### 2.3 驱动与集成

- `driver/p4c.xls.cfg` 让 `p4c --target xls --arch v1model_xls` 能找到后端
- `p4c --target-help` 能看到 `(xls, v1model_xls)` 配对
- 用 p4c 自带的 `testdata/p4_16_samples/*.p4` 做回归，先只断言"能跑不崩"

---

## 3. 中间表示 PIR 设计（新增）

p4c midend 之后，**不要直接吐 DSLX 文本**。先落一层显式 IR，把硬件所需信息全部具名化。
建议 JSON（便于 Python 侧消费，也便于人工 diff）：

```
PIR {
  arch:        { name, ingress_params, egress_params }
  phv:         { total_bits, headers: [{name, fields:[{name,width}], max_instances}] }
  parser:      { start_state, states: [{id, name, ops:[extract|set|transition], select_cases}] }
  controls:    { ingress: [ {stage_index, kind: table|action|condition, ...} ] }
  tables:      [ {name, key:[{field,width,match_type∈{exact,lpm,ternary,range}}],
                   size, actions:[{id, name, params:[{name,width}]}],
                   default_action, const_entries, iface: <见第 4 节> } ]
  actions:     [ {name, params, body: [assignments to phv / extern calls]} ]
  externs:     [ {name, type∈{counter,meter,register,hash,checksum,…}, width, size, params} ]
  deparser:    { emit_order: [header_name, ...] }
  metadata:    { fields: [{name,width}] }
}
```

**必须携带的三类"隐性信息"**（丢了下游无法补回来）：

1. **解析偏移**：每个 extract 的静态可推导 bit offset + 变长分支的长度来源
2. **表的 key 字段位置**：key 不是名字，是 PHV 里的 bit 区间（硬件要按位置取）
3. **action 与 phv 字段的读写集合**：用于算数据依赖

---

## 4. Key/Response 接口规范（本期新增核心交付）

裁剪 ① 之后，**表在 XLS 侧退化为一个通道接口**：输出 key，接收 response。
存储本体、匹配算法（EM/LPM/TCAM）全部由外部实现方负责。

### 4.1 接口形态

| 决策点 | v1 选择 | 理由 |
|---|---|---|
| 端口划分 | **每张表一对请求/响应通道** | 简单、无仲裁、与 P4 的 per-table 语义一致；表多的场景后续再合并共享总线 |
| 时序契约 | **握手式（ready/valid）为主，实现先按固定延迟 L 建模** | 握手能吸收任意延迟（BRAM 1–2 拍 / TCAM 3–4 拍 / 软件模型几十拍）；固定延迟实现简单且延迟可预测 |
| 并发度 | **串行**：一个表的 response 回来再发下一个 | 与 P4 control 的顺序语义一致，免去保序逻辑；多 outstanding 留给后续 |
| key 打包 | 按 PIR 字段顺序 MSB 对齐，未用位补零 | 契约必须唯一确定，否则外部无法对接 |
| mask 归属 | **不由 XLS 侧输出 mask**，外部表项自带 | 接口位宽减半；LPM/ternary 只需额外传 `key_len` |

### 4.2 通道定义

```
请求  table_req<T> {
  key:      bits<KW>     // 按表定义对齐（KW = Σ key 字段宽度，或向上取整到字节）
  key_len:  bits<L>      // 变长 key（LPM/ternary 用；EM 表可省）
  tag:      bits<N>      // in-flight 标识，串行模式下可省
}

响应  table_rsp<T> {
  hit:         bool
  action_id:   bits<A>   // A = ceil(log2(action 数量))
  action_data: bits<D>   // D = Σ 各"参数槽"最大宽度
  priority:    bits<P>   // 可选，TCAM 需要
}
```

**action_data 的布局**：不按 action 拼接，按**参数槽**——第 $i$ 个槽的宽度 = 所有 action 中第 $i$ 个参数的最大宽度。这样同一份 response 位宽对所有 action 一致。

### 4.3 未命中与常数表项

- `hit = 0` → XLS 侧执行 `default_action`（逻辑在 XLS 内，不占外部端口）
- **`const_entries` 编译期内联**：编译期已知的表项可以直接生成成 `sel`/`priority_sel` 逻辑，**完全不占外部端口**。这是零成本的优化，必须在生成器里做——很多 P4 程序的控制逻辑有一半表项是常数。

### 4.4 外部实现方的可选形态

接口定义好之后，右端可以逐级替换，**验证链路不用改**：

| 阶段 | 外部实现 | 用途 |
|---|---|---|
| M2 起点 | Python / C++ 行为模型（从 PIR 的 const_entries + 运行时注入项查表） | 功能验证，配合 XLS JIT 高速对拍 |
| M2 之后 | 简单 RTL 模型（BRAM + 比较器，仅 EM） | 真实时序仿真 |
| M5 | 真实 TCAM / SRAM / 厂商加速器 IP | 上板 |

---

## 5. 硬核难点（按风险排序，已按裁剪重排）

### 🔴 难点 1（新的头号风险）：访存延迟的时序契约

XLS `proc` 的 `next(state)` 是**每拍迭代一次**的模型，而外部存储器有固定延迟
（BRAM 1–2 拍、TCAM 3–4 拍、软件模型可能几十拍）。

- **纯握手方案**：`send(req)` 后 `recv(rsp)`，等待由 XLS 的 flow control 吸收。
  → 需要验证 XLS 的 channel 握手在 proc 内能否正确实现"发出后插等待"，这**不是文档保证的行为**。
- **计数状态方案**：用 `countdown` state 显式吸收固定延迟 $L$。
  → 简单可控，但 $L$ 写死；多表串联时延迟累积可预测（对后续流水划分反而友好）。

**建议：接口按握手定义（不写死 $L$），实现先走计数状态。M0 就要做一个最小闭环验证
"send key → 等 L 拍 → recv response → 分发"在 XLS 里到底成不成立。**
如果这条不通，整个 M2 的前提要重估。

### 🔴 难点 2：Parser 的 FSM + 变长语义

- P4 parser 是带变量的 FSM，DSLX 是固定大小数据流
- `varbit` 与变长 header、header stack 必须**按最大值实例化**后靠 valid 位管理
- parser 循环（MPLS 多层）需按编译期上限展开（`counted_for` / `dynamic_counted_for`）
- 动态 bit 偏移的 `bit_slice` 在硬件上是桶形移位器，**面积与位宽平方相关，成本必须提前评估**

### 🟡 难点 3：数据包接口

- 包是**变长字节流**（100G+ 下每周期几十字节），XLS 接口是固定位宽 `chan`
- 需自建 SOP/EOP 帧边界、位宽转换、背压（`flow_control_insertion_pass` 只给基础握手）
- ingress/egress 双管道 + 中间队列/缓冲，XLS 不管

### 🟡 难点 4：有状态 extern 与架构级特性

- `counter/meter/register` 的读-改-写需要原子性 → **复用第 4 节的 Key/Response 接口基建**，
  这正好是裁剪 ① 顺带带来的红利：一套外部资源接口同时解决表和状态
- `clone/multicast/resubmit/recirculate` 是架构级行为，RTL 侧实现，P4 侧只生成控制信号
- P4 与 DSLX 的算术语义差异（饱和算术、除零、负位移位、隐式位宽提升）需逐条规范化

### ⚪ 已移出本期：PISA ALU 阵列与 stage 划分

**不做的事**：stage 内 ALU 数量预算、资源复用约束、多表同 stage 打包、跨 stage 的确定性依赖划分。

**保留的事**：XLS 自带的自动调度（按 delay model）照常工作，产出的流水深度由它决定。

**后果要说清**：功能正确性不受影响；面积/时序**未优化**，不是不可用。
流水深度非确定性 → 吞吐/时延不可预期。
M5 再补：自定义 delay model + `--pipeline_stages` 约束，逐步逼近结构化流水。

---

## 6. 分阶段实施计划（v2）

| 阶段 | 交付物 | 关键验证 | 估算 |
|---|---|---|---|
| **M0 接口打样** | 固定头（Eth+IPv4）parse→改字段→deparse，**外加一条完整的 Key/Response 空壳通路**（key 输出到桩，response 硬编码回填） | ① 难点 1 的时序契约成立与否 ② XLS JIT 输出 vs bmv2 期望包逐字节比对 ③ iverilog 仿真 | 1.5–2.5 人月 |
| **M1 全流程无表** | v1model ingress/egress 全链路，action 逻辑完整，无外部访问 | p4c `testdata/p4_16_samples` 中无表程序回归 | 2–3 |
| **M2 表接口接通** | 三种匹配类型（EM→LPM→ternary）的 key 提取 + response 解释 + 外部行为模型；`const_entries` 内联 | STF/PTF 对拍；表项随机填充 fuzz | 2–3 |
| **M3 外部状态** | counter / meter / register / hash / checksum，复用 M2 的接口基建 | 与 bmv2 状态行为对拍 | 1.5–2 |
| **M4 基线评测** | 面积/时序/吞吐基线数据；决定是否进入 M5 | 综合报告 | 1–2 |
| **M5（可选，超出本期）** | ALU 阵列约束 + stage 划分 + 真实存储 IP 对接 | 线速/时序收敛 | 4–8 |

- **MVP = M0 + M1 + M2 ≈ 5.5–8.5 人月**
- **到基线评测 = M0…M4 ≈ 8–12.5 人月**
- **M0 是决策门**：接口时序契约 + DSLX/Verilog 产出质量，两点任一不成立就应停下来重估

---

## 7. 验证策略

三级对拍，缺一不可：

```
P4 源程序
   ├─→ bmv2 simple_switch ──────→ 参考输出包（golden）
   ├─→ XLS IR 解释器 / JIT ─────→ 功能输出        （快，覆盖率高）
   └─→ 生成的 Verilog + iverilog → 时序输出       （慢，验真实 RTL）
```

- **外部模型的黄金能力**：M2 起外部表/状态用行为模型，可以**直接喂 bmv2 的同一份表项与状态**，
  使三级对拍的输入完全一致——这是接口化后额外得到的好处
- **语料**：p4c 自带 `testdata/p4_16_samples/`（数百个程序）+ 自写协议覆盖用例
- **形式化**：XLS 自带 `xls/solvers`（Z3），可做 DSLX↔IR↔netlist 逻辑等价检查
- **回归门禁**：M0 起黄金用例进 CI，防止映射规则改动引入静默错误

---

## 8. 路线选型对比

| 路线 | 做法 | 开发速度 | 可调试性 | 耦合度 | 结论 |
|---|---|---|---|---|---|
| A | p4c backend 直接生成 DSLX 文本 | 中 | 中 | 高（生成逻辑在 C++） | 可用于生产，但迭代慢 |
| B | p4c backend 用 `ir_builder` 直接构造 XLS IR | 慢 | 差 | 最高（链接 XLS 库） | 不推荐起步 |
| **C** | **p4c backend 只做 frontend+midend → 输出 PIR JSON；Python 生成 DSLX** | **快** | **好** | **低（JSON 解耦）** | **✅ 推荐** |

路线 C 的额外好处：PIR JSON 是一份**可独立复用的资产**——将来换 RTL 生成后端
（自研、Bluespec、HLS）时，p4c 侧一行不用改。

---

## 9. 定位判断（裁剪后）

裁剪 ① ② 之后，这条路的定位更清晰了：**它是一个 "P4 逻辑 → RTL" 的生成器，不是 "P4 → 完整可编程数据面" 的方案**。存储、状态、调度全部外置。

| 场景 | 是否合适 |
|---|---|
| 功能验证 / 与 DPDK·bmv2 对拍 / 快速原型 | ✅ 非常合适，M0–M1 即可见效 |
| 卸载非核心路径逻辑（INT 遥测、自定义封装、校验） | ✅ 合适。逻辑规整、无表或表极少，正好绕开最麻烦的部分 |
| 研究平台 / 教学 / 论文 | ✅ 合适 |
| 有表但吞吐要求不高的数据面 | 🟡 可行，前提是 M0 的接口时序契约成立 |
| **高吞吐数据面核心流水线（100G+ 线速）** | ⚠️ 本期范围不覆盖。缺少 ALU 阵列约束 + 确定性流水划分，产出无法对标商用方案 |

**务实定位**：用 M0–M2 的 5.5–8.5 人月拿到一条**可验证的 P4→RTL 生成链路**，
先把"语义映射对不对"这件事确认下来；资源与调度留到 M5，届时再决定是否投入。

---

## 附：关键证据来源

| 结论 | 来源 |
|---|---|
| XLS IR 共 79 个 op，无存储原语 | `../third_party/xls/xls/ir/op_list.h` |
| proc → Verilog 有正式测试 | `xls/codegen_v_1_5/codegen_proc_test.cc` |
| 自动插流水寄存器 / ready-valid 握手 | `pipeline_register_insertion_pass.cc` / `flow_control_insertion_pass.cc` |
| DSLX `proc{config,init,next}` + `chan`/`state` | `docs_src/dslx_reference.md` §Communicating Sequential Processes |
| RAM 仅有低层改写 pass，非公开原语 | `xls/ir/ram_rewrite.proto`（`RAM_ABSTRACT/1RW/1R1W`） |
| p4c `Backend` 契约 | `p4c/backends/bmv2/common/backend.h` |
| p4c 新增后端的 CMake/driver 规范 | `p4c/README.md` §Defining new CMake targets |
| p4c 现有 backends | `bmv2, common, dpdk, ebpf, graphs, p4fmt, p4test, p4tools, tc, tofino, ubpf` |
