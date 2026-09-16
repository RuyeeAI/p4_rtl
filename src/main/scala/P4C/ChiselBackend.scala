package P4C

import P4C.Ast._
import P4C.Ir._
import P4C.IrBuilder._

import scala.collection.mutable

/** Chisel 后端：把 IR/AST 翻译为参数化 Chisel（Scala）源码。
  *
  *   - control → 组合 Module（M1 直行 action / M2 静态融合表）；
  *   - parser  → FSM Module（M3，512-bit 网络序报文窗口）。
  */
object ChiselBackend {

  private val IND = "  "

  private def pascal(s: String): String =
    if (s.isEmpty) s else s.charAt(0).toUpper + s.substring(1)

  /** Chisel 十六进制字面量：Scala 无 >64 位整数字面量，按值域分级发射——
    * ≤ Int.MaxValue 用裸 hex（与历史输出逐字节一致）；≤ 2^64-1 加 L 后缀（Long
    * 字面量，chisel3 fromLongToLiteral 支持 .U）；更大（如 demo10 的 80 位组合键）
    * 用 BigInt 形式（chisel3 fromBigIntToLiteral 支持 .U）。 */
  private def litU(v: BigInt, w: Int): String =
    if (v > Long.MaxValue) s"""BigInt("${v.toString(16)}", 16).U($w.W)"""
    else s"0x${v.toString(16)}${if (v > Int.MaxValue) "L" else ""}.U($w.W)"

  // ---------------- 表达式发射 ----------------

  /** 语句发射器：公共子表达式落成 val，叶子内联。 */
  private final class Emitter(
    dag: Dag,
    readPath: Seq[String] => String,
    val indent: String,
    val fireCond: Option[String] = None,
  ) {
    private val lines = mutable.ArrayBuffer.empty[String]
    private val stateful = mutable.ArrayBuffer.empty[String]
    private val memo = mutable.HashMap.empty[NodeId, String]
    private var tmp = 0

    private val refCount: mutable.HashMap[NodeId, Int] = {
      val m = mutable.HashMap.empty[NodeId, Int]
      def bump(id: NodeId): Unit = m(id) = m.getOrElse(id, 0) + 1
      dag.outputs.foreach {
        case o: OutputWrite => bump(o.value)
        case r: RegWrite => bump(r.index); bump(r.value)
        case c: CounterAdd => bump(c.index); bump(c.delta)
      }
      dag.nodes.indices.foreach { id =>
        dag.nodes(id) match {
          case z: Zext => bump(z.src)
          case t: Trunc => bump(t.src)
          case n: Not => bump(n.src)
          case s: Ir.Slice => bump(s.src)
          case c: Cat => c.parts.foreach(bump)
          case m2: Mux => bump(m2.c); bump(m2.t); bump(m2.f)
          case b2: Ir.Bin => bump(b2.l); bump(b2.r)
          case _ =>
        }
      }
      m
    }

    private def go(id: NodeId): String = {
      if (memo.contains(id)) return memo(id)
      val n = dag.nodes(id)
      val inlineStr = nodeExpr(dag, n, go, readPath)
      val isLeaf = n.isInstanceOf[Const] || n.isInstanceOf[InputRef]
      if (isLeaf || refCount.getOrElse(id, 0) <= 1) inlineStr
      else {
        val name = s"t${tmp}"
        tmp += 1
        lines += s"${indent}val $name = $inlineStr"
        memo(id) = name
        name
      }
    }

    def emitSink(s: Sink): Unit = s match {
      case o: OutputWrite =>
        lines += s"$indent${writePath(o.path)} := ${go(o.value)}"
      case r: RegWrite =>
        stateful += s"reg_${r.inst}(${fitIdx(go(r.index), dag.nodes(r.index).width, r.size)}) := ${go(r.value)}"
      case c: CounterAdd =>
        val idx = fitIdx(go(c.index), dag.nodes(c.index).width, c.size)
        stateful += s"cnt_${c.inst}($idx) := (cnt_${c.inst}($idx) +& ${go(c.delta)})"
    }

    def hasStateful: Boolean = stateful.nonEmpty

    def emitExprRoot(root: NodeId): String = go(root)

    def takeLines: Seq[String] =
      if (stateful.isEmpty) lines.toSeq
      else {
        val cond = fireCond.getOrElse("true.B")
        lines.toSeq ++ Seq(s"${indent}when ($cond) {") ++
          stateful.toSeq.map(l => s"$indent$IND$l") ++ Seq(s"$indent}")
      }

    private def writePath(path: Seq[String]): String =
      s"io.${path.head}Out.${path.drop(1).mkString(".")}"
  }

  // ---------------- 表达式模板（Emitter 与 StagedEmitter 共用，避免两份实现漂移） ----------------

  /** 索引表达式 fit 到 extern 地址宽度。 */
  private def fitIdx(expr: String, w: Int, size: Int): String = {
    val idxW = math.max(1, BigInt(size - 1).bitLength)
    if (w > idxW) s"($expr)(${idxW - 1}, 0)"
    else if (w < idxW) s"Cat(0.U(${idxW - w}.W), $expr)"
    else expr
  }

  /** 比较运算符 → Chisel 布尔运算符（cmpBoolOf 与 nodeExpr 共用，避免两份漂移）。 */
  private def cmpOpStr(op: Ir.Op): Option[String] = op match {
    case Eq => Some("===")
    case Neq => Some("=/=")
    case Lt => Some("<")
    case Le => Some("<=")
    case Gt => Some(">")
    case Ge => Some(">=")
    case _ => None
  }

  /** 比较节点 → 布尔表达式（Mux 条件路径；作为值使用时由 nodeExpr 发射 0/1 Mux）。 */
  private def cmpBoolOf(dag: Dag, id: NodeId, go: NodeId => String): Option[String] = dag.nodes(id) match {
    case Ir.Bin(op, l, r, _) =>
      cmpOpStr(op).map(o => s"(${go(l)} $o ${go(r)})")
    case _ => None
  }

  private def condOf(dag: Dag, id: NodeId, go: NodeId => String): String =
    cmpBoolOf(dag, id, go).getOrElse(s"(${go(id)} === 1.U)")

  /** 节点 → Chisel 表达式字符串。go 解析操作数；模板与结果宽度规则与 Ir 不变量一致
    * （+&/-& 保宽加减）。Emitter 与 StagedEmitter 共用，保证两路发射语义逐字一致。 */
  private def nodeExpr(dag: Dag, n: Node, go: NodeId => String, readPath: Seq[String] => String): String = n match {
    case Const(v, w) => s"0x${v.toString(16)}.U($w.W)"
    case InputRef(p, _) => readPath(p)
    case Ir.RegRead(inst, idx, _, size) =>
      s"reg_${inst}(${fitIdx(go(idx), dag.nodes(idx).width, size)})"
    case Zext(s, w) =>
      val sw = dag.nodes(s).width
      s"Cat(0.U(${w - sw}.W), ${go(s)})"
    case Trunc(s, w) => s"(${go(s)})(${w - 1}, 0)"
    case Not(s, _) => s"(~${go(s)})"
    case Ir.Slice(s, hi, lo) => s"(${go(s)})($hi, $lo)"
    case Cat(ps, _) => s"Cat(${ps.map(go).mkString(", ")})"
    case Mux(c, t, f, _) => s"Mux(${condOf(dag, c, go)}, ${go(t)}, ${go(f)})"
    case Ir.Bin(op, l, r, w) =>
      val ls = go(l); val rs = go(r)
      op match {
        case Ir.Add => s"($ls +& $rs)"
        case Ir.Sub => s"($ls -& $rs)"
        case Ir.And => s"($ls & $rs)"
        case Ir.Or => s"($ls | $rs)"
        case Ir.Xor => s"($ls ^ $rs)"
        case Ir.Shl => s"($ls << $rs)(${w - 1}, 0)"
        case Ir.Shr => s"($ls >> $rs)"
        // W0（缺口分析 R4）：比较节点作为值使用（`!x` 降级为 x == 0 后可赋值/参与
        // 运算）时，发射为节点宽度的 0/1——Mux 包裹形式，与 Interp.evalOp 的 0/1、
        // Smt 的 ite 0/1 位串口径一致。作为 Mux 条件时仍走 condOf 纯布尔路径，不变。
        case Ir.Eq | Ir.Neq | Ir.Lt | Ir.Le | Ir.Gt | Ir.Ge =>
          s"Mux(($ls ${cmpOpStr(op).getOrElse("===")} $rs), 1.U($w.W), 0.U($w.W))"
      }
  }

  // ---------------- 切拍发射（M1/M2/M4 的 N>1 路径；D4 结构隔离） ----------------

  /** 切拍发射的共享命名上下文：同一 control（或同一静态融合表）内的多个 StagedEmitter
    * 共享 valid 链与 val 命名，保证生成代码中 val 全局唯一。
    *   - valid 链按 (baseValid, n) 去重：首个链用规范名 sV_0..sV_{n-1}，
    *     不同规格的链追加序号后缀（sV_c1_0..）；
    *   - t（级内公共子表达式）与 v_k_j（第 k 级边界寄存器）由此统一分配。
    */
  private final class StagedShared {
    private val chainCache = mutable.HashMap.empty[(String, Int), Seq[String]]
    private val preludeBuf = mutable.ArrayBuffer.empty[String]
    private var chainSeq = 0
    private var tSeq = 0
    private val vSeq = mutable.HashMap.empty[Int, Int]
    // X3（XLS register_merge_strategy=identity 对标）：跨 DAG 同值边界寄存器合并缓存。
    // key = (末级 valid 信号名, 表达式文本, 位宽)：sV 名相同 ⇒ 同链同拍使能；表达式文本
    // 相同 ⇒ 值语义相同（叶子文本含实际信号路径，跨表/跨上下文不会误撞）。
    private val regCache = mutable.HashMap.empty[(String, String, Int), String]
    private val usedLastBuf = mutable.LinkedHashSet.empty[String]

