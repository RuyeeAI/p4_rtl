package P4C

import scala.collection.mutable

/** X11：SMT-LIB2 发射（对标 XLS 的 SMT-LIB 通道 / yosys 生态 z3 消费方）。
  *
  * 两个 [[Ir.Dag]] → 一段 SMT-LIB2 脚本：输入为 `(declare-const …)`、extern 为
  * `(declare-fun … () (Array …))`（`select` 天然建模 RegRead 的旧值读）、各节点
  * `define-fun`；断言"两版 Sink 不全相等"后 `check-sat` —— **unsat = 等价**。
  *
  * 语义映射注意（与 [[Interp.evalOp]] 逐条对齐）：
  *   - Shl/Shr 的移位量零扩到操作数宽（SMT-LIB bvshl/bvlshr 要求同 sort；
  *     移位 ≥ 位宽时结果 0，与 P4C 截断语义一致）；
  *   - Add/Sub → bvadd/bvsub（同宽即模 2^w）；比较 → ite(bvult/…) 产节点宽度
  *     的 0/1 位串（IR 比较节点宽度 = max 操作数宽，与 Interp.evalOp 口径一致）；
  *   - Mux 条件取 LSB 且显式转 Bool（`(= ((_ extract 0 0) c) (_ bv1 1))`——
  *     SMT-LIB ite 要求 Bool 条件，extract 产 BitVec 1 直接嵌套会 sort mismatch）；
  *   - Cat 首 part 为高位（SMT concat 同构）；
  *   - Trunc → ((_ extract w-1  0) x)。
  *
  * 已知保守性：RegRead 地址 ≥ size 时 Interp 读 0，SMT 读未约束值——两 DAG 读同一
  * 数组（声明共享），等价性证明在该语义下仍封闭（仅比 Interp 零填充语义更强）。
  */
object Smt {

  private def sanitize(s: String): String =
    s.map(c => if (c.isLetterOrDigit || c == '_') c else '_').mkString

  /** 单个 DAG 的命名上下文（节点名 / 输入 / 寄存器数组）。 */
  private final case class Ctx(
    prefix: String, // "a" / "b"
    dag: Ir.Dag,
    names: mutable.HashMap[Ir.NodeId, String], // 可达节点 → SMT 符号名
    inputs: mutable.LinkedHashMap[(Seq[String], Int), String], // (path, w) → 声明名
    regs: mutable.LinkedHashMap[(String, Int, Int, Int), String], // (inst, w, size, idxW) → 数组名
  )

  /** outputs 可达节点集合（与 dce/IrText 同口径——死代码不进 SMT 脚本）。 */
  private def reachable(dag: Ir.Dag): mutable.BitSet = {
    val reach = mutable.BitSet.empty
    def visit(id: Ir.NodeId): Unit =
      if (!reach(id)) {
        reach(id) = true
        Ir.operands(dag.nodes(id)).foreach(visit)
      }
    dag.outputs.foreach(s => Ir.visitSink(s, visit))
    reach
  }

