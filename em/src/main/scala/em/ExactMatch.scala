package em

import chisel3._
import chisel3.util._
import BaseCbb.memory._

// ===========================================================================
// EM（Exact Match）表模块
//
// 存储全部复用 HardwareDesign/BaseCbb 的 Memory 体系：
//   HT —— numBanks 个 TpMemoryWrap3（每 d-left 子表一个；双口：查找读/维护写并行）
//   KT —— 1 个 TpMemoryWrap3（读端口由 查找/维护/sweep 分时复用，候选逐个串行读）
//   AD —— 1 个 SpMemoryWrap3（单口：查找读与维护写互斥，写优先）
//
// OVFC（可配）：HT 溢出 TCAM —— 桶满时新条目落到这里；寄存器阵列并行比较，
//   深度 ovfcDepth 参数化（0 = 关闭）。查找时 OVFC 候选先于 HT 候选。
//
// 时序模型：SimMemory 读 1 拍有效（flopIn/flopOut/CheckOut 全关），
//   FSM 用 pend 计数器按 rdLat 等待；换真 SRAM 只改 Memory 配置。
//
// 初始化：io.memInit 广播到各存储的 dfx.init；全部 initDone（io.memInitDone）
//   之前所有 FSM 停住 —— 表存储必须先初始化才能用。
// ===========================================================================

object EmOp {
  val add = 0.U(2.W)   // 插入（已存在则覆盖动作数据）
  val del = 1.U(2.W)   // 删除
  val upd = 2.U(2.W)   // 更新（不存在则记失败）
}

class EmWrCmd(l: EmLayout) extends Bundle {
  val op  = UInt(2.W)
  val key = UInt(l.keyW.W)
  val ad  = UInt(l.adW.W)
}

class EmStatus(l: EmLayout) extends Bundle {
  val entries = UInt(log2Ceil(l.p.htDepth * l.p.htWays + l.p.ovfcDepth + 1).W)
  val insert  = UInt(16.W)
  val insFail = UInt(16.W)
  val delete  = UInt(16.W)
  val learn   = UInt(16.W)
  val ageDrop = UInt(16.W)
  val keyDrop = UInt(16.W)
  val ovfcUse = UInt(math.max(1, log2Ceil(l.p.ovfcDepth + 1)).W)
  val lkBusy  = Bool()
  val mtBusy  = Bool()
}

class EmIo(l: EmLayout) extends Bundle {
  // ---- 查表口（与 XLS proc 的 tbl_<名>_key / tbl_<名>_rsp 对接）----
  val key = Input(Valid(UInt(l.keyW.W)))
  val rsp = Output(Valid(UInt(l.rspW.W)))
  // ---- 维护口（控制面 / 软件下发）----
  val wr     = Input(Valid(new EmWrCmd(l)))
  val wrBusy = Output(Bool())
  // ---- 自学习 / 老化运行时开关 ----
  val learnAd = Input(UInt(l.adW.W))
  val learnEn = Input(Bool())
  val ageEn   = Input(Bool())
  // ---- 存储初始化 ----
  val memInit     = Input(Bool())
  val memInitDone = Output(Bool())
  // ---- CRC 配置（仅 CrcRuntime 模式生效）----
  val crcPoly = Input(UInt(l.crcW.W))
  val crcInit = Input(UInt(l.crcW.W))
  val crcXor  = Input(UInt(l.crcW.W))
  // ---- 状态与统计 ----
  val status = Output(new EmStatus(l))
}

class ExactMatch(params: EmParams) extends Module {
  val l = EmLayout(params)
  val io = IO(new EmIo(l))

  val numBanks = l.numBanks
  val ways     = l.ways
  val ktBanks  = l.ktBanks
  val keyW     = l.keyW
  val adW      = l.adW
  val useKt    = params.useKt
  val useAd    = params.useAd
  val ovfcEn   = l.ovfcEn
  val ovfcD    = params.ovfcDepth

  // =========================================================================
  // 存储实例（BaseCbb / Memory.scala）
  // =========================================================================
  private def memCfg(name: String, width: Int, depth: Int, tp: Boolean): Memory = Memory(
    name       = name,
    dataType   = UInt(width.W),
    depth      = depth,
    memoryType = if (tp) MemoryAccessType.TP else MemoryAccessType.SP,
    protect    = params.memProtect,
    flopIn     = false,
    flopOut    = false,
    CheckIn    = false,
    CheckOut   = false,
    RsAccess   = false,
    initValue  = MemoryInitType.AllZero
  )

  private def rdLat(m: Memory): Int = m.latency + (if (m.CheckOut) 1 else 0)

  private val htLat = rdLat(memCfg("EmHt", l.htWordW, l.bankDepth, tp = true))
  private val ktLat = rdLat(memCfg("EmKt", l.ktEntryW, l.ktDepthReal, tp = true))
  private val adLat = rdLat(memCfg("EmAd", l.adW, params.adDepth, tp = false))

  private def driveAux(m: TpMemoryWrap3): Unit = {
    m.io.cpu.we := false.B
    m.io.cpu.re := false.B
    m.io.cpu.addr := 0.U
    m.io.cpu.wdata := 0.U
    m.io.cpuCfg.idleCycleTh0 := 0.U
    m.io.dfx.init := io.memInit
    m.io.dfx.injCorrEn := false.B
    m.io.dfx.injUerrEn := false.B
  }

  private def driveAux(m: SpMemoryWrap3): Unit = {
    m.io.cpu.we := false.B
    m.io.cpu.re := false.B
    m.io.cpu.addr := 0.U
    m.io.cpu.wdata := 0.U
    m.io.cpuCfg.idleCycleTh0 := 0.U
    m.io.dfx.init := io.memInit
    m.io.dfx.injCorrEn := false.B
    m.io.dfx.injUerrEn := false.B
  }

