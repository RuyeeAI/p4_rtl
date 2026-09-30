# demo12 —— 二层/三层混合交换机（P4 源码级 Demo）

> 源文件：`testcases/p4/demo12-l2l3-switch.p4`（约 290 行）
> 一键跑通：`scripts/p4flow testcases/p4/demo12-l2l3-switch.p4 -o out/flow/demo12`
> 行为验证：`python3 scripts/demo12_verify.py`（30 条断言）

---

## 1. 这个 Demo 做什么

一个 4 端口简化交换机（端口 0 = 上联/三层口，端口 1..3 = 用户口）：

| 能力 | 实现 |
|---|---|
| **L2 转发** | 目的 MAC 精确匹配 → 从对应端口送出 |
| **L2 泛洪** | 未知单播（目的 MAC 未命中）→ 泛洪到端口 1/2/3 |
| **L3 路由** | 目的 IP 精确匹配 → 下一跳出端口 |
| **报文编辑** | 重写目的/源 MAC、TTL 递减、IP 头校验和增量更新（RFC 1624） |
| **上三层分流** | 目的 MAC = 交换机 MAC 的包交给路由表处理 |
| **TTL 耗尽丢弃** | 路由后 TTL == 0 的包丢弃并标记原因 |
| **统计** | 每端口累计字节（Register 读改写）、每类转发包计数（Counter） |

处理流水：

```
pkt_in → parser(Ethernet → IPv4 → UDP/TCP)
      → classify()          取 IPv4 长度、清丢弃原因
      → mac_table           目的 MAC 表：L2 转发 / 上三层 / 泛洪
      → route_table         路由表：命中则重写 MAC + TTL-1 + 更新校验和
      → ttl_guard           路由后 TTL == 0 ⇒ 丢弃
      → phv_out             headers + metadata
```

---

## 2. 子集约束下的两个设计要点（先读这段）

这个前端是 **P4-16 子集**（`src/main/scala/P4C`），写 demo 前必须知道两条硬约束，
它们直接决定了代码怎么写。

### 2.1 没有 `if` / `else` ⇒ 用「元数据参与表 key」代替控制流

`Ast.Stmt` 里没有条件语句，表达式只有三元。表一旦 `apply` 就一定会执行，
**下游表无法被上游"跳过"** —— 于是"先查 MAC 再决定要不要查 IP"这种自然写法写不出来。

本 demo 的解法：把**上游的判定结果塞进下游表的 key**。

```p4
table route_table {
    key = {
        meta.fwdType     : exact;   // ← 门控位：只有 L2 判定为"上三层"才可能命中
        hdr.ipv4.dstAddr : exact;
    }
    const entries = {
        8w2, 32w0x0a000001 : l3_forward(4w0, NH_MAC_1, SWITCH_MAC);
        ...
        default            : nop();   // 其余包直通，不改报文
    }
}
```

`mac_table` 若判为 L2 转发，`meta.fwdType` 就是 `FWD_L2(1)`，而路由条目第一段全是 `8w2`
⇒ key 必然不匹配 ⇒ 落到 `default: nop()` 直通。**等价于"条件执行"，但完全落在 exact 匹配能力内。**

### 2.2 字段单一写者 ⇒ 每个字段只由一组互斥的 action 写

顺序组合语义下，后写的表/语句会覆盖先写值。因此字段归属必须规划清楚：

| 字段 | 写者 |
|---|---|
| `meta.ipLen` / `meta.dropReason` | `classify` →（`drop_ttl` 覆盖 `dropReason`） |
| `meta.fwdType` | `mac_table` 三个 action（互斥）→ `l3_forward` / `drop_ttl`（条件覆盖） |
| `meta.outPort` / `outPortIdx` | `l2_forward` / `flood` / `l3_forward` |
| `hdr.*`（报文编辑） | 只有 `l3_forward` |

注意 `to_l3()` **刻意不写** `outPort` —— 出端口留给路由表决定，这样 `outPort` 的写者
在任一包上都不冲突。

### 2.3 还踩到/绕开的限制