  /** 建立命名上下文：可达的 InputRef/RegRead 登记声明名（LinkedHashMap 保首个出现序，
    * 脚本声明段确定性）；可达节点统一命名 `<prefix>_<NodeId>`——两侧 DAG（原版/变体）
    * 结构不同，按 id 命名即可保证不冲突。 */
  private def mkCtx(prefix: String, dag: Ir.Dag): Ctx = {
    val c = Ctx(prefix, dag, mutable.HashMap.empty,
      mutable.LinkedHashMap.empty, mutable.LinkedHashMap.empty)
    val reach = reachable(dag)
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) dag.nodes(id) match {
        case Ir.InputRef(path, w) =>
          val key = (path, w)
          if (!c.inputs.contains(key)) c.inputs(key) = s"in_${sanitize(path.mkString("_"))}_$w"
        case Ir.RegRead(inst, idx, w, size) =>
          val key = (inst, w, size, dag.nodes(idx).width)
          if (!c.regs.contains(key)) c.regs(key) = s"reg_${sanitize(inst)}_${w}_${size}"
        case _ =>
      }
    }
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) c.names(id) = s"${prefix}_$id"
    }
    c
  }

  /** 节点 → SMT 表达式（节点名）。要求操作数先定义（按 NodeId 升序发射即满足）。 */
  private def exprOf(c: Ctx, id: Ir.NodeId): String = c.dag.nodes(id) match {
    case Ir.Const(v, w) => s"(_ bv$v $w)"
    case Ir.InputRef(path, w) => c.inputs((path, w))
    case Ir.Zext(s, w) =>
      val sw = c.dag.nodes(s).width
      s"((_ zero_extend ${w - sw}) ${exprOf(c, s)})"
    case Ir.Trunc(s, w) => s"((_ extract ${w - 1} 0) ${exprOf(c, s)})"
    case Ir.Slice(s, hi, lo) => s"((_ extract $hi $lo) ${exprOf(c, s)})"
    case Ir.Cat(parts, _) => s"(concat ${parts.map(p => exprOf(c, p)).mkString(" ")})"
    case Ir.Not(s, _) => s"(bvnot ${exprOf(c, s)})"
    // Mux：条件取 LSB 且显式转 Bool（SMT-LIB ite 要求 Bool 条件，extract 产 BitVec 1）
    case Ir.Mux(cond, t, f, _) =>
      s"(ite (= ((_ extract 0 0) ${exprOf(c, cond)}) (_ bv1 1)) ${exprOf(c, t)} ${exprOf(c, f)})"
    case Ir.Bin(op, l, r, w) =>
      val le = exprOf(c, l)
      val re = exprOf(c, r)
      val rw = c.dag.nodes(r).width
      // 移位量与操作数同宽（bvshl/bvlshr 要求同 sort；窄移位量零扩——值语义不变）
      val rex = if (rw == w) re else s"((_ zero_extend ${w - rw}) $re)"
      op match {
        case Ir.Add => s"(bvadd $le $rex)"
        case Ir.Sub => s"(bvsub $le $rex)"
        case Ir.And => s"(bvand $le $rex)"
        case Ir.Or => s"(bvor $le $rex)"
        case Ir.Xor => s"(bvxor $le $rex)"
        case Ir.Shl => s"(bvshl $le $rex)"
        case Ir.Shr => s"(bvlshr $le $rex)"
        // 比较：值 0/1，但节点宽度为 w（Builder.bin 的比较宽度 = max 操作数宽；
        // Interp.evalOp 同口径——宽度 w 的 0/1 位串），常量按节点宽发射避免 sort mismatch
        case Ir.Eq => s"(ite (= $le $rex) (_ bv1 $w) (_ bv0 $w))"
        case Ir.Neq => s"(ite (distinct $le $rex) (_ bv1 $w) (_ bv0 $w))"
        case Ir.Lt => s"(ite (bvult $le $rex) (_ bv1 $w) (_ bv0 $w))"
        case Ir.Le => s"(ite (bvule $le $rex) (_ bv1 $w) (_ bv0 $w))"
        case Ir.Gt => s"(ite (bvugt $le $rex) (_ bv1 $w) (_ bv0 $w))"
        case Ir.Ge => s"(ite (bvuge $le $rex) (_ bv1 $w) (_ bv0 $w))"
      }
    case rr: Ir.RegRead => s"(select ${c.regs((rr.inst, rr.width, rr.size, c.dag.nodes(rr.index).width))} ${exprOf(c, rr.index)})"
  }

  /** 无符号零扩到目标宽（等值比较前对齐宽度用）。 */
  private def zextTo(c: Ctx, id: Ir.NodeId, w: Int): String = {
    val sw = c.dag.nodes(id).width
    val e = exprOf(c, id)
    if (sw >= w) e else s"((_ zero_extend ${w - sw}) $e)"
  }

  /** Sink 语义键（out:路径 / reg:实例 / cnt:实例，含同键序号消歧）。 */
  private def sinkKeys(dag: Ir.Dag): Seq[String] = {
    val seen = mutable.HashMap.empty[String, Int]
    dag.outputs.map {
      case o: Ir.OutputWrite => "out:" + o.path.mkString(".")
      case r: Ir.RegWrite => "reg:" + r.inst
      case c: Ir.CounterAdd => "cnt:" + c.inst
    }.map { k =>
      val n = seen.getOrElse(k, 0); seen(k) = n + 1
      if (n == 0) k else s"$k#$n"
    }
  }

  /** 生成 a/b 等价性检查脚本。Sink 序列的语义键必须一致（否则抛 [[P4Error]]）。 */
  def emit(a: Ir.Dag, b: Ir.Dag): String = {
    val ka = sinkKeys(a)
    val kb = sinkKeys(b)
    if (ka != kb)
      throw new P4Error(s"Smt.emit：两 DAG 的 Sink 不匹配（${ka.mkString(",")} vs ${kb.mkString(",")}）")

    val ca = mkCtx("a", a)
    val cb = mkCtx("b", b)
    val out = mutable.ArrayBuffer.empty[String]
    out += "(set-logic QF_ABV)"

    // 输入声明（两 DAG 共享；同路径不同宽各自声明）
    (ca.inputs ++ cb.inputs).foreach { case ((path, w), name) =>
      out += s"; input ${path.mkString(".")} : bits[$w]"
      out += s"(declare-const $name (_ BitVec $w))"
    }
    (ca.regs ++ cb.regs).foreach { case ((inst, w, size, idxW), name) =>
      out += s"; reg $inst : bits[$w][$size] (index width $idxW)"
      out += s"(declare-fun $name () (Array (_ BitVec $idxW) (_ BitVec $w)))"
    }

    // 节点定义（NodeId 升序 = 拓扑序，先定义后引用）
    def defs(c: Ctx): Unit = {
      val reach = reachable(c.dag)
      (0 until c.dag.nodes.length).foreach { id =>
        if (reach(id)) {
          val w = c.dag.nodes(id).width
          out += s"(define-fun ${c.names(id)} () (_ BitVec $w) ${exprOf(c, id)})"
        }
      }
    }
    defs(ca)
    defs(cb)

    // Sink 相等项（宽度不一致时零扩对齐——无符号保值）
    val terms = mutable.ArrayBuffer.empty[String]
    (a.outputs, b.outputs).zipped.foreach {
      case (oa: Ir.OutputWrite, ob: Ir.OutputWrite) =>
        val w = math.max(oa.width, ob.width)
        terms += s"(= ${zextTo(ca, oa.value, w)} ${zextTo(cb, ob.value, w)})"
      case (ra: Ir.RegWrite, rb: Ir.RegWrite) =>
        val wi = math.max(a.nodes(ra.index).width, b.nodes(rb.index).width)
        val wv = math.max(ra.width, rb.width)
        terms += s"(= ${zextTo(ca, ra.index, wi)} ${zextTo(cb, rb.index, wi)})"
        terms += s"(= ${zextTo(ca, ra.value, wv)} ${zextTo(cb, rb.value, wv)})"
      case (xa: Ir.CounterAdd, xb: Ir.CounterAdd) =>
        val wi = math.max(a.nodes(xa.index).width, b.nodes(xb.index).width)
        val wd = math.max(xa.width, xb.width)
        terms += s"(= ${zextTo(ca, xa.index, wi)} ${zextTo(cb, xb.index, wi)})"
        terms += s"(= ${zextTo(ca, xa.delta, wd)} ${zextTo(cb, xb.delta, wd)})"
      case (x, y) => throw new P4Error(s"Smt.emit：Sink 种类不匹配（$x vs $y）")
    }
    out += (if (terms.isEmpty) "(assert false)" // 两版均无 Sink：恒等价 → unsat
    else s"(assert (not (and ${terms.mkString(" ")})))")
    out += "(check-sat)"
    out.mkString("\n") + "\n"
  }
}