    /** 取得（必要时在 prelude 中发射）baseValid 起、n 级的 valid 链，返回各级 sV 名。 */
    def chain(baseValid: String, n: Int, indent: String): Seq[String] =
      chainCache.getOrElse((baseValid, n), {
        val suffix = if (chainCache.isEmpty) "" else s"_c${chainSeq}"
        chainSeq += 1
        val names = (0 until n).map(k => s"sV${suffix}_$k")
        preludeBuf += s"${indent}val ${names(0)} = $baseValid"
        // 注意：valid 链必须是纯延迟线（RegNext），不能用 RegEnable(en=sV_{k-1})——
        // RegEnable 在 en 为低时保持旧值，首脉冲后整条链将永久锁高，导致末级每拍重复写。
        // RegNext 自动清零：单拍脉冲逐级传播，sV_k 仅在第 k 拍为高。
        (1 until n).foreach { k =>
          preludeBuf += s"${indent}val ${names(k)} = RegNext(${names(k - 1)}, false.B)"
        }
        usedLastBuf += names(n - 1)
        chainCache((baseValid, n)) = names
        names
      })

    def freshT(): String = { val s = s"t$tSeq"; tSeq += 1; s }

    /** 边界寄存器命名 + 同值合并：命中缓存返回既有 val 名（isNew=false，发射方
      * 跳过 RegEnable 定义行）；未命中分配新名并登记。 */
    def boundaryReg(sVName: String, expr: String, width: Int): (String, Boolean) =
      regCache.get((sVName, expr, width)) match {
        case Some(n) => (n, false)
        case None =>
          val k = sVName.substring(sVName.lastIndexOf('_') + 1).toInt
          val j = vSeq.getOrElse(k, 0)
          vSeq(k) = j + 1
          val name = s"v_${k}_$j"
          regCache((sVName, expr, width)) = name
          (name, true)
      }

    /** 本上下文内所有已用 valid 链的末级名（多链时 outValid 取与）。 */
    def usedLastStages: Seq[String] = usedLastBuf.toSeq

    def prelude: Seq[String] = preludeBuf.toSeq
  }

  /** 切拍发射器：仅当 DAG 已调度（切拍启用）时使用；N=1 恒走 [[Emitter]] 原路径，
    * 保证默认生成代码逐字节不变（D4）。
    *
    * 发射结构：
    *   - valid 链：sV_0 = baseValid（组合）；sV_k = RegEnable(sV_{k-1}, false.B, sV_{k-1})；
    *     链由 [[StagedShared]] 管理（多 DAG 共享，避免 val 重名）；
    *   - 逐级发射：级内公共子表达式落 val（引用计数按级重算）；跨级节点在其所在级末尾
    *     `val v_k_j = RegEnable(<级内表达式>, 0.U(w.W), sV_k)` 边界寄存，更高级消费者引用之；
    *   - 末级：OutputWrite 组合直出（与 sV_{n-1} 同拍有效）；RegWrite/CounterAdd 仅
    *     when(sV_{n-1} [&& finalGate]) 写——状态原子性 + 同 DAG 读旧值（D3）+ 单一
    *     outValid 末级契约（D4），三点缺一不可。
    *
    * 时序契约：发起间隔（initiation interval）≥ N（Top 一次性 fire 天然满足；
    * 独立例化 control 模块的下游需自行保证，见生成文件头注释）。
    */
  private final class StagedEmitter(
    dag: Dag,
    readPath: Seq[String] => String,
    val indent: String,
    baseValid: String,
    finalGate: Option[String],
    shared: StagedShared,
  ) {
    require(dag.isScheduled, "StagedEmitter 需要已调度的 DAG（先经 Scheduler.schedule）")

    private val nStages = dag.stageCount
    private val last = nStages - 1
    private val lines = mutable.ArrayBuffer.empty[String]
    private val memo = mutable.HashMap.empty[NodeId, String]
    private val boundary = mutable.HashMap.empty[NodeId, String]
    private val refK = mutable.HashMap.empty[NodeId, Int]
    private var stage = 0

    private def stageOf(id: NodeId): Int = dag.stages.getOrElse(id, 0)

    // 每节点消费者的最大级（Sink 恒视为末级）；大于自身级 ⇒ 跨级，需边界寄存
    private val maxConsumerStage: mutable.HashMap[NodeId, Int] = {
      val m = mutable.HashMap.empty[NodeId, Int]
      def bump(id: NodeId, st: Int): Unit = m(id) = math.max(m.getOrElse(id, 0), st)
      dag.outputs.foreach(s => Ir.visitSink(s, id => bump(id, last)))
      dag.nodes.indices.foreach { id =>
        if (dag.stages.contains(id)) {
          val st = stageOf(id)
          Ir.operands(dag.nodes(id)).foreach(op => bump(op, st))
        }
      }
      m
    }

    private def crossing(id: NodeId): Boolean =
      maxConsumerStage.getOrElse(id, stageOf(id)) > stageOf(id)

    private def go(id: NodeId): String = {
      val s = stageOf(id)
      if (s < stage) return boundary.getOrElse(id,
        throw new P4Error(s"切拍发射内部错误：节点 $id 的边界寄存器缺失（级 $s → $stage）"))
      if (memo.contains(id)) return memo(id)
      val node = dag.nodes(id)
      val inlineStr = nodeExpr(dag, node, go, readPath)
      val isLeaf = node.isInstanceOf[Const] || node.isInstanceOf[InputRef]
      if (isLeaf || refK.getOrElse(id, 0) <= 1) inlineStr
      else {
        val name = shared.freshT()
        lines += s"${indent}val $name = $inlineStr"
        memo(id) = name
        name
      }
    }

    private def sinkLine(s: Sink): String = s match {
      case o: OutputWrite =>
        s"$indent${writePath(o.path)} := ${go(o.value)}"
      case r: RegWrite =>
        s"reg_${r.inst}(${fitIdx(go(r.index), dag.nodes(r.index).width, r.size)}) := ${go(r.value)}"
      case c: CounterAdd =>
        val idx = fitIdx(go(c.index), dag.nodes(c.index).width, c.size)
        s"cnt_${c.inst}($idx) := (cnt_${c.inst}($idx) +& ${go(c.delta)})"
    }

    private def writePath(path: Seq[String]): String =
      s"io.${path.head}Out.${path.drop(1).mkString(".")}"

    def hasStateful: Boolean = dag.outputs.exists {
      case _: RegWrite | _: CounterAdd => true
      case _ => false
    }

    /** 完整发射：valid 链（必要时）+ 逐级 val/边界寄存器 + 末级 Sink。
      * 返回新增行（含本发射器新引入的 sV 链 prelude 行，保证先定义后使用）。
      *
      * @param emitOutputs false 时跳过 OutputWrite 直出（静态融合表由 MuxCase 统一驱动） */
    def emit(emitOutputs: Boolean = true): Seq[String] = {
      val preBefore = shared.prelude.length
      // valid 链：N≥2 恒发射——D4 契约 outValid = sV_{n-1} 是 N 拍延迟的结构基础，
      // 下游按拍对齐依赖之；X10 refine 可产生"全挤末级"布局（无 crossing），此时
      // 免链会让 outValid 提前 io.valid 一拍宣告有效，破坏多 control 级联时序。
      // 仅 n=1 纯组合 DAG（无状态写、无跨级）免除，减少死代码。
      val existsCrossing = dag.nodes.indices.exists(id => dag.stages.contains(id) && crossing(id))
      val sV = if (nStages > 1 || hasStateful || existsCrossing) shared.chain(baseValid, nStages, indent) else Seq.empty
      (0 until nStages).foreach { k =>
        stage = k
        // 本级引用计数：级内节点间引用 + 末级 Sink 引用（跨级引用走边界寄存器，不计入）
        refK.clear()
        def bump(id: NodeId): Unit = refK(id) = refK.getOrElse(id, 0) + 1
        if (k == last) dag.outputs.foreach(s => Ir.visitSink(s, bump))
        (0 until dag.nodes.length).foreach { id =>
          if (dag.stages.get(id).contains(k)) {
            val expr = go(id)
            if (crossing(id)) {
              val w = dag.nodes(id).width
              val (name, isNew) = shared.boundaryReg(sV(k), expr, w)
              boundary(id) = name
              if (isNew) lines += s"${indent}val $name = RegEnable($expr, 0.U($w.W), ${sV(k)})"
            }
          }
        }
        if (k == last) {
          val stateLines = mutable.ArrayBuffer.empty[String]
          dag.outputs.foreach { s =>
            s match {
              case _: OutputWrite if !emitOutputs => // 静态融合表：字段值由 MuxCase 统一驱动
              case _: OutputWrite => lines += sinkLine(s)
              case _: RegWrite | _: CounterAdd => stateLines += sinkLine(s)
            }
          }
          if (stateLines.nonEmpty) {
            val cond = finalGate match {
              case Some(g) => s"${sV(last)} && $g"
              case None => sV(last)
            }
            lines += s"${indent}when ($cond) {"
            stateLines.foreach(l => lines += s"$indent$IND$l")
            lines += s"$indent}"
          }
        }
      }
      stage = 0
      shared.prelude.drop(preBefore) ++ lines.toSeq
    }

    /** 末级取值：root 在末级 → 组合表达式；root 在更早级 → 其边界寄存器名。
      * 必须在 [[emit]] 之后调用（依赖其中的解析 memo / boundary 表）。 */
    def emitExprAtLastStage(root: NodeId): String = {
      val saved = stage
      stage = last
      val r = go(root)
      stage = saved
      r
    }
  }

  private def readOf(param: String, rest: Seq[String]): String =
    s"io.${param}In.${rest.mkString(".")}"

  // ---------------- 运行时表（表项运行时可配置） ----------------

