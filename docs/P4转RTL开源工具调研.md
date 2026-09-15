# P4 → RTL 既有工具调研（GitHub 开源生态）

> 调研时间：2026-09-14
> 结论摘要：**GitHub 上不存在"活跃维护 + 全 pipeline + 直接输出可综合 RTL"的成熟开源 P4→RTL 工具。**
> 能落地的只有：parser 级工具（新，2026）、已停维护的学术原型（2016–2017）、依赖商用 license 的封装框架，以及"参数化 RTL + 运行时配置"这类不生成 RTL 的替代路线。

---

## 1. 结论

| 判断项 | 结论 |
|---|---|
| p4c 官方是否带 HDL/RTL backend | **否**。截至 2026-09，p4c `main` 分支 `backends/` 目录仅：bmv2、common、dpdk、ebpf、graphs、p4fmt、p4test、p4tools、tc、tofino、ubpf |
| 是否有开源全流程 P4→RTL | **否**。无任一项目同时满足：parser+MA+deparser 全覆盖、活跃维护、宽松 license |
| 最接近的开源件 | `p4fpga`（全流程但 2017 停更）、`patterns-to-parsers`（新但只做 parser） |
| 工业可用方案在哪 | **全部闭源商用**：AMD Vitis Networking P4（原 SDNet）、Intel/Altera P4 Suite for FPGA |
| 空白点是否真实 | **是**。这是明确的生态缺口，而非"我没搜到" |

---

## 2. 开源项目清单（GitHub，按可用度排序）

### 2.1 直接相关