  val htMems = Seq.tabulate(numBanks) { b =>
    val m = Module(new TpMemoryWrap3(memCfg(s"EmHt$b", l.htWordW, l.bankDepth, tp = true)))
    driveAux(m)
    m
  }
  val ktMem = Module(new TpMemoryWrap3(memCfg("EmKt", l.ktEntryW, l.ktDepthReal, tp = true)))
  driveAux(ktMem)
  val adMem = Module(new SpMemoryWrap3(memCfg("EmAd", l.adW, params.adDepth, tp = false)))
  driveAux(adMem)

  private val memReady = htMems.map(_.io.dfx.initDone).reduce(_ && _) &&
    ktMem.io.dfx.initDone && adMem.io.dfx.initDone && !io.memInit
  io.memInitDone := memReady

  // =========================================================================
  // OVFC：HT 溢出 TCAM（寄存器阵列，并行比较）
  // =========================================================================
  val ovfcV = if (ovfcEn) RegInit(VecInit(Seq.fill(ovfcD)(false.B))) else null
  val ovfcK = if (ovfcEn) Reg(Vec(ovfcD, UInt(keyW.W))) else null
  val ovfcP = if (ovfcEn) Reg(Vec(ovfcD, UInt(math.max(1, l.ovfcPayW).W))) else null
  val ovfcT = if (ovfcEn) Reg(Vec(ovfcD, UInt(l.ageW1.W))) else null
  val ovfcUseCnt = if (ovfcEn) RegInit(0.U(math.max(1, log2Ceil(ovfcD + 1)).W)) else 0.U(1.W)

  /** 对某个 key 做 OVFC 并行匹配（未启用时恒 miss） */
  def ovfcMatch(key: UInt): (Bool, UInt) =
    if (!ovfcEn) (false.B, 0.U(1.W))
    else {
      val hit = VecInit((0 until ovfcD).map(i => ovfcV(i) && ovfcK(i) === key))
      (hit.asUInt.orR, PriorityEncoder(hit))
    }

  // =========================================================================
  // 空闲条目栈
  // =========================================================================
  val ktFree = Module(new FreeList(l.ktDepthReal))
  ktFree.io.alloc := false.B
  ktFree.io.free  := false.B
  ktFree.io.faddr := 0.U
  val adFree = Module(new FreeList(params.adDepth))
  adFree.io.alloc := false.B
  adFree.io.free  := false.B
  adFree.io.faddr := 0.U

  // =========================================================================
  // 哈希
  // =========================================================================
  val useSerial = params.crc.isInstanceOf[CrcRuntime]
  val lkCrc = if (useSerial) Some(Module(new CrcSerial(l.crcW, keyW))) else None
  val mtCrc = if (useSerial) Some(Module(new CrcSerial(l.crcW, keyW))) else None
  Seq(lkCrc, mtCrc).flatten.foreach { m =>
    m.io.start := false.B
    m.io.din   := 0.U
    m.io.poly  := io.crcPoly
    m.io.init  := io.crcInit
    m.io.xor   := io.crcXor
  }

  def hardHash(d: UInt): UInt = {
    val c = params.crc
    Crc.hardwired(d, l.crcW, c.poly, c.init, c.xorout, c.refin, c.refout)
  }

  /** 一份哈希切成 numBanks 个子表索引（d-left：一次哈希、多段取用） */
  def slices(h: UInt): Vec[UInt] =
    VecInit((0 until numBanks).map(b => h(math.min(l.crcW - 1, (b + 1) * l.idxW - 1), b * l.idxW)))

  // =========================================================================
  // 时间基准（老化）
  // =========================================================================
  val agingCfg = params.aging
  val now = if (agingCfg.isDefined) RegInit(0.U(l.ageW1.W)) else WireDefault(0.U(1.W))
  if (agingCfg.isDefined) {
    val tick = RegInit(0.U(32.W))
    when(tick === (agingCfg.get.tickDiv - 1).U) { tick := 0.U; now := now + 1.U }
    .otherwise { tick := tick + 1.U }
  }

  // =========================================================================
  // 统计
  // =========================================================================
  val cntInsert  = RegInit(0.U(16.W))
  val cntInsFail = RegInit(0.U(16.W))
  val cntDelete  = RegInit(0.U(16.W))
  val cntLearn   = RegInit(0.U(16.W))
  val cntAgeDrop = RegInit(0.U(16.W))
  val cntKeyDrop = RegInit(0.U(16.W))
  val entryCnt   = RegInit(0.U(log2Ceil(params.htDepth * ways + ovfcD + 1).W))

  // =========================================================================
  // key 输入缓存
  // =========================================================================
  val qEnq = Wire(Decoupled(UInt(keyW.W)))
  qEnq.bits  := io.key.bits
  qEnq.valid := io.key.valid
  val qDeq = Queue(qEnq, math.max(1, params.keyFifoDepth))
  when(qEnq.valid && !qEnq.ready) { cntKeyDrop := cntKeyDrop + 1.U }
  qDeq.ready := false.B

  // =========================================================================
  // FSM 状态与寄存器
  // =========================================================================
  val LK_IDLE = 0.U(4.W); val LK_HASH = 1.U(4.W); val LK_HT = 2.U(4.W); val LK_HTW = 3.U(4.W)
  val LK_KT   = 4.U(4.W); val LK_CMP  = 5.U(4.W); val LK_KTF = 6.U(4.W); val LK_AD = 7.U(4.W)
  val LK_ADW  = 8.U(4.W); val LK_REF  = 9.U(4.W); val LK_DONE = 10.U(4.W)