| 限制 | 影响 | 本 demo 的应对 |
|---|---|---|
| 只支持 `exact` 匹配（无 lpm/ternary） | 无最长前缀匹配 | 用 `/32` 主机路由；网段前缀可另加一张"掩码后切片"的表，但**表 key 不能写切片**，故当前版本未做 |
| 表 key 只能是**字段路径**（不能写 `hdr.ipv4.dstAddr[31:8]`） | 无法用"截断后的前缀"做 key | 同上 |
| 不能给切片赋值（`hdr.f[3:0] = x` 报错） | 不能改半字节字段 | 本 demo 只改整字段 |
| 无 header stack / `setValid` / `push_front` | **做不到插入或剥离 VLAN tag** | VLAN 未纳入；变长封装还会让同一 header 在不同路径的字节偏移不同，前端直接报错（`ChiselBackend:1103`） |
| 无 `apply` 内局部变量（`VarDecl` 抛错） | 不能存临时变量 | 旧 TTL 用表达式复用；校验和更新靠"先算后改"的语句顺序 |
| 无 `checksum` / `hash` 外部函数 | 不能调库算校验和 | 手写 RFC 1624 增量更新（见 §4） |
| 表必须有 `const entries` | 空表编译报错 | 每张表都给出条目（含 `default`） |

---

## 3. 报文编辑（本 Demo 的核心）

`l3_forward` 一次完成三件事，**顺序不能变**：

```p4
action l3_forward(bit<4> port, bit<48> nexthopMac, bit<48> srcMac) {
    // ① 先更新校验和 —— 此刻 hdr.ipv4.ttl 还是旧值
    hdr.ipv4.hdrChecksum = ~(~hdr.ipv4.hdrChecksum
                           + ~((bit<16>)hdr.ipv4.ttl)
                           + ((bit<16>)hdr.ipv4.ttl - 16w1));
    // ② 再递减 TTL（顺序反了上面读到的就是新值）
    hdr.ipv4.ttl = hdr.ipv4.ttl - 8w1;
    // ③ 重写二层地址
    hdr.ethernet.dstAddr = nexthopMac;
    hdr.ethernet.srcAddr = srcMac;
    ...
}
```

**为什么必须"先算校验和"**：字段被写之后，后续读到的就是新值（SSA 语义）。
校验和的增量公式同时需要旧 TTL 与新 TTL，所以必须在写 TTL 之前算完。

---

## 4. 校验和的数学：RFC 1624 增量更新

TTL 是 IPv4 头的一个 16 位字的高字节，每转发一跳 TTL 减一，
按 RFC 1624 无需重算整个头：

```
HC' = ~( ~HC + ~m + m' )
```

- `HC` = 原校验和
- `m`  = 旧字段值（这里是旧 TTL，零扩展到 16 位）
- `m'` = 新字段值（TTL − 1）

**手算核对**（demo 验证脚本里被用作独立参考实现）：

| 量 | 值 |
|---|---|
| `HC` | `0x1234` |
| `~HC` | `0xEDCB` |
| `m = TTL = 64` | `0x0040`，`~m = 0xFFBF` |
| `~HC + ~m` | `0x1ED8A` → 截 16 位 `0xED8A` |
| `+ m' = 63 = 0x003F` | `0xEDC9` |
| `~` 取反 | **`0x1236`** |

IR 抽象求值给出的结果正是 `0x1236` —— 两条独立路径（手算/参考实现 vs. P4→IR 编译结果）一致。

> 注意：本 demo 只更新 IPv4 头校验和。**UDP/TCP 的校验和不受 TTL 影响**，
> 但若改了 IP 地址（本 demo 未改）则需要一并处理；这是本 demo 的已知边界。

---

## 5. 接口

| 端口 | 位宽 | 方向 | 内容 |
|---|---|---|---|
| `pkt_in` | 512 | in | 入包（报文窗口 512 位） |
| `phv_out` | 552 | out | headers + metadata 拼接 |
| `ex_portBytes` | 256 | out | Register：8 × 32 位每端口字节数 |
| `ex_fwdCnt` | 128 | out | Counter：4 × 32 位每类转发计数 |

**无 `tbl_*` 通道** —— 三张表全部是静态融合表（`const entries` 编译期展开），
查表逻辑在生成的 RTL 内部，没有外部表接口。

> 想改成控制面可写的运行时表：在 `table route_table` 的声明行之上加
> `// p4c: table route_table runtime size=16`，此后该表会生成
> `tbl_route_table_key` / `tbl_route_table_rsp` 通道，需要外部表模块（见 `docs/A3-EM模块设计.md`）
> 提供应答。本 demo 保持静态，是为了让仿真/验证不依赖外部表模型。