| 项目 | Star | 主体语言 | 最后实质活动 | 产出 | 覆盖范围 | License | 状态 |
|---|---|---|---|---|---|---|---|
| [p4fpga/p4fpga](https://github.com/p4fpga/p4fpga) | 92 | Bluespec | 2016–2017 | P4-14/16 → Bluespec → Verilog | parser + MA + deparser **全流程** | BSD-2 | ⚠️ 停更，依赖已废弃的旧版 p4c/Bluespec |
| [boutros-lab/patterns-to-parsers](https://github.com/boutros-lab/patterns-to-parsers) | 0 | SystemVerilog + Python | 2026-08 | PIR → **SystemVerilog** | **仅 parser** | MIT | ✅ 新（2026-06 建），Waterloo + Meta |
| [benycze/PhD-Thesis](https://github.com/benycze/PhD-Thesis)（P4-to-VHDL） | 4 | VHDL | — | P4-14 → VHDL | **仅 parser/deparser** | — | ⚠️ 博士项目，未完成全流程，仅 P4-14 |
| [NetFPGA/P4-NetFPGA-public](https://github.com/NetFPGA/P4-NetFPGA-public) | 105 | 文档为主 | 2018-02 | 依赖 Xilinx P4-SDNet 生成 RTL | 全流程 | — | ❌ 需 Vivado + SDNet 商用 license |
| [OCT-FPGA/P4Framework](https://github.com/OCT-FPGA/P4Framework) | 4 | SV / P4 / Tcl | 2026（小改） | 包装 VitisNetP4 IP 进 OpenNIC shell | 全流程 | — | ❌ 需 `sdnet_p4` + `Tcam` license |
| [esnet/esnet-smartnic-hw](https://github.com/esnet/esnet-smartnic-hw) | — | SV / Tcl | 活跃 | VitisNetP4 流程示例 | 全流程 | — | ❌ 同上，依赖 AMD 商用编译器 |
| [Xilinx/open-nic](https://github.com/Xilinx/open-nic) | — | SV | 活跃 | FPGA NIC shell（本身不产 RTL） | shell | Apache-2.0 | 集成底座 |

### 2.2 替代路线：不生成 RTL，而是"固定 RTL + P4 编译成配置"

| 项目 | Star | 语言 | 最后活动 | 说明 |
|---|---|---|---|---|
| [AlessandroVacca/menshen-open-nic](https://github.com/AlessandroVacca/menshen-open-nic) | 0 | SV / P4 | 2024-09 | Menshen 参数化 RMT pipeline 移植到 AMD OpenNIC。P4 编译成配置包，RTL 是固定的 |
| [system-fab/mensheNIC](https://github.com/system-fab/mensheNIC) | — | SV / Python | 2024 | 同上，配套 `p4c-fpga` 后端生成配置 |
| [Xilinx/HLS_packet_processing](https://github.com/Xilinx/HLS_packet_processing) | 49 | C++ (HLS) | 2019 | HLS 网络库，需自行接 Vivado HLS → RTL。已停更 |
| [MagicLabFast/FAST](https://github.com/MagicLabFast/FAST) | 42 | C | 2017 | FPGA 加速 SDN 交换机，非 P4 编译路线，已停更 |

Menshen 本身值得单独标注：论文 *Isolation Mechanisms for High-Speed Packet-Processing Pipelines*（arXiv:2101.12691），**6,330 行 Verilog** 的可编程 RMT pipeline（parser + 5 级流水 + deparser），已集成到 Corundum NIC 与 NetFPGA。它是目前**公开可用的最完整的 RTL 级 RMT 实现**，但它是"运行时重配置"模型，不是综合时生成 RTL。

### 2.3 学术侧（无/未确认公开仓库）

| 工作 | 出处 | 要点 |
|---|---|---|
| **CaT** | arXiv:2211.06475, *High-Level Synthesis for Packet-Processing Pipelines* | P4 → 低级 pipeline 表示，三阶段 HLS。目标：Tofino + Menshen |
| **P4HLS** | 文献综述提及 | 生成模板化 C++ 类，交给 HLS 工具链 |
| **Calyx / CIRCT** | Cornell / LLVM | 通用硬件 IR，可作 P4 frontend 的下游，但非 P4 专用 |

### 2.4 明确排除项

调研中大量镜像/聚合站（`onlybits.org`、`ghub.com`、`bithub`、`git-hub.com`、`arcxiv.org` 等）转载的 p4c README 含有 `p4c-apollo-tuna` 之类 backend 描述，**经 GitHub 官方 API 核对，p4lang/p4c `main` 的 `backends/` 目录中不存在该 backend**，且 GitHub 上检索不到对应的 `tuna_nic` / `tunic` 公开仓库。此类内容不可采信。

---

## 3. 为什么开源侧长期空缺

1. **RTL 生成质量门槛高**。P4 的关键字/字段宽度/parser 状态机映射到硬件需要做字节对齐、多周期字段跨越、PHV 分配、流水级平衡与依赖调度。学术原型止步于 parser，根因在此。
2. **HLS 路线性能偏软**。走 C++ → HLS 能快速跑通，但资源与时序劣于手写/模板化 RTL，工业界只在有 vendor 深度调优时才敢用。
3. **商业模式集中在闭源**。SDNet/VitisNetP4、Intel P4 Suite 都是绑 FPGA 卖的工具，开源会削弱板卡价值。
4. **维护成本**。P4-16 语言与 p4c 演进快，2016–2017 年的原型依赖的旧 API 已不可用。

---

## 4. 工业界对照（闭源，但有工程参考价值）

| 方案 | 厂商 | 技术路线 | 输入 → 输出 |
|---|---|---|---|
| **Vitis Networking P4**（原 SDNet） | AMD | P4 → 模板化 C++ → Vitis HLS → RTL IP | P4 + 架构 → `.sv` + IP block |
| **P4 Suite for FPGA** | Intel / Altera | P4 + 自定义架构 → 直接生成 RTL IP（2025 年推出，Altera Innovators Day 2025 演示 200G SmartNIC） | P4 + 架构 → synthesizable RTL + 控制面 API |
| **Netcope P4** | Netcope | 云服务形式输出 FPGA firmware | P4 → bitstream |
| **Tofino / TNA** | Intel | 专用 ASIC，非 RTL | P4 → 设备二进制 |

> 注意 AMD 路线的关键设计：**不用 P4 直出 RTL，而是 P4 出模板化 C++ 再走 HLS**。这是绕开"直接 RTL 生成"难点的工程折中。
> Intel 路线则是真·P4→RTL 直出，且支持完全自定义 P4 架构。

---

## 5. 若自研，可复用的骨架（建议路线）

```
p4c frontend + midend (Apache-2.0)
        │
        ├─ 路径 A：自研 RTL backend
        │     参考 Menshen 的 6.3k 行参数化 RTL 作为模板底座
        │     参考 patterns-to-parsers 的 PIR + Jinja2 → SystemVerilog 做 parser
        │     MA 表 / ALU 阵列需自研（当前无开源现成件）
        │
        └─ 路径 B：HLS 中间层
              仿 AMD：IR → 模板化 C++ → Vitis HLS / oneAPI
              开发快，PPA 需自行调优
```

**关键缺口（谁做谁有价值）**：
- Match-Action 单元的开源 RTL 生成（含 TCAM/LPM/EM 表映射）——目前**完全没有**公开件
- ALU/流水级资源分配与调度（CaT 做了算法但未开源）
- P4-16 完整语义到 RTL 的降级规则化

---

## 6. 参考链接

- p4c 官方：https://github.com/p4lang/p4c
- P4 FPGA backend 讨论帖（P4 官方论坛，2025-09）：https://forum.p4.org/t/p4-backend-for-fpgas-hdl/1383
- From Patterns to Parsers：https://arxiv.org/abs/2607.16058
- Menshen 论文：https://arxiv.org/abs/2101.12691
- CaT 论文：https://arxiv.org/abs/2211.06475
- P4 数据平面综述：https://arxiv.org/abs/2101.10632
- SmartNIC 综述：https://arxiv.org/abs/2405.09499
- Intel P4 Suite for FPGA：https://altera.com/products/development-tools/p4-suite-fpga