  val lkState   = RegInit(LK_IDLE)
  val keyReg    = Reg(UInt(keyW.W))
  val idxReg    = Reg(Vec(numBanks, UInt(l.idxW.W)))
  val htWordReg = Reg(Vec(numBanks, Vec(ways, UInt(l.htEntryW.W))))
  val hitReg    = RegInit(false.B)
  val adReg     = Reg(UInt(adW.W))
  val adPtrReg  = Reg(UInt(math.max(1, l.adPtrW).W))
  val lkScanIdx = Reg(UInt(l.scanW.W))     // KT 串行扫描的当前候选槽
  val lkKtPtr   = Reg(UInt(l.ktPtrW.W))    // OVFC 命中后的单次取指地址
  val lkHitBk   = Reg(UInt(l.bankW.W))     // 命中槽位（HT 刷新用）
  val lkHitWy   = Reg(UInt(l.wayW.W))
  val lkHitOvSel = Reg(UInt(l.ovfcCntW.W))
  val lkHitInOvfc = RegInit(false.B)

  val MT_IDLE = 0.U(4.W); val MT_HASH = 1.U(4.W); val MT_HT = 2.U(4.W); val MT_HTW = 3.U(4.W)
  val MT_KT   = 4.U(4.W); val MT_KTF  = 5.U(4.W); val MT_DEC = 6.U(4.W); val MT_ALLOC = 7.U(4.W)
  val MT_WR   = 8.U(4.W); val MT_FREE = 9.U(4.W); val MT_CMP = 10.U(4.W)

  val mState   = RegInit(MT_IDLE)
  val mOp      = Reg(UInt(2.W))
  val mIsLearn = RegInit(false.B)
  val mKey     = Reg(UInt(keyW.W))
  val mAd      = Reg(UInt(adW.W))
  val mIdx     = Reg(Vec(numBanks, UInt(l.idxW.W)))
  val mHtWord  = Reg(Vec(numBanks, Vec(ways, UInt(l.htEntryW.W))))
  val mKtWord  = Reg(UInt(math.max(1, l.ktEntryW).W))
  val mFound   = RegInit(false.B)
  val mInOvfc  = RegInit(false.B)
  val mSelBk   = Reg(UInt(l.bankW.W))
  val mSelWy   = Reg(UInt(l.wayW.W))
  val mScanIdx = Reg(UInt(l.scanW.W))
  val mBank    = Reg(UInt(l.bankW.W))
  val mWay     = Reg(UInt(l.wayW.W))
  val mKtPtr   = Reg(UInt(l.ktPtrW.W))
  val mAdPtr   = Reg(UInt(math.max(1, l.adPtrW).W))
  val mUseOvfc = RegInit(false.B)
  val mOvfcSel = Reg(UInt(l.ovfcCntW.W))

  val SW_IDLE = 0.U(4.W); val SW_RD = 1.U(4.W); val SW_RDW = 2.U(4.W); val SW_SCAN = 3.U(4.W)
  val SW_KTF  = 4.U(4.W); val SW_FREE = 5.U(4.W); val SW_WR = 6.U(4.W); val SW_OV = 7.U(4.W)

  val swState = RegInit(SW_IDLE)
  val swB     = Reg(UInt(l.bankW.W))
  val swI     = Reg(UInt(l.idxW.W))
  val swW     = Reg(UInt(l.wayW.W))
  val swWord  = Reg(Vec(ways, UInt(l.htEntryW.W)))
  val swOvIdx = Reg(UInt(l.ovfcCntW.W))

  // 学习请求
  val learnPend  = RegInit(false.B)
  val learnKey   = Reg(UInt(keyW.W))
  val learnAdVal = Reg(UInt(adW.W))

  def startHash(m: Option[CrcSerial], d: UInt): Unit =
    m.foreach { c => c.io.start := true.B; c.io.din := d }

  // =========================================================================
  // 公共工具
  // =========================================================================
  /** 扁平槽位 s = b*ways + w 取条目 */
  def flatOf(word: Vec[Vec[UInt]], s: UInt): UInt = word(l.slotBank(s))(l.slotWay(s))

  /** 串行扫描：返回第一个"槽号 >= start 且有效"的候选 —— start=0 即从头找 */
  def nextFrom(valid: Vec[Bool], start: UInt): (Bool, UInt) = {
    val cand = VecInit((0 until ktBanks).map(i => (i.U >= start) && valid(i)))
    (cand.asUInt.orR, PriorityEncoder(cand))
  }

  // =========================================================================
  // 读端口仲裁（pend 计数器；grant 拍组合驱动 raddr，窗口内源寄存器保持不变）
  // =========================================================================
  // HT 读：查找 > 维护 > sweep
  val htRdPend  = RegInit(0.U(4.W))
  val htRdOwner = RegInit(0.U(2.W))   // 0=lk 1=mt 2=sw
  val lkHtReq = lkState === LK_HT
  val mtHtReq = mState === MT_HT
  val swHtReq = swState === SW_RD
  val htGrantLk = memReady && lkHtReq && htRdPend === 0.U
  val htGrantMt = memReady && !lkHtReq && mtHtReq && htRdPend === 0.U
  val htGrantSw = memReady && !lkHtReq && !mtHtReq && swHtReq && htRdPend === 0.U
  when(htGrantLk || htGrantMt || htGrantSw) { htRdPend := htLat.U }
  .elsewhen(htRdPend =/= 0.U) { htRdPend := htRdPend - 1.U }
  when(htGrantLk) { htRdOwner := 0.U }
  .elsewhen(htGrantMt) { htRdOwner := 1.U }
  .elsewhen(htGrantSw) { htRdOwner := 2.U }

