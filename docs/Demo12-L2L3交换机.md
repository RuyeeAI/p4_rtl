# demo12 —— 二层/三层混合交换机（v2：并行查找 + 报文编辑 + 报文重组）

> 源文件：`testcases/p4/demo12-l2l3-switch.p4`（约 330 行）
> 一键跑通：`scripts/p4flow testcases/p4/demo12-l2l3-switch.p4 -o out/flow/demo12`
> 行为验证：`python3 scripts/demo12_verify.py`（47 条断言）

> **v2 变更**（对应郝宇三条要求）：① 新增 deparser（报文重组）并显式化报文编辑阶段；
> ② 两张查找表外置为 key/rsp 通道；③ `// p4c: lookup-group` 并行查找分组。

---

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
| `pkt_in` | 512 | in | 入包 |
| `pkt_out` | 500 | out | **重组后的报文**（ethernet113+ipv4161+udp65+tcp161） |
| `ex_portBytes` | 256 | out | Register：8×32 每端口字节 |
| `ex_fwdCnt` | 128 | out | Counter：4×32 每类计数（0=L2 1=L3 2=丢弃） |
| `tbl_mac_table_key/rsp` | 48/6 | out/in | L2 查找 |
| `tbl_route_table_key/rsp` | 40/102 | out/in | L3 查找 |

## 7. 实测

| 项 | 结果 |
|---|---|
| p4flow | 8 步 OK（sim 因无 iverilog SKIP），II=1 |
| 真 XLS parser 校验 | IR 良构、往返一致 |
| IR lint | 通过（0 issue） |
| 形式验证 | 11 fn：**等价 22 · 不等价 0 · 未决 0** |
| Verilog | 828 行 / 寄存器 54 个 1242 位 |
| 行为断言 | `demo12_verify.py` **47/47 通过** |

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