  /** 运行时表条目布局（MSB → LSB）：valid(1) | actionId(actW) | 参数位串(argW) | key(keyBits)。
    *
    * 单条目单字打包：写口只需一条 wdata 总线，未来映射 SRAM 时天然是一行 wide word
    * （P2-1；替换点 = `emitRuntimeTable` 的存储/写端口两段发射）。
    *
    * @param argOffsets action 名 → 各形参在 argW 字段内的 LSB 偏移（按声明序，先声明在高位） */
  private final case class RuntimeLayout(
    addrW: Int, entryW: Int, actW: Int, argW: Int, keyBits: Int,
    argOffsets: Map[String, Seq[(String, Int, Int)]], // action → Seq(形参名, LSB 偏移, 宽)
  )

  private def addrWidthOf(size: Int): Int = math.max(1, BigInt(math.max(0, size - 1)).bitLength)

  private def runtimeLayout(t: TableDecl, c: ControlDecl, keyBits: Int): RuntimeLayout = {
    val actions = t.actions.map { n =>
      c.actions.find(_.name == n).getOrElse(throw new P4Error(s"table '${t.name}'：未知 action '$n'"))
    }
    val actW = math.max(1, BigInt(math.max(0, actions.size - 1)).bitLength)
    val argW = actions.map(a => a.params.map(_.width).sum).foldLeft(0)(math.max)
    val argOffsets = actions.map { a =>
      // 第 j 个形参的 LSB 偏移 = 其后所有形参宽度之和（先声明者占高位，不足处高位补 0）
      val offs = a.params.zipWithIndex.map { case (p, j) =>
        (p.name, a.params.drop(j + 1).map(_.width).sum, p.width)
      }
      a.name -> offs
    }.toMap
    RuntimeLayout(addrWidthOf(t.runtimeSize), 1 + actW + argW + keyBits, actW, argW, keyBits, argOffsets)
  }

  private def resolverFor(prog: P4Program, c: ControlDecl): WidthResolver =
    new WidthResolver(
      prog.headerTypes.map(ht => ht.name -> ht).toMap,
      prog.structs.map(st => st.name -> st).toMap,
      c.params,
    )

  /** 表 key 各段宽度（静态/运行时共用）。
    * 字段路径直接查 resolver；其余表达式（demo10 条件选 key：三元在 UDP/TCP 字段间
    * 选择、切片、拼接等）lowering 一次推宽——emitTableKey 会以同口径二次 lower 并
    * fit 到该宽度，两处一致。 */
  private def tableKeyWidths(t: TableDecl, resolver: WidthResolver): Seq[Int] =
    t.keys.map { k =>
      k.expr match {
        case Name(p, _) => resolver.widthOf(p)
        case other =>
          val b = new Ir.Builder
          val (_, w) = new ExprLowering(resolver, b).lower(other, None, Map.empty)
          w
      }
    }

  /** 运行时表清单（用于生成文件头协议注释）：（control 名, table 声明, 布局）。 */
  private def runtimeTables(prog: P4Program): Seq[(String, TableDecl, RuntimeLayout)] =
    prog.controls.flatMap { c =>
      val resolver = resolverFor(prog, c)
      c.tables.filter(_.isRuntime).map { t =>
        (c.name, t, runtimeLayout(t, c, tableKeyWidths(t, resolver).sum))
      }
    }

  /** 运行时表写接口协议注释（生成文件头；PRD 验收 5：表深 / key 宽 / actW / argW 编译期回显）。 */
  private def runtimeTableHeaderComment(prog: P4Program): Seq[String] = {
    val rts = runtimeTables(prog)
    if (rts.isEmpty) Seq.empty
    else {
      val buf = mutable.ArrayBuffer.empty[String]
      buf += "// 运行时表（`// p4c: table <表名> runtime [size=N]`，条目内容运行时可写）："
      buf += "//   - 写接口：tbl_<表名>_we / _waddr / _wdata；we=1 且 waddr < size 时在时钟沿提交"
      buf += "//     整条目（单字单口，结构上原子）；waddr 越界写被忽略（表内容不变）；"
      buf += "//     写 valid=0 的条目即删除该表项（回 miss）。"
      buf += "//   - 可见性：写在时钟沿提交，查找是当前寄存器值的组合函数——写拍当拍的查找看到"
      buf += "//     旧值，下一拍起的查找看到新值（结果为旧/新之一，绝不撕裂）。写口与查找无互锁。"
      buf += "//   - 上电：全部条目为 0（valid=0）⇒ 空表全 miss，走编译期固定的 default action。"
      buf += "//   - 条目位布局（MSB → LSB）：valid(1) | actionId(actW) | args(argW) | key(keyBits)；"
      buf += "//     多命中时低地址优先（PriorityMux，与静态表 MuxCase 声明序同构）。"
      buf += "//   - 表清单（size / keyBits / actW / argW / entryW / addrW）："
      rts.foreach { case (cn, t, lay) =>
        buf += s"//     ${cn}.${t.name}: size=${t.runtimeSize}, keyBits=${lay.keyBits}, " +
          s"actW=${lay.actW}, argW=${lay.argW}, entryW=${lay.entryW}, addrW=${lay.addrW}"
      }
      buf.toSeq
    }
  }

  // ---------------- 类型发射 ----------------

  private def emitBundles(prog: P4Program, tmap: String => String): Seq[String] = {
    val out = mutable.ArrayBuffer.empty[String]
    out += s"${IND}// ---------- 类型 Bundle ----------"
    prog.headerTypes.foreach { ht =>
      val cn = tmap(ht.name)
      out += s"final class $cn extends Bundle {"
      ht.fields.foreach(f => out += s"${IND}val ${f.name} = UInt(${f.width}.W)")
      out += s"}"
      out += ""
    }
    prog.structs.foreach { st =>
      val cn = tmap(st.name)
      out += s"final class $cn extends Bundle {"
      st.members.foreach {
        case StructMember(_, true, w, n) => out += s"${IND}val $n = UInt($w.W)"
        case StructMember(tn, false, _, n) => out += s"${IND}val $n = new ${tmap(tn)}"
      }
      out += s"}"
      out += ""
    }
    out.toSeq
  }

  // ---------------- control 发射（M1 / M2 / M4） ----------------