---

## 6. 实测结果

### 6.1 流程（`scripts/p4flow`）

| 步骤 | 状态 | 说明 |
|---|---|---|
| P4 前端解析 | OK | parser=Top control=Ingress tables=3 actions=7 |
| XLS IR 生成 | OK | state 43 项/1132 位 · 节点 536 · **II=1** |
| 函数级 IR dump | OK | 8 个 fn（供形式验证） |
| XLS parser 校验 | OK | IR 良构、往返一致（走真实 XLS parser） |
| IR 静态 lint | OK | 通过（0 issue） |
| 形式验证 | OK | 8 个 fn：**等价 16 · 不等价 0 · 未决 0**（z3 可用） |
| Verilog 生成 | OK | `Ingress_pipeline` 寄存器 47 个/1136 位 · 线网 293 · 792 行 |
| Chisel BlackBox | OK | 1 个 `.scala` |
| 仿真模型 | **SKIP** | 本机缺 `iverilog`/`vvp`（见 §6.3） |

### 6.2 IR 级行为验证（`scripts/demo12_verify.py`）

**30 条断言全部通过**，覆盖：

- L3 编辑四件套：TTL 64→63、校验和（与独立 RFC1624 参考实现比对）、
  下一跳 MAC、源 MAC；边界 TTL=1→0
- L2 转发：端口位图独热、端口号、字节统计、计数、**确认不改 TTL**
- 泛洪：位图 `0x000e`、无单一端口（索引 15）、计数
- 上三层：只置 `fwdType`，**不写 `outPort`**（门控设计成立）
- TTL 丢弃：`fwdType`/`dropReason`/计数
- 直通：`nop` 条目**零输出**（不命中就不改包）

这个脚本的价值在于：期望值**不是抄运行结果**——校验和的期望由 Python 里独立实现的
RFC 1624 公式给出，TTL/位图/统计由直接计算给出。两条独立路径得到同一结论才算验证。

### 6.3 为什么没有 Verilog 仿真

本机当前**没有 `iverilog` / `vvp`**（`/opt/homebrew/bin` 只有 `verilator` 与 `z3`），
所以 `p4flow` 的仿真步骤如实标记为 **SKIP**（不是通过）。
Verilog 与自动 TB 骨架都已生成，装上 `iverilog` 后 `scripts/p4flow` 即可跑仿真。

在此之前，行为正确性由 §6.2 的 IR 级验证承担 —— 它与 RTL 发射共用同一套语义
（`P4C.Interp` 的求值规则逐条对应 `ChiselBackend.nodeExpr`）。

---

## 7. 复现命令

```bash
cd <项目根>

# 一条命令跑完（前端 / IR / 校验 / lint / 形式验证 / Verilog / Chisel BB）
scripts/p4flow testcases/p4/demo12-l2l3-switch.p4 -o out/flow/demo12

# 行为验证（30 条断言）
python3 scripts/demo12_verify.py

# 单独看某个 action 的求值结果
scripts/p4xls eval-ir out/flow/demo12/ir_fn/*route_table_l3_forward.ir \
    --in hdr.ipv4.ttl=64 --in hdr.ipv4.hdrChecksum=0x1234 --in meta.ipLen=100
```

---

## 8. 本次顺带修掉的一个工具链 bug

用 48 位 MAC 常量（`0x00deadbeef01`）时，形式验证报

```
Demo12L2l3Switch__table_route_table_l3_forward.ir：解析失败 For input string: "956397711105"
```

根因：`IrText.scala` 解析 `literal(value=...)` 时走了 `intArg`（内部 `.toInt`），
**超过 32 位的常量必然溢出** —— 而 MAC 常量恰恰是最常见的大常量。
已改为按 `BigInt` 解析（支持十进制 / `0x` / `0b`）。

修前形式验证 `未决 1`，修后 **`等价 16 · 不等价 0 · 未决 0`**。

> 附带说明：`p4flow` 是 **fat jar 优先**，改完 Scala 必须 `sbt cli/assembly` 才生效 ——
> 这次先踩了一次"改了没生效"，重打包后才确认修复。
