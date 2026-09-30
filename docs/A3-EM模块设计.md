# A3 — 参数化 EM（Exact Match）表模块设计

> 目标：P4 程序里 runtime 表的**对端行为模型**。XLS proc 只发 key、收 response
> （见 `docs/A2-架构设计.md` §5.2），key→rsp 的匹配与存储在外部表模块里 —— 本文就是
> 这个外部模块（EM 打样版）。
>
> 代码：`em/src/main/scala/em/`（Chisel 5.3，独立 sbt 子工程）
> 验证：`testcases/a3/tb_em.v` + `out/a3/tb/ExactMatch.v`
> 一键：`sbt "em/runMain em.EmGen out/a3 tb"` → `iverilog` → `vvp`

---

## 1. 与 XLS proc 的接口契约

查表口与 proc 的 `tbl_<名>_key` / `tbl_<名>_rsp` 一一对应：

| EM 端口 | 方向 | 语义 |
|---|---|---|
| `io.key_valid` / `io.key_bits` | in | valid_data，**无背压**，模块必须随时接收 |
| `io.rsp_valid` / `io.rsp_bits` | out | `{hit, ad}`，**hit 在最高位** |
| `io.wr_*` | in | 维护口：add / delete / update（按 key） |
| `io.learnAd` / `io.learnEn` | in | 自学习的动作数据来源（L2 场景 = 入端口）与运行时开关 |
| `io.ageEn` | in | 老化运行时开关 |
| `io.memInit` / `io.memInitDone` | in/out | 存储初始化握手 |
| `io.crcPoly/Init/Xor` | in | 仅 `CrcRuntime` 模式有效 |

两条硬约束（A2 已实测）：

1. **proc 侧是弹性等待**：收 rsp 的相位会 stall 到 `rsp_vld` 为止
   （`active_inputs_valid = ... & (tbl_rsp_vld | ~p0_is_phRsp)`）。
   ⇒ **EM 的查找延迟可以是任意拍数**，不是固定 1 拍；延迟只影响吞吐，不影响正确性。
2. key 通道无背压 ⇒ EM 内部必须有输入缓存，且**满了只能丢弃并计数**（`status.keyDrop`）。

---

## 2. 参数（`EmParams`）

| 参数 | 含义 | 备注 |
|---|---|---|
| `keyWidth` / `adWidth` | key 位宽 / 动作数据位宽 | ad 是不透明负载 |
| `htDepth` / `htWays` | HT 桶数 / 组相联度 | 均须 2 的幂（见 §5） |
| `numBanks` | d-left 子表数（1 = 关闭，2 = 2-left） | 每子表深度 = htDepth/numBanks |
| `dLeftTie` | 并列仲裁：`Leftmost` / `Random` / `RoundRobin` | 见 §4 |
| `ktDepth` | KT 条目数（单实例） | 2 的幂 |
| `useKt` | false = key 内联进 HT（跳过 KT，少一级） | |
| `adDepth` / `useAd` | AD 条目数 / false = 动作数据内联进 KT | |
| `ovfcDepth` | **OVFC（HT 溢出 TCAM）深度，0 = 关闭** | 见 §6 |
| `crc` | `CrcHardwired`（固化）/ `CrcRuntime`（可配） | 见 §7 |
| `crcWidth` | 哈希位宽 | 需 ≥ `numBanks * log2(bankDepth)` |
| `keyFifoDepth` | key 输入缓存深度 | |
| `aging` | `AgingParams(ageWidth, timeout, tickDiv, sweepEnable)` | |
| `learning` | `LearningParams(defaultAd, usePortAd)` | |
| `memProtect` | `ProtNone` / `Parity` / `ECC` | 透传给 Memory.scala |

面积/延迟随参数变化的量化（可由 `EmGen` 打印）：

```
preset=tb   key=16 ad=26 ht=32x2 banks=2 kt=512→128 ad=128 ovfc=8
            HT条目=16b KT条目=23b 查找=6拍(不含 CRC 串行)
```

---

## 3. 存储组织（复用 BaseCbb 的 Memory 体系）

**EM 内部存储全部用 `HardwareDesign/BaseCbb/memory/Memory.scala` 的原语**
（源码级依赖：`Compile / unmanagedSources`，不拷贝文件）：