  // KT 读：查找 > 维护 > sweep（查找的扫描态只在当前候选有效时发起读）
  val lkValidVec = VecInit((0 until ktBanks).map(s => l.htValid(flatOf(htWordReg, s.U))))
  val mtValidVec = VecInit((0 until ktBanks).map(s => l.htValid(flatOf(mHtWord, s.U))))
  val ktRdPend  = RegInit(0.U(4.W))
  val ktRdOwner = RegInit(0.U(2.W))
  val lkKtReq = (lkState === LK_KTF) || (lkState === LK_KT && lkValidVec(lkScanIdx))
  val mtKtReq = (mState === MT_KTF) || (mState === MT_KT && mtValidVec(mScanIdx))
  val swKtReq = swState === SW_KTF
  val ktGrantLk = memReady && lkKtReq && ktRdPend === 0.U
  val ktGrantMt = memReady && !lkKtReq && mtKtReq && ktRdPend === 0.U
  val ktGrantSw = memReady && !lkKtReq && !mtKtReq && swKtReq && ktRdPend === 0.U
  when(ktGrantLk || ktGrantMt || ktGrantSw) { ktRdPend := ktLat.U }
  .elsewhen(ktRdPend =/= 0.U) { ktRdPend := ktRdPend - 1.U }
  when(ktGrantLk) { ktRdOwner := 0.U }
  .elsewhen(ktGrantMt) { ktRdOwner := 1.U }
  .elsewhen(ktGrantSw) { ktRdOwner := 2.U }

  // =========================================================================
  // 写端口
  // =========================================================================
  val mtWantsHt = (mState === MT_WR && !(mUseOvfc || (mFound && mInOvfc))) ||
    (mState === MT_FREE && !mInOvfc)
  val swWantsHt = (swState === SW_WR)
  val rfWantsHt = (lkState === LK_REF && !lkHitInOvfc)

  // ---- 维护要写的内容 ----
  val mEntry   = mHtWord(mSelBk)(mSelWy)
  val mNewPay  = Mux(mFound && !mInOvfc,
    (if (!useKt && !useAd) Cat(mAd, l.htKey(mEntry)) else l.htPay(mEntry)),
    (if (useKt) mKtPtr else if (useAd) Cat(mAdPtr, mKey) else Cat(mAd, mKey)))
  val mNewEntry = l.htEntry(mState === MT_WR, now, mNewPay)   // MT_FREE → valid=0
  val mtHtBank = Mux(mFound && !mInOvfc, mSelBk, mBank)
  val mtHtWay  = Mux(mFound && !mInOvfc, mSelWy, mWay)
  val mtHtIdx  = mIdx(mtHtBank)
  val mtHtData = VecInit((0 until ways).map(i => Mux(mtHtWay === i.U, mNewEntry, mHtWord(mtHtBank)(i))))
  val mNewKtData = if (useAd) mAdPtr else mAd   // KT 条目的负载位（= 动作数据指针或内联 AD）

  // ---- sweep 要写的内容 ----
  val swWData = VecInit((0 until ways).map(i =>
    Mux(swW === i.U, l.htEntry(false.B, now, l.htPay(swWord(i))), swWord(i))))

  // ---- 命中刷新要写的内容 ----
  val rfEntry  = htWordReg(lkHitBk)(lkHitWy)
  val rfWData  = VecInit((0 until ways).map(i =>
    Mux(lkHitWy === i.U, l.htEntry(true.B, now, l.htPay(rfEntry)), rfEntry)))

  for (b <- 0 until numBanks) {
    val p = htMems(b).io.lgc
    val selMt = mtWantsHt && mtHtBank === b.U
    val selSw = swWantsHt && swB === b.U
    p.we    := selMt || selSw || rfWantsHt
    p.re    := htRdPend =/= 0.U
    p.waddr := Mux(selMt, mtHtIdx, Mux(selSw, swI, idxReg(lkHitBk)))
    p.wdata := Mux(selMt, mtHtData.asUInt, Mux(selSw, swWData.asUInt, rfWData.asUInt))
  }

  // ---- KT 写（仅维护的插入；OVFC 落点同样需要 KT 条目）----
  ktMem.io.lgc.we    := mState === MT_WR && !mFound && useKt.B
  ktMem.io.lgc.re    := ktRdPend =/= 0.U
  ktMem.io.lgc.waddr := mKtPtr
  ktMem.io.lgc.wdata := l.ktEntry(mKey, mNewKtData)

  // ---- AD 单口（读=查找，写=维护；写优先，读窗口期间写等待）----
  val adRdPend  = RegInit(0.U(4.W))
  val lkAdReq   = lkState === LK_AD
  val mtAdWrReq = (mState === MT_WR) && useAd.B && adRdPend === 0.U
  val mOldAdPtr =
    if (!useAd) 0.U(1.W)
    else if (useKt) l.ktAdPtr(mKtWord)
    else Mux(mInOvfc, (if (ovfcEn) ovfcP(mOvfcSel) else 0.U(1.W)), l.htAdPtr(mEntry))
  val mOldKtPtr =
    if (useKt) Mux(mInOvfc, (if (ovfcEn) ovfcP(mOvfcSel) else 0.U(1.W)), l.htKtPtr(mEntry))
    else 0.U(1.W)
  val adGrantRd = memReady && lkAdReq && adRdPend === 0.U && !mtAdWrReq
  when(adGrantRd) { adRdPend := adLat.U }
  .elsewhen(adRdPend =/= 0.U) { adRdPend := adRdPend - 1.U }
  adMem.io.lgc.we    := mtAdWrReq
  adMem.io.lgc.re    := adGrantRd
  adMem.io.lgc.addr  := Mux(mtAdWrReq, Mux(mFound, mOldAdPtr, mAdPtr), adPtrReg)
  adMem.io.lgc.wdata := mAd

