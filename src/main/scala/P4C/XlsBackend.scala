package P4C

import P4C.Ast._
import P4C.XlsProc.{Builder, IdGen}

import scala.collection.mutable

/** A2：P4 → XLS `proc` 的编排层。
  *
  * 与 [[ChiselBackend]] 平行（D3 决策：两条 RTL 路线并存，共享前端与 [[Ir.Dag]]）。
  * 分工：
  *   - [[IrText]]   —— 单个 `Ir.Dag` → 单个 XLS `fn`（组合逻辑）
  *   - [[XlsProc]]  —— proc 形态的 IR 文本发射器（时序编排）
  *   - '''本对象''' —— 把 P4 的 parser/control 结构映射到 proc 的 FSM + 通道
  *
  * == 映射设计（详见 docs/A2-架构设计.md）==
  *
  * 1. '''一个 P4 程序 → 一个 proc'''（parser FSM + control FSM 合一）。
  *    跨 proc 互连需要 package 级 `chan` 声明 + 绑定（尚未验证），单 proc 是已验证形态。
  *
  * 2. '''每个 header 一对 state'''（`h_<inst>` 存数据、`v_<inst>` 存 valid 位），
  *    而不是把 PHV 拼成一个大的 `bits[K]`。理由：`extract` 只更新对应 header，
  *    拆开就不必反复对整个 PHV 做 `concat` / `bit_slice`。输出时再拼回去。
  *
  * 3. '''相位编码'''：`0..N-1` = 各 parser 状态（按声明序），`N` = accept，
  *    `N+1` = reject；accept/reject 处理完回到相位 0 重新收包。
  *    复位值 0 天然落在第一个状态（要求它叫 `start`）。
  *
  * 4. '''通道操作都接同一个 `state_read(tok)`'''（P0 定案：不得串链），
  *    末尾 `after_all` 汇聚。
  *
  * 5. 复用 [[ChiselBackend.layoutParser]] 的字节偏移计算 —— 两份实现必然漂移。
  *
  * == 第一版范围 ==
  *
  * 只有 parser（无 control、无 extern、无 table）。control 编排是 A2-2 的后续。
  */
object XlsBackend {

  /** 报文窗口宽度，与 Chisel 线一致（`io.in = Input(UInt(512.W))`）。 */
  private val PktWindowBits = 512

  /** 一个 header 实例在 proc state 里的槽位。 */
  private final case class HdrSlot(
    inst: String,      // struct 成员名，如 "ethernet"
    ht: HeaderType,
    width: Int,        // 各字段宽度之和
    dataState: String, // state 名：包头数据
    validState: String // state 名：valid 位
  )

  private def bitsTy(w: Int): String = s"bits[$w]"

  /** 能表示 `0..n-1` 的最少位数。 */
  private def widthFor(n: Int): Int = math.max(1, BigInt(math.max(0, n - 1)).bitLength)

  // ------------------------------------------------------------------
  // parser → proc
  // ------------------------------------------------------------------