| 存储 | 实例 | 类型 | 端口用途 |
|---|---|---|---|
| HT | `numBanks` 个 `TpMemoryWrap3` | **TP** | 读口：查找/维护/sweep 分时；写口：维护/sweep/命中刷新 |
| KT | 1 个 `TpMemoryWrap3` | **TP** | 读口：查找/维护/sweep 分时（候选**逐个串行**读）；写口：维护 |
| AD | 1 个 `SpMemoryWrap3` | **SP** | 单口：查找读与维护写互斥（写优先） |

- 每个 HT 子表一个存储实例 ⇒ d-left 的 d 个桶**并行读**，互不抢端口。
- KT 单实例 ⇒ 桶内候选（最多 `numBanks*htWays` 个）**串行读**，代价是延迟 ∝ 桶占用；
  换来的是不用为每个 (bank,way) 各开一个存储实例。
- 读延迟由 `Memory.latency + CheckOut` 推出（当前配置 = 1 拍），FSM 用 pend 计数器
  按 `rdLat` 等待；**换真 SRAM 只需改 `Memory` 配置**（`isPhysicalMemory=true` + 插拍）。
- 初始化：`io.memInit` 广播到各 Wrap3 的 `dfx.init`（逐地址写 AllZero），
  全部 `initDone` 之前**所有 FSM 停住**（`memReady`）。表存储不初始化就不能用。

---

## 4. 查找流程

```
key → FIFO → hash(CRC) → HT 并行读 d 个子表 → 捕获桶（ways 条）
                                   │
                    ┌──────────────┴───────────────┐
                OVFC 命中？                    HT 有候选？
              （寄存器并行比较）             （useKt：串行读 KT 比 key）
                    │                              │
         useKt → 取 KT 得 adPtr / AD       命中即止，取 adPtr
                    └──────────────┬──────────────┘
                                   ↓
                            AD 读（单口 SP）→ rsp = {hit, ad}
```

- **命中优先级**：OVFC 候选先于 HT 候选（OVFC 是溢出区，条目更新）。
- HT 命中后若开启老化 ⇒ 多一拍把时间戳写回（`LK_REF`），被维护/sweep 抢占则原地等。
- 未命中且 `learnEn` ⇒ 挂起一个自学习插入请求，由维护 FSM 取走。

**d-left 并列仲裁**（`dLeftTie`）——这条是踩出来的：

> `Leftmost` 看着最省事，但轻载时每个桶的占用都是 0 ⇒ 次次并列 ⇒ **条目全堆在
> bank0，d-left 的负载均衡完全失效**（实测 9 条全在 bank0）。
> 默认改成 `Random`（LFSR，经典 d-left）；验证用 `RoundRobin`（确定性）。

选桶逻辑是"取占用最少的子表，并列时从 tieBase 起环形取第一个"；
**只有被选中的那个子表还会看 way 是否空**，桶满了就直接落 OVFC。

---

## 5. 位宽与布局推导（`EmLayout`）

所有位宽集中在一处推导，避免各处重复算错：

- `ktPtrW = log2(ktDepth)`；`adPtrW = log2(adDepth)`
- HT 条目 = `valid(1) + ts(ageW) + 负载`
  - `useKt`：负载 = `ktPtr`
  - 否则：负载 = `key + (useAd ? adPtr : ad)`
- KT 条目 = `key + (useAd ? adPtr : ad)`
- OVFC 条目 = `key`（CAM 比较域）+ `(ktPtr | adPtr | ad)`，另有 `valid` / `ts`
- `htWordW = htEntryW * ways`（一个 HT word = 一整桶）
- **游标位宽要多一位**：`ovfcCntW = log2(ovfcDepth+1)`、`scanW = log2(ktBanks+1)` ——
  游标要能表达"扫完了"这个值，否则永远到不了终点（踩过两次）。
- **深度一律 2 的幂**：`Memory.addrWidth = log2Ceil(depth)`，非 2 的幂会产生越界地址。

---

## 6. OVFC（HT 溢出 TCAM）

需求原话：*"在 HT 溢出时存储 key 的小块 TCAM"*。