  // =========================================================================
  // 读地址驱动（grant 拍组合生效）
  // =========================================================================
  val htRdataVec = VecInit(htMems.map(_.io.lgc.rdata.asTypeOf(Vec(ways, UInt(l.htEntryW.W)))))
  // ⚠️ 两个易错点（都实测踩过）：
  // ① "本拍 grant" 优先级必须最高 —— owner 寄存器在 grant 拍末才更新，
  //    只看 owner 会让 grant 当拍用上一个 owner 的地址；
  // ② 复用 owner 判断"谁在占用"时，必须同时要求**读在途**（pend≠0）——
  //    owner 寄存器初值是 0（lookup），空闲时会把 lookup 的垃圾地址选中。
  val htBusy    = htRdPend =/= 0.U
  val htRdSelLk = htGrantLk || (htBusy && htRdOwner === 0.U)
  val htRdSelMt = !htRdSelLk && (htGrantMt || (htBusy && htRdOwner === 1.U))
  val htRdSelSw = !htRdSelLk && !htRdSelMt && (htGrantSw || (htBusy && htRdOwner === 2.U))
  for (b <- 0 until numBanks) {
    htMems(b).io.lgc.raddr := Mux(htRdSelLk, idxReg(b),
      Mux(htRdSelMt, mIdx(b), Mux(htRdSelSw && swB === b.U, swI, 0.U)))
  }
  val lkKtRAddr = Mux(lkState === LK_KTF, lkKtPtr,
    (if (useKt) l.htKtPtr(flatOf(htWordReg, lkScanIdx)) else 0.U))
  val mtKtRAddr = Mux(mState === MT_KTF, mKtPtr,
    (if (useKt) l.htKtPtr(flatOf(mHtWord, mScanIdx)) else 0.U))
  val swKtRAddr = if (useKt) l.htKtPtr(swWord(swW)) else 0.U
  val ktBusy    = ktRdPend =/= 0.U
  val ktRdSelLk = ktGrantLk || (ktBusy && ktRdOwner === 0.U)
  val ktRdSelMt = !ktRdSelLk && (ktGrantMt || (ktBusy && ktRdOwner === 1.U))
  val ktRdSelSw = !ktRdSelLk && !ktRdSelMt
  ktMem.io.lgc.raddr := Mux(ktRdSelLk, lkKtRAddr, Mux(ktRdSelMt, mtKtRAddr, swKtRAddr))

  // =========================================================================
  // OVFC 匹配
  // =========================================================================
  val (lkOvfcHit, lkOvfcSel) = ovfcMatch(keyReg)
  val (mtOvfcHit, mtOvfcSel) = ovfcMatch(mKey)

  // =========================================================================
  // 查找 FSM
  // =========================================================================
  io.rsp.valid := false.B
  io.rsp.bits  := 0.U

  when(lkState === LK_IDLE) {
    when(memReady && qDeq.valid) {
      qDeq.ready := true.B
      keyReg := qDeq.bits
      hitReg := false.B
      if (useSerial) { startHash(lkCrc, qDeq.bits); lkState := LK_HASH }
      else { idxReg := slices(hardHash(qDeq.bits)); lkState := LK_HT }
    }
  }
  if (useSerial) {
    when(lkState === LK_HASH) {
      when(lkCrc.get.io.done) { idxReg := slices(lkCrc.get.io.out); lkState := LK_HT }
    }
  }

  when(lkState === LK_HT && htGrantLk) { lkState := LK_HTW }

  // 桶数据捕获与裁决必须分成两拍：捕获是寄存器写，当拍读到的还是旧值
  //（寄存器初值为 X 时，`when(X)` 会让状态寄存器也变成 X —— 实测踩过）
  when(lkState === LK_HTW && htRdPend === 1.U && htRdOwner === 0.U) {
    for (b <- 0 until numBanks) htWordReg(b) := htRdataVec(b)
    lkState := LK_CMP
  }

  when(lkState === LK_CMP) {
    if (ovfcEn) {
      when(lkOvfcHit) {
        hitReg := true.B
        lkHitInOvfc := true.B
        lkHitOvSel := lkOvfcSel
        if (useKt) { lkKtPtr := ovfcP(lkOvfcSel); lkState := LK_KTF }
        else if (useAd) { adPtrReg := ovfcP(lkOvfcSel); lkState := LK_AD }
        else { adReg := ovfcP(lkOvfcSel); lkState := Mux(io.ageEn, LK_REF, LK_DONE) }
      }.elsewhen(useKt.B) {
        val (has, first) = nextFrom(lkValidVec, 0.U)
        when(has) { lkScanIdx := first; lkState := LK_KT }
        .otherwise { lkState := LK_DONE }
      }.otherwise {
        if (!useKt) lkInlineCmp()
      }
    } else {
      when(useKt.B) {
        val (has, first) = nextFrom(lkValidVec, 0.U)
        when(has) { lkScanIdx := first; lkState := LK_KT }
        .otherwise { lkState := LK_DONE }
      }.otherwise {
        if (!useKt) lkInlineCmp()
      }
    }
  }

  // key 内联（!useKt）时的并行比较 + 命中裁决
  def lkInlineCmp(): Unit = {
    val hits = VecInit((0 until ktBanks).map(s =>
      l.htValid(flatOf(htWordReg, s.U)) && l.htKey(flatOf(htWordReg, s.U)) === keyReg))
    when(hits.asUInt.orR) {
      val sel = PriorityEncoder(hits)
      hitReg := true.B
      lkHitInOvfc := false.B
      lkHitBk := l.slotBank(sel)
      lkHitWy := l.slotWay(sel)
      if (useAd) { adPtrReg := l.htAdPtr(flatOf(htWordReg, sel)); lkState := LK_AD }
      else { adReg := l.htAd(flatOf(htWordReg, sel)); lkState := Mux(io.ageEn, LK_REF, LK_DONE) }
    }.otherwise {
      lkState := LK_DONE
    }
  }