  /** 发射一个 parser 的 proc。
    *
    * @param pkg package 名（进 IR 的 `package` 行）
    * @param ids 全局 id 分配器（跨 fn/proc 共用，A2-0 硬规则）
    */
  private def emitParser(p: ParserDecl, prog: P4Program, pkg: String, ids: IdGen): String = {
    val outParam = p.params.find(_.direction == "out")
      .getOrElse(throw new P4Error(s"XlsBackend：parser '${p.name}' 缺少 out 参数"))
    val hdrStruct = prog.structs.find(_.name == outParam.typeName)
      .getOrElse(throw new P4Error(
        s"XlsBackend：parser '${p.name}' 的 out 参数类型 '${outParam.typeName}' 不是 struct"))
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap

    val slots: Seq[HdrSlot] = hdrStruct.members.filterNot(_.isBits).map { m =>
      val ht = headerTypes.getOrElse(m.typeName,
        throw new P4Error(s"XlsBackend：未知 header 类型 '${m.typeName}'"))
      HdrSlot(m.name, ht, ht.fields.map(_.width).sum, s"h_${m.name}", s"v_${m.name}")
    }
    if (slots.isEmpty) throw new P4Error(s"XlsBackend：parser '${p.name}' 的 out struct 里没有 header 成员")
    val slotOf: Map[String, HdrSlot] = slots.map(s => s.inst -> s).toMap

    // ---- 相位编码 ----
    val mainStates = p.states.map(_.name).filter(n => n != "accept" && n != "reject")
    if (mainStates.isEmpty) throw new P4Error(s"XlsBackend：parser '${p.name}' 没有任何状态")
    if (mainStates.head != "start")
      throw new P4Error(s"XlsBackend：parser '${p.name}' 的第一个状态必须是 'start'（got '${mainStates.head}'）")
    val phaseOf: Map[String, Int] = mainStates.zipWithIndex.toMap
    val phAccept = mainStates.length
    val phReject = mainStates.length + 1
    val nPhases = mainStates.length + 2
    val pw = widthFor(nPhases)

    // 字节偏移：复用 Chisel 线的实现（可见性已放宽到 private[P4C]）
    val layouts = ChiselBackend.layoutParser(p, prog)

    val phvWidth = slots.map(s => 1 + s.width).sum

    val b = new Builder(pkg, s"${p.name}_parser", ids)
    b.declareChan("pkt_in", "receive", PktWindowBits, "valid_data")
    b.declareChan("phv_out", "send", phvWidth, "ready_valid")
    b.declareState("phase", bitsTy(pw), "0")
    b.declareState("pkt", bitsTy(PktWindowBits), "0")
    slots.foreach { s =>
      b.declareState(s.dataState, bitsTy(s.width), "0")
      b.declareState(s.validState, "bits[1]", "0")
    }

    // ---- 1) 读状态 ----
    val tok = b.stateRead("tok", "token", "tok")
    val ph = b.stateRead("phase", bitsTy(pw), "phase")
    val pkt = b.stateRead("pkt", bitsTy(PktWindowBits), "pkt")
    val slotReads: Map[String, (String, String)] = slots.map { s =>
      s.inst -> (b.stateRead(s.dataState, bitsTy(s.width), s.dataState),
                 b.stateRead(s.validState, "bits[1]", s.validState))
    }.toMap

    // ---- 2) 相位节点与判据 ----
    val phaseLit: Seq[String] = (0 until nPhases).map(k => b.literal(k, bitsTy(pw), s"k$k"))
    val isPh: Seq[String] = phaseLit.zipWithIndex.map { case (c, k) => b.eq(ph, c, s"is_ph$k") }
    val oneBit = b.literal(1, "bits[1]", "one")

    def phaseNodeOf(target: String): String =
      if (target == "accept") phaseLit(phAccept)
      else if (target == "reject") phaseLit(phReject)
      else phaseLit(phaseOf.getOrElse(target,
        throw new P4Error(s"XlsBackend：parser 转移到了未知状态 '$target'")))

    // ---- 3) extract：更新 nextHdr / nextVal，并记录本状态的切片供 select 用 ----
    val nextHdr = mutable.LinkedHashMap.empty[String, String]
    val nextVal = mutable.LinkedHashMap.empty[String, String]
    slots.foreach { s =>
      val (d, v) = slotReads(s.inst)
      nextHdr(s.inst) = d
      nextVal(s.inst) = v
    }
    // (相位, 实例) -> 本相位切出来的 header word 节点名
    val extractWord = mutable.HashMap.empty[(Int, String), String]

    mainStates.zipWithIndex.foreach { case (stName, k) =>
      val lay = layouts.getOrElse(stName,
        throw new P4Error(s"XlsBackend：parser 状态 '$stName' 未在布局中"))
      lay.extracts.foreach { case (path, ht, byteOff) =>
        if (path.length != 2)
          throw new P4Error(s"XlsBackend：extract 路径必须是 param.instance（got '${path.mkString(".")}'）")
        val inst = path(1)
        val slot = slotOf.getOrElse(inst, throw new P4Error(s"XlsBackend：未知 header 实例 '$inst'"))
        val hb = slot.width
        if (hb % 8 != 0)
          throw new P4Error(s"XlsBackend：header '${ht.name}' 总宽 $hb bit 非字节对齐（需字节对齐）")
        val shift = PktWindowBits - 8 * byteOff - hb
        if (shift < 0)
          throw new P4Error(s"XlsBackend：header '${ht.name}' 在偏移 $byteOff 超出 $PktWindowBits-bit 窗口")
        val w = b.bitSlice(pkt, shift, hb, s"w_$inst")
        extractWord((k, inst)) = w
        // 只在**本状态**对应的相位采样
        nextHdr(inst) = b.sel(isPh(k), Seq(nextHdr(inst), w), Some(nextHdr(inst)), bitsTy(hb), s"nh_$inst")
        nextVal(inst) = b.sel(isPh(k), Seq(nextVal(inst), oneBit), Some(nextVal(inst)), "bits[1]", s"nv_$inst")
      }
    }

    // ---- 4) 输出 PHV：按 header 声明序拼 (valid, data) ----
    val phvParts = slots.flatMap { s => Seq(nextVal(s.inst), nextHdr(s.inst)) }
    val phv = b.concat(phvParts, bitsTy(phvWidth), "phv")

    // ---- 5) 通道操作：都接同一个 tok（P0：不得串链）----
    val (recvTok, pktIn) = b.receive(tok, "pkt_in", bitsTy(PktWindowBits), Some(isPh(0)), "rcv")
    val sOut = b.send(tok, phv, "phv_out", Some(isPh(phAccept)), "snd")

    // ---- 6) next_pkt：只在相位 0 采样 ----
    val nextPkt = b.sel(isPh(0), Seq(pkt, pktIn), Some(pkt), bitsTy(PktWindowBits), "next_pkt")

    // ---- 7) 相位推进 ----
    def targetOf(k: Int): String = {
      val stName = mainStates(k)
      val lay = layouts.getOrElse(stName, throw new P4Error(s"XlsBackend：状态 '$stName' 无布局"))
      lay.trans match {
        case Goto(t, _) => phaseNodeOf(t)
        case Select(value, cases, deft, line) =>
          val path = value match {
            case Name(pp, _) => pp
            case _ => throw new P4Error(s"行 $line：XlsBackend 只支持字段路径作为 select 值")
          }
          if (path.length != 3)
            throw new P4Error(s"行 $line：select 值必须是 param.instance.field（got '${path.mkString(".")}'）")
          val inst = path(1)
          val slot = slotOf.getOrElse(inst, throw new P4Error(s"XlsBackend：未知 header 实例 '$inst'"))
          val word = extractWord.getOrElse((k, inst),
            throw new P4Error(
              s"行 $line：select 值 '${path.mkString(".")}' 所在的 header 未在本状态 extract（与 Chisel 线同规则）"))
          val ht = slot.ht
          val fname = path(2)
          val f = ht.fields.find(_.name == fname).getOrElse(
            throw new P4Error(s"XlsBackend：header '${ht.name}' 无字段 '$fname'"))
          // 与 Chisel 线同口径：word 的高位是第一个字段
          val p0 = ht.fields.takeWhile(_.name != fname).map(_.width).sum
          val hb = ht.fields.map(_.width).sum
          val selVal = b.bitSlice(word, hb - p0 - f.width, f.width, "selv")
          // 倒序嵌套 sel ⇒ 声明在前面的 case 优先级高
          var acc = phaseNodeOf(deft)
          cases.reverse.foreach { case (ce, tgt) =>
            val cv = constOf(ce, line)
            val cond = b.eq(selVal, b.literal(cv, bitsTy(f.width), "ck"), s"c_$fname")
            acc = b.sel(cond, Seq(acc, phaseNodeOf(tgt)), None, bitsTy(pw), "sw")
          }
          acc
      }
    }

    var nextPhase = ph // 兜底：保持当前相位（各 isPh 互斥且全覆盖，正常不会走到）
    (0 until nPhases).reverse.foreach { k =>
      val tgt = if (k < mainStates.length) targetOf(k) else phaseLit(0)
      nextPhase = b.sel(isPh(k), Seq(nextPhase, tgt), Some(nextPhase), bitsTy(pw), s"np$k")
    }

    // ---- 8) token 汇聚与状态写回 ----
    val tokAll = b.afterAll(Seq(recvTok, sOut), "next_tok")
    b.nextValue("tok", tokAll)
    b.nextValue("phase", nextPhase)
    b.nextValue("pkt", nextPkt)
    slots.foreach { s =>
      b.nextValue(s.dataState, nextHdr(s.inst))
      b.nextValue(s.validState, nextVal(s.inst))
    }

    b.render
  }

