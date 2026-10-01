# demo12 —— 二层/三层混合交换机（v4：可选 OpaqueTag/VLAN + 并行查找 + 报文编辑 + 报文重组）

> **v4 变更**（2026-10-02）：报文格式支持**可选封装** —— 1 层 OpaqueTag + 最多 4 层 VLAN，
> 并在 ingress 里真正用起来（内层 VID 参与二层查表、上联口打标签、用户口剥标签）。
> 见 §0。

> 源文件：`testcases/p4/demo12-l2l3-switch.p4`（约 330 行）
> 一键跑通：`scripts/p4flow testcases/p4/demo12-l2l3-switch.p4 -o out/flow/demo12`
> 行为验证：`python3 scripts/demo12_verify.py`（47 条断言）

> **v2 变更**（对应郝宇三条要求）：① 新增 deparser（报文重组）并显式化报文编辑阶段；
> ② 两张查找表外置为 key/rsp 通道；③ `// p4c: lookup-group` 并行查找分组。

---

## 0. 可选封装：固定槽位方案（v4）

子集**不支持** header stack / setValid / 变长 extract（看 `docs/进度与计划.md` 的语法子集表），
所以「层数可选」按**固定槽位**实现：

```
Ethernet | [OpaqueTag] | [802.1Q VLAN × 0..4] | ethertype | IPv4 | (UDP | TCP)
  14 B   |     6 B     |        4 B × n       |    2 B    | 20 B |  8 B or 20 B
```

- **VLAN 是标准 802.1Q 4 字节**：TPID(16) + TCI(16) = PCP(3) | DEI(1) | VID(12)。
- 每个可选层占一个**固定偏移**的槽位；不存在时槽位填 0；存在性由该槽位自身的
  `tpid` 判定（`0x8100` = VLAN、`0x8200` = OpaqueTag）⇒ **层数天然可选（0..4）**。
- 802.1Q 标签内部**没有**"下一层类型"字段（真实帧里它就是下一个标签/etype 的位置），
  故标签链末端单独放一个 2 字节 `etype`（等价于真实帧里最后一个标签之后的那 2 字节），
  它决定 `isIpv4` / `isL3`。OpaqueTag 是自定义标签，自带 `nextType`。
- 代价：① 不存在的层仍占带宽；② 入包缓冲必须**归一化到最坏布局**（缺的层补 0），
  这也正是多数交换芯片内部报文总线的形态。
- VLAN 增删 = **槽位内容搬移**（push：vlan3←vlan2←vlan1←vlan0←新；pop 反之）。
  子集「同一 action 内读的是入口快照」的语义在这里正好是我们要的（整槽搬移）。

**层数可选的行为边界**（`resolve` 写 `doPush/doPop`，`rewrite` 执行）：

| 出端口 | 当前层数 | 动作 |
|---|---|---|
| 上联口（L3/trunk） | 0..3 | **加一层**外层 VLAN（`UPLINK_VID`） |
| 上联口（L3/trunk） | 4 | **饱和保护**：不再加层（槽位已满） |
| 用户口（L2/access） | 1..4 | **剥一层**外层 VLAN |
| 用户口（L2/access） | 0 | 无可剥，报文不动 |
| 泛洪 | 任意 | 不编辑 |

**报文窗口**：`// p4c: pkt-window 640`（新指示，见 §6）—— 默认 512 位装不下最坏布局
（78 字节 = 624 位）。

**偏移口径（易踩，本轮刚修）**：header 长度 = **header 总宽**向上取整到字节
（此前是"逐字段 ceil 再求和"，会把 4 字节的 802.1Q 算成 6 字节、20 字节的 IPv4/TCP
算成 22 字节）；header **起点**字节对齐，header **内部**字段位紧凑排列。

## 1. 交换机结构与处理流水

4 端口交换机：端口 0 = 上联/三层口，端口 1..3 = 用户口。

```
pkt_in
 → parser            Ethernet → IPv4 → (UDP|TCP)
 → classify()        预分类：isL3 门控位（目的 MAC == 交换机 MAC）
 → mac_table   ┐     runtime 表，并行查找组 g0：同拍发 key / 同拍收 rsp
 → route_table ┘     命中结果写入各自决策字段（macHit/macPort、rtHit/rtPort/rtDstMac/rtSrcMac）
 → resolve()         合并仲裁：L3 > L2 > 泛洪 ⇒ fwdType / outPort / outPortIdx
 → rewrite()         **报文编辑**：MAC 重写、TTL 递减、IP 校验和增量更新（RFC 1624）
                     + TTL 耗尽标记 + 每端口字节统计
 → ttl_guard         静态表：路由后 TTL == 0 ⇒ 丢弃
 → Deparser          emit 序重组报文（ethernet → ipv4 → udp → tcp）→ pkt_out
```