  if (useKt) {
    // KT 串行扫描：逐候选读 KT 并比较 key，命中即止
    when(lkState === LK_KT && ktRdPend === 1.U && ktRdOwner === 0.U) {
      when(l.ktKey(ktMem.io.lgc.rdata) === keyReg) {
        hitReg := true.B
        lkHitInOvfc := false.B
        lkHitBk := l.slotBank(lkScanIdx)
        lkHitWy := l.slotWay(lkScanIdx)
        if (useAd) { adPtrReg := l.ktAdPtr(ktMem.io.lgc.rdata); lkState := LK_AD }
        else { adReg := l.ktAd(ktMem.io.lgc.rdata); lkState := Mux(io.ageEn, LK_REF, LK_DONE) }
      }.otherwise {
        val (has, nxt) = nextFrom(lkValidVec, lkScanIdx + 1.U)
        when(has) { lkScanIdx := nxt }
        .otherwise { lkState := LK_DONE }
      }
    }
    // OVFC 命中后的单次取指
    when(lkState === LK_KTF && ktRdPend === 1.U && ktRdOwner === 0.U) {
      if (useAd) { adPtrReg := l.ktAdPtr(ktMem.io.lgc.rdata); lkState := LK_AD }
      else { adReg := l.ktAd(ktMem.io.lgc.rdata); lkState := Mux(io.ageEn, LK_REF, LK_DONE) }
    }
  }

  when(lkState === LK_AD && adGrantRd) { lkState := LK_ADW }
  when(lkState === LK_ADW && adRdPend === 1.U) {
    adReg := adMem.io.lgc.rdata
    lkState := Mux(io.ageEn, LK_REF, LK_DONE)
  }

  // 命中刷新：HT 命中写回时间戳；OVFC 命中直接改寄存器
  when(lkState === LK_REF) {
    when(lkHitInOvfc) {
      if (ovfcEn) ovfcT(lkHitOvSel) := now
      lkState := LK_DONE
    }.elsewhen(!mtWantsHt && !swWantsHt) {
      lkState := LK_DONE
    }
  }

  when(lkState === LK_DONE) {
    io.rsp.valid := true.B
    io.rsp.bits := Cat(hitReg, adReg)
    when(!hitReg && params.learning.isDefined.B && io.learnEn) {
      learnPend := true.B
      learnKey := keyReg
      learnAdVal := (params.learning match {
        case Some(lp) => if (lp.usePortAd) io.learnAd else lp.defaultAd.U(adW.W)
        case None     => 0.U(adW.W)
      })
    }
    lkState := LK_IDLE
  }

  // =========================================================================
  // 维护 FSM：插入 / 删除 / 更新 / 自学习
  // =========================================================================
  io.wrBusy := (mState =/= MT_IDLE) || learnPend

  when(mState === MT_IDLE) {
    when(memReady && io.wr.valid) {
      mOp := io.wr.bits.op
      mKey := io.wr.bits.key
      mAd := io.wr.bits.ad
      mIsLearn := false.B
      if (useSerial) { startHash(mtCrc, io.wr.bits.key); mState := MT_HASH }
      else { mIdx := slices(hardHash(io.wr.bits.key)); mState := MT_HT }
    }.elsewhen(memReady && learnPend) {
      mOp := EmOp.add
      mKey := learnKey
      mAd := learnAdVal
      mIsLearn := true.B
      learnPend := false.B
      if (useSerial) { startHash(mtCrc, learnKey); mState := MT_HASH }
      else { mIdx := slices(hardHash(learnKey)); mState := MT_HT }
    }
  }
  if (useSerial) {
    when(mState === MT_HASH) {
      when(mtCrc.get.io.done) { mIdx := slices(mtCrc.get.io.out); mState := MT_HT }
    }
  }

  when(mState === MT_HT && htGrantMt) { mState := MT_HTW }

  when(mState === MT_HTW && htRdPend === 1.U && htRdOwner === 1.U) {
    for (b <- 0 until numBanks) mHtWord(b) := htRdataVec(b)
    mState := MT_CMP
  }

  when(mState === MT_CMP) {
    if (ovfcEn) {
      when(mtOvfcHit) {
        mFound := true.B
        mInOvfc := true.B
        mOvfcSel := mtOvfcSel
        if (useKt) { mKtPtr := ovfcP(mtOvfcSel); mState := MT_KTF }
        else { mState := MT_DEC }
      }.elsewhen(useKt.B) {
        val (has, first) = nextFrom(mtValidVec, 0.U)
        when(has) { mScanIdx := first; mState := MT_KT }
        .otherwise { mFound := false.B; mInOvfc := false.B; mState := MT_DEC }
      }.otherwise {
        if (!useKt) mtInlineCmp()
      }
    } else {
      when(useKt.B) {
        val (has, first) = nextFrom(mtValidVec, 0.U)
        when(has) { mScanIdx := first; mState := MT_KT }
        .otherwise { mFound := false.B; mInOvfc := false.B; mState := MT_DEC }
      }.otherwise {
        if (!useKt) mtInlineCmp()
      }
    }
  }

  def mtInlineCmp(): Unit = {
    val hits = VecInit((0 until ktBanks).map(s =>
      l.htValid(flatOf(mHtWord, s.U)) && l.htKey(flatOf(mHtWord, s.U)) === mKey))
    mFound := hits.asUInt.orR
    mInOvfc := false.B
    when(hits.asUInt.orR) {
      val sel = PriorityEncoder(hits)
      mSelBk := l.slotBank(sel)
      mSelWy := l.slotWay(sel)
    }
    mState := MT_DEC
  }