  /** select 分支值必须是编译期数字字面量。 */
  private def constOf(e: Expr, line: Int): BigInt = e match {
    case Num(v, _, _) => v
    case _ => throw new P4Error(s"行 $line：XlsBackend 要求 select 分支值是数字字面量")
  }

  // ------------------------------------------------------------------
  // 顶层入口
  // ------------------------------------------------------------------

  /** 整个程序 → XLS IR 文本。
    *
    * @param pkg        package 名（进 IR 的 `package` 行）
    * @param sourceName 源文件名（写进注释）
    */
  def emitProgram(prog: P4Program, pkg: String, sourceName: String): String = {
    // 全局 id：跨 fn/proc 唯一（A2-0 硬规则）
    val ids = new IdGen(1)
    val b = new StringBuilder
    b ++= s"// Generated by P4C (P4 → XLS IR). DO NOT EDIT.\n"
    b ++= s"// source: $sourceName\n"
    b ++= s"package $pkg\n\n"

    if (prog.controls.nonEmpty)
      throw new P4Error("XlsBackend：control 尚未支持（A2 目前只有 parser）")
    if (prog.parsers.isEmpty)
      throw new P4Error("XlsBackend：程序里没有 parser")

    prog.parsers.foreach { p =>
      b ++= emitParser(p, prog, pkg, ids)
      b ++= "\n"
    }
    b.toString
  }
}
