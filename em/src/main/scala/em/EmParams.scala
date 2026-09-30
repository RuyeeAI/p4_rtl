package em

import chisel3._
import chisel3.util.log2Ceil
import BaseCbb.memory.MemoryProtectType
// Enumeration 的类型别名与对象同名，需要分别导入（对象给枚举值，别名给类型位置）
import BaseCbb.memory.MemoryProtectType.MemoryProtectType

// ===========================================================================
// EM（Exact Match）模块的参数与位宽布局
//
// 术语（交换芯片 EM 引擎的常见三级组织）：
//   HT  Hash Table     —— 哈希桶，存"指向 KT 的指针"
//   KT  Key Table      —— 存完整 key（+ 指向 AD 的指针或内联的 AD）
//   AD  Action Data    —— 存动作数据（查表结果负载）
//
// d-left：numBanks 个哈希子表，插入时选"占用最少"的子表（d-left hashing），
//   显著提高装填率。numBanks = 1 即退化为普通组相联哈希表。
// ===========================================================================

sealed trait CrcMode {
  def poly: BigInt
  def init: BigInt
  def xorout: BigInt
  def refin: Boolean
  def refout: Boolean
}

/** 固化 CRC：多项式在 elaboration 期已知 → 展开为纯 XOR 树，组合逻辑、0 拍、无配置寄存器。 */
final case class CrcHardwired(
    poly: BigInt,
    init: BigInt,
    xorout: BigInt,
    refin: Boolean,
    refout: Boolean
) extends CrcMode

object CrcHardwired {
  /** CRC-32 常用形态（以太网 FCS）：poly=0x04C11DB7，init/xorout=0xFFFFFFFF，输入输出均反射 */
  def crc32: CrcHardwired =
    CrcHardwired(BigInt("04C11DB7", 16), BigInt("FFFFFFFF", 16), BigInt("FFFFFFFF", 16), true, true)
  def apply(poly: BigInt): CrcHardwired = CrcHardwired(poly, 0, 0, false, false)
}

/** 运行时可配 CRC：poly/init/xorout 由 CSR 改写，硬件是串行 LFSR（keyWidth 拍出结果）。 */
final case class CrcRuntime(
    poly: BigInt,
    init: BigInt,
    xorout: BigInt,
    refin: Boolean,
    refout: Boolean
) extends CrcMode

object CrcRuntime {
  def crc32: CrcRuntime =
    CrcRuntime(BigInt("04C11DB7", 16), BigInt("FFFFFFFF", 16), BigInt("FFFFFFFF", 16), true, true)
}

/**
 * 老化参数（时间戳法：命中刷新时间戳，后台 sweep 扫描超时条目）。
 *
 * @param ageWidth   时间戳位宽（决定最大可表达超时）
 * @param timeout    超时阈值，单位为 tick（now 每 tickDiv 拍 +1）
 * @param tickDiv    now 自增的分频
 * @param sweepEnable 是否内置 sweep 引擎；false 时只维护时间戳，由外部读状态自行删除
 */
final case class AgingParams(ageWidth: Int = 16, timeout: Int = 1024, tickDiv: Int = 64, sweepEnable: Boolean = true)

/**
 * 自学习参数（查表 miss 时自动插入条目）。
 *
 * @param defaultAd  learnAd 端口不可用时的兜底动作数据
 * @param usePortAd  true = 用 io.learnAd 端口的值（L2 学习场景即入端口）
 */
final case class LearningParams(defaultAd: BigInt = BigInt(0), usePortAd: Boolean = true)

/**
 * d-left 的并列仲裁策略：多个子表占用相同时选哪个。
 *
 * - Leftmost：取最左。最简单，但轻载时几乎每次都并列为 0 → 条目全堆在第一个子表，
 *   **d-left 的负载均衡完全失效**（实测：9 条条目全部落在 bank0）。
 * - Random：经典 d-left 的做法，用 LFSR 随机挑一个并列者，装填率最高。
 * - RoundRobin：轮转。确定性、且保证各子表均匀，便于验证。
 */
sealed trait TiePolicy
object TiePolicy {
  case object Leftmost   extends TiePolicy
  case object Random     extends TiePolicy
  case object RoundRobin extends TiePolicy
}