  private def emitControl(
    prefix: String, c: ControlDecl, prog: P4Program, tmap: String => String, globalStages: Int = 1,
    sigs: Option[mutable.ArrayBuffer[Signature.ControlSig]] = None,
    clock: Option[Int] = None, model: DelayModel = DelayModels.default,
    irDump: Option[(String, Ir.Dag) => Unit] = None,
  ): (Seq[String], Int) = {
    // E2：声明级指示覆盖全局预算（无指示 → 全局值；全局 1 且无指示 → 与历史逐字节一致）
    val stages = c.stagesOpt.getOrElse(globalStages)
    // X2：clock 模式（--clock W，与声明级 stages 指示互斥——指示优先）。
    // 每 DAG 独立解算最小可行级数；io 端口取保守超集（恒发 valid/outValid，供 Top 稳定接线）。
    val clockMode = clock.isDefined && c.stagesOpt.isEmpty
    val resolver = resolverFor(prog, c)
    val externMap = c.externs.map(e => e.name -> e).toMap
    val stateful = c.externs.nonEmpty
    // 运行时表（D1/D2/D4）：写端口 + 存储；静态表（isRuntime=false）不新增任何端口/存储
    val rtTables = c.tables.filter(_.isRuntime)
    val rtLayouts: Map[String, RuntimeLayout] = rtTables.map { t =>
      t.name -> runtimeLayout(t, c, tableKeyWidths(t, resolver).sum)
    }.toMap
    val out = mutable.ArrayBuffer.empty[String]
    val className = pascal(prefix) + pascal(c.name)
    out += s"final class $className extends Module {"
    out += s"${IND}val io = IO(new Bundle {"
    c.params.foreach {
      case ControlParam(n, "inout", t, _) =>
        out += s"$IND$IND val ${n}In = Input(new ${tmap(t)})"
        out += s"$IND$IND val ${n}Out = Output(new ${tmap(t)})"
      case ControlParam(n, "in", t, _) => out += s"$IND$IND val ${n}In = Input(new ${tmap(t)})"
      case ControlParam(n, "out", t, _) => out += s"$IND$IND val ${n}Out = Output(new ${tmap(t)})"
      case p => throw new P4Error(s"行 ${p.line}：不支持的方向 '${p.direction}'")
    }
    // D5：valid 输入端口仅在 externs 非空或（切拍启用）时发射；默认模式 IO 与现状一致
    if (stateful || stages > 1 || clockMode) out += s"$IND$IND val valid = Input(Bool())"
    // extern 状态观察口
    c.externs.foreach { e => out += s"$IND$IND val ex_${e.name} = Output(Vec(${e.size}, UInt(${e.width}.W)))" }
    // D4：切拍模式下末级 stageValid 对外暴露
    if (stages > 1 || clockMode) out += s"$IND$IND val outValid = Output(Bool())"
    // 运行时表写端口（D4：每表独立写口）
    if (rtTables.nonEmpty) {
      val existingIo = c.params.flatMap {
        case ControlParam(n, "inout", _, _) => Seq(s"${n}In", s"${n}Out")
        case ControlParam(n, "in", _, _) => Seq(s"${n}In")
        case ControlParam(n, "out", _, _) => Seq(s"${n}Out")
        case _ => Seq.empty
      }.toSet ++ (if (stateful || stages > 1 || clockMode) Set("valid") else Set.empty[String]) ++
        c.externs.map(e => s"ex_${e.name}").toSet ++ (if (stages > 1 || clockMode) Set("outValid") else Set.empty[String])
      out += s"$IND$IND // 运行时表写端口（协议见文件头注释）"
      rtTables.foreach { t =>
        val lay = rtLayouts(t.name)
        val ports = Seq(s"tbl_${t.name}_we", s"tbl_${t.name}_waddr", s"tbl_${t.name}_wdata")
        ports.find(p => existingIo.contains(p) || out.exists(_.contains(s"val $p ="))).foreach { p =>
          throw new P4Error(s"table '${t.name}'：运行时表写端口 '$p' 与既有 io 成员命名冲突")
        }
        out += s"$IND$IND val ${ports(0)} = Input(Bool())"
        out += s"$IND$IND val ${ports(1)} = Input(UInt(${lay.addrW}.W))"
        out += s"$IND$IND val ${ports(2)} = Input(UInt(${lay.entryW}.W))"
      }
    }
    out += s"$IND})"
    out += ""

    // 状态单元（M4）：Register / Counter
    c.externs.foreach {
      case ExternInst("Register", w, size, n, _) =>
        out += s"$IND val reg_$n = RegInit(VecInit(Seq.fill($size)(0.U($w.W))))"
        out += s"$IND io.ex_$n := reg_$n"
      case ExternInst("Counter", w, size, n, _) =>
        out += s"$IND val cnt_$n = RegInit(VecInit(Seq.fill($size)(0.U($w.W))))"
        out += s"$IND io.ex_$n := cnt_$n"
      case e => throw new P4Error(s"行 ${e.line}：未知 extern '${e.kind}'")
    }
    if (stateful) out += ""

    // 运行时表条目存储（D2：Vec[Reg] 组合读，上电全 0 ⇒ 空表全 miss → 走 default）
    rtTables.foreach { t =>
      val lay = rtLayouts(t.name)
      out += s"$IND // 运行时表 ${t.name}：size=${t.runtimeSize}, entryW=${lay.entryW}（布局见文件头注释）"
      out += s"$IND val rt_${t.name} = RegInit(VecInit(Seq.fill(${t.runtimeSize})(0.U(${lay.entryW}.W))))"
      // 越界守卫：size 非 2 的幂时 addrW 有冗余组合；2 的幂时该条件恒真，被常量折叠
      out += s"$IND when (io.tbl_${t.name}_we && io.tbl_${t.name}_waddr < ${t.runtimeSize}.U) {"
      out += s"$IND$IND rt_${t.name}(io.tbl_${t.name}_waddr) := io.tbl_${t.name}_wdata"
      out += s"$IND }"
    }
    if (rtTables.nonEmpty) out += ""

    // 透传
    c.params.foreach { p =>
      if (p.direction == "inout") out += s"$IND io.${p.name}Out := io.${p.name}In"
    }

    val actions = c.actions.map(a => a.name -> a).toMap
    val tables = c.tables.map(t => t.name -> t).toMap

    def lowerStmts(stmts: Seq[Stmt], binds: Bindings, lowering: ExprLowering): Seq[Sink] =
      stmts.map {
        case asg: Assign => lowering.lowerAssign(asg.path, asg.expr, binds)
        case mc: MethodCall => lowering.lowerMethodCall(mc, binds)
        case s => throw new P4Error(s"行 ${s.line}：action 体中不支持的语句（M4 子集：赋值 / extern 方法调用）")
      }

    val fire = if (stateful) Some("io.valid") else None

    // 切拍共享上下文与发射路由（D4：N=1 不创建、不走 StagedEmitter，原路径逐字节不变）
    val shared = if (stages > 1 || clockMode) new StagedShared else null

    // 签名收集（X1）：各 DAG 的节点 → 流水级映射（未调度 = 全 0 级）；
    // X8：irDump 旁路（IR 文本导出）；X13：critPath/regs/bom 增补
    val dagSigs = mutable.ArrayBuffer.empty[Signature.DagSig]
    def recordDag(ctx: String, d: Dag): Unit = {
      dagSigs += Signature.dagSig(ctx, d, model)
      irDump.foreach(f => f(ctx, d))
    }

    /** 预算解算：声明级指示 > clock 模式（逐 DAG 最小可行级数）> 全局固定 N。 */
    def budgetOf(dag: Dag, ctx: String): Int = c.stagesOpt.getOrElse(clock match {
      case Some(w) => Scheduler.minFeasibleStages(dag, w, ctx, model)
      case None => globalStages
    })

    def scheduleDag(dag: Dag, ctx: String): Dag = {
      val n = budgetOf(dag, ctx)
      // X10：编译管线显式开启 SDC-lite 精化（寄存器最小化；级数不变，等价性由测试兜底）
      val d = if (n > 1) Scheduler.maybeSchedule(dag, n, ctx, model = model, refine = true, clockW = clock) else dag
      recordDag(ctx, d)
      d
    }

    def emitDag(dag: Dag): Unit = {
      if (dag.isScheduled) {
        val se = new StagedEmitter(dag, p => readOf(p.head, p.drop(1)), s"$IND", "io.valid", None, shared)
        out ++= se.emit()
      } else {
        val em = new Emitter(dag, p => readOf(p.head, p.drop(1)), s"$IND", fire)
        dag.outputs.foreach(em.emitSink)
        out ++= em.takeLines
      }
    }
    var maxStageCount = 1

    // 发射 apply 体
    c.applyBody.foreach {
      case ActionCall(name, args, ln) =>
        val a = actions.getOrElse(name, throw new P4Error(s"行 $ln：未知 action '$name'"))
        if (args.length != a.params.length)
          throw new P4Error(s"行 $ln：action '$name' 实参数量不匹配")
        val b = new Ir.Builder
        val lowering = new ExprLowering(resolver, b, externMap)
        val binds: Bindings = a.params.zip(args).map { case (p, e) =>
          val (id, w) = lowering.lower(e, Some(p.width), Map.empty)
          p.name -> ((b.fit(id, w, p.width), p.width))
        }.toMap
        val outs = lowerStmts(a.body, binds, lowering)
        val dag = scheduleDag(Passes.runAll(b.finish(outs)), s"control ${c.name}/action $name")
        if (dag.isScheduled) maxStageCount = math.max(maxStageCount, dag.stageCount)
        out += s"$IND// action $name"
        emitDag(dag)
        out += ""
      case asg: Assign =>
        val b = new Ir.Builder
        val lowering = new ExprLowering(resolver, b, externMap)
        val dag0 = b.finish(Seq(lowering.lowerAssign(asg.path, asg.expr, Map.empty)))
        val dag = scheduleDag(Passes.runAll(dag0), s"control ${c.name}/assign ${asg.path.mkString(".")}")
        if (dag.isScheduled) maxStageCount = math.max(maxStageCount, dag.stageCount)
        emitDag(dag)
        out += ""
      case mc: MethodCall =>
        val b = new Ir.Builder
        val lowering = new ExprLowering(resolver, b, externMap)
        val dag0 = b.finish(Seq(lowering.lowerMethodCall(mc, Map.empty)))
        val dag = scheduleDag(Passes.runAll(dag0), s"control ${c.name}/${mc.inst}.${mc.method}")
        if (dag.isScheduled) maxStageCount = math.max(maxStageCount, dag.stageCount)
        emitDag(dag)
        out += ""
      case TableApply(n, ln) =>
        val t = tables.getOrElse(n, throw new P4Error(s"行 $ln：未知 table '$n'"))
        val (lines, tableN) =
          if (t.isRuntime) {
            // D3：运行时表条目的 key/action/参数是运行时状态，编译期只允许固定的 default 行
            if (t.entries.exists(!_.isDefault))
              throw new P4Error(
                s"行 ${t.line}：运行时表 '${t.name}' 不允许 const entries（仅允许 default 行；" +
                  "表项请在运行时经写接口下发）")
            if (stages > 1 || clockMode)
              throw new P4Error(
                s"行 ${t.line}：运行时表 '${t.name}' 暂不支持切拍（--stages > 1 或 --clock 约束下），本期仅 N=1")
            emitRuntimeTable(t, c, prog, resolver, externMap, stateful, s"$IND", rtLayouts(t.name), recordDag)
          } else {
            if (t.entries.isEmpty) throw new P4Error(s"行 $ln：table '$n' 无 const entries（M2 仅支持静态融合）")
            val clockRes = clock.map { w => (d: Dag) => Scheduler.minFeasibleStages(d, w, s"table ${t.name}", model) }
            emitStaticTable(t, c, prog, resolver, externMap, stateful, s"$IND", stages, shared, recordDag, clockRes, model, clock)
          }
        out ++= lines
        maxStageCount = math.max(maxStageCount, tableN)
        out += ""
      case v: VarDecl => throw new P4Error(s"行 ${v.line}：M1/M2 暂不支持 apply 内局部变量")
      case s => throw new P4Error(s"行 ${s.line}：control 中不支持的语句")
    }

    // D4：control 输出有效 = 所有已发射 valid 链的末级 stageValid（多链取与）
    if (stages > 1 || clockMode) {
      val cond = shared.usedLastStages match {
        case Seq() => "io.valid" // 无切拍管线（全部 DAG 为空/纯组合单级）
        case Seq(single) => single
        case multi => multi.map(n => s"($n)").mkString(" && ")
      }
      out += s"$IND io.outValid := $cond"
      out += ""
    }

    out += "}"
    sigs.foreach { buf =>
      val ports = mutable.ArrayBuffer.empty[Signature.PortSig]
      def flattenInOut(paramName: String, typeName: String, dir: String): Unit =
        prog.structs.find(_.name == typeName).foreach { st =>
          st.members.foreach {
            case StructMember(_, true, w, n) => ports += Signature.PortSig(s"$paramName.$n", dir, w)
            case StructMember(tn, false, _, n) =>
              prog.headerTypes.find(_.name == tn).foreach { ht =>
                ht.fields.foreach(f => ports += Signature.PortSig(s"$paramName.$n.${f.name}", dir, f.width))
              }
          }
        }
      c.params.foreach {
        case ControlParam(n, "inout", t, _) =>
          flattenInOut(s"${n}In", t, "input"); flattenInOut(s"${n}Out", t, "output")
        case ControlParam(n, "in", t, _) => flattenInOut(s"${n}In", t, "input")
        case ControlParam(n, "out", t, _) => flattenInOut(s"${n}Out", t, "output")
        case _ =>
      }
      if (stateful || stages > 1 || clockMode) ports += Signature.PortSig("valid", "input", 1)
      c.externs.foreach(e => ports += Signature.PortSig(s"ex_${e.name}", "output", e.width, vecSize = e.size))
      if (stages > 1 || clockMode) ports += Signature.PortSig("outValid", "output", 1)
      rtTables.foreach { t =>
        val lay = rtLayouts(t.name)
        ports += Signature.PortSig(s"tbl_${t.name}_we", "input", 1)
        ports += Signature.PortSig(s"tbl_${t.name}_waddr", "input", lay.addrW)
        ports += Signature.PortSig(s"tbl_${t.name}_wdata", "input", lay.entryW)
      }
      val tableSigs = c.tables.map { t =>
        if (t.isRuntime) {
          val lay = rtLayouts(t.name)
          Signature.TableSig(t.name, runtime = true, t.runtimeSize, lay.keyBits, lay.actW, lay.argW, lay.entryW, lay.addrW)
        } else Signature.TableSig(t.name, runtime = false, t.entries.size)
      }
      val externSigs = c.externs.map(e => Signature.ExternSig(e.name, e.kind, e.width, e.size))
      buf += Signature.ControlSig(className, ports.toSeq, tableSigs, externSigs, dagSigs.toSeq)
    }
    (out.toSeq, maxStageCount)
  }