  if (useKt) {
    when(mState === MT_KT && ktRdPend === 1.U && ktRdOwner === 1.U) {
      when(l.ktKey(ktMem.io.lgc.rdata) === mKey) {
        mKtWord := ktMem.io.lgc.rdata
        mFound := true.B
        mInOvfc := false.B
        mSelBk := l.slotBank(mScanIdx)
        mSelWy := l.slotWay(mScanIdx)
        mState := MT_DEC
      }.otherwise {
        val (has, nxt) = nextFrom(mtValidVec, mScanIdx + 1.U)
        when(has) { mScanIdx := nxt }
        .otherwise { mFound := false.B; mInOvfc := false.B; mState := MT_DEC }
      }
    }
    when(mState === MT_KTF && ktRdPend === 1.U && ktRdOwner === 1.U) {
      mKtWord := ktMem.io.lgc.rdata
      mState := MT_DEC
    }
  }

  // d-left：选"占用最少"的子表，并列按策略仲裁
  val mCounts = VecInit((0 until numBanks).map { b =>
    PopCount(VecInit((0 until ways).map(w => l.htValid(mHtWord(b)(w)))).asUInt)
  })
  val tieBase: UInt =
    if (numBanks == 1) 0.U(l.bankW.W)
    else params.dLeftTie match {
      case TiePolicy.Leftmost => 0.U(l.bankW.W)
      case TiePolicy.Random =>
        val lfsr = RegInit(1.U(16.W))
        lfsr := Cat(lfsr(14, 0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
        lfsr(log2Ceil(numBanks) - 1, 0)
      case TiePolicy.RoundRobin =>
        val rr = RegInit(0.U(l.bankW.W))
        when(mState === MT_WR && !mFound && !mUseOvfc) {
          rr := Mux(rr === (numBanks - 1).U, 0.U, rr + 1.U)
        }
        rr
    }
  val mMinCnt  = if (numBanks == 1) mCounts(0) else mCounts.reduce((a, b) => Mux(a < b, a, b))
  val mMinMask = VecInit((0 until numBanks).map(b => mCounts(b) === mMinCnt))
  val mBestBank =
    if (numBanks == 1) 0.U(l.bankW.W)
    else {
      // 从 tieBase 开始环形找第一个"占用最少"的子表（numBanks 字面量需要 bankW+1 位）
      val nb = numBanks.U((l.bankW + 1).W)
      val order = VecInit((0 until numBanks).map { k =>
        val s = tieBase +& k.U(l.bankW.W)
        Mux(s >= nb, s - nb, s)(l.bankW - 1, 0)
      })
      order(PriorityEncoder(VecInit(order.map(i => mMinMask(i)))))
    }
  val mFreeMask   = VecInit((0 until ways).map(w => !l.htValid(mHtWord(mBestBank)(w))))
  val mHasFree    = mFreeMask.asUInt.orR
  val mFreeWay    = PriorityEncoder(mFreeMask)
  val ovfcHasFree = if (ovfcEn) ovfcUseCnt =/= ovfcD.U else false.B
  val ovfcFreeSel = if (ovfcEn) PriorityEncoder(VecInit((0 until ovfcD).map(i => !ovfcV(i)))) else 0.U(1.W)

  when(mState === MT_DEC) {
    when(mFound) {
      mState := Mux(mOp === EmOp.del, MT_FREE, MT_WR)
    }.elsewhen(mOp === EmOp.del) {
      mState := MT_IDLE                                        // 删除幂等
    }.elsewhen(mOp === EmOp.upd) {
      cntInsFail := cntInsFail + 1.U
      mState := MT_IDLE
    }.otherwise {
      when(mHasFree) {
        mUseOvfc := false.B
        mBank := mBestBank
        mWay := mFreeWay
        mState := MT_ALLOC
      }.elsewhen(ovfcHasFree) {
        mUseOvfc := true.B
        mOvfcSel := ovfcFreeSel
        mState := MT_ALLOC
      }.otherwise {
        cntInsFail := cntInsFail + 1.U
        mState := MT_IDLE
      }
    }
  }

  when(mState === MT_ALLOC) {
    if (useKt) ktFree.io.alloc := true.B
    if (useAd) adFree.io.alloc := true.B
    val ktOk = if (useKt) ktFree.io.ok else true.B
    val adOk = if (useAd) adFree.io.ok else true.B
    when(ktOk && adOk) {
      if (useKt) mKtPtr := ktFree.io.addr
      if (useAd) mAdPtr := adFree.io.addr
      mState := MT_WR
    }.otherwise {
      cntInsFail := cntInsFail + 1.U
      mState := MT_IDLE
    }
  }

  when(mState === MT_WR) {
    when(!mFound) {
      // ---- 写新条目 ----
      // OVFC 未启用时 ovfc* 寄存器是 null，必须在 Scala 层收掉（不能只靠 when 条件）
      if (ovfcEn) {
        when(mUseOvfc) {
          ovfcV(mOvfcSel) := true.B
          ovfcK(mOvfcSel) := mKey
          ovfcP(mOvfcSel) := (if (useKt) mKtPtr else if (useAd) mAdPtr else mAd)
          ovfcT(mOvfcSel) := now
          ovfcUseCnt := ovfcUseCnt + 1.U
        }
      }
      entryCnt := entryCnt + 1.U
    }.elsewhen(mInOvfc) {
      // ---- 覆盖 OVFC 条目：刷新时间戳 ----
      if (ovfcEn) ovfcT(mOvfcSel) := now
    }
    // 覆盖 HT 条目的时间戳刷新与插入的 HT 写都由 mtWantsHt 驱动的写口完成
    when(mIsLearn) { cntLearn := cntLearn + 1.U }.otherwise { cntInsert := cntInsert + 1.U }
    mState := MT_IDLE
  }

  when(mState === MT_FREE) {
    if (ovfcEn) {
      when(mInOvfc) {
        ovfcV(mOvfcSel) := false.B
        ovfcUseCnt := ovfcUseCnt - 1.U
      }
    }
    if (useKt) { ktFree.io.free := true.B; ktFree.io.faddr := mOldKtPtr }
    if (useAd) { adFree.io.free := true.B; adFree.io.faddr := mOldAdPtr }
    when(entryCnt =/= 0.U) { entryCnt := entryCnt - 1.U }
    cntDelete := cntDelete + 1.U
    mState := MT_IDLE
  }

  // =========================================================================
  // 老化 sweep：先扫 HT（逐 bank 逐桶），再扫 OVFC
  // =========================================================================
  /** OVFC 条目是否过期（未启用老化/未启用 OVFC 时恒否） */
  def swOvExpired(i: UInt): Bool =
    if (!ovfcEn || agingCfg.isEmpty) false.B
    else io.ageEn && memReady && ovfcV(i) &&
      ((now - ovfcT(i)) >= agingCfg.get.timeout.U(l.ageW1.W))

  val swKtData = Reg(UInt(math.max(1, l.ktEntryW).W))
  val swE   = swWord(swW)
  // 未启用老化时 sweep 恒判"不过期"（不能拿 timeout=0 兜底，否则所有条目都会被删）
  val swExp = (if (agingCfg.isDefined)
    io.ageEn && l.htValid(swE) && ((now - l.htTs(swE)) >= agingCfg.get.timeout.U(l.ageW1.W))
    else false.B) && memReady

  // ⚠️ 不能写 `when(swW < ways.U)`：swW 位宽只有 wayW 位，永远 < ways，
  // "换下一桶"分支会变成不可达代码（firrtl 把 swI/swB 折叠成常量 0 —— 实测踩过）。
  val swLastWay = swW === (ways - 1).U
  def swAdvance: Unit = {
    when(swI === (l.bankDepth - 1).U) {
      swI := 0.U
      swB := Mux(swB === (numBanks - 1).U, 0.U, swB + 1.U)
    }.otherwise {
      swI := swI + 1.U
    }
  }

  when(swState === SW_IDLE) {
    when(memReady) {
      swB := 0.U
      swI := 0.U
      swW := 0.U
      swState := SW_RD
    }
  }

  when(swState === SW_RD && htGrantSw) { swState := SW_RDW }

  when(swState === SW_RDW && htRdPend === 1.U && htRdOwner === 2.U) {
    swWord := htRdataVec(swB)
    swState := SW_SCAN
  }

  when(swState === SW_SCAN) {
    when(swExp) {
      if (useKt) { swState := SW_KTF }
      else { swState := SW_FREE }
    }.elsewhen(swLastWay) {
      swAdvance
      swW := 0.U
      swState := Mux(swB === (numBanks - 1).U && swI === (l.bankDepth - 1).U, (if (ovfcEn) SW_OV else SW_IDLE), SW_RD)
    }.otherwise {
      swW := swW + 1.U
    }
  }

  if (useKt) {
    when(swState === SW_KTF && ktRdPend === 1.U && ktRdOwner === 2.U) {
      swState := SW_FREE
    }
  }

  // 归还 KT / AD 条目（与维护删除互斥：维护优先）
  val swFreeOk = mState =/= MT_FREE
  if (useKt) {
    when(swState === SW_KTF && ktRdPend === 1.U && ktRdOwner === 2.U) { swKtData := ktMem.io.lgc.rdata }
  }
  when(swState === SW_FREE && swFreeOk) {
    if (useKt) { ktFree.io.free := true.B; ktFree.io.faddr := l.htKtPtr(swE) }
    if (useAd) {
      adFree.io.free := true.B
      adFree.io.faddr := (if (useKt) l.ktAdPtr(swKtData) else l.htAdPtr(swE))
    }
    swState := SW_WR
  }

  when(swState === SW_WR && !mtWantsHt) {
    swState := Mux(swLastWay,
      Mux(swB === (numBanks - 1).U && swI === (l.bankDepth - 1).U, (if (ovfcEn) SW_OV else SW_IDLE), SW_RD),
      SW_SCAN)
    when(swLastWay) {
      swAdvance
      swW := 0.U
    }.otherwise {
      swW := swW + 1.U
    }
    when(entryCnt =/= 0.U) { entryCnt := entryCnt - 1.U }
    cntAgeDrop := cntAgeDrop + 1.U
  }

  when(swState === SW_OV) {
    when(swOvIdx === ovfcD.U) {
      swState := SW_IDLE
    }.elsewhen(swOvExpired(swOvIdx)) {
      if (ovfcEn) {
        ovfcV(swOvIdx) := false.B
        ovfcUseCnt := ovfcUseCnt - 1.U
        if (useKt) { ktFree.io.free := true.B; ktFree.io.faddr := ovfcP(swOvIdx) }
        if (useAd) { adFree.io.free := true.B; adFree.io.faddr := ovfcP(swOvIdx) }
      }
      when(entryCnt =/= 0.U) { entryCnt := entryCnt - 1.U }
      cntAgeDrop := cntAgeDrop + 1.U
      swOvIdx := swOvIdx + 1.U
    }.otherwise {
      swOvIdx := swOvIdx + 1.U
    }
  }

  // =========================================================================
  // 状态输出
  // =========================================================================
  io.status.entries := entryCnt
  io.status.insert  := cntInsert
  io.status.insFail := cntInsFail
  io.status.delete  := cntDelete
  io.status.learn   := cntLearn
  io.status.ageDrop := cntAgeDrop
  io.status.keyDrop := cntKeyDrop
  io.status.ovfcUse := ovfcUseCnt
  io.status.lkBusy  := lkState =/= LK_IDLE
  io.status.mtBusy  := mState =/= MT_IDLE
}