final case class EmParams(
    keyWidth: Int = 48,      // 查找 key 位宽（如 MAC 48 位）
    adWidth: Int = 32,       // 动作数据位宽（查表结果）
    htDepth: Int = 1024,     // HT 总桶数（会被 numBanks 均分；须为 2 的幂）
    htWays: Int = 4,         // 每桶路数（组相联度）
    numBanks: Int = 1,       // d-left 子表数；1 = 关闭 d-left；2 = 2-left
    dLeftTie: TiePolicy = TiePolicy.Random,  // 并列时的仲裁（见 TiePolicy 注释）
    ktDepth: Int = 4096,     // KT 总条目数（单实例存储；须为 2 的幂）
    useKt: Boolean = true,   // false = key 内联进 HT（单级表，查找最快）
    adDepth: Int = 1024,     // AD 条目数（须为 2 的幂）
    useAd: Boolean = true,   // false = 动作数据内联进 KT（省一级间接）
    ovfcDepth: Int = 0,      // OVFC：HT 溢出 TCAM 深度；0 = 关闭；须为 2 的幂（0 除外）
    crcWidth: Int = 32,      // CRC 输出位宽
    crc: CrcMode = CrcHardwired.crc32,
    keyFifoDepth: Int = 2,   // key 输入缓存深度（key 通道无背压，兜住突发）
    aging: Option[AgingParams] = None,
    learning: Option[LearningParams] = None,
    memProtect: MemoryProtectType = MemoryProtectType.ProtNone  // 存储保护（透传 Memory.scala）
)