  /** M2：静态融合 exact 表。
    *
    * 切拍（T03，P1）：key 构建与 hit_i 保持第 0 级组合、结构不变（表匹配整体视为
    * 原子级——主理人裁定）；action 部分经 Scheduler.maybeSchedule + StagedEmitter 切拍。
    * key 来自 io.*In（调用期间稳定），末级直接引用 hit_i / keyVal 无需寄存。
    * default entry 同样调度（各路径末级对齐）。
    */
  private def emitStaticTable(
    t: TableDecl, c: ControlDecl, prog: P4Program, resolver: WidthResolver,
    externMap: Map[String, ExternInst], stateful: Boolean, indent: String,
    stages: Int = 1, shared: StagedShared = null,
    recordDag: (String, Ir.Dag) => Unit = (_, _) => (),
    clockRes: Option[Ir.Dag => Int] = None,
    model: DelayModel = DelayModels.default,
    clockW: Option[Int] = None,
  ): (Seq[String], Int) = {
    val out = mutable.ArrayBuffer.empty[String]
    out += s"$indent// table ${t.name}（静态融合，${t.entries.size} 项）"

    if (t.keys.exists(_.matchKind != "exact"))
      throw new P4Error(s"table '${t.name}'：M2 仅支持 exact 匹配")

    val keyWidths = tableKeyWidths(t, resolver)

    // key 表达式（读取输入）——静态/运行时表共用（emitTableKey，输出与抽取前逐字一致）
    val (keyLines, keyVal, totalKeyWidth) = emitTableKey(t, keyWidths, resolver, externMap, indent)
    out ++= keyLines

    // 命中信号
    val nonDefault = t.entries.filterNot(_.isDefault)
    nonDefault.zipWithIndex.foreach { case (e, i) =>
      val kv = combineKeys(e.keys.map(lowerConstKey(_, resolver, t.name)), keyWidths)
      out += s"$indent val hit_$i = $keyVal === ${litU(kv, totalKeyWidth)}"
    }

    // 各字段写出：收集所有非 default 表项的写出字段
    val defaultEntry = t.entries.find(_.isDefault)
    case class EntryDag(entry: TableEntry, dag: Ir.Dag, hits: Boolean, idx: Int)
    /** 预算解算：clock 模式逐 entry DAG 解最小可行级数；否则固定 stages。 */
    def nOf(d: Ir.Dag, ctx: String): Int = clockRes match {
      case Some(f) => f(d)
      case None => stages
    }
    def schedEntry(e: TableEntry): Ir.Dag = {
      val d0 = lowerEntry(e, resolver, c, prog, externMap)
      val ctx = s"table ${t.name}/${e.action}"
      val n = nOf(d0, ctx)
      // X10：编译管线开启 SDC-lite 精化（clockW 约束下保持每级延迟不超限）
      if (n > 1) Scheduler.maybeSchedule(d0, n, ctx, model = model, refine = true, clockW = clockW)
      else d0
    }
    val entryDags = nonDefault.zipWithIndex.map { case (e, i) =>
      EntryDag(e, schedEntry(e), hits = true, idx = i)
    } ++ defaultEntry.map(e => EntryDag(e, schedEntry(e), hits = false, -1))
    entryDags.foreach(ed => recordDag(s"table ${t.name}/${ed.entry.action}", ed.dag))

    // 切拍：各 entry 的 StagedEmitter（字段值/状态写都从这些实例取）
    val stagedEms = mutable.HashMap.empty[Int, StagedEmitter]
    var maxStageCount = 1
    entryDags.foreach { ed =>
      if ((stages > 1 || clockRes.isDefined) && ed.dag.isScheduled) {
        val se = new StagedEmitter(
          ed.dag, p => readOf(p.head, p.drop(1)), indent,
          baseValid = if (stateful) "io.valid" else "true.B",
          finalGate = if (ed.hits) Some(s"hit_${ed.idx}") else None,
          shared,
        )
        out ++= se.emit(emitOutputs = false)
        stagedEms(ed.idx) = se
        maxStageCount = math.max(maxStageCount, ed.dag.stageCount)
      } else {
        // 状态单元写（M4）：每个表项在其命中（或 default + io.valid）条件下写
        val stateSinks = ed.dag.outputs.filter {
          case _: OutputWrite => false
          case _ => true
        }
        if (stateSinks.nonEmpty) {
          val fire = (if (stateful) "io.valid" else "true.B") +
            (if (ed.hits) s" && hit_${ed.idx}" else "")
          val em = new Emitter(ed.dag, p => readOf(p.head, p.drop(1)), indent, Some(fire))
          stateSinks.foreach(em.emitSink)
          out ++= em.takeLines
        }
      }
    }

    val fieldOrder = mutable.LinkedHashSet.empty[Seq[String]]
    entryDags.foreach(ed => ed.dag.outputs.foreach {
      case o: OutputWrite => fieldOrder += o.path
      case _ =>
    })

    fieldOrder.foreach { path =>
      val param = path.head
      val dir = c.params.find(_.name == param).map(_.direction).getOrElse("inout")
      val readExpr = readOf(path.head, path.drop(1))
      val fallbackEntry = entryDags.find(ed => !ed.hits && ed.dag.outputs.exists {
        case o: OutputWrite => o.path == path
        case _ => false
      })
      val writeExprs = entryDags.filter(_.hits).flatMap { ed =>
        ed.dag.outputs.collectFirst { case ow: OutputWrite if ow.path == path => ow }.map { ow =>
          if (stages > 1) {
            // 行已由对应 StagedEmitter.emit 发射；这里只取末级表达式
            (Seq.empty[String], stagedEms(ed.idx).emitExprAtLastStage(ow.value))
          } else {
            val em = new Emitter(ed.dag, p => readOf(p.head, p.drop(1)), indent)
            val e = em.emitExprRoot(ow.value)
            (em.takeLines, e)
          }
        }
      }
      writeExprs.foreach { case (ls, _) => out ++= ls }
      val muxPairs = writeExprs.zipWithIndex.map { case ((_, e), i) => s"hit_$i -> $e" }
      val rhs = fallbackEntry match {
        case Some(fed) =>
          val ow = fed.dag.outputs.collectFirst { case o: OutputWrite if o.path == path => o }.get
          if (stages > 1) stagedEms(fed.idx).emitExprAtLastStage(ow.value)
          else {
            val em = new Emitter(fed.dag, p => readOf(p.head, p.drop(1)), indent)
            em.emitExprRoot(ow.value)
          }
        case None => readExpr
      }
      if (muxPairs.isEmpty) {
        if (fallbackEntry.isDefined || dir == "out") out += s"$indent io.${param}Out.${path.drop(1).mkString(".")} := $rhs"
      } else {
        out += s"$indent io.${param}Out.${path.drop(1).mkString(".")} := MuxCase($rhs, Seq("
        out += s"$indent$indent ${muxPairs.mkString(", ")}))"
      }
    }
    (out.toSeq, maxStageCount)
  }

