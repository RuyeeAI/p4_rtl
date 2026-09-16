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
  * parser 与 control **各自可做，但暂不能混在同一个程序里**（A2-5 合并）：
  *   - parser：extract / transition 子集（A2-2）；
  *   - control：action / const 表 / 赋值（A2-4，见 [[emitControl]]）；
  *   - 未支持：extern 状态（Register/Counter）、运行时表、p4c 遗留的组合写法。
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
  // control → proc
  // ------------------------------------------------------------------

  /** control 参数展平后的一个**字段级**槽位（对应 proc 的一个 state）。
    *
    * A2-4 用字段级（而非 header 级）槽位：control 的 action 是**局部修改**
    * （`meta.cls = c`），字段级槽位让「改哪个字段就更新哪个 state」成为最自然的写法，
    * 不必把整个 header 拆开再拼回去。
    */
  private final case class CtrlSlot(stateName: String, path: Seq[String], width: Int)

  /** `Ir.Op` → XLS op 名（与 [[IrText]] 同一张表）。 */
  private def opNameOf(op: Ir.Op): String = op match {
    case Ir.Add => "add"; case Ir.Sub => "sub"
    case Ir.And => "and"; case Ir.Or => "or"; case Ir.Xor => "xor"
    case Ir.Shl => "shll"; case Ir.Shr => "shrl"
    case Ir.Eq => "eq"; case Ir.Neq => "ne"
    case Ir.Lt => "ult"; case Ir.Le => "ule"
    case Ir.Gt => "ugt"; case Ir.Ge => "uge"
  }

  /** 把一个 [[Ir.Dag]] **内联发射**进 proc。
    *
    * 为什么不走 `fn` + `invoke`（A2-0 §2 已验 invoke 可用）：本工程是**单 proc**
    * 形态，action 不存在复用收益（XLS 对 invoke 也是内联展开，产物等价），
    * 而走 fn 需要额外确定「fn 形参/返回值如何与 proc 的 state 对接」——
    * 内联让 `InputRef` 直接落到 state 读节点上，少一层未验证的接口。
    *
    * @param inputOf `Ir.InputRef` 路径 → proc 里代表该路径当前值的节点名
    * @return `OutputWrite` 的路径 → 值节点名
    */
  private def emitDagInto(
    b: Builder, dag: Ir.Dag, inputOf: Seq[String] => String, hint: String,
  ): Map[Seq[String], String] = {
    val emitted = mutable.HashMap.empty[Ir.NodeId, String]
    // 深度优先：先发操作数再发自身，天然满足 XLS「引用先于定义」
    def go(id: Ir.NodeId): String = emitted.getOrElseUpdate(id, {
      dag.nodes(id) match {
        case Ir.Const(v, w) => b.literal(v, bitsTy(w), s"${hint}_k")
        case Ir.InputRef(path, _) => inputOf(path)
        case Ir.Zext(s, w) => b.zeroExt(go(s), w, s"${hint}_zx")
        case Ir.Trunc(s, w) => b.bitSlice(go(s), 0, w, s"${hint}_tr")
        case Ir.Slice(s, hi, lo) => b.bitSlice(go(s), lo, hi - lo + 1, s"${hint}_sl")
        case Ir.Cat(parts, w) => b.concat(parts.map(go), bitsTy(w), s"${hint}_ct")
        case Ir.Not(s, w) => b.notNode(go(s), w, s"${hint}_nt")
        // Ir.Mux(c,t,f)：XLS sel 的 cases 按 selector **取值**索引 ⇒ cases = [假臂, 真臂]
        case Ir.Mux(c, t, f, w) => b.sel(go(c), Seq(go(f), go(t)), None, bitsTy(w), s"${hint}_mx")
        case Ir.Bin(op, l, r, w) => b.binOp(opNameOf(op), go(l), go(r), bitsTy(w), s"${hint}_bn")
        case other => throw new P4Error(
          s"XlsBackend：proc 内暂不支持 $other 节点（extern 状态属 A2-5）")
      }
    })
    dag.outputs.foreach {
      case Ir.OutputWrite(_, v, _) => go(v)
      case s => throw new P4Error(s"XlsBackend：proc 内暂不支持 $s 汇点（extern 状态属 A2-5）")
    }
    dag.outputs.collect { case Ir.OutputWrite(path, v, _) => path -> go(v) }.toMap
  }

  /** 一个 action 的 body → [[Ir.Dag]]（复用 Chisel 线的 [[IrBuilder.ExprLowering]]）。
    *
    * **同一套语义只写一份实现**：宽度推断、位宽 fit、运算符映射全部走既有 lowering，
    * 不在这里重复一遍。
    */
  private def actionDag(
    a: ActionDecl, args: Seq[Expr], resolver: IrBuilder.WidthResolver,
    externs: Map[String, ExternInst],
  ): Ir.Dag = {
    val ib = new Ir.Builder
    val lowering = new IrBuilder.ExprLowering(resolver, ib, externs)
    if (a.params.length != args.length)
      throw new P4Error(s"行 ${a.line}：action '${a.name}' 需要 ${a.params.length} 个实参，给了 ${args.length} 个")
    val binds: IrBuilder.Bindings = a.params.zip(args).map { case (p, e) =>
      val (id, w) = lowering.lower(e, Some(p.width), Map.empty)
      p.name -> ((ib.fit(id, w, p.width), p.width))
    }.toMap
    val outs = a.body.map {
      case asg: Assign => lowering.lowerAssign(asg.path, asg.expr, binds)
      case st => throw new P4Error(
        s"行 ${st.line}：XlsBackend 的 action 体暂只支持赋值（extern 方法调用属 A2-5）")
    }
    Passes.runAll(ib.finish(outs))
  }

  /** 发射一个 control 的 proc。
    *
    * 相位编码：`0` = 收 PHV，`1..S` = apply 体各语句（每条一拍），`S+1` = 发 PHV，
    * 处理完回相位 0 重新收包。
    *
    * @param pkg package 名（进 IR 的 `package` 行）
    * @param ids 全局 id 分配器（跨 fn/proc 共用，A2-0 硬规则）
    */
  private def emitControl(c: ControlDecl, prog: P4Program, pkg: String, ids: IdGen): String = {
    val structs = prog.structs.map(st => st.name -> st).toMap
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap

    if (c.externs.nonEmpty)
      throw new P4Error(s"XlsBackend：control '${c.name}' 含 extern 状态" +
        s"（${c.externs.map(_.name).mkString(", ")}）—— A2 暂不支持（计划 A2-5）")

    // ---- 1) 展平 control 参数 → 字段级 state ----
    val slots: Seq[CtrlSlot] = c.params.flatMap { p =>
      val st = structs.getOrElse(p.typeName, throw new P4Error(
        s"XlsBackend：control '${c.name}' 的参数 '${p.name}' 类型 '${p.typeName}' 不是 struct"))
      st.members.flatMap { m =>
        if (m.isBits) Seq(CtrlSlot(s"${p.name}_${m.name}", Seq(p.name, m.name), m.bitsWidth))
        else {
          val ht = headerTypes.getOrElse(m.typeName, throw new P4Error(
            s"XlsBackend：control '${c.name}' 的参数 '${p.name}.${m.name}' 类型 '${m.typeName}' 未知"))
          ht.fields.map(f =>
            CtrlSlot(s"${p.name}_${m.name}_${f.name}", Seq(p.name, m.name, f.name), f.width))
        }
      }
    }
    if (slots.isEmpty)
      throw new P4Error(s"XlsBackend：control '${c.name}' 的参数里没有可处理字段")
    val dup = slots.groupBy(_.stateName).filter(_._2.size > 1).keys.toSeq
    if (dup.nonEmpty)
      throw new P4Error(s"XlsBackend：control '${c.name}' 展平后字段重名：${dup.mkString(", ")}")
    val slotAt: Map[Seq[String], CtrlSlot] = slots.map(s => s.path -> s).toMap

    val phvWidth = slots.map(_.width).sum

    // ---- 2) 相位编码 ----
    val stmts = c.applyBody
    if (stmts.isEmpty) throw new P4Error(s"XlsBackend：control '${c.name}' 的 apply 体为空")
    val phSend = stmts.length + 1
    val nPhases = stmts.length + 2
    val pw = widthFor(nPhases)

    val resolver = new IrBuilder.WidthResolver(headerTypes, structs, c.params)
    val externMap = c.externs.map(e => e.name -> e).toMap
    val actionOf = c.actions.map(a => a.name -> a).toMap
    val tableOf = c.tables.map(t => t.name -> t).toMap

    // ---- 3) 声明 ----
    val b = new Builder(pkg, s"${c.name}_control", ids)
    b.declareChan("phv_in", "receive", phvWidth, "valid_data")
    b.declareChan("phv_out", "send", phvWidth, "ready_valid")
    b.declareState("phase", bitsTy(pw), "0")
    slots.foreach(s => b.declareState(s.stateName, bitsTy(s.width), "0"))

    // ---- 4) 读状态 ----
    val tok = b.stateRead("tok", "token", "tok")
    val ph = b.stateRead("phase", bitsTy(pw), "phase")
    val cur: Map[Seq[String], String] =
      slots.map(s => s.path -> b.stateRead(s.stateName, bitsTy(s.width), s.stateName)).toMap

    // ---- 5) 相位判据 ----
    val phaseLit: Seq[String] = (0 until nPhases).map(k => b.literal(k, bitsTy(pw), s"k$k"))
    val isPh: Seq[String] = phaseLit.zipWithIndex.map { case (cl, k) => b.eq(ph, cl, s"is_ph$k") }

    // ---- 6) 收：PHV 拆成各字段（字段序 = 展平序，先声明在高位）----
    val (recvTok, rcvData) =
      b.receive(tok, "phv_in", bitsTy(phvWidth), Some(isPh(0)), "rcv")
    val rcvField: Map[Seq[String], String] = {
      var off = phvWidth
      slots.map { s =>
        off -= s.width
        s.path -> b.bitSlice(rcvData, off, s.width, s"fi_${s.stateName}")
      }.toMap
    }

    /** 每个 state 的下一拍值：初值 = 当前读出值（未改 = 保持原值），逐相位 sel 更新。 */
    val nextOf = mutable.LinkedHashMap.empty[String, String]
    slots.foreach(s => nextOf(s.stateName) = cur(s.path))

    /** 把某个相位对若干字段的修改并入 next 值（sel 的 cases = [selector 假臂, 真臂]）。 */
    def applyPhase(phK: String, updates: Map[Seq[String], String], tag: String): Unit =
      updates.foreach { case (path, v) =>
        val s = slotAt.getOrElse(path, throw new P4Error(
          s"XlsBackend：内部错误 —— 未知字段路径 '${path.mkString(".")}'"))
        val prev = nextOf(s.stateName)
        nextOf(s.stateName) = b.sel(phK, Seq(prev, v), Some(prev), bitsTy(s.width), s"${tag}_${s.stateName}")
      }

    applyPhase(isPh(0), rcvField, "nr")

    // ---- 7) apply 体：每条语句一个相位 ----
    /** action 读到的控制参数路径 → proc 里的当前值节点。 */
    def inputOf(path: Seq[String]): String =
      cur.getOrElse(path, throw new P4Error(
        s"XlsBackend：读到非 control 参数字段的路径 '${path.mkString(".")}'"))

    stmts.zipWithIndex.foreach { case (stmt, k) =>
      val phK = isPh(k + 1)
      val tag = s"ns$k"
      val outs: Map[Seq[String], String] = stmt match {
        case ActionCall(name, args, ln) =>
          val a = actionOf.getOrElse(name, throw new P4Error(s"行 $ln：未知 action '$name'"))
          val dag = actionDag(a, args, resolver, externMap)
          emitDagInto(b, dag, inputOf, s"${tag}_$name")

        case asg: Assign =>
          val ib = new Ir.Builder
          val lowering = new IrBuilder.ExprLowering(resolver, ib, externMap)
          val dag = Passes.runAll(ib.finish(Seq(lowering.lowerAssign(asg.path, asg.expr, Map.empty))))
          emitDagInto(b, dag, inputOf, tag)

        case TableApply(name, ln) =>
          val t = tableOf.getOrElse(name, throw new P4Error(s"行 $ln：未知 table '$name'"))
          if (t.isRuntime) throw new P4Error(
            s"行 $ln：运行时表 '${name}' 需要 key_out/rsp_in 通道（A2 暂不支持，见架构文档 §5）")
          if (t.entries.isEmpty) throw new P4Error(s"行 $ln：table '$name' 无 const entries")
          if (t.keys.exists(_.matchKind != "exact"))
            throw new P4Error(s"行 $ln：table '$name' 目前只支持 exact 匹配")

          // 运行时 key：只支持字段路径（demo2 口径）
          val keyVals: Seq[(String, Int)] = t.keys.map { ke =>
            ke.expr match {
              case Name(p, _) =>
                val s = slotAt.getOrElse(p, throw new P4Error(
                  s"行 ${ke.line}：table '$name' 的 key 路径 '${p.mkString(".")}' 不是 control 参数的字段"))
                (cur(s.path), s.width)
              case other => throw new P4Error(
                s"行 ${ke.line}：A2 只支持字段路径作 table key（got ${other.getClass.getSimpleName}）")
            }
          }

          // 逐表项：hit 条件（逐 key 元素比较后 and —— 与整体 concat 后比较等价）+ action 输出
          val entryHits: Seq[(String, Map[Seq[String], String])] = t.entries.filterNot(_.isDefault).map { e =>
            val a = actionOf.getOrElse(e.action, throw new P4Error(
              s"行 ${e.line}：table '$name' 引用了未知 action '${e.action}'"))
            if (e.keys.length != keyVals.length) throw new P4Error(
              s"行 ${e.line}：表项 key 个数 ${e.keys.length} 与表定义 ${keyVals.length} 不符")
            val conds = e.keys.zip(keyVals).map { case (ce, (kn, kw)) =>
              val cv = constOf(ce, e.line)
              b.eq(kn, b.literal(cv, bitsTy(kw), "ck"), "hit")
            }
            val hit = conds.reduceLeft((x, y) => b.binOp("and", x, y, "bits[1]", "hit"))
            (hit, emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_${e.action}"))
          }

          // default 表项的输出作为基线（优先级最低）；各表项按**声明序**优先 ⇒ 倒序嵌套 sel
          val defaultOuts: Map[Seq[String], String] = t.entries.find(_.isDefault).map { e =>
            val a = actionOf.getOrElse(e.action, throw new P4Error(
              s"行 ${e.line}：table '$name' 的 default 引用了未知 action '${e.action}'"))
            emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_default_${e.action}")
          }.getOrElse(Map.empty)

          val touched = (defaultOuts.keySet ++ entryHits.flatMap(_._2.keySet)).toSeq
          touched.map { path =>
            val s = slotAt(path)
            var acc = defaultOuts.getOrElse(path, cur(path))
            entryHits.reverse.foreach { case (hit, eouts) =>
              eouts.get(path).foreach { v =>
                acc = b.sel(hit, Seq(acc, v), Some(acc), bitsTy(s.width), s"${tag}_sel")
              }
            }
            path -> acc
          }.toMap

        case v: VarDecl => throw new P4Error(
          s"行 ${v.line}：XlsBackend 暂不支持 apply 体内的局部变量")
        case other => throw new P4Error(
          s"行 ${other.line}：XlsBackend 不支持该 control 语句（A2 只做 action/表/赋值）")
      }
      applyPhase(phK, outs, tag)
    }

    // ---- 8) 发：各字段拼回 PHV ----
    val outParts = slots.map(s => nextOf(s.stateName))
    val phv = if (outParts.length == 1) outParts.head
              else b.concat(outParts, bitsTy(phvWidth), "phv")
    val sOut = b.send(tok, phv, "phv_out", Some(isPh(phSend)), "snd")

    // ---- 9) 相位推进：k → k+1，发送相位 → 0 ----
    var nextPhase = ph
    (0 until nPhases).reverse.foreach { k =>
      val tgt = if (k == phSend) phaseLit(0) else phaseLit(k + 1)
      nextPhase = b.sel(isPh(k), Seq(nextPhase, tgt), Some(nextPhase), bitsTy(pw), s"np$k")
    }

    // ---- 10) token 汇聚与状态写回 ----
    val tokAll = b.afterAll(Seq(recvTok, sOut), "next_tok")
    b.nextValue("tok", tokAll)
    b.nextValue("phase", nextPhase)
    slots.foreach(s => b.nextValue(s.stateName, nextOf(s.stateName)))

    b.render
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

    if (prog.parsers.nonEmpty && prog.controls.nonEmpty)
      throw new P4Error(
        "XlsBackend：parser + control 混合的程序尚未支持（A2-4 只做纯 control，两条线在 A2-5 合并）")
    if (prog.parsers.isEmpty && prog.controls.isEmpty)
      throw new P4Error("XlsBackend：程序里既没有 parser 也没有 control")

    prog.parsers.foreach { p =>
      b ++= emitParser(p, prog, pkg, ids)
      b ++= "\n"
    }
    prog.controls.foreach { c =>
      b ++= emitControl(c, prog, pkg, ids)
      b ++= "\n"
    }
    b.toString
  }
}