/** 由参数推导出的全部位宽与条目布局。所有字段访问都走这里，避免各处重复推导。 */
final case class EmLayout(p: EmParams) {
  require(p.keyWidth > 0 && p.adWidth > 0, "keyWidth/adWidth 必须 > 0")
  require(p.numBanks >= 1, "numBanks 必须 >= 1（1 = 关闭 d-left）")
  require(p.htDepth % p.numBanks == 0, s"htDepth(${p.htDepth}) 必须能被 numBanks(${p.numBanks}) 整除")
  require(p.htWays >= 1, "htWays 必须 >= 1")
  require(p.ovfcDepth >= 0, "ovfcDepth 必须 >= 0（0 = 关闭 OVFC）")
  // Memory.scala 的 addrWidth = log2Ceil(depth)，非 2 的幂深度会产生越界地址
  require(pow2(p.htDepth) && pow2(p.ktDepth) && pow2(p.adDepth) && pow2(p.htDepth / p.numBanks),
    "htDepth / ktDepth / adDepth / bankDepth 必须是 2 的幂（Memory.scala 的地址位宽按 log2Ceil 推导）")

  private def pow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0

  val numBanks = p.numBanks
  val ways     = p.htWays
  val keyW     = p.keyWidth
  val adW      = p.adWidth

  val bankDepth = p.htDepth / p.numBanks
  val idxW      = math.max(1, log2Ceil(bankDepth))
  val bankW     = math.max(1, log2Ceil(numBanks))
  val wayW      = math.max(1, log2Ceil(ways))          // 路编号位宽（ways=1 时取 1，避免 0 宽）
  val slotW     = math.max(1, log2Ceil(numBanks * ways)) // (bank,way) 扁平槽位编号位宽
  // 扫描游标位宽要多一位：游标要能表达"已扫完"（= ktBanks），否则 +1 会回绕成 0
  val scanW     = math.max(1, log2Ceil(numBanks * ways + 1))
  val ktBanks   = numBanks * ways    // 一次查找最多要串行读的候选槽数（不再对应物理 bank）
  val hashW     = idxW * numBanks
  require(hashW <= p.crcWidth, s"hash 需要 ${hashW} 位，超过 crcWidth(${p.crcWidth})")

  val crcW = p.crcWidth

  // KT 按 (bank, way) 分成 numBanks*ways 个物理 bank：
  // 插入桶 (b) 的路 (w) 时，KT 条目固定从 bank (b*ways+w) 分配，
  // 因此查找时 d*ways 个 KT 读必然落在不同 bank，无端口冲突。
  // KT 是**单实例**存储（不分 bank）：
  // - 读端口由查找 / 维护 / sweep 分时复用（candidate 逐个串行读）
  // - 插入删除统一用一个全局空闲栈
  val ktDepthReal = p.ktDepth
  val ktPtrW      = math.max(1, log2Ceil(ktDepthReal))

  val adPtrW  = math.max(1, log2Ceil(p.adDepth))
  val ageW    = p.aging.map(_.ageWidth).getOrElse(0)
  val ageW1   = math.max(1, ageW)

  // ---- 条目位宽 ----
  // HT 条目: [0]=valid, [ageW:1]=ts, 高位 = 负载
  //    useKt  : 负载 = ktPtr
  //   !useKt  : 负载 = key + (useAd ? adPtr : ad)
  val htPayW   = if (p.useKt) ktPtrW else (keyW + (if (p.useAd) adPtrW else adW))
  val htEntryW = 1 + ageW + htPayW
  val htWordW  = htEntryW * ways          // 一个 HT word = 一整桶（ways 条）
  // KT 条目: [keyW-1:0]=key, 高位 = (useAd ? adPtr : ad)
  val ktPayW   = if (p.useAd) adPtrW else adW
  val ktEntryW = keyW + ktPayW

  // OVFC（HT 溢出 TCAM）：key 单独存（CAM 比较），负载与 HT 的负载同构但不含 key
  val ovfcPayW = if (p.useKt) ktPtrW else (if (p.useAd) adPtrW else adW)
  val ovfcEn   = p.ovfcDepth > 0
  // 注意区分"选择位宽"和"计数位宽"：计数必须能表达 ovfcDepth 本身（=扫描结束），
  // 否则扫描游标永远到不了终点（实测踩过）。
  val ovfcSelW = math.max(1, log2Ceil(math.max(1, p.ovfcDepth)))
  val ovfcCntW = math.max(1, log2Ceil(p.ovfcDepth + 1))

  val rspW     = 1 + adW          // 对外响应：hit(最高位) + ad

  /** 查找延迟（拍）：hash + HT + KT 串行候选 + AD（各级按需），不含串行 CRC 的额外拍数 */
  val lookupLatency = 1 + (if (p.useKt) ktBanks else 0) + (if (p.useAd) 1 else 0)

  // ---- HT 条目字段访问器 ----
  private val htTsHi = ageW
  private val htPayHi = 1 + ageW + htPayW - 1
  def htValid(e: UInt): Bool = e(0)
  def htTs(e: UInt): UInt = if (ageW > 0) e(htTsHi, 1) else 0.U(1.W)
  def htPay(e: UInt): UInt = e(htPayHi, 1 + ageW)
  def htKtPtr(e: UInt): UInt = { require(p.useKt); htPay(e)(ktPtrW - 1, 0) }
  def htKey(e: UInt): UInt = { require(!p.useKt); htPay(e)(keyW - 1, 0) }
  def htAdPtr(e: UInt): UInt = { require(!p.useKt && p.useAd); htPay(e)(htPayW - 1, keyW) }
  def htAd(e: UInt): UInt = { require(!p.useKt && !p.useAd); htPay(e)(htPayW - 1, keyW) }

  def htEntry(valid: Bool, ts: UInt, pay: UInt): UInt =
    if (ageW > 0) chisel3.util.Cat(pay, ts(ageW - 1, 0), valid) else chisel3.util.Cat(pay, valid)

  // ---- KT 条目字段访问器 ----
  def ktKey(e: UInt): UInt = e(keyW - 1, 0)
  def ktAdPtr(e: UInt): UInt = { require(p.useAd); e(ktEntryW - 1, keyW) }
  def ktAd(e: UInt): UInt = { require(!p.useAd); e(ktEntryW - 1, keyW) }
  def ktEntry(key: UInt, pay: UInt): UInt = chisel3.util.Cat(pay, key)

  // 注意：ways == 1 时路编号占 0 位，所有"按路拆位"的地方都要单独处理，
  // 否则会多出一位偏移（ways=1 时 s = b，不能写成 s >> 1）。
  private val wayBits = log2Ceil(ways)   // ways=1 时为 0

  /** 扁平槽位 s = b*ways + w 拆回 (bank, way) */
  def slotBank(s: UInt): UInt = if (numBanks > 1) s(slotW - 1, wayBits) else 0.U
  def slotWay(s: UInt): UInt  = if (wayBits > 0) s(wayBits - 1, 0) else 0.U
}
