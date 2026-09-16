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
  * 1. '''一个 P4 程序 → 一个 proc'''（parser FSM 与 control 相位接在同一条 FSM 上）。
  *    跨 proc 互连需要 package 级 `chan` 声明 + 绑定（尚未验证），单 proc 是已验证形态。
  *
  * 2. '''每个字段一个 state'''（A2-5 起统一口径）：
  *    - header 字段 → `<inst>_<field>`；header 有效性 → `<inst>_v`；
  *    - meta 字段 → `md_<member>`。
  *    不用「整个 header 一个 state」是因为 control 的 action 是**局部修改**
  *    （`meta.cls = c`）——字段级让「改哪个就更新哪个」最直接，不必把整个 header
  *    拆开再拼回去；parser 的 `extract` 也天然是按字段切片的。
  *
  * 3. '''相位编码'''（`P` = parser 主状态数，`S` = control apply 体语句数）：
  *    {{{
  *    0 .. P-1        parser 各状态（按声明序；相位 0 兼收包）
  *    ctrlBase .. +S-1  control 各语句（每条一拍）    ctrlBase = P>0 ? P : 1
  *    phSend          send PHV，然后回相位 0
  *    }}}
  *    复位值 0 天然落在第一个 parser 状态（要求它叫 `start`）。
  *    `accept` → `ctrlBase`（进入 control）；`reject` → `phSend`（直接发）。
  *    无 parser 时相位 0 专用于收 PHV（ctrlBase = 1）。
  *
  * 4. '''PHV 输出的布局'''：按 header 声明序拼 `(valid, 字段…)`，再拼 meta 字段。
  *    与 [[ChiselBackend]] 的端口展平口径一致（先声明者在高位）。
  *
  * 5. '''通道操作都接同一个 `state_read(tok)`'''（P0 定案：不得串链），
  *    末尾 `after_all` 汇聚。
  *
  * 6. 复用 [[ChiselBackend.layoutParser]] 的字节偏移计算 —— 两份实现必然漂移。
  *
  * == 范围 ==
  *
  * - parser：extract / transition 子集；control：action / const 表 / 赋值；
  * - 未支持：extern 状态（Register/Counter）、运行时表、多 parser/control、
  *   apply 体内局部变量。
  */
object XlsBackend {

  /** 报文窗口宽度，与 Chisel 线一致（`io.in = Input(UInt(512.W))`）。 */
  private val PktWindowBits = 512

  /** 一个 header 实例（struct 成员）。 */
  private final case class HdrInst(inst: String, ht: HeaderType, totalWidth: Int)

  /** proc 里的一个**字段级** state 槽位。 */
  private final case class Slot(stateName: String, key: Seq[String], width: Int)

  private def bitsTy(w: Int): String = s"bits[$w]"

  /** 能表示 `0..n-1` 的最少位数。 */
  private def widthFor(n: Int): Int = math.max(1, BigInt(math.max(0, n - 1)).bitLength)

  /** `Ir.Op` → XLS op 名（与 [[IrText]] 同一张表）。 */
  private def opNameOf(op: Ir.Op): String = op match {
    case Ir.Add => "add"; case Ir.Sub => "sub"
    case Ir.And => "and"; case Ir.Or => "or"; case Ir.Xor => "xor"
    case Ir.Shl => "shll"; case Ir.Shr => "shrl"
    case Ir.Eq => "eq"; case Ir.Neq => "ne"
    case Ir.Lt => "ult"; case Ir.Le => "ule"
    case Ir.Gt => "ugt"; case Ir.Ge => "uge"
  }

  /** select 分支值必须是编译期数字字面量。 */
  private def constOf(e: Expr, line: Int): BigInt = e match {
    case Num(v, _, _) => v
    case _ => throw new P4Error(s"行 $line：需要数字字面量")
  }

  /** extern（Register/Counter）与 proc 的对接钩子：由 [[emitPipeline]] 按当前相位构造。
    *
    * - `read`：inst 的元素读（array_index）；
    * - `write`：inst 的元素写（array_update 链 + 相位 sel，最后写胜出）。
    * 索引/值都已发射成 proc 节点名。 */
  private final case class ExternHooks(
    read: (String, String) => String,
    write: (String, String, String) => Unit,
  )