## 2. 三项升级的落地

### 2.1 报文编辑 + 报文重组（deparser）

- **字段级编辑**集中在 `rewrite` action（显式的"报文编辑"阶段）：
  重写目的/源 MAC、TTL 递减、IP 校验和增量更新（RFC 1624），三元门控仅 L3 命中改包。
- **报文重组**：`control Deparser(packet_out pkt, in headers_t hdr) { pkt.emit(...); }`
  按 emit 声明序拼接各 header 的 (valid, 字段…) —— 通道名 `pkt_out`（500 位），**不含 metadata**。
  子集语义：无条件 emit（无 isValid/变长），invalid header 输出全 0。

### 2.2 查找表外置（key/rsp 接口）

| 通道 | 位宽 | 方向 | 说明 |
|---|---|---|---|
| `tbl_mac_table_key` | 48 | out | 目的 MAC |
| `tbl_mac_table_rsp` | 6 | in | hit(1) \| actId(1) \| args(4) |
| `tbl_route_table_key` | 40 | out | isL3(8) + 目的 IP(32) |
| `tbl_route_table_rsp` | 102 | in | hit(1) \| actId(1) \| args(100)=port(4)+nexthopMac(48)+srcMac(48) |

- runtime 表只固化**结构**（key/actId/args 位宽、action 编号），**表项由控制面经写接口下发**
  （L2 示例：`48w0x001122334455 → l2_forward(4w1)`；路由示例：`isL3=1 + 32w0x0a000003 → l3_forward(4w0, NH_MAC_3, SWITCH_MAC)`）。
- `ttl_guard` 是防御逻辑不是配置表，保持**静态融合**（与 runtime 表共存，验证两种形态）。
- 布局口径与 A3 外部表模块一致（`docs/A3-EM模块设计.md`）。

### 2.3 并行查找分组（`// p4c: lookup-group`）

```p4
// p4c: lookup-group g0 = mac_table, route_table
mac_table.apply();
route_table.apply();
```

- **效果**：组内 runtime 表**同拍发 key、同拍收 rsp 并应用** —— 查找延时 2 拍（串行 2×2=4 拍）。
  IR 证据：两表 key 的发送谓词是**同一个相位节点** `is_ph6_70`，rsp 同为 `is_ph7_70`。
- **声明方法**：顶层指示 `// p4c: lookup-group <组名> = <表1>, <表2>, ...`，可多组。
- **工具链强制校验**（违反即编译报错，绝不静默降级串行）：
  1. 组内表都必须是 runtime 表（静态表是纯组合逻辑，并行无意义）；
  2. 每张表至多属一个组；
  3. 组内表在 apply 里**连续排列**（中间夹语句会让它的相位落在查找/应答之间）；
  4. 组内各表 action 的**写集互斥**（同拍应用无法定义覆盖次序）；
  5. 先声明表的写集 ∩ 后声明表的 **key 读集** = ∅（并行 ⇒ 读到的是进入该拍的快照）。

## 3. 并行查找的结构前提：预分类替代串行门控

v1 用 `route_table.key 含 meta.fwdType`（mac_table 的输出）做门控 —— 这是**串行依赖**，
并行后 route 会读到旧值。v2 改为标准交换机做法：

```
classify(): meta.isL3 = (dst MAC == SWITCH_MAC) ? 1 : 0   ← 只看报文，不依赖任何表
mac_table  : key = dstAddr                                ← 与 route 无依赖
route_table: key = (isL3, dstAddr)                        ← isL3 是预分类位
resolve()  : L3 命中 > L2 命中 > 泛洪（合并两表的并行查找结果）
```

## 4. 关键语义陷阱：同一 action 内读不到自己刚写的值（W1）

顺序组合语义下，**同一 action 的 DAG 用入口快照求值** —— action 内先写 `meta.fwdType`
再读它，读到的是**旧值**（`docs/进度与计划.md` 的"字段单一写者"早有预警，本次实割验证：
统计条件用了刚写的 fwdType，增量恒为 0）。

