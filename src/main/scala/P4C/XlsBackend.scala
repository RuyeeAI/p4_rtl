package P4C

import P4C.Ast._
import P4C.XlsProc.{Builder, ChanDecl, IdGen}

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

  /** proc 间 FIFO 通道的深度（用户需求：查找结果 / 包头 FIFO 对齐）。
    * codegen 为每条 loopback chan 实例化一个 `xls_fifo_wrapper(depth)`。 */
  private val FifoDepth = 4

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
    * - `write`：inst 的元素写（array_update 链，最后写胜出）。
    *   `pred` 是**额外谓词**（如 const 表条目的 hit）—— array_update 是「效果」
    *   而非数据，无法像 PHV 字段那样靠数据 sel 选择，**必须谓词门控**，否则
    *   未命中条目的写也会生效（demo9 首跑踩过：miss 条目的累加照样发生）。
    * 索引/值都已发射成 proc 节点名。 */
  private final case class ExternHooks(
    read: (String, String) => String,
    write: (String, String, String, Option[String]) => Unit,
  )

  private val NoExtern = ExternHooks(
    (inst, _) => throw new P4Error(s"XlsBackend：读了未声明的 extern '$inst'"),
    (inst, _, _, _) => throw new P4Error(s"XlsBackend：写了未声明的 extern '$inst'"),
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
    ext: ExternHooks = NoExtern, pred: Option[String] = None,
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
      // extern 写：RegWrite 直接写值；CounterAdd 读改写（旧值 + delta）。谓词透传。
      case Ir.RegWrite(inst, idx, v, _, _) => ext.write(inst, go(idx), go(v), pred)
      case Ir.CounterAdd(inst, idx, delta, w, _) =>
        val cur = ext.read(inst, go(idx))
        val inc = b.binOp("add", cur, go(delta), bitsTy(w), s"${hint}_cnt")
        ext.write(inst, go(idx), inc, pred)
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

  /** action body → Dag，形参用**伪路径** `$arg.<name>`（[[emitDagInto]] 的 inputOf
    * 负责映射到 proc 域节点）—— runtime 表的形参来自 rsp 位段切片，不是 AST 常量。 */
  private def actionDagArgNodes(
    a: ActionDecl, resolver: IrBuilder.WidthResolver, externs: Map[String, ExternInst],
  ): Ir.Dag = {
    val ib = new Ir.Builder
    val lowering = new IrBuilder.ExprLowering(resolver, ib, externs)
    val binds: IrBuilder.Bindings = a.params.map { p =>
      val id = ib.add(Ir.InputRef(Seq("$arg", p.name), p.width))
      p.name -> ((id, p.width))
    }.toMap
    val outs = a.body.map {
      case asg: Assign => lowering.lowerAssign(asg.path, asg.expr, binds)
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
  /** 段（多 proc 拆分的单元）。一个段 = 一个 XLS proc。
    *
    * - [[ParserSeg]]：报文解析（extract FSM + 通道收发）
    * - [[CtrlSeg]]  ：一段连续的 control 语句（动作 / 静态表 / runtime 查找组）
    * - [[DeparserSeg]]：报文重组（emit 序拼接，无 PHV state 的纯透传段）
    */
  private sealed trait Seg
  private final case class ParserSeg(p: ParserDecl) extends Seg
  private final case class CtrlSeg(
    stmts: Seq[(Int, Ast.Stmt)],          // 段内语句（原始全局索引）
    externInsts: Set[String],             // 段内用到的 extern 实例
    rtTableNames: Set[String],            // 段内 apply 的 runtime 表
  ) extends Seg
  private final case class DeparserSeg(d: DeparserDecl) extends Seg

  /** 一个 emit 的通道约定：输入通道名/宽 + 输出通道名/布局。 */
  private final case class SegIo(inChan: String, inWidth: Int,
                                 outChan: String, outSlots: Option[Seq[Slot]])
  // outSlots = None ⇒ 输出全槽位拼接（PHV 透传）；Some(ls) ⇒ 按 ls 拼接（最终输出段）。

  /** 槽位推导结果（emitSeg 与 Top 编排共用，消除两处重复推导的漂移）。 */
  private final case class SlotTables(
    insts: Seq[HdrInst],
    validSlotOf: Map[String, Slot],
    fieldSlotOf: Map[Seq[String], Slot],
    metaSlotOf: Map[Seq[String], Slot],
    hdrPhvSlots: Seq[Slot],
    metaSlots: Seq[Slot],
    phvSlots: Seq[Slot],
    phvWidth: Int,
    allSlots: Seq[Slot],
  )

  /** header 实例 / meta / PHV 布局推导（v2 的 1-4 节，提取成纯函数）。 */
  private def slotTablesOf(prog: P4Program, parserOpt: Option[ParserDecl],
                           ctrlOpt: Option[ControlDecl]): SlotTables = {
    val structs = prog.structs.map(st => st.name -> st).toMap
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap
    val hdrStruct: StructType = parserOpt match {
      case Some(p) =>
        val o = p.params.find(_.direction == "out").getOrElse(
          throw new P4Error(s"XlsBackend：parser '${p.name}' 缺少 out 参数"))
        structs.getOrElse(o.typeName, throw new P4Error(
          s"XlsBackend：parser '${p.name}' 的 out 参数类型 '${o.typeName}' 不是 struct"))
      case None =>
        val c = ctrlOpt.getOrElse(throw new P4Error(
          "XlsBackend：内部错误 —— deparser/纯 control 段缺 control 引用"))
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
    val metaSlots: Seq[Slot] = ctrlOpt.toSeq.flatMap { c =>
      c.params.flatMap { p =>
        structs.get(p.typeName) match {
          case Some(st) if st.members.nonEmpty && st.members.forall(_.isBits) =>
            st.members.map(m => Slot(s"md_${m.name}", Seq(p.name, m.name), m.bitsWidth))
          case _ => Seq.empty
        }
      }
    }
    val validSlotOf: Map[String, Slot] = insts.map { hi =>
      hi.inst -> Slot(s"${hi.inst}_v", Seq(hi.inst, "#valid"), 1)
    }.toMap
    val fieldSlotOf: Map[Seq[String], Slot] = insts.flatMap { hi =>
      hi.ht.fields.map(f => Seq(hi.inst, f.name) -> Slot(s"${hi.inst}_${f.name}", Seq(hi.inst, f.name), f.width))
    }.toMap
    val metaSlotOf: Map[Seq[String], Slot] = metaSlots.map(s => s.key -> s).toMap
    val hdrPhvSlots: Seq[Slot] = insts.flatMap { hi =>
      Seq(validSlotOf(hi.inst)) ++ hi.ht.fields.map(f => fieldSlotOf(Seq(hi.inst, f.name)))
    }
    val phvSlots: Seq[Slot] = hdrPhvSlots ++ metaSlots
    val allSlots: Seq[Slot] = (hdrPhvSlots ++ metaSlots).distinct
    val dupState = allSlots.groupBy(_.stateName).filter(_._2.size > 1).keys.toSeq
    if (dupState.nonEmpty)
      throw new P4Error(s"XlsBackend：展平后 state 重名：${dupState.mkString(", ")}")
    SlotTables(insts, validSlotOf, fieldSlotOf, metaSlotOf, hdrPhvSlots, metaSlots,
      phvSlots, phvSlots.map(_.width).sum, allSlots)
  }

  private def emitSeg(
    seg: Seg,
    segName: String,
    io: SegIo,
    isTop: Boolean,
    ctrlOpt: Option[ControlDecl],
    prog: P4Program, pkg: String, ids: IdGen,
  ): String = {
    val parserOpt = seg match { case ParserSeg(p) => Some(p); case _ => None }
    val deparserOpt = seg match { case DeparserSeg(d) => Some(d); case _ => None }
    val structs = prog.structs.map(st => st.name -> st).toMap
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap

    // ---- 1-4) header 实例 / meta / 槽位表 / PHV 布局（共享推导）----
    val st = slotTablesOf(prog, parserOpt, ctrlOpt)
    val SlotTables(insts, validSlotOf, fieldSlotOf, metaSlotOf, hdrPhvSlots, metaSlots,
      phvSlots, phvWidth, allSlots) = st

    /** AST 路径 → 槽位。两段 = meta 字段（`param.member`），三段 = header 字段（`param.inst.field`）。 */
    def slotForPath(path: Seq[String], line: Int): Slot = path match {
      case Seq(_, m, f) if fieldSlotOf.contains(Seq(m, f)) => fieldSlotOf(Seq(m, f))
      case Seq(p, m) if metaSlotOf.contains(Seq(p, m)) => metaSlotOf(Seq(p, m))
      case _ => throw new P4Error(
        s"行 $line：路径 '${path.mkString(".")}' 不对应 PHV 里的任何字段" +
          "（header 字段须写 param.instance.field，meta 字段须写 param.member）")
    }

    // ---- 4b) 输出布局：由段约定（io.outSlots）决定。
    //   Some(ls) ⇒ 最终输出段（有 deparser：emit 序拼接，**不含 metadata**；
    //             无 deparser：PHV 全量拼接，通道名 phv_out —— 既有行为）。
    //   None     ⇒ 中间段：PHV 全量透传（phvSlots）。
    // 子集语义：无条件 emit —— 没有 isValid/变长，invalid header 输出全 0（valid=0）。
    if (prog.deparser.isDefined && parserOpt.isEmpty && ctrlOpt.isEmpty)
      throw new P4Error("XlsBackend：deparser 依赖 parser 产出的报文窗口，纯 control 程序不支持 deparser")
    val outSlots: Seq[Slot] = io.outSlots.getOrElse(phvSlots)
    val outChan = io.outChan
    val outWidth = outSlots.map(_.width).sum

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
    // 段内语句（CtrlSeg 携带；parser/deparser 段为空）
    val stmts: Seq[Ast.Stmt] = seg match {
      case CtrlSeg(ss, _, _) => ss.map(_._2)
      case _                 => Seq.empty
    }
    // ---- 5a-2) 并行查找组：静态校验在 emitProgram 全局做（组的合法性跨段一致）；
    // 这里只保留段的映射与局部索引。op 契约表（tableOfAll/actionOfAll）供 11 节发射用。
    val tableOfAll: Map[String, Ast.TableDecl] =
      ctrlOpt.map(_.tables.map(t => t.name -> t).toMap).getOrElse(Map.empty)
    val actionOfAll: Map[String, Ast.ActionDecl] =
      ctrlOpt.map(_.actions.map(a => a.name -> a).toMap).getOrElse(Map.empty)
    val groupOfTable: Map[String, Ast.LookupGroup] =
      prog.lookupGroups.flatMap(g => g.tables.map(t => t -> g)).toMap
    // ⚠️ firstStmtOfGroup 用**段内局部索引**：并行组的相位合并是段内概念
    //（组的校验已在全局做过，这里只做映射）。
    val firstStmtOfGroup: Map[String, Int] = {
      val m = scala.collection.mutable.LinkedHashMap.empty[String, Int]
      stmts.zipWithIndex.foreach { case (stmt, k) => stmt match {
        case Ast.TableApply(n, _) => groupOfTable.get(n).foreach { g => if (!m.contains(g.name)) m(g.name) = k }
        case _ =>
      }}
      m.toMap
    }

    // 相位占用：**runtime 表 2 拍**（发 key；收 rsp 并同拍应用 action），其余语句 1 拍。
    // ⚠️ 不能把「应用 action」放到收 rsp 的下一拍：stages=2 流水化会把相邻相位的
    // 判据寄存器化（p0_is_phX），永远互相错开一拍 —— 而 receive 的数据只在
    // p0_is_phRsp 拍有效（其余拍被门控清零），下一拍应用时数据已经没了。
    // 应用是纯组合 sel，与接收同拍毫无问题。
    // 并行组：**首表**占 2 拍，组内其余表 0 拍（base 与首表相同 ⇒ 同拍发 key/收 rsp）。
    // rtNames = **本段** apply 的 runtime 表（段化后表通道只属于所属段）
    val rtNames: Set[String] = seg match {
      case CtrlSeg(_, _, rt) => rt
      case _                 => Set.empty
    }
    val stmtLens: Seq[Int] = stmts.zipWithIndex.map { case (stmt, k) =>
      stmt match {
        case TableApply(n, _) if rtNames.contains(n) =>
          groupOfTable.get(n) match {
            case Some(g) => if (firstStmtOfGroup(g.name) == k) 2 else 0
            case None    => 2
          }
        case _ => 1
      }
    }
    val stmtBase: Seq[Int] = stmtLens.scanLeft(0)(_ + _)
    val ctrlSpan = stmtLens.sum
    val phSend = ctrlBase + ctrlSpan
    val nPhases = phSend + 1
    val pw = widthFor(nPhases)
    val phaseOf: Map[String, Int] = mainStates.zipWithIndex.toMap

    // ---- 5b) runtime 表的接口布局。存储与匹配都在**外部表模块**（M0 已验证
    // send key → 等 rsp 的时序契约；A4：key/rsp 都走 valid_data 无背压）；
    // proc 只是查表客户端。rsp 布局（自定，与 Chisel 线的条目布局不同 ——
    // proc 不回传 key，由表模块自行比对）：hit(1) | actId(actW) | args(argW)。
    case class RtLayout(name: String, keyBits: Int, actW: Int, argW: Int,
                        argOffsets: Map[String, Seq[(String, Int, Int)]])
    val rtLayouts: Map[String, RtLayout] = ctrlOpt.toSeq.flatMap { c =>
      val actByName = c.actions.map(a => a.name -> a).toMap
      c.tables.filter(_.isRuntime).map { t =>
        val acts = t.actions.map { n => actByName.getOrElse(n,
          throw new P4Error(s"table '${t.name}'：引用了未知 action '$n'")) }
        val actW = math.max(1, BigInt(math.max(0, acts.size - 1)).bitLength)
        val argW = acts.map(a => a.params.map(_.width).sum).foldLeft(0)(math.max)
        val argOffsets = acts.map { a =>
          // 第 j 个形参的 LSB 偏移 = 其后所有形参宽度之和（先声明者占高位，与 Chisel 线同口径）
          a.name -> a.params.zipWithIndex.map { case (p, j) =>
            (p.name, a.params.drop(j + 1).map(_.width).sum, p.width)
          }
        }.toMap
        val keyBits = t.keys.map { ke => ke.expr match {
          case Name(p, _) => slotForPath(p, ke.line).width
          case other => throw new P4Error(
            s"行 ${ke.line}：runtime 表 key 只支持字段路径（got ${other.getClass.getSimpleName}）")
        } }.sum
        RtLayout(t.name, keyBits, actW, argW, argOffsets)
      }.map(rl => rl.name -> rl)
    }.toMap

    // ---- 6) 声明（通道清单与 Top 绑定共用 segChans，保证顺序一致）----
    val inChan = io.inChan
    val inWidth = io.inWidth
    val b = new Builder(pkg, segName, ids, top = isTop)
    segChans(seg, io, ctrlOpt, prog, phvWidth).foreach { c =>
      b.declareChan(c.name, c.direction, c.width, c.flowControl, c.flopKind)
    }
    b.declareState("phase", bitsTy(pw), "0")
    if (hasParser) b.declareState("pkt", bitsTy(PktWindowBits), "0")
    allSlots.foreach(s => b.declareState(s.stateName, bitsTy(s.width), "0"))

    // ---- 6b) extern 状态：每个实例一个**数组** state（A2-5b 前置实验定案：方案 a）。
    // 与 PHV 槽位的本质区别：extern **跨包持久**，不参与相位 0 的清零。
    // 段化后只声明**本段用到**的 extern（归属段的静态划分，见 emitProgram）。
    case class ExtState(name: String, inst: String, width: Int, size: Int)
    val extInsts: Set[String] = seg match {
      case CtrlSeg(_, insts2, _) => insts2
      case _                     => Set.empty
    }
    val extStates: Seq[ExtState] = ctrlOpt.toSeq.flatMap(_.externs)
      .filter(e => extInsts.contains(e.name)).map { e =>
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
    // extern 观察通道（ex_*）与 runtime 表通道（tbl_*_key/rsp）的声明已并入
    // segChans（与 Top 绑定共用同一份清单，见 emitProgram）。

    // ---- 6d) 表延时契约注释：把每张 runtime 表的 latency 配置写进 IR，
    // 作为对外部表模块的接口契约（XLS parser 忽略注释，纯文档用途）。
    ctrlOpt.foreach { c =>
      c.tables.filter(t => rtNames.contains(t.name)).foreach { t =>
        (t.latencyMin, t.latencyMax) match {
          case (Some(mn), Some(mx)) =>
            b.raw(s"// contract: table '${t.name}' lookup latency ${mn}..${mx} cycles (external module)")
          case _ => ()
        }
      }
    }

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
        // 本语句的起始相位（runtime 表占 3 拍，见 stmtBase）
        val phK = isPh(ctrlBase + stmtBase(k))
        val tag = s"ns$k"

        /** extern 与 proc 的对接（每个语句相位重建一次，捕获本相位的谓词）：
          * 读 = array_index；写 = array_update 链 + 相位 sel ——
          * 同拍多次写后写胜出，不同相位的写互不覆盖。 */
        def extRead(inst: String, idxNode: String): String = {
          val es = extByName.getOrElse(inst, throw new P4Error(s"XlsBackend：未声明的 extern '$inst'"))
          b.arrayIndex(extCur(inst), idxNode, bitsTy(es.width), s"ar_${es.name}")
        }
        /** extern 写：谓词 = extPhase（& 附加条件如表项 hit）。同拍多次写链式后写胜出。
          *
          * ⚠️ extPhase 默认 = phK，但 **runtime 表必须改指 phRsp**：它的 action 是
          * 在「收 rsp 那一拍」应用的（见下面 runtime 分支），而 phK 是发 key 那一拍。
          * 用错相位 → Counter/Register 写落在 rsp 之前（读到的是上一包的值），
          * 表现是「命中了但计数永远不涨」（demo12 TB 实测踩到）。 */
        var extPhase: String = phK
        def extWrite(inst: String, idxNode: String, valNode: String,
                     extra: Option[String] = None): Unit = {
          val es = extByName.getOrElse(inst, throw new P4Error(s"XlsBackend：未声明的 extern '$inst'"))
          val arrTy = s"bits[${es.width}][${es.size}]"
          val prev = extNext(inst)
          val upd = b.arrayUpdate(prev, valNode, idxNode, arrTy, s"au_${tag}_$inst")
          val pred = extra match {
            case Some(p) => b.binOp("and", extPhase, p, "bits[1]", s"wp_${tag}_$inst")
            case None => extPhase
          }
          extNext(inst) = b.sel(pred, Seq(prev, upd), Some(prev), arrTy, s"ne_${tag}_$inst")
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
            // ⚠️ 并行组内**所有表**都用首表语句的 base —— 组内非首表在 scanLeft 前缀和里
            // 会被排到首表的 rsp 相位，必须显式回指首表 base，才是真正的"同拍查找"。
            val stmtPhaseBase = groupOfTable.get(name).flatMap(g => firstStmtOfGroup.get(g.name)) match {
              case Some(k0) => stmtBase(k0)
              case None     => stmtBase(k)
            }
            if (t.isRuntime) {
              // ================= runtime 表：2 拍 =================
              // 拍1 发 key；拍2 收 rsp 并**同拍**应用 action（见 stmtLens 处的说明）。
              // 存储与匹配在外部表模块。
              val rl = rtLayouts.getOrElse(name, throw new P4Error(
                s"行 $ln：内部错误 —— runtime 表 '$name' 无接口布局"))
              val base = ctrlBase + stmtPhaseBase
              val phKey = isPh(base)
              val phRsp = isPh(base + 1)   // 收 rsp + 应用 action（同一拍）
              // ⚠️ 本语句（含 default 表项）的 extern 写必须与 action 应用同拍
              extPhase = phRsp

              // key：各 key 元素的当前值 concat（先声明在高位）
              val keyElems: Seq[(String, Int)] = t.keys.map { ke =>
                ke.expr match {
                  case Name(p, _) =>
                    val s = slotForPath(p, ke.line)
                    (cur(s.stateName), s.width)
                  case other => throw new P4Error(
                    s"行 ${ke.line}：runtime 表 key 只支持字段路径")
                }
              }
              val keyVal = if (keyElems.length == 1) keyElems.head._1
                           else b.concat(keyElems.map(_._1), bitsTy(rl.keyBits), s"key_$name")
              b.send(tok, keyVal, s"tbl_${name}_key", Some(phKey), s"ks_$name")

              // 拍 2：收 rsp（布局 hit | actId | args，hit 在最高位）
              val rspW = 1 + rl.actW + rl.argW
              val (_, rspData) = b.receive(tok, s"tbl_${name}_rsp", bitsTy(rspW), Some(phRsp), s"rs_$name")
              val hitB = b.bitSlice(rspData, rl.argW + rl.actW, 1, s"hit_$name")
              val actId = b.bitSlice(rspData, rl.argW, rl.actW, s"act_$name")
              val argsB = b.bitSlice(rspData, 0, rl.argW, s"args_$name")

              // 拍 3：按 actId 选择 action（各 cond 互斥）；default 表项作基线（hit=0）
              val hitActs: Seq[(String, Map[Seq[String], String])] = t.actions.zipWithIndex.map { case (an, i) =>
                val a = actionOf.getOrElse(an, throw new P4Error(
                  s"行 $ln：table '$name' 引用了未知 action '$an'"))
                val cond = b.binOp("and", hitB,
                  b.eq(actId, b.literal(i, bitsTy(rl.actW), s"aid_${an}_$i")), "bits[1]", s"ra_${an}_$i")
                // 形参 → args 位段切片（伪路径 $arg.<name> 映射到 proc 节点）
                val argNodes: Map[String, String] =
                  rl.argOffsets.getOrElse(an, Seq.empty).collect {
                    case (pn, off, w) if w > 0 => pn -> b.bitSlice(argsB, off, w, s"rg_${an}_$pn")
                  }.toMap
                val argInputOf: Seq[String] => String = path =>
                  if (path.headOption.contains("$arg"))
                    argNodes.getOrElse(path(1), throw new P4Error(
                      s"XlsBackend：内部错误 —— action '$an' 未知形参 '${path(1)}'"))
                  else inputOf(path)
                (cond, emitDagInto(b, actionDagArgNodes(a, resolver, externMap), argInputOf, s"${tag}_rt_$an", ext, Some(cond)))
              }

              val defaultOuts: Map[Seq[String], String] = t.entries.find(_.isDefault).map { e =>
                val a = actionOf.getOrElse(e.action, throw new P4Error(
                  s"行 ${e.line}：table '$name' 的 default 引用了未知 action '${e.action}'"))
                // default 的 extern 写谓词 = 都不命中（~hit）
                val dPred = Some(b.notNode(hitB, 1, s"miss_$tag"))
                emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_rt_dflt_${e.action}", ext, dPred)
              }.getOrElse(Map.empty)

              val touched = (defaultOuts.keySet ++ hitActs.flatMap(_._2.keySet)).toSeq
              val finalOuts = touched.map { path =>
                val s = slotForPath(path, 0)
                var acc = defaultOuts.getOrElse(path, cur(s.stateName))
                hitActs.reverse.foreach { case (cond, outs) =>
                  outs.get(path).foreach { v =>
                    acc = b.sel(cond, Seq(acc, v), Some(acc), bitsTy(s.width), s"${tag}_rtsel")
                  }
                }
                path -> acc
              }.toMap
              // ⚠️ 动作应用与收 rsp 同拍（phRsp）—— 数据只在 p0_is_phRsp 拍有效。
              finalOuts.foreach { case (path, v) =>
                val s = slotForPath(path, 0)
                selNext(phRsp, s.stateName, s.width, v, tag)
              }
              Map.empty[Seq[String], String]
            } else {
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

            // 逐表项：hit 条件（逐 key 元素比较后 and —— 与整体 concat 后比较等价）+ action 输出。
            // ⚠️ extern 写必须带 hit 谓词（array_update 是效果不是数据，见 ExternHooks 注释）。
            val entryHits: Seq[(String, Map[Seq[String], String], String)] =
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
                (hit, emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_${e.action}", ext, Some(hit)), hit)
              }

            // default 表项的输出作为基线（优先级最低）；各表项按**声明序**优先 ⇒ 倒序嵌套 sel。
            // default 的 extern 写谓词 = 都不命中（~anyHit）—— 命中条目时 default 不生效。
            val anyHit: Option[String] = entryHits.headOption.map { _ =>
              entryHits.map(_._3).reduceLeft((x, y) => b.binOp("or", x, y, "bits[1]", s"anyhit_$tag"))
            }
            val defaultOuts: Map[Seq[String], String] = t.entries.find(_.isDefault).map { e =>
              val a = actionOf.getOrElse(e.action, throw new P4Error(
                s"行 ${e.line}：table '$name' 的 default 引用了未知 action '${e.action}'"))
              val dPred = anyHit.map(h => b.notNode(h, 1, s"miss_$tag"))
              emitDagInto(b, actionDag(a, e.args, resolver, externMap), inputOf, s"${tag}_dflt_${e.action}", ext, dPred)
            }.getOrElse(Map.empty)

            val touched = (defaultOuts.keySet ++ entryHits.flatMap(_._2.keySet)).toSeq
            touched.map { path =>
              val s = slotForPath(path, 0)
              var acc = defaultOuts.getOrElse(path, cur(s.stateName))
              entryHits.reverse.foreach { case (h, eouts, _) =>
                eouts.get(path).foreach { v =>
                  acc = b.sel(h, Seq(acc, v), Some(acc), bitsTy(s.width), s"${tag}_sel")
                }
              }
              path -> acc
            }.toMap
            }   // end const-表（非 runtime）分支

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

    // ---- 12) 对外输出 ----
    val outParts = outSlots.map(s => nextOf(s.stateName))
    val outData = if (outParts.length == 1) outParts.head
                  else b.concat(outParts, bitsTy(outWidth), "out")
    val sOut = b.send(tok, outData, outChan, Some(isPh(phSend)), "snd")

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

  /** lookup-group 的静态校验（全局，跨段一致）。
    * 违反任一约束即 P4Error（显式拒绝，绝不静默降级为串行）。 */
  private def validateLookupGroups(prog: P4Program, ctrlOpt: Option[ControlDecl],
                                   globalStmts: Seq[Ast.Stmt]): Unit = {
    val tableOfAll: Map[String, Ast.TableDecl] =
      ctrlOpt.map(_.tables.map(t => t.name -> t).toMap).getOrElse(Map.empty)
    val actionOfAll: Map[String, Ast.ActionDecl] =
      ctrlOpt.map(_.actions.map(a => a.name -> a).toMap).getOrElse(Map.empty)

    /** action 的 PHV 写集 = body 里 Assign 的路径（extern 写不算 —— 状态单元不是查找依赖）。 */
    def writeSet(a: Ast.ActionDecl): Set[Seq[String]] =
      a.body.collect { case Ast.Assign(p, _, _) => p.toSeq }.toSet

    /** 表全部 action 的写集并集。 */
    def tableWriteSet(t: Ast.TableDecl): Set[Seq[String]] =
      t.actions.flatMap(an => actionOfAll.get(an).map(writeSet).getOrElse(Set.empty)).toSet

    /** 表的 key 读集（key 只支持字段路径）。 */
    def keyReadSet(t: Ast.TableDecl): Set[Seq[String]] =
      t.keys.collect { case Ast.KeyElem(Ast.Name(p, _), _, _) => p.toSeq }.toSet

    // 每张表至多属一个组
    prog.lookupGroups.flatMap(_.tables).groupBy(identity)
      .filter(_._2.size > 1).keys.foreach { tn =>
        throw new P4Error(s"XlsBackend：表 '$tn' 出现在多个 lookup-group 里（每张表至多一组）")
      }
    prog.lookupGroups.foreach { g =>
      // 组内表必须都是 runtime 表（静态融合表没有 key/rsp 通道，并行无意义）
      g.tables.foreach { tn =>
        tableOfAll.get(tn) match {
          case Some(t) if t.isRuntime => ()
          case Some(_) => throw new P4Error(
            s"行 ${g.line}：lookup-group '${g.name}' 的成员表 '$tn' 不是 runtime 表" +
              "（静态融合表是纯组合逻辑、无 key/rsp 通道，并行无意义）")
          case None => throw new P4Error(
            s"行 ${g.line}：lookup-group '${g.name}' 引用了未声明的表 '$tn'")
        }
      }
      // 组内表必须在 apply 里相邻排列（否则夹在中间的语句时序会落在组的查找相位之间）
      val idxs = globalStmts.zipWithIndex.collect {
        case (Ast.TableApply(n, _), k) if g.tables.contains(n) => k
      }
      if (idxs.size != g.tables.size)
        throw new P4Error(s"行 ${g.line}：lookup-group '${g.name}' 的成员表没有全部在 apply 里 apply")
      if (idxs.nonEmpty && idxs.max - idxs.min + 1 != idxs.size)
        throw new P4Error(s"行 ${g.line}：lookup-group '${g.name}' 的成员表在 apply 里不相邻" +
          "（并行组必须连续排列，否则组间语句的相位会落在查找/应答之间）")
      // 两两校验：先声明者的写集 vs 后声明者的 key 读集（并行 ⇒ 读到的是进入该拍时的快照）
      g.tables.combinations(2).foreach {
        case Seq(aName, bName) =>
          val (ta, tb) = (tableOfAll(aName), tableOfAll(bName))
          val wA = tableWriteSet(ta)
          val clashKey = wA.intersect(keyReadSet(tb))
          if (clashKey.nonEmpty)
            throw new P4Error(
              s"行 ${g.line}：lookup-group '${g.name}'：表 '$aName' 的写集与 '$bName' 的 key 读集相交" +
                s"（${clashKey.map(_.mkString(".")).mkString(", ")}）—— 并行查找会让 '$bName' 读到旧值，请改回串行")
          val clashW = wA.intersect(tableWriteSet(tb))
          if (clashW.nonEmpty)
            throw new P4Error(
              s"行 ${g.line}：lookup-group '${g.name}'：表 '$aName' 与 '$bName' 的 action 写集相交" +
                s"（${clashW.map(_.mkString(".")).mkString(", ")}）—— 同拍应用无法定义覆盖次序")
      }
    }
  }

  /** 扫描一个 action 用到的 extern 实例（段归属用）。
    * extern 引用形态：`<inst>.read(<idx>)`（Call 双段路径）与
    * `<inst>.write(<idx>, v)` / `<inst>.count(<idx>)`（MethodCall 语句）。 */
  private def externsOfAction(a: Ast.ActionDecl, externNames: Set[String]): Set[String] = {
    val s = scala.collection.mutable.LinkedHashSet.empty[String]
    def walkExpr(e: Expr): Unit = e match {
      case Call(path, args, _) =>
        if (path.length == 2 && externNames.contains(path.head)) s += path.head
        args.foreach(walkExpr)
      case Slice(e2, _, _, _) => walkExpr(e2)
      case Cast(_, e2, _) => walkExpr(e2)
      case Ternary(c, t, f, _) => walkExpr(c); walkExpr(t); walkExpr(f)
      case Un(_, e2, _) => walkExpr(e2)
      case Bin(_, l, r, _) => walkExpr(l); walkExpr(r)
      case _ => ()
    }
    a.body.foreach {
      case Assign(_, e, _) => walkExpr(e)
      case MethodCall(inst, _, args, _) =>
        if (externNames.contains(inst)) s += inst
        args.foreach(walkExpr)
      case _ => ()
    }
    s.toSet
  }

  /** 表动作集合用到的 extern 实例。 */
  private def externsOfTable(t: Ast.TableDecl, actionOf: Map[String, Ast.ActionDecl],
                             externNames: Set[String]): Set[String] =
    t.actions.flatMap(an => actionOf.get(an).map(externsOfAction(_, externNames)).getOrElse(Set.empty)).toSet

  /** runtime 表的接口布局（emitSeg 与 Top 编排共用）。 */
  private final case class RtLayout(name: String, keyBits: Int, actW: Int, argW: Int,
                                    argOffsets: Map[String, Seq[(String, Int, Int)]])

  /** 一个段的 interface 通道清单（emitSeg 声明与 Top 绑定共用同一份，保证顺序一致）。
    * 顺序：输入、输出、extern 观察口、表 key/rsp。 */
  private def segChans(
    seg: Seg, io: SegIo, ctrlOpt: Option[ControlDecl],
    prog: P4Program, phvWidth: Int,
  ): Seq[ChanDecl] = {
    val inFc = if (io.inChan.startsWith("ph_")) "ready_valid" else "valid_data"
    val in = ChanDecl(io.inChan, "receive", inFc, io.inWidth)
    val outW = io.outSlots.map(_.map(_.width).sum).getOrElse(phvWidth)
    val out = ChanDecl(io.outChan, "send", "ready_valid", outW)
    val exts: Seq[ChanDecl] = seg match {
      case CtrlSeg(_, externInsts, _) => ctrlOpt.toSeq.flatMap { c =>
        c.externs.filter(e => externInsts.contains(e.name))
          .map(e => ChanDecl(s"ex_${e.name}", "send", "valid_data", e.width * e.size))
      }
      case _ => Seq.empty
    }
    val tbls: Seq[ChanDecl] = seg match {
      case CtrlSeg(_, _, rtNames) => ctrlOpt.toSeq.flatMap { c =>
        val actByName = c.actions.map(a => a.name -> a).toMap
        val st = slotTablesOf(prog, seg match {
          case ParserSeg(p) => Some(p); case _ => None
        }, ctrlOpt)
        c.tables.filter(t => rtNames.contains(t.name) && t.isRuntime).flatMap { t =>
          val acts = t.actions.map(an => actByName.getOrElse(an,
            throw new P4Error(s"table '${t.name}'：引用了未知 action '$an'")))
          val actW = math.max(1, BigInt(math.max(0, acts.size - 1)).bitLength)
          val argW = acts.map(a => a.params.map(_.width).sum).foldLeft(0)(math.max)
          val keyBits = t.keys.map { ke => ke.expr match {
            case Name(p, _) =>
              // key 字段宽度：meta 两段路径或 header 三段路径（复用槽位表推导）
              p match {
                case Seq(_, m, f) if st.fieldSlotOf.contains(Seq(m, f)) => st.fieldSlotOf(Seq(m, f)).width
                case Seq(pp, m) if st.metaSlotOf.contains(Seq(pp, m)) => st.metaSlotOf(Seq(pp, m)).width
                case _ => throw new P4Error(s"runtime 表 key 路径 '${p.mkString(".")}' 无对应槽位")
              }
            case other => throw new P4Error(
              s"runtime 表 key 只支持字段路径（got ${other.getClass.getSimpleName}）")
          } }.sum
          Seq(ChanDecl(s"tbl_${t.name}_key", "send", "valid_data", keyBits),
              ChanDecl(s"tbl_${t.name}_rsp", "receive", "valid_data", 1 + actW + argW))
        }
      }
      case _ => Seq.empty
    }
    Seq(in, out) ++ exts ++ tbls
  }

  /** 整个程序 → XLS IR 文本（多 proc 网络：Parser 段 / 查找段×N / Deparser 段 + Top 壳）。
    *
    * 段划分规则（用户需求：Parser、Deparser、每个表组及其 Action 各一个 module）：
    * - parser 段、deparser 段各一个 proc；
    * - control 语句在每个 runtime 查找组的首语句处切段 —— 组连同其后的动作
    *   归入同一段（组的查找结果由同段动作消费，天然对齐）；
    * - 段数 = 1 时退化为单 proc（与 v2 完全兼容，外部通道直接在该 proc 上）；
    * - 段数 > 1 时生成 Top 壳：proc 间通道 ready_valid + `flop_kind=skid`
    *   （2 深缓冲 —— 查找结果与包头/Meta 对齐后进动作，慢表 stall 不丢包）。
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

    val parserOpt = prog.parsers.headOption
    val ctrlOpt = prog.controls.headOption
    ctrlOpt.foreach { c =>
      if (c.applyBody.isEmpty) throw new P4Error(s"XlsBackend：control '${c.name}' 的 apply 体为空")
    }

    // ---- 全局组校验（段内只保留映射） ----
    val globalStmts = ctrlOpt.map(_.applyBody).getOrElse(Seq.empty)
    validateLookupGroups(prog, ctrlOpt, globalStmts)

    // ---- 段归属：每条语句用到哪些 extern ----
    val actionOfAll: Map[String, Ast.ActionDecl] =
      ctrlOpt.map(_.actions.map(a => a.name -> a).toMap).getOrElse(Map.empty)
    val tableOfAll: Map[String, Ast.TableDecl] =
      ctrlOpt.map(_.tables.map(t => t.name -> t).toMap).getOrElse(Map.empty)
    val externNames: Set[String] =
      ctrlOpt.map(_.externs.map(_.name).toSet).getOrElse(Set.empty)
    def stmtExterns(st: Ast.Stmt): Set[String] = st match {
      case ActionCall(n, _, _) =>
        actionOfAll.get(n).map(externsOfAction(_, externNames)).getOrElse(Set.empty)
      case TableApply(n, _) =>
        tableOfAll.get(n).map(externsOfTable(_, actionOfAll, externNames)).getOrElse(Set.empty)
      case MethodCall(inst, _, _, _) => Set(inst)
      case _ => Set.empty
    }

    // ---- 段划分 ----
    // control 段边界：每个 runtime 组的首语句前切段（组连同其后的动作同段）。
    val groupOfTable: Map[String, Ast.LookupGroup] =
      prog.lookupGroups.flatMap(g => g.tables.map(t => t -> g)).toMap
    val groupFirstStmt: Set[Int] = {
      val first = scala.collection.mutable.LinkedHashSet.empty[Int]
      val seenGroups = scala.collection.mutable.LinkedHashSet.empty[String]
      globalStmts.zipWithIndex.foreach { case (stmt, k) => stmt match {
        case TableApply(n, _) => groupOfTable.get(n).foreach { g =>
          if (!seenGroups.contains(g.name)) { seenGroups += g.name; first += k }
        }
        case _ =>
      }}
      first.toSet
    }
    val ctrlSegs: Seq[CtrlSeg] = {
      val acc = scala.collection.mutable.ArrayBuffer.empty[scala.collection.mutable.ArrayBuffer[(Int, Ast.Stmt)]]
      var cur = scala.collection.mutable.ArrayBuffer.empty[(Int, Ast.Stmt)]
      globalStmts.zipWithIndex.foreach { case (stmt, k) =>
        if (groupFirstStmt.contains(k) && cur.nonEmpty) { acc += cur; cur = scala.collection.mutable.ArrayBuffer.empty }
        cur += ((k, stmt))
      }
      if (cur.nonEmpty) acc += cur
      acc.toSeq.map { ss =>
        val exts = ss.flatMap { case (_, st) => stmtExterns(st) }.toSet
        val rts = ss.collect { case (_, TableApply(n, _)) if tableOfAll.get(n).exists(_.isRuntime) => n }.toSet
        CtrlSeg(ss.toSeq, exts, rts)
      }
    }

    // ---- 段序列 ----
    val segs: Seq[Seg] =
      parserOpt.map(ParserSeg).toSeq ++ ctrlSegs ++ prog.deparser.map(DeparserSeg).toSeq
    if (segs.isEmpty) throw new P4Error("XlsBackend：没有可发射的段")

    // ---- 段命名（Top 壳沿用 v2 的模块名，TB/下游对接不变）----
    val topName = (parserOpt, ctrlOpt) match {
      case (Some(p), None) => s"${p.name}_parser"
      case (None, Some(c)) => s"${c.name}_control"
      case (Some(_), Some(c)) => s"${c.name}_pipeline"
      case _ => throw new P4Error("XlsBackend：程序里既没有 parser 也没有 control")
    }
    val segNames: Seq[String] =
      if (segs.length == 1) Seq(topName)
      else segs.zipWithIndex.map {
        case (ParserSeg(_), _) => s"${topName}__parser"
        case (DeparserSeg(_), _) => s"${topName}__deparser"
        case (_, i) => s"${topName}__ctrl$i"
      }

    // ---- PHV 宽度（与 v2 同口径：全槽位拼接；共享推导避免漂移）----
    val phvWidth = slotTablesOf(prog, parserOpt, ctrlOpt).phvWidth

    // ---- 最终输出布局（最后一段）：有 deparser ⇒ emit 序；否则 ⇒ PHV 全量 ----
    val emitLayoutSlots: Option[Seq[Slot]] = prog.deparser.map { d =>
      val stt = slotTablesOf(prog, parserOpt, ctrlOpt)
      val seen = scala.collection.mutable.LinkedHashSet.empty[Slot]
      d.emits.foreach { e =>
        val inst = e.path.last
        val hi = stt.insts.find(_.inst == inst).getOrElse(throw new P4Error(
          s"行 ${e.line}：deparser emit 引用了未知的 header 实例 '$inst'"))
        seen += stt.validSlotOf(hi.inst)
        hi.ht.fields.foreach(f => seen += stt.fieldSlotOf(Seq(hi.inst, f.name)))
      }
      seen.toSeq
    }
    val finalOutChan = if (prog.deparser.isDefined) "pkt_out" else "phv_out"

    // ---- 段的 IO ----
    // 输入：parser 段 = pkt_in（报文窗口）；无 parser 的首段 = phv_in（外部直接给 PHV）；
    //       其余段 = proc 间通道 ph_<i-1>。
    // 输出：最后一段 = finalOutChan；中间段 = ph_<i>（ready_valid + FIFO）。
    val ios: Seq[SegIo] = segs.zipWithIndex.map { case (seg, i) =>
      val in: (String, Int) = (seg, i) match {
        case (ParserSeg(_), _) => ("pkt_in", PktWindowBits)
        case (_, 0)            => ("phv_in", phvWidth)      // 无 parser 的首段
        case (_, k)            => (s"ph_${k - 1}", phvWidth)
      }
      val isLast = i == segs.length - 1
      val out: (String, Option[Seq[Slot]]) =
        if (isLast) (finalOutChan, emitLayoutSlots) else (s"ph_$i", None)
      SegIo(in._1, in._2, out._1, out._2)
    }

    // ---- 发射各段 ----
    val segTexts: Seq[String] = segs.zip(segNames).zip(ios).map {
      case ((seg, name), io) =>
        val isTop = segs.length == 1
        emitSeg(seg, name, io, isTop, ctrlOpt, prog, pkg, ids)
    }

    if (segs.length == 1) {
      b ++= segTexts.head
      b ++= "\n"
      b.toString
    } else {
      // ---- Top 壳：外部通道借给子段 + proc 间 FIFO 通道 + proc_instantiation ----
      // proc 间通道（ph_*）= ready_valid + fifo_depth=FIFO_DEPTH 的 package 级 chan，
      // codegen 自动实例化 xls_fifo_wrapper —— 查找结果与包头/Meta 在 FIFO 里对齐后
      // 进入下一段的 action 处理；慢表 stall 只影响 FIFO 占用，不丢包。
      val top = new Builder(pkg, topName, ids, top = true, noState = true)
      val instChans: Seq[Seq[String]] = segs.zip(ios).zipWithIndex.map {
        case ((seg, io), i) =>
          val chans = segChans(seg, io, ctrlOpt, prog, phvWidth)
          // 1) 外部通道在 Top 签名上同名同方向声明（proc_instantiation 绑定需要）
          chans.foreach { c =>
            if (!c.name.startsWith("ph_"))
              top.declareChan(c.name, c.direction, c.width, c.flowControl)
          }
          // 2) 中间通道（本段 send 出去的 ph_i）在 Top 上 loopback
          if (io.outChan.startsWith("ph_"))
            top.declareLoopbackChan(io.outChan, phvWidth, "ready_valid", FifoDepth)
          // 3) 本段的绑定顺序 = 子段 interface 顺序
          chans.map(_.name)
      }
      segs.zip(segNames).zip(instChans).foreach { case ((_, name), chans) =>
        top.instantiateProc(s"i_$name", name, chans)
      }
      // ⚠️ 顺序：子 proc 必须先于 Top（ParseProcInstantiation 用 TryGetProc 解析
      // proc= 引用，被实例化者须已 parse —— 先定义后引用）
      b ++= segTexts.mkString("\n")
      b ++= "\n"
      b ++= top.render
      b ++= "\n"
      b.toString
    }
  }
}