  /** 运行时表（表项运行时可配置；`// p4c: table <表名> runtime [size=N]`）。
    *
    * 与静态融合表（[[emitStaticTable]]）的结构差异仅在 hit / action / 参数的来源：
    * 静态是编译期常量（每条目一份 DAG），运行时是存储切片（每 **action** 一份
    * 参数化 DAG，按 actionId 选通）。key 构建两段共用 [[emitTableKey]]。
    *
    * 时序：存储为 Vec[Reg] 组合读，`hits/hit/sel/act/args` 全是第 0 级组合信号，
    * 相对 io.valid 零拍延迟——与静态表 hit_i 的处理一致，切拍 valid 链契约不受影响。
    */
  private def emitRuntimeTable(
    t: TableDecl, c: ControlDecl, prog: P4Program, resolver: WidthResolver,
    externMap: Map[String, ExternInst], stateful: Boolean, indent: String, lay: RuntimeLayout,
    recordDag: (String, Ir.Dag) => Unit = (_, _) => (),
  ): (Seq[String], Int) = {
    val out = mutable.ArrayBuffer.empty[String]
    out += s"$indent// table ${t.name}（运行时，size=${t.runtimeSize}）"

    if (t.keys.exists(_.matchKind != "exact"))
      throw new P4Error(s"table '${t.name}'：运行时表仅支持 exact 匹配")

    val (keyLines, keyVal, _) = emitTableKey(t, tableKeyWidths(t, resolver), resolver, externMap, indent)
    out ++= keyLines

    // 命中：逐条目 valid && key 匹配；多命中时低地址优先（与静态表 MuxCase 声明序同构）
    val hits = s"rt_${t.name}_hits"
    out += s"$indent val $hits = VecInit(rt_${t.name}.map { e =>"
    out += s"$indent$IND e(${lay.entryW - 1}) && e(${lay.keyBits - 1}, 0) === $keyVal"
    out += s"$indent })"
    out += s"$indent val rt_${t.name}_hit = $hits.asUInt.orR"
    out += s"$indent val rt_${t.name}_sel = rt_${t.name}(PriorityMux($hits.zipWithIndex.map { case (h, i) => (h, i.U(${lay.addrW}.W)) }))"
    out += s"$indent val rt_${t.name}_act = rt_${t.name}_sel(${lay.entryW - 2}, ${lay.argW + lay.keyBits})"
    if (lay.argW > 0)
      out += s"$indent val rt_${t.name}_args = rt_${t.name}_sel(${lay.argW + lay.keyBits - 1}, ${lay.keyBits})"

    // 每个 action 一份参数化 DAG：形参绑定为合成路径 __rtarg（发射时映射到参数位串切片）
    val actionDags: Seq[(Int, ActionDecl, Ir.Dag)] = t.actions.zipWithIndex.map { case (aname, i) =>
      val a = c.actions.find(_.name == aname)
        .getOrElse(throw new P4Error(s"table '${t.name}'：未知 action '$aname'"))
      val b = new Ir.Builder
      val lowering = new ExprLowering(resolver, b, externMap)
      val binds: Bindings = a.params.map { p =>
        p.name -> ((b.add(InputRef(Seq("__rtarg", p.name), p.width)), p.width))
      }.toMap
      val outs = a.body.map {
        case asg: Assign => lowering.lowerAssign(asg.path, asg.expr, binds)
        case mc: MethodCall => lowering.lowerMethodCall(mc, binds)
        case s => throw new P4Error(s"行 ${s.line}：action 体中不支持的语句")
      }
      (i, a, Passes.runAll(b.finish(outs)))
    }
    actionDags.foreach { case (_, a, dag) => recordDag(s"rt table ${t.name}/${a.name}", dag) }

    /** 形参 → 参数位串切片。偏移相对 `rt_<name>_args` 字段内部（该字段已是条目的
      * [argW+keyBits-1 : keyBits] 段），先声明者占高位（见 RuntimeLayout.argOffsets）。 */
    def readPathFor(a: ActionDecl): Seq[String] => String = {
      case Seq("__rtarg", pname) =>
        val (off, w) = lay.argOffsets(a.name).find(_._1 == pname)
          .map(x => (x._2, x._3))
          .getOrElse(throw new P4Error(s"action '${a.name}'：未知参数 '$pname'"))
        s"rt_${t.name}_args(${off + w - 1}, $off)"
      case other => readOf(other.head, other.drop(1))
    }

    val baseFire = if (stateful) "io.valid" else "true.B"
    def actGate(i: Int): String = s"rt_${t.name}_hit && (rt_${t.name}_act === $i.U(${lay.actW}.W))"

    // stateful 写（Register/Counter）：仅在命中该 action 时提交
    actionDags.foreach { case (i, a, dag) =>
      val stateSinks = dag.outputs.filter { case _: OutputWrite => false; case _ => true }
      if (stateSinks.nonEmpty) {
        val em = new Emitter(dag, readPathFor(a), indent, Some(s"$baseFire && ${actGate(i)}"))
        stateSinks.foreach(em.emitSink)
        out ++= em.takeLines
      }
    }

    // default entry（D3：编译期固定；参数走 lowerEntry 的常量路径，stateful 写无命中门控）
    val defaultDag = t.entries.find(_.isDefault).map(e => lowerEntry(e, resolver, c, prog, externMap))
    defaultDag.foreach { dag =>
      recordDag(s"rt table ${t.name}/default", dag)
      val stateSinks = dag.outputs.filter { case _: OutputWrite => false; case _ => true }
      if (stateSinks.nonEmpty) {
        val em = new Emitter(dag, p => readOf(p.head, p.drop(1)), indent, Some(baseFire))
        stateSinks.foreach(em.emitSink)
        out ++= em.takeLines
      }
    }

    // 字段写出：MuxCase(默认值/default, Seq(各 action 的命中选通 -> 值))
    val fieldOrder = mutable.LinkedHashSet.empty[Seq[String]]
    (actionDags.map(_._3) ++ defaultDag.toSeq).foreach(_.outputs.foreach {
      case o: OutputWrite => fieldOrder += o.path
      case _ =>
    })

    fieldOrder.foreach { path =>
      val dir = c.params.find(_.name == path.head).map(_.direction).getOrElse("inout")
      val pairs = actionDags.flatMap { case (i, a, dag) =>
        dag.outputs.collectFirst { case ow: OutputWrite if ow.path == path => ow }.map { ow =>
          val em = new Emitter(dag, readPathFor(a), indent)
          (em.takeLines, s"(${actGate(i)}) -> ${em.emitExprRoot(ow.value)}")
        }
      }
      pairs.foreach { case (ls, _) => out ++= ls }
      val rhs = defaultDag.flatMap { dag =>
        dag.outputs.collectFirst { case o: OutputWrite if o.path == path => o }.map { ow =>
          val em = new Emitter(dag, p => readOf(p.head, p.drop(1)), indent)
          em.emitExprRoot(ow.value)
        }
      }.getOrElse(readOf(path.head, path.drop(1)))
      val lhs = s"io.${path.head}Out.${path.drop(1).mkString(".")}"
      if (pairs.isEmpty) {
        if (defaultDag.isDefined || dir == "out") out += s"$indent $lhs := $rhs"
      } else {
        out += s"$indent $lhs := MuxCase($rhs, Seq("
        out += s"$indent$indent ${pairs.map(_._2).mkString(", ")}))"
      }
    }
    (out.toSeq, 1)
  }

  /** 表 key 发射（emitStaticTable / emitRuntimeTable 共用）：
    * 字段路径 lowering → Emitter → 多 key Cat。
    * 单 key 返回内联读取表达式；多 key 发射 `val key = Cat(...)`（先声明的 key 在高位）。
    *
    * @return (新增行, key 表达式, key 总位宽)。重构红线：静态路径输出与抽取前逐字节一致。
    */
  private def emitTableKey(
    t: TableDecl, keyWidths: Seq[Int], resolver: WidthResolver,
    externMap: Map[String, ExternInst], indent: String,
  ): (Seq[String], String, Int) = {
    val out = mutable.ArrayBuffer.empty[String]
    val keyExprs = t.keys.zip(keyWidths).map { case (k, w) =>
      val b = new Ir.Builder
      val lowering = new ExprLowering(resolver, b, externMap)
      val (id, kw) = lowering.lower(k.expr, Some(w), Map.empty)
      val dag = Passes.runAll(b.finish(Seq(OutputWrite(Seq("__key"), b.fit(id, kw, w), w))))
      val em = new Emitter(dag, p => readOf(p.head, p.drop(1)), indent)
      val expr = em.emitExprRoot(dag.outputs.head.asInstanceOf[OutputWrite].value)
      (expr, em.takeLines, w)
    }
    keyExprs.foreach { case (_, ls, _) => out ++= ls }
    val keyVal = keyExprs match {
      case Seq((e, _, _)) => e
      case multi =>
        // 多 key：Cat 拼接（先声明的 key 在高位）
        out += s"$indent val key = Cat(${multi.map(_._1).mkString(", ")})"
        "key"
    }
    (out.toSeq, keyVal, keyWidths.sum)
  }

  private def lowerConstKey(e: Expr, resolver: WidthResolver, tableName: String): (BigInt, Int) = {
    val b = new Ir.Builder
    val lowering = new ExprLowering(resolver, b)
    val (id, w) = lowering.lower(e, None, Map.empty)
    val dag = Passes.runAll(b.finish(Seq(OutputWrite(Seq("__k"), id, w))))
    dag.nodes(dag.outputs.head.asInstanceOf[OutputWrite].value) match {
      case Const(v, ww) => (v, ww)
      case _ => throw new P4Error(s"table '$tableName'：表项 key 必须是常量")
    }
  }

  private def combineKeys(vals: Seq[(BigInt, Int)], widths: Seq[Int]): BigInt = {
    var acc = BigInt(0)
    vals.zip(widths).foreach { case ((v, w), expected) =>
      if (w > expected) throw new P4Error("表项 key 值超出字段宽度")
      acc = (acc << expected) | (v & ((BigInt(1) << expected) - 1))
    }
    acc
  }

  private def lowerEntry(e: TableEntry, resolver: WidthResolver, c: ControlDecl, prog: P4Program, externMap: Map[String, ExternInst]): Ir.Dag = {
    val actions = c.actions.map(a => a.name -> a).toMap
    val a = actions.getOrElse(e.action, throw new P4Error(s"静态融合表项：未知 action '${e.action}'"))
    if (e.args.length != a.params.length)
      throw new P4Error(s"action '${e.action}' 表项实参数量不匹配")
    val b = new Ir.Builder
    val lowering = new ExprLowering(resolver, b, externMap)
    val binds: Map[String, (NodeId, Int)] = a.params.zip(e.args).map { case (p, arg) =>
      val (id, w) = lowering.lower(arg, Some(p.width), Map.empty)
      p.name -> ((b.fit(id, w, p.width), p.width))
    }.toMap
    val outs = a.body.map {
      case asg: Assign => lowering.lowerAssign(asg.path, asg.expr, binds)
      case mc: MethodCall => lowering.lowerMethodCall(mc, binds)
      case s => throw new P4Error(s"行 ${s.line}：action 体中不支持的语句")
    }
    Passes.runAll(b.finish(outs))
  }

  // ---------------- parser 发射（M3） ----------------

  // E2 注：ParserDecl.stagesOpt（parser 上的编译指示）当前不生效——parser 是
  // FSM Module，不参与 Scheduler 切拍；指示仅在解析层记录并打日志，语义由
  // 未来"parser 流水化"设计承接。

  /** 一个 parser 状态的布局：本状态要 extract 的 header（含字节偏移）+ 转移语句。
    *
    * 可见性从 `private` 放宽到 `private[P4C]`（2026-09-16）：[[XlsBackend]] 需要
    * 复用同一套偏移计算，避免两份实现漂移。放宽可见性不改变任何行为，
    * Chisel 线不受影响。 */
  private[P4C] final case class StateLayout(
    extracts: Seq[(Seq[String], HeaderType, Int)], // 路径, header 类型, 字节偏移
    trans: TransStmt,
  )

