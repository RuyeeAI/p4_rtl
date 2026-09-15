package P4C

import scala.collection.mutable

/** X12：IR 工具链（对标 XLS ir_stats_main / delay_info_main / benchmark_main 的核心输出
  * 与 ir_minimizer_main 的外部谓词模式）。 */
object IrStats {

  /** 一个 DAG 的统计报告。 */
  final case class Report(
    ctx: String,
    nodes: Int, // 可达节点数
    byOp: Map[String, Int], // 按算子分列（Bin 附运算符）
    critPath: Double, // 最大到达延迟（ND2 口径，未调度 DAG = 全组合延迟）
    stageCount: Int,
    stageDelays: Seq[Double], // 各级组合延迟（未调度 = 单元素 Seq(critPath)）
    regs: Int, // 边界寄存器数（X10 口径；未调度 = 0）
  )

  /** BOM 条目：(op, width) → 数量（对标 XLS print_bom）。 */
  final case class BomEntry(op: String, width: Int, count: Int)

  /** 可达节点集合（dce 同口径）。 */
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

  def report(dag: Ir.Dag, ctx: String = "", model: DelayModel = DelayModels.default): Report = {
    val reach = reachable(dag)
    val byOp = mutable.SortedMap.empty[String, Int]
    reach.foreach { id =>
      val k = Signature.opOf(dag.nodes(id))
      byOp(k) = byOp.getOrElse(k, 0) + 1
    }
    Report(
      ctx = ctx,
      nodes = reach.size,
      byOp = byOp.toMap,
      critPath = Scheduler.criticalPath(dag, model),
      stageCount = dag.stageCount,
      stageDelays = Scheduler.stageDelays(dag, model),
      regs = Scheduler.boundaryRegisters(dag),
    )
  }

  /** 按 (op, width) 聚合的元件清单（仅可达节点；确定性排序）。 */
  def bom(dag: Ir.Dag): Seq[BomEntry] = {
    val reach = reachable(dag)
    val m = mutable.HashMap.empty[(String, Int), Int]
    reach.foreach { id =>
      val n = dag.nodes(id)
      val k = (Signature.opOf(n), n.width)
      m(k) = m.getOrElse(k, 0) + 1
    }
    m.toSeq.map { case ((op, w), c) => BomEntry(op, w, c) }.sortBy(e => (e.op, e.width))
  }

  /** 人可读文本（对标 delay_info_main 的关键输出）。 */
  def format(r: Report): String = {
    val b = new StringBuilder
    b ++= s"[ir-stats] ctx=${r.ctx}\n"
    b ++= s"[ir-stats] nodes=${r.nodes} (${r.byOp.toSeq.map { case (k, v) => s"$k=$v" }.mkString(", ")})\n"
    b ++= s"[ir-stats] critPath=${r.critPath} ND2, stages=${r.stageCount}, regs=${r.regs}\n"
    b ++= s"[ir-stats] stageDelays=${r.stageDelays.mkString(", ")}\n"
    b.toString
  }
}

/** X12：IR 最小化（对标 XLS ir_minimizer_main 的模式 3——外部谓词）。
  *
  * Delta 调试：逐个尝试把节点替换为**同宽零常量**（Const(0, w)），保留使谓词
  * （"仍复现失败"）为真的替换，至不动点。叶子（Const/InputRef）不替换；
  * 替换只改节点内容不改编号，操作数引用始终有效。
  */
object IrMinimizer {

  /** @param predicate 复现谓词：初始 DAG 必须为真（否则抛 [[P4Error]]） */
  def minimize(dag: Ir.Dag)(predicate: Ir.Dag => Boolean): Ir.Dag = {
    require(predicate(dag), "IrMinimizer：初始 DAG 必须满足 predicate")
    var cur = dag
    var changed = true
    var pass = 0
    while (changed && pass < 32) { // 替换只会增多 Const ⇒ 收敛；上限防御
      changed = false
      pass += 1
      (cur.nodes.length - 1 to 0 by -1).foreach { id =>
        cur.nodes(id) match {
          case _: Ir.Const | _: Ir.InputRef => // 叶子替换无意义
          case n =>
            val candidate = cur.copy(nodes = cur.nodes.updated(id, Ir.Const(0, n.width)))
            if (predicate(candidate)) {
              cur = candidate
              changed = true
            }
        }
      }
    }
    cur
  }
}