/** z3 子进程运行器（stdin 喂脚本，stdout 收结果）。 */
object Z3 {
  lazy val available: Boolean = try {
    val p = new java.lang.ProcessBuilder("z3", "--version").start()
    p.waitFor() == 0
  } catch { case _: java.io.IOException => false }

  /** 运行 SMT-LIB2 脚本，返回 stdout（首行通常为 unsat/sat/unknown）。 */
  def run(script: String, timeoutSec: Int = 60): String = {
    val p = new java.lang.ProcessBuilder("z3", s"-T:$timeoutSec", "-in").start()
    // 写线程：避免大脚本塞满管道缓冲导致死锁
    val writer = new Thread(() => {
      val os = p.getOutputStream
      os.write(script.getBytes)
      os.flush()
      os.close()
    })
    writer.setDaemon(true)
    writer.start()
    val outText = scala.io.Source.fromInputStream(p.getInputStream)("UTF-8").mkString
    val errText = scala.io.Source.fromInputStream(p.getErrorStream)("UTF-8").mkString
    p.waitFor()
    if (p.exitValue() != 0 && outText.isEmpty)
      throw new P4Error(s"z3 运行失败（exit=${p.exitValue()}）：$errText")
    outText
  }
}

/** X11：形式等价检查（对标 XLS check_ir_equivalence）。
  *
  * 三级策略（按证明强度递减）：
  *   1. **穷尽**：两侧无 RegRead 且输入位宽合计 ≤ 16 → Interp 全枚举（完备，可给反例）；
  *   2. **SMT**：z3 可用 → [[Smt.emit]] 脚本，unsat = 等价（完备）；
  *   3. **随机采样**：固定 seed 200 组输入（不完备，结果 Unknown）。
  */
object Equivalence {