  private[P4C] def layoutParser(p: ParserDecl, prog: P4Program): Map[String, StateLayout] = {
    val headerTypes = prog.headerTypes.map(ht => ht.name -> ht).toMap
    val states = p.states.map(s => s.name -> s).toMap
    val layouts = mutable.HashMap.empty[String, StateLayout]

    def headerSize(ht: HeaderType): Int = ht.fields.map(f => (f.width + 7) / 8).sum

    def dfs(name: String, byteOffset: Int): Unit = {
      if (name == "accept" || name == "reject") return
      val st = states.getOrElse(name, throw new P4Error(s"parser：未知状态 '$name'"))
      layouts.get(name) match {
        case Some(existing) =>
          val curOff = existing.extracts.headOption.map(_._3).getOrElse(byteOffset)
          if (curOff != byteOffset)
            throw new P4Error(s"parser：状态 '$name' 在不同路径下字节偏移不同（${curOff} vs $byteOffset），M3 子集要求固定偏移")
        case None =>
          var off = byteOffset
          val extracts = mutable.ArrayBuffer.empty[(Seq[String], HeaderType, Int)]
          st.stmts.foreach {
            case Extract(path, ln) =>
              if (path.length != 2) throw new P4Error(s"行 $ln：extract 路径必须是 param.instance")
              val inst = path(1)
              // 找到 out 参数 struct 中该实例的类型
              val member = p.params.collectFirst {
                case cp if cp.name == path.head && cp.direction == "out" => (cp, prog.structs.find(_.name == cp.typeName))
              }.flatMap { case (cp, so) => so.flatMap(_.members.find(_.name == inst)) }
                .getOrElse(throw new P4Error(s"行 $ln：extract 目标 '$path' 未找到"))
              if (member.isBits) throw new P4Error(s"行 $ln：extract 目标必须是 header 实例")
              val ht = headerTypes.getOrElse(member.typeName, throw new P4Error(s"未知 header 类型 '${member.typeName}'"))
              extracts += ((path, ht, off))
              off += headerSize(ht)
            case _: Goto | _: Select =>
            case s => throw new P4Error(s"行 ${s.line}：parser 状态中不支持的语句")
          }
          layouts(name) = StateLayout(extracts.toSeq, st.stmts.collectFirst { case t: TransStmt => t }
            .getOrElse(throw new P4Error(s"parser 状态 '$name' 缺少 transition")))
          // 递归后继
          layouts(name).trans match {
            case Goto(t, _) => dfs(t, off)
            case Select(_, cases, deft, _) =>
              cases.foreach { case (_, t) => dfs(t, off) }
              dfs(deft, off)
          }
      }
    }
    dfs("start", 0)
    layouts.toMap
  }

  private def emitParser(prefix: String, p: ParserDecl, prog: P4Program, tmap: String => String): Seq[String] = {
    val out = mutable.ArrayBuffer.empty[String]
    val layouts = layoutParser(p, prog)
    val className = pascal(prefix) + pascal(p.name) + "Parser"
    val windowBits = 512

    // out 参数 bundle
    val outParams = p.params.filter(_.direction == "out")
    val bundleDefs = mutable.ArrayBuffer.empty[String]
    outParams.foreach { op =>
      val st = prog.structs.find(_.name == op.typeName).getOrElse(throw new P4Error(s"parser out 参数 '${op.name}' 类型不是 struct"))
      val bn = s"${prefix}_out_${op.name}"
      bundleDefs += s"final class $bn extends Bundle {"
      st.members.foreach {
        case StructMember(_, true, w, n) => bundleDefs += s"${IND}val $n = UInt($w.W)"
        case StructMember(tn, false, _, n) =>
          bundleDefs += s"${IND}val ${n}Valid = Bool()"
          bundleDefs += s"${IND}val $n = new ${tmap(tn)}"
      }
      bundleDefs += "}"
      bundleDefs += ""
    }

    // 状态枚举（保持声明顺序 + accept/reject）
    val stateNames = p.states.map(_.name).filter(n => n != "accept" && n != "reject") ++ Seq("accept", "reject")
    val enumNames = stateNames.map(n => s"s_$n")

    out ++= bundleDefs
    out += s"final class $className extends Module {"
    out += s"${IND}val io = IO(new Bundle {"
    out += s"$IND$IND val in = Input(UInt($windowBits.W))"
    outParams.foreach(op => out += s"$IND$IND val ${op.name}Out = Output(new ${prefix}_out_${op.name})")
    out += s"$IND$IND val done = Output(Bool())"
    out += s"$IND$IND val error = Output(Bool())"
    out += s"$IND})"
    out += s"$IND val ${enumNames.mkString(" :: ")} :: Nil = Enum(${enumNames.size})"
    out += s"$IND val state = RegInit(s_start)"
    out += s"$IND val done = RegInit(false.B)"
    out += s"$IND val error = RegInit(false.B)"
    outParams.foreach(op => out += s"$IND val r_${op.name} = RegInit(0.U.asTypeOf(new ${prefix}_out_${op.name}))")
    outParams.foreach(op => out += s"$IND io.${op.name}Out := r_${op.name}")
    out += s"$IND io.done := done"
    out += s"$IND io.error := error"
    out += ""
    out += s"$IND switch (state) {"

    // 供 select 引用的字段发射：返回 wire 名
    def extractStatements(path: Seq[String], ht: HeaderType, byteOff: Int, indent: String, paramName: String): Seq[String] = {
      val inst = path(1)
      val hb = ht.fields.map(_.width).sum
      if (hb % 8 != 0) throw new P4Error(s"header '${ht.name}' 总宽度 $hb bit 非字节对齐（M3 子集限制）")
      val shift = windowBits - 8 * byteOff - hb
      if (shift < 0) throw new P4Error(s"header '${ht.name}' 在偏移 $byteOff 超出 $windowBits-bit 报文窗口")
      val ls = mutable.ArrayBuffer.empty[String]
      ls += s"$indent val w_$inst = (io.in >> ${shift}.U)(${hb - 1}, 0)"
      var p = 0
      ht.fields.foreach { f =>
        ls += s"$indent r_$paramName.$inst.${f.name} := w_$inst(${hb - 1 - p}, ${hb - p - f.width})"
        p += f.width
      }
      ls += s"$indent r_$paramName.${inst}Valid := true.B"
      ls.toSeq
    }

    layouts.toSeq.sortBy { case (n, _) => stateNames.indexOf(n) }.foreach { case (name, layout) =>
      out += s"$IND$IND is (s_$name) {"
      layout.extracts.foreach { case (path, ht, off) =>
        out ++= extractStatements(path, ht, off, s"$IND$IND$IND", path.head)
      }
      layout.trans match {
        case Goto(t, _) =>
          if (t == "accept") out += s"$IND$IND$IND state := s_accept"
          else if (t == "reject") out += s"$IND$IND$IND state := s_reject"
          else out += s"$IND$IND$IND state := s_$t"
        case Select(value, cases, deft, _) =>
          // select 字段必须是本状态刚提取的字段
          val path = value match {
            case Name(pp, _) => pp
            case _ => throw new P4Error("M3 select 值必须是字段路径")
          }
          val ext = layout.extracts.find { e =>
            val (ep, eht2, _) = e
            ep.head == path.head && ep(1) == path(1) && eht2.fields.exists(_.name == path.lift(2).getOrElse(""))
          }.getOrElse(
            throw new P4Error(s"select 字段 '${path.mkString(".")}' 未在本状态提取"))
          val (_, eht, _) = ext
          val inst = path(1)
          val fname = path(2)
          val f = eht.fields.find(_.name == fname).getOrElse(throw new P4Error(s"header '${eht.name}' 无字段 '$fname'"))
          val hb = eht.fields.map(_.width).sum
          val p0 = eht.fields.takeWhile(_.name != fname).map(_.width).sum
          val w = f.width
          out += s"$IND$IND$IND val sel = w_$inst(${hb - 1 - p0}, ${hb - p0 - w})"
          val defTgt = if (deft == "accept") "s_accept" else if (deft == "reject") "s_reject" else s"s_$deft"
          // when/elsewhen/otherwise 链（switch 的 default 在 chisel3 3.6 不可用）
          cases.zipWithIndex.foreach { case ((ce, target), i) =>
            val (cv, cw) = constSelValue(ce, w, prog, p)
            val tgt = if (target == "accept") "s_accept" else if (target == "reject") "s_reject" else s"s_$target"
            val cond = s"sel === 0x${cv.toString(16)}.U($cw.W)"
            if (i == 0) out += s"$IND$IND$IND when ($cond) { state := $tgt }"
            else out += s"$IND$IND$IND .elsewhen ($cond) { state := $tgt }"
          }
          out += s"$IND$IND$IND .otherwise { state := $defTgt }"
      }
      out += s"$IND$IND }"
    }
    out += s"$IND$IND is (s_accept) { done := true.B }"
    out += s"$IND$IND is (s_reject) { error := true.B; done := true.B }"
    out += s"$IND }"
    out += "}"
    out.toSeq
  }

  private def constSelValue(e: Expr, w: Int, prog: P4Program, p: ParserDecl): (BigInt, Int) = {
    val resolver = new WidthResolver(
      prog.headerTypes.map(ht => ht.name -> ht).toMap,
      prog.structs.map(st => st.name -> st).toMap,
      p.params,
    )
    val b = new Ir.Builder
    val lowering = new ExprLowering(resolver, b)
    val (id, lw) = lowering.lower(e, Some(w), Map.empty)
    val dag = Passes.runAll(b.finish(Seq(OutputWrite(Seq("__s"), b.fit(id, lw, w), w))))
    dag.nodes(dag.outputs.head.asInstanceOf[OutputWrite].value) match {
      case Const(v, ww) => (v, ww)
      case _ => throw new P4Error("select 分支值必须是常量")
    }
  }