  private val NoExtern = ExternHooks(
    (inst, _) => throw new P4Error(s"XlsBackend：读了未声明的 extern '$inst'"),
    (inst, _, _) => throw new P4Error(s"XlsBackend：写了未声明的 extern '$inst'"),
  )

  /** 把一个 [[Ir.Dag]] **内联发射**进 proc。
    *
    * 为什么不走 `fn` + `invoke`（A2-0 §2 已验 invoke 可用）：本工程是**单 proc**
    * 形态，action 不存在复用收益（XLS 对 invoke 也是内联展开，产物等价），
    * 而走 fn 需要额外确定「fn 形参/返回值如何与 proc 的 state 对接」——
    * 内联让 `Ir.InputRef` 直接落到 state 读节点上，少一层未验证的接口。
    *
    * @param inputOf `Ir.InputRef` 路径 → proc 里代表该路径当前值的节点名
    * @return `OutputWrite` 的路径 → 值节点名
    */
  private def emitDagInto(
    b: Builder, dag: Ir.Dag, inputOf: Seq[String] => String, hint: String,
    ext: ExternHooks = NoExtern,
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
        case Ir.RegRead(inst, idx, _, _) => ext.read(inst, go(idx))
        case other => throw new P4Error(
          s"XlsBackend：proc 内暂不支持 $other 节点")
      }
    })
    dag.outputs.foreach {
      case Ir.OutputWrite(_, v, _) => go(v)
      // extern 写：RegWrite 直接写值；CounterAdd 读改写（旧值 + delta）
      case Ir.RegWrite(inst, idx, v, _, _) => ext.write(inst, go(idx), go(v))
      case Ir.CounterAdd(inst, idx, delta, w, _) =>
        val cur = ext.read(inst, go(idx))
        val inc = b.binOp("add", cur, go(delta), bitsTy(w), s"${hint}_cnt")
        ext.write(inst, go(idx), inc)
      case s => throw new P4Error(s"XlsBackend：proc 内暂不支持 $s 汇点")
    }
    dag.outputs.collect { case Ir.OutputWrite(path, v, _) => path -> go(v) }.toMap
  }

  /** 一个 action 的 body → [[Ir.Dag]]（复用 Chisel 线的 [[IrBuilder.ExprLowering]]）。
    *
    * **同一套语义只写一份实现**：宽度推断、位宽 fit、运算符映射全部走既有 lowering。
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
      // extern 方法调用：Register.write / Counter.count（read 是表达式侧，走 lower）
      case mc: MethodCall => lowering.lowerMethodCall(mc, binds)
      case st => throw new P4Error(
        s"行 ${st.line}：XlsBackend 的 action 体暂只支持赋值与 extern 方法调用")
    }
    Passes.runAll(ib.finish(outs))
  }

  // ------------------------------------------------------------------
  // 程序 → proc（parser 与 control 同一条 FSM）
  // ------------------------------------------------------------------

  /** 发射整个程序的 proc。
    *
    * @param pkg package 名（进 IR 的 `package` 行）
    * @param ids 全局 id 分配器（跨 fn/proc 共用，A2-0 硬规则）
    */
  private def emitPipeline(
    parserOpt: Option[ParserDecl], ctrlOpt: Option[ControlDecl],
    prog: P4Program, pkg: String, ids: IdGen,
  ): String = {
    val structs = prog.structs.map(st => st.name -> st).toMap
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap

    // ---- 0) 前置检查 ----
    ctrlOpt.foreach { c =>
      if (c.applyBody.isEmpty) throw new P4Error(s"XlsBackend：control '${c.name}' 的 apply 体为空")
    }

    // ---- 1) header 实例：来源是 parser 的 out struct，无 parser 时取 control 里含 header 的 struct 参数 ----
    val hdrStruct: StructType = parserOpt match {
      case Some(p) =>
        val o = p.params.find(_.direction == "out").getOrElse(
          throw new P4Error(s"XlsBackend：parser '${p.name}' 缺少 out 参数"))
        structs.getOrElse(o.typeName, throw new P4Error(
          s"XlsBackend：parser '${p.name}' 的 out 参数类型 '${o.typeName}' 不是 struct"))
      case None =>
        val c = ctrlOpt.get
        c.params.flatMap { p =>
          structs.get(p.typeName)
            .filter(st => st.members.exists(m => !m.isBits && headerTypes.contains(m.typeName)))
        }.headOption.getOrElse(throw new P4Error(
          s"XlsBackend：control '${c.name}' 没有含 header 的 struct 参数"))
    }
    val insts: Seq[HdrInst] = hdrStruct.members.filterNot(_.isBits).map { m =>
      val ht = headerTypes.getOrElse(m.typeName,
        throw new P4Error(s"XlsBackend：未知 header 类型 '${m.typeName}'"))
      HdrInst(m.name, ht, ht.fields.map(_.width).sum)
    }
    if (insts.isEmpty) throw new P4Error(s"XlsBackend：'${hdrStruct.name}' 里没有 header 成员")

    // ---- 2) meta 字段：control 参数里「成员全是 bits」的 struct ----
    val metaSlots: Seq[Slot] = ctrlOpt.toSeq.flatMap { c =>
      c.params.flatMap { p =>
        structs.get(p.typeName) match {
          case Some(st) if st.members.nonEmpty && st.members.forall(_.isBits) =>
            st.members.map(m => Slot(s"md_${m.name}", Seq(p.name, m.name), m.bitsWidth))
          case _ => Seq.empty
        }
      }
    }

    // ---- 3) 槽位表 ----
    val validSlotOf: Map[String, Slot] = insts.map { hi =>
      hi.inst -> Slot(s"${hi.inst}_v", Seq(hi.inst, "#valid"), 1)
    }.toMap
    val fieldSlotOf: Map[Seq[String], Slot] = insts.flatMap { hi =>
      hi.ht.fields.map(f => Seq(hi.inst, f.name) -> Slot(s"${hi.inst}_${f.name}", Seq(hi.inst, f.name), f.width))
    }.toMap
    val metaSlotOf: Map[Seq[String], Slot] = metaSlots.map(s => s.key -> s).toMap

    /** AST 路径 → 槽位。两段 = meta 字段（`param.member`），三段 = header 字段（`param.inst.field`）。 */
    def slotForPath(path: Seq[String], line: Int): Slot = path match {
      case Seq(_, m, f) if fieldSlotOf.contains(Seq(m, f)) => fieldSlotOf(Seq(m, f))
      case Seq(p, m) if metaSlotOf.contains(Seq(p, m)) => metaSlotOf(Seq(p, m))
      case _ => throw new P4Error(
        s"行 $line：路径 '${path.mkString(".")}' 不对应 PHV 里的任何字段" +
          "（header 字段须写 param.instance.field，meta 字段须写 param.member）")
    }

    // ---- 4) PHV 布局：header 按声明序拼 (valid, 字段…)，再拼 meta ----
    val hdrPhvSlots: Seq[Slot] = insts.flatMap { hi =>
      Seq(validSlotOf(hi.inst)) ++ hi.ht.fields.map(f => fieldSlotOf(Seq(hi.inst, f.name)))
    }
    val phvSlots: Seq[Slot] = hdrPhvSlots ++ metaSlots
    val phvWidth = phvSlots.map(_.width).sum
    val allSlots: Seq[Slot] = (hdrPhvSlots ++ metaSlots).distinct
    val dupState = allSlots.groupBy(_.stateName).filter(_._2.size > 1).keys.toSeq
    if (dupState.nonEmpty)
      throw new P4Error(s"XlsBackend：展平后 state 重名：${dupState.mkString(", ")}")

    // ---- 5) 相位编码 ----
    val mainStates: Seq[String] =
      parserOpt.map(_.states.map(_.name).filter(n => n != "accept" && n != "reject")).getOrElse(Seq.empty)
    if (parserOpt.isDefined) {
      if (mainStates.isEmpty) throw new P4Error(s"XlsBackend：parser 没有任何状态")
      if (mainStates.head != "start")
        throw new P4Error(s"XlsBackend：parser 的第一个状态必须是 'start'（got '${mainStates.head}'）")
    }
    val pCount = mainStates.length
    val hasParser = parserOpt.isDefined
    val ctrlBase = if (pCount > 0) pCount else 1
    val stmts = ctrlOpt.map(_.applyBody).getOrElse(Seq.empty)
    val phSend = ctrlBase + stmts.length
    val nPhases = phSend + 1
    val pw = widthFor(nPhases)
    val phaseOf: Map[String, Int] = mainStates.zipWithIndex.toMap

    // ---- 6) 声明 ----
    val procName = (parserOpt, ctrlOpt) match {
      case (Some(p), None) => s"${p.name}_parser"
      case (None, Some(c)) => s"${c.name}_control"
      case (Some(_), Some(c)) => s"${c.name}_pipeline"
      case _ => throw new P4Error("XlsBackend：程序里既没有 parser 也没有 control")
    }
    val inChan = if (hasParser) "pkt_in" else "phv_in"
    val inWidth = if (hasParser) PktWindowBits else phvWidth
    val b = new Builder(pkg, procName, ids)
    b.declareChan(inChan, "receive", inWidth, "valid_data")
    b.declareChan("phv_out", "send", phvWidth, "ready_valid")
    b.declareState("phase", bitsTy(pw), "0")
    if (hasParser) b.declareState("pkt", bitsTy(PktWindowBits), "0")
    allSlots.foreach(s => b.declareState(s.stateName, bitsTy(s.width), "0"))

    // ---- 6b) extern 状态：每个实例一个**数组** state（A2-5b 前置实验定案：方案 a）。
    // 与 PHV 槽位的本质区别：extern **跨包持久**，不参与相位 0 的清零。
    case class ExtState(name: String, inst: String, width: Int, size: Int)
    val extStates: Seq[ExtState] = ctrlOpt.toSeq.flatMap(_.externs).map { e =>
      val kind = e.kind match {
        case "Register" => "reg"
        case "Counter" => "cnt"
        case k => throw new P4Error(s"行 ${e.line}：未知 extern '$k'")
      }
      ExtState(s"${kind}_${e.name}", e.name, e.width, e.size)
    }
    val clash = extStates.map(_.name).toSet.intersect(allSlots.map(_.stateName).toSet)
    if (clash.nonEmpty) throw new P4Error(s"XlsBackend：extern state 与 PHV 槽位重名：${clash.mkString(", ")}")
    extStates.foreach { es =>
      val initVals = Seq.fill(es.size)("0").mkString("[", ", ", "]")
      b.declareState(es.name, s"bits[${es.width}][${es.size}]", initVals)
    }
    val extByName: Map[String, ExtState] = extStates.map(es => es.inst -> es).toMap

    // ---- 6c) extern 观察通道（对应 Chisel 线的 io.ex_<name>）。
    // **必须有**：没有对外可观测性时，XLS 会把「只写不读」的 state 当死代码
    // 优化掉（A2-5b 实测：demo5 的 counter 整个消失）。走 valid_data（无背压）
    // 且不带谓词——观察口尽力而为，绝不反压主数据通路。
    // 布局：元素 0 在最高位（与 PHV「先声明者在高位」同原则）。
    extStates.foreach(es =>
      b.declareChan(s"ex_${es.inst}", "send", es.width * es.size, "valid_data"))

    // ---- 7) 读状态 + 相位判据 ----
    val tok = b.stateRead("tok", "token", "tok")
    val ph = b.stateRead("phase", bitsTy(pw), "phase")
    val pkt = if (hasParser) Some(b.stateRead("pkt", bitsTy(PktWindowBits), "pkt")) else None
    val cur: Map[String, String] =
      allSlots.map(s => s.stateName -> b.stateRead(s.stateName, bitsTy(s.width), s.stateName)).toMap
    // extern 数组：当前读节点（读共用）与下一拍值（写更新）
    val extCur = mutable.HashMap.empty[String, String]
    val extNext = mutable.HashMap.empty[String, String]
    extStates.foreach { es =>
      val rd = b.stateRead(es.name, s"bits[${es.width}][${es.size}]", es.name)
      extCur(es.inst) = rd
      extNext(es.inst) = rd
    }
    val phaseLit: Seq[String] = (0 until nPhases).map(k => b.literal(k, bitsTy(pw), s"k$k"))
    val isPh: Seq[String] = phaseLit.zipWithIndex.map { case (cl, k) => b.eq(ph, cl, s"is_ph$k") }
    val oneBit = b.literal(1, "bits[1]", "one")
    val zeroBit = b.literal(0, "bits[1]", "zero")

    /** 每个 state 的下一拍值：初值 = 当前读出值（未改 = 保持原值），逐相位 sel 更新。 */
    val nextOf = mutable.LinkedHashMap.empty[String, String]
    allSlots.foreach(s => nextOf(s.stateName) = cur(s.stateName))

    /** 把某个相位对若干 state 的修改并入 next 值（sel 的 cases = [假臂, 真臂]）。 */
    def selNext(phK: String, stateName: String, width: Int, value: String, tag: String): Unit = {
      val prev = nextOf(stateName)
      nextOf(stateName) = b.sel(phK, Seq(prev, value), Some(prev), bitsTy(width), s"${tag}_$stateName")
    }

    // ---- 8) 输入 ----
    val (recvTok, rcvData) = b.receive(tok, inChan, bitsTy(inWidth), Some(isPh(0)), "rcv")
    // 无 parser：相位 0 把输入 PHV 拆成各字段（字段序 = PHV 布局，先声明在高位）
    val rcvOf: Map[String, String] =
      if (hasParser) Map.empty
      else {
        var off = phvWidth
        phvSlots.map { s =>
          off -= s.width
          s.stateName -> b.bitSlice(rcvData, off, s.width, s"fi_${s.stateName}")
        }.toMap
      }

    // ---- 9) 相位 0：**清所有槽位**（每包从头开始，绝不跨包残留），再叠本包的处理 ----
    // 这里把 header 的**数据字段也一并清零**，而不只是 valid：
    // valid=0 时数据本无意义，但清零让输出**确定**（下游与验证都能依赖），
    // 代价是每字段一个 mux。extract 的 sel 叠在外层，所以同拍仍以 extract 为准。
    allSlots.foreach { s =>
      val z = b.literal(0, bitsTy(s.width), s"z_${s.stateName}")
      selNext(isPh(0), s.stateName, s.width, z, "nz")
    }
    rcvOf.foreach { case (sn, v) =>
      val s = allSlots.find(_.stateName == sn).get
      selNext(isPh(0), s.stateName, s.width, v, "nr")
    }

    // ---- 10) parser：extract 按**字段**切片，并在本相位采样 ----
    val layouts = parserOpt.map(p => ChiselBackend.layoutParser(p, prog)).getOrElse(Map.empty)
    // (相位, 实例, 字段) -> 本相位从报文里切出来的字段值
    val extractNode = mutable.HashMap.empty[(Int, String, String), String]
    val instOf: Map[String, HdrInst] = insts.map(hi => hi.inst -> hi).toMap

    mainStates.zipWithIndex.foreach { case (stName, k) =>
      val lay = layouts.getOrElse(stName,
        throw new P4Error(s"XlsBackend：parser 状态 '$stName' 未在布局中"))
      // 相位 0 用刚收到的报文（pktIn），其余相位用 pkt state
      val src = if (k == 0) rcvData else pkt.get
      lay.extracts.foreach { case (path, ht, byteOff) =>
        if (path.length != 2)
          throw new P4Error(s"XlsBackend：extract 路径必须是 param.instance（got '${path.mkString(".")}'）")
        val inst = path(1)
        val hi = instOf.getOrElse(inst, throw new P4Error(s"XlsBackend：未知 header 实例 '$inst'"))
        if (hi.totalWidth % 8 != 0)
          throw new P4Error(s"XlsBackend：header '${ht.name}' 总宽 ${hi.totalWidth} bit 非字节对齐")
        var prefix = 0
        hi.ht.fields.foreach { f =>
          val shift = PktWindowBits - 8 * byteOff - prefix - f.width
          if (shift < 0) throw new P4Error(
            s"XlsBackend：header '${ht.name}.${f.name}' 在偏移 $byteOff 超出 $PktWindowBits-bit 窗口")
          val w = b.bitSlice(src, shift, f.width, s"w_${inst}_${f.name}")
          extractNode((k, inst, f.name)) = w
          prefix += f.width
        }
        // 数据字段写回（本相位）
        hi.ht.fields.foreach { f =>
          val s = fieldSlotOf(Seq(inst, f.name))
          selNext(isPh(k), s.stateName, s.width, extractNode((k, inst, f.name)), s"nh_$inst")
        }
        // 有效位置 1
        val vs = validSlotOf(inst)
        selNext(isPh(k), vs.stateName, 1, oneBit, s"nv_$inst")
      }
    }

    /** parser 转移目标 → 相位节点。`accept` 进入 control，`reject` 直接发。 */
    def phaseNodeOf(target: String): String =
      if (target == "accept") phaseLit(ctrlBase)
      else if (target == "reject") phaseLit(phSend)
      else phaseLit(phaseOf.getOrElse(target,
        throw new P4Error(s"XlsBackend：parser 转移到了未知状态 '$target'")))

    // ---- 11) control：每条语句一个相位 ----
    ctrlOpt.foreach { c =>
      val resolver = new IrBuilder.WidthResolver(headerTypes, structs, c.params)
      val externMap = c.externs.map(e => e.name -> e).toMap
      val actionOf = c.actions.map(a => a.name -> a).toMap
      val tableOf = c.tables.map(t => t.name -> t).toMap

      /** action 读到的路径 → proc 里的当前值节点。 */
      def inputOf(path: Seq[String]): String =
        cur.getOrElse(slotForPath(path, 0).stateName, throw new P4Error(
          s"XlsBackend：内部错误 —— 路径 '${path.mkString(".")}' 无对应的 state 读"))

      stmts.zipWithIndex.foreach { case (stmt, k) =>
        val phK = isPh(ctrlBase + k)
        val tag = s"ns$k"

        /** extern 与 proc 的对接（每个语句相位重建一次，捕获本相位的谓词）：
          * 读 = array_index；写 = array_update 链 + 相位 sel ——
          * 同拍多次写后写胜出，不同相位的写互不覆盖。 */
        def extRead(inst: String, idxNode: String): String = {
          val es = extByName.getOrElse(inst, throw new P4Error(s"XlsBackend：未声明的 extern '$inst'"))
          b.arrayIndex(extCur(inst), idxNode, bitsTy(es.width), s"ar_${es.name}")
        }
        def extWrite(inst: String, idxNode: String, valNode: String): Unit = {
          val es = extByName.getOrElse(inst, throw new P4Error(s"XlsBackend：未声明的 extern '$inst'"))
          val arrTy = s"bits[${es.width}][${es.size}]"
          val prev = extNext(inst)
          val upd = b.arrayUpdate(prev, valNode, idxNode, arrTy, s"au_${tag}_$inst")
          extNext(inst) = b.sel(phK, Seq(prev, upd), Some(prev), arrTy, s"ne_${tag}_$inst")
        }
        val ext = ExternHooks(extRead, extWrite)

        val outs: Map[Seq[String], String] = stmt match {
          case ActionCall(name, args, ln) =>
            val a = actionOf.getOrElse(name, throw new P4Error(s"行 $ln：未知 action '$name'"))
            emitDagInto(b, actionDag(a, args, resolver, externMap), inputOf, s"${tag}_$name", ext)

          case asg: Assign =>
            val ib = new Ir.Builder
            val lowering = new IrBuilder.ExprLowering(resolver, ib, externMap)
            val dag = Passes.runAll(ib.finish(Seq(lowering.lowerAssign(asg.path, asg.expr, Map.empty))))
            emitDagInto(b, dag, inputOf, tag, ext)

          case TableApply(name, ln) =>
            val t = tableOf.getOrElse(name, throw new P4Error(s"行 $ln：未知 table '$name'"))
            if (t.isRuntime) throw new P4Error(
              s"行 $ln：运行时表 '${name}' 需要 key_out/rsp_in 通道（暂不支持，见架构文档 §5）")
            if (t.entries.isEmpty) throw new P4Error(s"行 $ln：table '$name' 无 const entries")
            if (t.keys.exists(_.matchKind != "exact"))
              throw new P4Error(s"行 $ln：table '$name' 目前只支持 exact 匹配")

            // 运行时 key：只支持字段路径
            val keyVals: Seq[(String, Int)] = t.keys.map { ke =>
              ke.expr match {
                case Name(p, _) =>
                  val s = slotForPath(p, ke.line)
                  (cur(s.stateName), s.width)
                case other => throw new P4Error(
                  s"行 ${ke.line}：只支持字段路径作 table key（got ${other.getClass.getSimpleName}）")
              }
            }

            // 逐表项：hit 条件（逐 key 元素比较后 and —— 与整体 concat 后比较等价）+ action 输出
            val entryHits: Seq[(String, Map[Seq[String], String])] =
              t.entries.filterNot(_.isDefault).map { e =>
                val a = actionOf.getOrElse(e.action, throw new P4Error(
                  s"行 ${e.line}：table '$name' 引用了未知 action '${e.action}'"))
                if (e.keys.length != keyVals.length) throw new P4Error(
                  s"行 ${e.line}：表项 key 个数 ${e.keys.length} 与表定义 ${keyVals.length} 不符")
                val conds = e.keys.zip(keyVals).map { case (ce, (kn, kw)) =>
                  val cv = constOf(ce, e.line)
                  b.eq(kn, b.literal(cv, bitsTy(kw), "ck"), "hit")
                }
                val hit = conds.reduceLeft((x, y) => b.binOp("and", x, y, "bits[1]", "hit"))
                (hit, emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_${e.action}", ext))
              }

            // default 表项的输出作为基线（优先级最低）；各表项按**声明序**优先 ⇒ 倒序嵌套 sel
            val defaultOuts: Map[Seq[String], String] = t.entries.find(_.isDefault).map { e =>
              val a = actionOf.getOrElse(e.action, throw new P4Error(
                s"行 ${e.line}：table '$name' 的 default 引用了未知 action '${e.action}'"))
              emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_dflt_${e.action}", ext)
            }.getOrElse(Map.empty)

            val touched = (defaultOuts.keySet ++ entryHits.flatMap(_._2.keySet)).toSeq
            touched.map { path =>
              val s = slotForPath(path, 0)
              var acc = defaultOuts.getOrElse(path, cur(s.stateName))
              entryHits.reverse.foreach { case (hit, eouts) =>
                eouts.get(path).foreach { v =>
                  acc = b.sel(hit, Seq(acc, v), Some(acc), bitsTy(s.width), s"${tag}_sel")
                }
              }
              path -> acc
            }.toMap

          case v2: VarDecl => throw new P4Error(
            s"行 ${v2.line}：暂不支持 apply 体内的局部变量")
          case other => throw new P4Error(
            s"行 ${other.line}：不支持该 control 语句（目前只做 action/表/赋值）")
        }
        outs.foreach { case (path, v) =>
          val s = slotForPath(path, 0)
          selNext(phK, s.stateName, s.width, v, tag)
        }
      }
    }

    // ---- 12) 输出 PHV ----
    val outParts = phvSlots.map(s => nextOf(s.stateName))
    val phv = if (outParts.length == 1) outParts.head
              else b.concat(outParts, bitsTy(phvWidth), "phv")
    val sOut = b.send(tok, phv, "phv_out", Some(isPh(phSend)), "snd")

    // ---- 12b) extern 观察值：逐元素 array_index + concat（元素 0 在最高位）。
    // 数据取 extNext（本拍处理后的值）—— 观测者看到的是「已含本拍更新」的结果。
    val extObsSends: Seq[String] = extStates.map { es =>
      val idxW = widthFor(es.size)
      val elems = (0 until es.size).map { i =>
        val ix = b.literal(i, bitsTy(idxW), s"oi_${es.inst}_$i")
        b.arrayIndex(extNext(es.inst), ix, bitsTy(es.width), s"oe_${es.inst}_$i")
      }
      val obs = if (elems.length == 1) elems.head
                else b.concat(elems, bitsTy(es.width * es.size), s"obs_${es.inst}")
      b.send(tok, obs, s"ex_${es.inst}", None, s"es_${es.inst}")
    }

    // ---- 13) 相位推进 ----
    val nextPkt: Option[String] = pkt.map { p =>
      b.sel(isPh(0), Seq(p, rcvData), Some(p), bitsTy(PktWindowBits), "next_pkt")
    }
    var nextPhase = ph // 兜底：各 isPh 互斥且全覆盖，正常不会走到
    (0 until nPhases).reverse.foreach { k =>
      val tgt =
        if (k == phSend) phaseLit(0)                        // 发完回相位 0
        else if (k >= ctrlBase) phaseLit(k + 1)             // control 相位顺序推进
        else if (pCount == 0) phaseLit(ctrlBase)            // 纯 control：相位 0 专用于收 PHV
        else {                                              // parser 相位的 transition
          val stName = mainStates(k)
          layouts(stName).trans match {
            case Goto(t, _) => phaseNodeOf(t)
            case Select(value, cases, deft, line) =>
              val path = value match {
                case Name(pp, _) => pp
                case _ => throw new P4Error(s"行 $line：只支持字段路径作为 select 值")
              }
              if (path.length != 3) throw new P4Error(
                s"行 $line：select 值必须是 param.instance.field（got '${path.mkString(".")}'）")
              val inst = path(1)
              val fname = path(2)
              val hi = instOf.getOrElse(inst, throw new P4Error(s"XlsBackend：未知 header 实例 '$inst'"))
              val f = hi.ht.fields.find(_.name == fname).getOrElse(
                throw new P4Error(s"XlsBackend：header '${hi.ht.name}' 无字段 '$fname'"))
              val word = extractNode.getOrElse((k, inst, fname), throw new P4Error(
                s"行 $line：select 值 '${path.mkString(".")}' 所在的 header 未在本状态 extract" +
                  "（与 Chisel 线同规则）"))
              // 倒序嵌套 sel ⇒ 声明在前面的 case 优先级高
              var acc = phaseNodeOf(deft)
              cases.reverse.foreach { case (ce, tgt) =>
                val cv = constOf(ce, line)
                val cond = b.eq(word, b.literal(cv, bitsTy(f.width), "ck"), s"c_$fname")
                acc = b.sel(cond, Seq(acc, phaseNodeOf(tgt)), None, bitsTy(pw), "sw")
              }
              acc
          }
        }
      nextPhase = b.sel(isPh(k), Seq(nextPhase, tgt), Some(nextPhase), bitsTy(pw), s"np$k")
    }

    // ---- 14) token 汇聚与状态写回 ----
    val tokAll = b.afterAll(Seq(recvTok, sOut) ++ extObsSends, "next_tok")
    b.nextValue("tok", tokAll)
    b.nextValue("phase", nextPhase)
    nextPkt.foreach(v => b.nextValue("pkt", v))
    allSlots.foreach(s => b.nextValue(s.stateName, nextOf(s.stateName)))
    // extern 数组写回（跨包持久：不在相位 0 清零，只在这里写本拍的变化）
    extStates.foreach(es => b.nextValue(es.name, extNext(es.inst)))

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

    if (prog.parsers.isEmpty && prog.controls.isEmpty)
      throw new P4Error("XlsBackend：程序里既没有 parser 也没有 control")
    if (prog.parsers.length > 1)
      throw new P4Error(s"XlsBackend：暂不支持多个 parser（有 ${prog.parsers.length} 个）")
    if (prog.controls.length > 1)
      throw new P4Error(s"XlsBackend：暂不支持多个 control（有 ${prog.controls.length} 个）")

    b ++= emitPipeline(prog.parsers.headOption, prog.controls.headOption, prog, pkg, ids)
    b ++= "\n"
    b.toString
  }
}