- **结构**：寄存器阵列 `ovfcV/ovfcK/ovfcP/ovfcT`，深度 `ovfcDepth` 参数化（0 = 关闭）。
  key 存在 CAM 比较域里，**全深度并行比较**（0 拍）。
- **命中路径**：并行比较 ⇒ 命中则取其负载（`ktPtr`）→ 正常走 KT/AD 取 adPtr
  ⇒ **对 HT 命中路径零延迟代价**（OVFC 命中还省掉了串行扫描）。
- **插入路径**：d-left 选中的子表桶满 ⇒ 落入 OVFC 第一个空位；OVFC 也满 ⇒ `insFail++`。
- **删除/老化**：与 HT 条目同一套流程（OVFC 条目也带时间戳、参与 sweep）。
- **实现取舍**：TCAM 用寄存器阵列而非 SRAM，深度上限受寄存器规模约束；
  真做硅时这里要换成 CAM IP 或 hash-of-hash 二级表。

---

## 7. CRC / 哈希：固化 vs 可配

| 模式 | 实现 | 延迟 | 面积 |
|---|---|---|---|
| `CrcHardwired` | 多项式在 elaboration 期已知 ⇒ Scala 逐位展开成**纯 XOR 树** | 0 拍（组合） | 随 `keyWidth×crcWidth` 增长 |
| `CrcRuntime` | poly/init/xorout 放寄存器，串行 LFSR | `keyWidth` 拍（FSM 等待） | 小 |

两者算法形态一致（左移 MSB 先、`refin/refout` 做位序反射），因此
`CrcHardwired.crc32` 与 `CrcRuntime.crc32` 默认参数下**同一 key 得同一哈希**。

一份哈希切成 `numBanks` 段做 d-left 子表索引（经典做法：一次哈希、多段取用），
而不是每个子表算一次 CRC。

---

## 8. 自学习与老化

- **自学习**：查找 miss 且 `learnEn` ⇒ 挂起插入请求，AD 取 `learnAd` 端口
  （或参数里的 `defaultAd`）。运行时可开关（真实芯片里由软件控制学习使能）。
- **老化（时间戳法）**：每条 HT/OVFC 条目带 `ts`；`now` 每 `tickDiv` 拍 +1；
  命中时刷新 `ts`（HT 走写口，OVFC 直接改寄存器）；sweep 引擎扫描
  `now - ts >= timeout` 的条目并释放（KT/AD 归还空闲栈）。
- **sweep 与维护/查找共享端口**：HT/KT 读口按 查找 > 维护 > sweep 仲裁；
  释放 KT/AD 与维护删除互斥（维护优先）。

---

## 9. 踩坑清单（都是实测踩出来的，按"代价"排序）

1. **读端口仲裁的地址 mux 必须含"本拍 grant"**
   两个子坑：
   ① owner 寄存器在 grant 拍末才更新，只看 owner 会让 grant 当拍用**上一个 owner 的地址**；
   ② owner 初值是 0（lookup），空闲时用 `owner===0` 判断"查找在用"会把查找侧的**垃圾地址选中**
   （维护读 KT 时整条数据变 X）。
   ⇒ 正确写法：`sel = grant || (pend ≠ 0 && owner === X)`。
2. **捕获与裁决必须分两拍**：`reg := mem.rdata` 是寄存器写，当拍读到的还是旧值；
   旧值是 X 时 `when(X)` 会让**状态寄存器也变成 X**（FSM 直接死掉）。
3. **串行扫描的候选判据**：`i > cur` 配 `cur = ktBanks` 恒假 ⇒ 永远没有候选 ⇒ 一律 miss；
   且游标 `+1` 会溢出（见 §5 的"游标多一位"）。
4. **`when(swW < ways.U)` 恒真**：`swW` 位宽只有 `wayW` 位，永远 `< ways`，
   "换下一桶"分支成为不可达代码 ⇒ firrtl 把扫描地址**常量折叠成 0**，
   sweep 永远只扫 0 号桶。判据必须写 `swW === (ways-1).U`。