  // ---------------- 程序发射 ----------------

  /** 仅类型 Bundle（CLI 单文件模式用，原名发射）。 */
  def emitTypes(prog: P4Program): String = {
    val out = mutable.ArrayBuffer.empty[String]
    out += "// Generated by P4C (P4 → Chisel). DO NOT EDIT."
    out += "package p4cgen"
    out += ""
    out += "import chisel3._"
    out += "import chisel3.util._"
    out += ""
    out ++= emitBundles(prog, n => n)
    out.mkString("\n")
  }

  /** 仅模块（自带带前缀的 Bundle 定义）。
    * @return (生成源码, 各 control 的实际切分级数，仅 stages>1 时有意义) */
  def emitModules(
    prog: P4Program, moduleNamePrefix: String, sourceName: String, stages: Int = 1,
    sigs: Option[mutable.ArrayBuffer[Signature.ControlSig]] = None,
    clock: Option[Int] = None, model: DelayModel = DelayModels.default,
    irDump: Option[(String, Ir.Dag) => Unit] = None,
  ): (String, Map[String, Int]) = {
    if (stages < 1) throw new P4Error(s"拍数预算 N 必须 ≥ 1（got $stages）")
    val tmap0 = typeMapOf(prog, moduleNamePrefix)
    val tmap: String => String = n => tmap0.getOrElse(n, n)
    val out = mutable.ArrayBuffer.empty[String]
    out += s"// Generated by P4C (P4 → Chisel) from $sourceName. DO NOT EDIT."
    if (stages > 1) {
      out += s"// 时序契约（切拍 N=$stages）：io.valid 为单拍脉冲，每个脉冲发起一次调用；流水 N 级，"
      out += "// 输出/状态更新在末级 stageValid（io.outValid）拍各提交一次；"
      out += "// 相邻脉冲间隔（initiation interval）必须 ≥ N——Top 的一次性 fire 天然满足，"
      out += "// 独立例化 control 模块的下游需自行保证；表匹配的 key/hit 在末级被组合引用，"
      out += "// 调用期间（脉冲后 N-1 拍内）输入字段需保持稳定（Top 场景由 parser 输出寄存保证）。"
    }
    out ++= runtimeTableHeaderComment(prog)
    out += "package p4cgen"
    out += ""
    out += "import chisel3._"
    out += "import chisel3.util._"
    out += ""
    out ++= emitBundles(prog, tmap)
    val stageCounts = mutable.LinkedHashMap.empty[String, Int]
    prog.controls.foreach { c =>
      val (lines, n) = emitControl(moduleNamePrefix, c, prog, tmap, stages, sigs, clock, model, irDump)
      out ++= lines; out += ""
      stageCounts(c.name) = n
    }
    prog.parsers.foreach { p => out ++= emitParser(moduleNamePrefix, p, prog, tmap); out += "" }
    // M5：parser + control 同时存在时，发射管线 Top
    if (prog.parsers.nonEmpty && prog.controls.nonEmpty) {
      out ++= emitTop(moduleNamePrefix, prog.parsers.head, prog.controls.head, prog, tmap, stages, clock)
      out += ""
    }
    (out.mkString("\n"), stageCounts.toMap)
  }

  /** M5：管线 Top —— parser 解析后触发 control（一次性），输出锁存。
    * 切拍（stages>1）：control 的 valid 由 fire 起保持到 outValid（n 级流水全程），
    * 满足发起间隔 ≥ N 契约；io.outValid 透传 control 的末级 stageValid。 */
  private def emitTop(
    prefix: String, p: ParserDecl, c: ControlDecl, prog: P4Program, tmap: String => String,
    globalStages: Int = 1, clock: Option[Int] = None,
  ): Seq[String] = {
    // E2：Top 的流水契约跟随 control 的生效预算（声明级指示优先）
    val stages = c.stagesOpt.getOrElse(globalStages)
    // X2：clock 模式下 control 恒发 valid/outValid 端口（保守超集），Top 稳定接线
    val clockMode = clock.isDefined && c.stagesOpt.isEmpty
    val out = mutable.ArrayBuffer.empty[String]
    val parserCls = pascal(prefix) + pascal(p.name) + "Parser"
    val controlCls = pascal(prefix) + pascal(c.name)
    val topCls = pascal(prefix) + "Top"
    val windowBits = 512

    // control 的第一个 inout/in 参数接 parser 输出（类型需匹配），其余参数清零
    val hdrParam = c.params.find(_.direction == "inout").orElse(c.params.find(_.direction == "in"))
      .getOrElse(throw new P4Error("Top 组装：control 无 inout/in 参数可接 parser 输出"))
    val parserOut = p.params.find(_.direction == "out")
      .getOrElse(throw new P4Error("Top 组装：parser 无 out 参数"))
    if (hdrParam.typeName != parserOut.typeName)
      throw new P4Error(s"Top 组装：parser 输出类型 '${parserOut.typeName}' 与 control 参数 '${hdrParam.typeName}' 不匹配")
    val zeroParams = c.params.filter(x => x != hdrParam && (x.direction == "inout" || x.direction == "in"))
    // parser 输出 struct 中的 header 成员（valid 位随解析置位）
    val hdrMembers = prog.structs.find(_.name == parserOut.typeName)
      .map(_.members.filterNot(_.isBits)).getOrElse(Seq.empty)

    out += s"final class $topCls extends Module {"
    out += s"${IND}val io = IO(new Bundle {"
    out += s"$IND$IND val in = Input(UInt($windowBits.W))"
    out += s"$IND$IND val outValid = Output(Bool())"
    out += s"$IND$IND val error = Output(Bool())"
    hdrMembers.foreach { m => out += s"$IND$IND val ${m.name}Valid = Output(Bool())" }
    c.externs.foreach { e => out += s"$IND$IND val ex_${e.name} = Output(Vec(${e.size}, UInt(${e.width}.W)))" }
    c.params.foreach {
      case ControlParam(n, "inout", t, _) => out += s"$IND$IND val ${n}Out = Output(new ${tmap(t)})"
      case ControlParam(n, "out", t, _) => out += s"$IND$IND val ${n}Out = Output(new ${tmap(t)})"
      case _ =>
    }
    // 运行时表写端口透出（D5：无运行时表时零新增端口）
    c.tables.filter(_.isRuntime).foreach { t =>
      val lay = runtimeLayout(t, c, tableKeyWidths(t, resolverFor(prog, c)).sum)
      out += s"$IND$IND val tbl_${t.name}_we = Input(Bool())"
      out += s"$IND$IND val tbl_${t.name}_waddr = Input(UInt(${lay.addrW}.W))"
      out += s"$IND$IND val tbl_${t.name}_wdata = Input(UInt(${lay.entryW}.W))"
    }
    out += s"$IND})"
    out += ""
    out += s"$IND val parser = Module(new $parserCls)"
    out += s"$IND val ingress = Module(new $controlCls)"
    out += ""
    out += s"$IND parser.io.in := io.in"
    out += s"$IND ingress.io.${hdrParam.name}In := parser.io.${parserOut.name}Out"
    zeroParams.foreach { zp =>
      out += s"$IND ingress.io.${zp.name}In := 0.U.asTypeOf(new ${tmap(zp.typeName)})"
    }
    hdrMembers.foreach { m =>
      out += s"$IND io.${m.name}Valid := parser.io.${parserOut.name}Out.${m.name}Valid"
    }
    c.externs.foreach { e =>
      out += s"$IND io.ex_${e.name} := ingress.io.ex_${e.name}"
    }
    c.tables.filter(_.isRuntime).foreach { t =>
      out += s"$IND ingress.io.tbl_${t.name}_we := io.tbl_${t.name}_we"
      out += s"$IND ingress.io.tbl_${t.name}_waddr := io.tbl_${t.name}_waddr"
      out += s"$IND ingress.io.tbl_${t.name}_wdata := io.tbl_${t.name}_wdata"
    }
    // control 只在解析完成后执行一次
    out += s"$IND val fired = RegInit(false.B)"
    out += s"$IND val fire = parser.io.done && !parser.io.error && !fired"
    if (stages > 1 || clockMode) {
      // 切拍：fire 为单拍脉冲，作为流水第 0 级 valid（sV 链为延迟线，脉冲逐级传播，
      // 末级在 fire 后第 n-1 拍出现单拍 outValid 与一次状态提交）
      out += s"$IND ingress.io.valid := fire"
    } else {
      if (c.externs.nonEmpty) out += s"$IND ingress.io.valid := fire"
    }
    out += s"$IND when (fire) { fired := true.B }"
    out += ""
    if (stages > 1 || clockMode) out += s"$IND io.outValid := ingress.io.outValid"
    else out += s"$IND io.outValid := RegNext(fire)"
    out += s"$IND io.error := parser.io.error"
    c.params.foreach { cp =>
      if (cp.direction == "inout" || cp.direction == "out")
        out += s"$IND io.${cp.name}Out := ingress.io.${cp.name}Out"
    }
    out += "}"
    out.toSeq
  }

  /** 单文件发射（CLI 模式：类型 + 模块合一）。 */
  def emitProgram(
    prog: P4Program, moduleNamePrefix: String, sourceName: String, stages: Int = 1,
    sigs: Option[mutable.ArrayBuffer[Signature.ControlSig]] = None,
    clock: Option[Int] = None, model: DelayModel = DelayModels.default,
    irDump: Option[(String, Ir.Dag) => Unit] = None,
  ): String = {
    val (modules, _) = emitModules(prog, moduleNamePrefix, sourceName, stages, sigs, clock, model, irDump)
    emitTypes(prog) + "\n" + modules
  }

  /** 每个模块文件自带带前缀的 Bundle，避免多文件间类名冲突。 */
  def typeMapOf(prog: P4Program, prefix: String): Map[String, String] =
    (prog.headerTypes.map(ht => ht.name -> s"${pascal(prefix)}_${ht.name}") ++
      prog.structs.map(st => st.name -> s"${pascal(prefix)}_${st.name}")).toMap
}