  sealed trait Verdict
  final case class Equivalent(how: String) extends Verdict
  final case class NotEquivalent(how: String, inputs: Option[Map[Seq[String], BigInt]]) extends Verdict
  final case class Unknown(reason: String) extends Verdict

  private val ExhaustiveBitsLimit = 16
  private val RandomRounds = 200

  private def hasRegRead(dag: Ir.Dag): Boolean = dag.nodes.exists(_.isInstanceOf[Ir.RegRead])

  private def distinctInputs(dags: Ir.Dag*): Seq[(Seq[String], Int)] = {
    val m = mutable.LinkedHashMap.empty[(Seq[String], Int), Int]
    dags.foreach { dag =>
      val reach = {
        val r = mutable.BitSet.empty
        def visit(id: Ir.NodeId): Unit =
          if (!r(id)) { r(id) = true; Ir.operands(dag.nodes(id)).foreach(visit) }
        dag.outputs.foreach(s => Ir.visitSink(s, visit))
        r
      }
      (0 until dag.nodes.length).foreach { id =>
        if (reach(id)) dag.nodes(id) match {
          case Ir.InputRef(path, w) => m((path, w)) = 0
          case _ =>
        }
      }
    }
    m.keys.toSeq
  }

  /** 两个求值结果的 Sink 级一致性比对（键不同直接 NotEquivalent）。 */
  private def sameResults(ra: Interp.Result, rb: Interp.Result): Boolean =
    ra.outputs == rb.outputs && ra.regWrites == rb.regWrites && ra.counterAdds == rb.counterAdds

  def check(a: Ir.Dag, b: Ir.Dag): Verdict = {
    if (sinkKeysOf(a) != sinkKeysOf(b))
      return NotEquivalent("Sink 序列不匹配", None)

    val inputs = distinctInputs(a, b)
    if (!hasRegRead(a) && !hasRegRead(b) && inputs.map(_._2).sum <= ExhaustiveBitsLimit) {
      // 1. 穷尽
      val total = inputs.map(_._2).sum
      var v = BigInt(0)
      while (v < (BigInt(1) << total)) {
        val env = Interp.Env(inputs = buildEnv(inputs, v))
        val (ra, rb) = (Interp.eval(a, env), Interp.eval(b, env))
        if (!sameResults(ra, rb))
          return NotEquivalent("穷尽反例", Some(env.inputs))
        v += 1
      }
      Equivalent(s"穷尽（$total 位输入，${BigInt(1) << total} 组）")
    } else if (Z3.available) {
      // 2. SMT
      val out = try Z3.run(Smt.emit(a, b)) catch { case e: P4Error => return Unknown(s"z3 运行失败：${e.getMessage}") }
      out.trim.split("\n").headOption.getOrElse("") match {
        case "unsat" => Equivalent("z3 SMT 证明（unsat）")
        case "sat" => NotEquivalent("z3 判定可满足（存在使两版 Sink 不等的输入）", None)
        case other => Unknown(s"z3 返回 '$other'")
      }
    } else {
      // 3. 随机采样兜底
      val rng = new java.util.Random(20260908L)
      var r = 0
      while (r < RandomRounds) {
        val env = Interp.Env(inputs = inputs.map { case (p, w) =>
          p -> BigInt(w, rng)
        }.toMap)
        if (!sameResults(Interp.eval(a, env), Interp.eval(b, env)))
          return NotEquivalent("随机采样反例", Some(env.inputs))
        r += 1
      }
      Unknown("z3 不可用，随机采样未发现差异（无证明）")
    }
  }

  private def sinkKeysOf(dag: Ir.Dag): Seq[String] = {
    val seen = mutable.HashMap.empty[String, Int]
    dag.outputs.map {
      case o: Ir.OutputWrite => "out:" + o.path.mkString(".")
      case r: Ir.RegWrite => "reg:" + r.inst
      case c: Ir.CounterAdd => "cnt:" + c.inst
    }.map { k =>
      val n = seen.getOrElse(k, 0); seen(k) = n + 1
      if (n == 0) k else s"$k#$n"
    }
  }

  /** 组合计数器 → 输入环境（第 i 项占 bits [off+w-1 : off]）。 */
  private def buildEnv(inputs: Seq[(Seq[String], Int)], v: BigInt): Map[Seq[String], BigInt] = {
    val m = mutable.HashMap.empty[Seq[String], BigInt]
    var rest = v
    inputs.foreach { case (p, w) =>
      val mask = (BigInt(1) << w) - 1
      m(p) = rest & mask
      rest >>= w
    }
    m.toMap
  }
}