⇒ **必须靠相位边界传递中间结果**：resolve（仲裁）与 rewrite（编辑+统计）拆成两个 action，
rewrite 才能读到 resolve 写出的 fwdType/outPortIdx。这也正是流水线的本来语义。

同类陷阱：rewrite 里编辑完 TTL 后再读 TTL，读到的仍是编辑前的值 ——
dropReason 的判定改用**编辑前口径**（`ttl <= 1` ⇒ 减后为 0 或已耗尽）。

## 5. 表项下发（控制面视角）

runtime 表的 rsp 布局 `hit | actId | args`（hit 最高位）。以 route_table 为例：

| 字段 | 位宽 | 含义 |
|---|---|---|
| hit | 1 | 命中标志（表模块比对 key 后置位） |
| actId | 1 | 0=l3_forward 1=l3_miss（actions 声明序编号） |
| args | 100 | port(4) \| nexthopMac(48) \| srcMac(48)（**先声明形参占高位**） |

key 布局：`isL3(8) | dstAddr(32)`（先声明的 key 在高位）。外部表模块按 key 比对后回 rsp；
proc 在收 rsp 的相位**同拍**应用对应 action（stall 等 rsp，查找延迟由表模块决定）。

## 6. 接口总表

| 端口 | 位宽 | 方向 | 内容 |
|---|---|---|---|
| `pkt_in` | **640**（`pkt-window`） | in | 入包（固定槽位最坏布局，见 §0） |
| `pkt_out` | **698** | out | **重组后的报文**（eth113 + otag49 + vlan×4 33 + etype17 + ipv4 161 + udp65 + tcp161） |
| `ex_portBytes` | 256 | out | Register：8×32 每端口字节 |
| `ex_fwdCnt` | 128 | out | Counter：4×32 每类计数（0=L2 1=L3 2=丢弃） |
| `tbl_mac_table_key/rsp` | **60**/6 | out/in | L2 查找（key = 目的 MAC 48 + **内层 VID** 12） |
| `tbl_route_table_key/rsp` | 40/102 | out/in | L3 查找 |

## 7. 实测

| 项 | 结果 |
|---|---|
| p4flow | 9 步 OK（含 verilator 仿真），II=1 |
| **RTL 仿真（TB 激励）** | `testcases/a2/tb_demo12_l2l3_switch.v` **38 条断言全绿**（verilator + FST），8 类场景按**层数**组织：0/1/2/4 层 × (L2 剥 / L3 打 / 饱和保护) + OpaqueTag + TTL 耗尽 + 泛洪 |
| 真 XLS parser 校验 | IR 良构、往返一致 |
| IR lint | 通过（0 issue） |
| 形式验证 | 11 fn：**等价 22 · 不等价 0 · 未决 0** |
| Verilog | 828 行 / 寄存器 54 个 1242 位 |
| 行为断言 | `demo12_verify.py` **55/55 通过**（IR 级）+ TB **38/38**（RTL 级） |

并行查找证据（IR 文本）：

```
ks_mac_table_245:   send(tok, ..., predicate=is_ph6_70, channel=tbl_mac_table_key)
ks_route_table_276: send(tok, ..., predicate=is_ph6_70, channel=tbl_route_table_key)
rs_mac_table_246:   receive(tok, predicate=is_ph7_70, channel=tbl_mac_table_rsp)
rs_route_table_277: receive(tok, predicate=is_ph7_70, channel=tbl_route_table_rsp)
```

两表共用同一相位判据节点（is_ph6_70 / is_ph7_70）⇒ 真正同拍。总相位 11（串行需 13）。

## 8. 已知边界

- 无 LPM（子集仅 exact）⇒ 路由是 /32 主机路由；表 key 不能写切片 ⇒ 也做不了掩码前缀表
- 无 header stack / setValid ⇒ 无 VLAN 增删；emit 无条件（invalid header 输出全 0）
- 无 iverilog ⇒ 未做 RTL 仿真（TB 骨架已生成；行为正确性由 47 条 IR 级断言承担，
  `P4C.Interp` 与 RTL 发射共用同一语义）
- Chisel 线暂不发射 deparser（stderr 告警提示走 XLS 线）；lookup-group 仅作用于 XLS 线 proc 相位

## 9. 复现