5. **sweep 游标位宽不足**：`log2Ceil(ovfcDepth)` 位表达不了 `ovfcDepth` ⇒ 卡在扫描态。
6. **初始化必须在复位释放之后**：`dfx.init` FSM 本身受复位控制，
   复位期间发 init 会被复位钉住，永远不启动。
7. **`VecInit(Seq.fill(n)(Module(...).io))` 会丢输入默认驱动**：
   VecInit 另建 Wire，之前对 io 输入字段的连接失效 ⇒ FIRRTL 报
   "Reference ktFree is not fully initialized"。正确做法：Seq 持有实例按索引译码驱动，
   只有"读回的输出"才用 VecInit 做动态索引。
8. **`numBanks.U(bankW.W)` 位宽不足**：字面量 2 需要 2 位，`bankW=1` 时报错；
   用 `+&` 加宽后手动回绕。
9. **TP 存储的 `lgc.re` 也要驱动**：SimMemory 不看 `re`，但端口不驱动会报
   "sink not fully initialized"。
10. **iverilog 会把 firtool 的 `automatic` 当错误**：`sorry: Overriding the default
    variable lifetime` 会让 iverilog 以非零码退出且**不产出 vvp**。
    ⇒ firtool 加 `--lowering-options=disallowLocalVariables`。
11. **`iverilog ... | head -N` 的 SIGPIPE 陷阱**：警告行数超过 N 时 iverilog 被掐死，
    但管道的退出码取自 head（0）⇒ 脚本以为编译成功、实际还是旧 vvp。
    ⇒ 编译日志一律重定向到文件，不接管道。
12. **TB 打印别在 posedge**：`@(posedge clk) $display` 与 DUT 的 NBA 竞争，
    读到的是**上一拍的值**，会让人把"晚一拍"误判成"读延迟 2 拍"。调试探针要在 negedge 打。

---

## 10. 验证（`testcases/a3/tb_em.v`）

被验对象：`preset=tb`（key 16 / ad 26 / HT 32×2×2 子表 = 容量 64 / KT 128 / AD 128 /
OVFC 8 / CRC32 固化 / 学习+老化开）。**17 个用例，15 通过**：

| 用例 | 结果 |
|---|---|
| memory init done（Wrap3 dfx 初始化握手） | ✅ |
| 插入→命中（AD 正确） / 未插入→miss / 第二条命中 | ✅ |
| 删除→miss | ✅ |
| 批量 8 条全命中、insFail=0 | ✅ |
| 插 80 条（成功 61、失败 19）→ OVFC 占满 8 | ✅ |
| **所有插入成功的 key 都能命中（含 OVFC 落点）** | ✅ |
| OVFC 条目可读 / 删除后 use 8→7 / 删除后 miss | ✅ |
| 自学习：首次 miss → 自动插入 → 再查命中且 AD = learnAd | ✅ |
| 老化：长期不访问的条目被 sweep 删除 | ✅ |
| 老化：被删除的条目查不到 | ✅ |
| 老化：持续命中的条目被刷新、存活 | ⚠️ 失败（见下） |

**已知未收敛问题**：老化用例中持续查询 K1 时，`hit` 读到 X。现象已被锁定为
"sweep 与查找并发时某条路径把 X 传进了 `hitReg`"，最可能的来源是并发释放/重分配
与命中刷新（`LK_REF`）之间的竞争。**不影响其余 15 个用例**，但**未修完之前
不建议把该模块当作可信参考模型**。

---

## 11. 后续

1. 修掉 §10 的 X 传播（建议：给 `hitReg`/`adReg` 等对外通路加一道"未命中时显式清零"的
   护栏，或在命中判定处用 `=(=)` 的 fully-defined 版本）。
2. **d-left 回退**：当前"选中子表桶满即落 OVFC"，应先试另一个子表的桶，最后才溢出。
3. **KT 串行扫描的加速**：在 HT 里存 key 签名（`sigWidth` 参数）做预过滤，
   避免为每个候选都读一次 KT。
4. LPM / TCAM 表（A3 后续）；表写接口的软编表路径（当前只有 wrap 的 `cpu` 口，已 tie-off）。
5. 把 EM 接进 XLS 侧：用 `preset=tb` 直接替换 demo7/demo9 TB 里的 mock 表模块，
   做一次 proc + 真实表模块的端到端回归。