```bash
scripts/p4flow testcases/p4/demo12-l2l3-switch.p4 -o out/flow/demo12
python3 scripts/demo12_verify.py
scripts/p4xls eval-ir out/flow/demo12/ir_fn/*action_rewrite.ir \
    --in meta.rtHit=1 --in meta.fwdType=2 --in hdr.ipv4.ttl=64 --in hdr.ipv4.hdrChecksum=0x1234 \
    --in meta.rtDstMac=0x00deadbeef01 --in meta.rtSrcMac=0x020000000001 --in meta.ipLen=100
```

## v3 —— 模块化拆分（Parser / 表组+动作 / Deparser 各一个 module）+ FIFO 对齐 + 延时配置

### 1. 生成代码的模块化（用户需求 ①）

生成代码从「单 proc 单模块」升级为 **proc 网络（多模块 Verilog）**：

```
top proc Ingress_pipeline（连线壳：外部端口 + proc_instantiation + FIFO 通道）
├── Ingress_pipeline__parser    解析（extract FSM）
├── Ingress_pipeline__ctrl1     [classify 动作段]
├── Ingress_pipeline__ctrl2     [查找组 g0 + resolve/rewrite/ttl_guard]
└── Ingress_pipeline__deparser  报文重组（emit 序）
```

实现机制（全部经 XLS 官方 codegen 路径验证）：

- **proc_instantiation**：Top 壳通过 `proc_instantiation i_xxx(<通道列表>, proc=子proc)`
  组网，子 proc 按 interface 声明序绑定通道。
- **proc 间通道**：Top 内 package 级 `chan ph_N(bits[W], id=.., kind=streaming, ops=send_receive,
  flow_control=ready_valid, fifo_depth=N, strictness=proven_mutually_exclusive)` +
  `chan_interface` 的 send/receive 成对引用（loopback 形态，**不进 Top 签名** ——
  放进签名会让 interface 计数翻倍，verifier 报 `Duplicate channel reference`，实测踩过）。
- **codegen**：`verilog_codegen_main` 加 `schedule_all_procs(true)`（否则子 proc 无调度表，
  ConvertToBlock 在 GetSchedule 处 out_of_range 崩溃）。codegen 自动为每条 fifo 通道
  实例化 `xls_fifo_wrapper`（Width/Depth/EnableBypass 参数化）并连接各模块。
- parse 顺序：**子 proc 先于 Top**（`proc=` 引用走 TryGetProc，先定义后引用）。
- 单段程序（无 runtime 组 / 无 deparser）退化为单 proc，与 v2 完全兼容（TB 对接不变）。

### 2. 查找结果 FIFO 对齐（用户需求 ③）

- proc 间通道 `fifo_depth=4`（`XlsBackend.FifoDepth`，可改）：PHV（包头+Meta）随
  通道流动，上一段的处理结果在 FIFO 里排队，与下一段的查找节奏解耦 ——
  慢表 stall 只占 FIFO，不丢包（ready_valid 背压 + XLS 流控插入 pass）。
- 查找组内「同拍发 key / 全收齐 rsp 才进动作」的既有语义不变（结果天然对齐）。
- demo12 实测 Verilog：5 模块 + 3 个 `xls_fifo_wrapper` 实例。

### 3. 每表延时范围可配（用户需求 ②）

指令扩展：`// p4c: table <名> runtime size=N latency=<min>-<max>`。

- 语义：外部表模块对一次查找的响应延时范围（拍）。查找 FSM 是「一包在途」——
  阻塞等 rsp，任意落在 [min, max] 的延时都正确，慢表只降吞吐不丢包。
- 生成侧：① 校验（min ≥ 1 —— 组合表不存在；min ≤ max）；② 写进 IR 的
  `// contract: table 'X' lookup latency min..max cycles` 注释（外部表模块接口契约）。
- demo12：mac_table `latency=1-4`、route_table `latency=2-8`。

### 4. 验证

- p4flow 8 步全绿 ×7 样本（formal：demo12 等价 32 · 未决 0）。
- demo12_verify 47/47（多 proc 拆分后 action 语义不变，fn dump 与 eval-ir 均不受影响）。
- 已知小瑕疵：报告的「state 项/位 · 节点」统计在多 proc IR 上为 0（profileOf 的
  状态提取按单 proc 头解析；查表口/II 计数正确），待后续适配。
