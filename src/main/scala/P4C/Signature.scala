package P4C

import P4C.Ast._
import P4C.Ir._

/** 模块签名与调度信息的机器可读导出（对标 XLS 的 signature/schedule textproto）。
  *
  * 用途：上位机/集成方按已知端口编程；回归与调试工具消费"节点 → 流水级"映射。
  * 端口按 Bundle 展平为点分路径叶子（`hdrIn.ethernet.etherType`），向量端口
  * （ex_*、tbl_*_wdata 等）以 `vecSize` 标注元素个数。
  *
  * JSON 序列化手写实现（无新增依赖；字段名均为标识符，转义仅防御性覆盖）。
  */
object Signature {

  final case class PortSig(path: String, dir: String, width: Int, vecSize: Int = 0)
  final case class TableSig(
    name: String, runtime: Boolean, size: Int,
    keyBits: Int = 0, actW: Int = 0, argW: Int = 0, entryW: Int = 0, addrW: Int = 0,
  )
  final case class ExternSig(name: String, kind: String, width: Int, size: Int)
  final case class NodeSig(id: Int, op: String, width: Int, stage: Int)
  /** X13 增补（追加在既有字段之后）：critPath = 最大到达延迟（ND2）、regs = 边界
    * 寄存器数（X10 口径）、bom = 按算子分组的可达节点计数。 */
  final case class DagSig(
    ctx: String, stageCount: Int, nodes: Seq[NodeSig],
    critPath: Double = 0.0, regs: Int = 0, bom: Map[String, Int] = Map.empty,
  )
  final case class ControlSig(
    module: String, ports: Seq[PortSig], tables: Seq[TableSig], externs: Seq[ExternSig], dags: Seq[DagSig],
  )

  /** 节点算子名（Bin 附运算符）。 */
  def opOf(n: Ir.Node): String = n match {
    case Ir.Bin(op, _, _, _) => s"Bin(${op})"
    case _: Ir.Const => "Const"
    case _: Ir.Zext => "Zext"
    case _: Ir.Trunc => "Trunc"
    case _: Ir.Slice => "Slice"
    case _: Ir.Cat => "Cat"
    case _: Ir.Mux => "Mux"
    case _: Ir.Not => "Not"
    case _: Ir.RegRead => "RegRead"
    case _: Ir.InputRef => "InputRef"
  }

  /** DAG → 节点级映射（未调度 DAG 全部记 stage=0、stageCount=1）。
    * X13：附带 critPath/regs/bom（需延迟模型计算关键路径与边界寄存器）。 */
  def dagSig(ctx: String, d: Ir.Dag, model: DelayModel = DelayModels.default): DagSig = {
    val reach = {
      val r = scala.collection.mutable.BitSet.empty
      def visit(id: Int): Unit =
        if (!r(id)) {
          r(id) = true
          Ir.operands(d.nodes(id)).foreach(visit)
        }
      d.outputs.foreach(s => Ir.visitSink(s, visit))
      r
    }
    val bomCounts = scala.collection.mutable.SortedMap.empty[String, Int]
    reach.foreach { id =>
      val k = opOf(d.nodes(id))
      bomCounts(k) = bomCounts.getOrElse(k, 0) + 1
    }
    DagSig(
      ctx, d.stageCount,
      d.nodes.indices.map { id =>
        val n = d.nodes(id)
        NodeSig(id, opOf(n), n.width, d.stages.getOrElse(id, 0))
      },
      critPath = Scheduler.criticalPath(d, model),
      regs = Scheduler.boundaryRegisters(d),
      bom = bomCounts.toMap,
    )
  }

  // ---------------- JSON ----------------

  private def esc(s: String): String = s.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\t' => "\\t"
    case '\r' => "\\r"
    case c => c.toString
  }

  private def quo(s: String): String = s""""${esc(s)}""""

  implicit private class IntOps(private val b: StringBuilder) extends AnyVal {
    def kvStr(k: String, v: String, first: Boolean = false): StringBuilder = {
      if (!first) b += ','
      b ++= quo(k) ++= ":" ++= quo(v)
    }
    def kvInt(k: String, v: Int, first: Boolean = false): StringBuilder = {
      if (!first) b += ','
      b ++= quo(k) ++= s":$v"
    }
    def kvDouble(k: String, v: Double, first: Boolean = false): StringBuilder = {
      if (!first) b += ','
      b ++= quo(k) ++= s":$v"
    }
    def kvBool(k: String, v: Boolean, first: Boolean = false): StringBuilder = {
      if (!first) b += ','
      b ++= quo(k) ++= s":$v"
    }
    def kvArr(k: String, json: String, first: Boolean = false): StringBuilder = {
      if (!first) b += ','
      b ++= quo(k) ++= ":[" ++= json ++= "]"
    }
  }

  private def arr(items: Seq[StringBuilder]): String = items.mkString(",")

  def portJson(p: PortSig): StringBuilder = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("path", p.path, first = true)
    b.kvStr("dir", p.dir)
    b.kvInt("width", p.width)
    if (p.vecSize > 0) b.kvInt("vecSize", p.vecSize)
    b += '}'
    b
  }

  def tableJson(t: TableSig): StringBuilder = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("name", t.name, first = true)
    b.kvBool("runtime", t.runtime)
    b.kvInt("size", t.size)
    if (t.runtime) {
      b.kvInt("keyBits", t.keyBits)
      b.kvInt("actW", t.actW)
      b.kvInt("argW", t.argW)
      b.kvInt("entryW", t.entryW)
      b.kvInt("addrW", t.addrW)
    }
    b += '}'
    b
  }

  def externJson(e: ExternSig): StringBuilder = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("name", e.name, first = true)
    b.kvStr("kind", e.kind)
    b.kvInt("width", e.width)
    b.kvInt("size", e.size)
    b += '}'
    b
  }

  def dagJson(d: DagSig): StringBuilder = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("ctx", d.ctx, first = true)
    b.kvInt("stageCount", d.stageCount)
    b.kvDouble("critPath", d.critPath) // X13
    b.kvInt("regs", d.regs) // X13
    b.kvArr("bom", d.bom.toSeq.sortBy(_._1).map { case (op, c) =>
      s"${quo(op)}:$c"
    }.mkString(",")) // X13
    b.kvArr("nodes", arr(d.nodes.map { n =>
      val nb = new StringBuilder
      nb += '{'
      nb.kvInt("id", n.id, first = true)
      nb.kvStr("op", n.op)
      nb.kvInt("width", n.width)
      nb.kvInt("stage", n.stage)
      nb += '}'
      nb
    }))
    b += '}'
    b
  }

  def controlJson(c: ControlSig): StringBuilder = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("module", c.module, first = true)
    b.kvArr("ports", arr(c.ports.map(portJson)))
    b.kvArr("tables", arr(c.tables.map(tableJson)))
    b.kvArr("externs", arr(c.externs.map(externJson)))
    b.kvArr("dags", arr(c.dags.map(dagJson)))
    b += '}'
    b
  }

  /** 一次生成的完整签名文档。 */
  def toJson(source: String, controls: Seq[ControlSig]): String = {
    val b = new StringBuilder
    b += '{'
    b.kvStr("source", source, first = true)
    b.kvArr("controls", arr(controls.map(controlJson)))
    b += '}'
    b.toString
  }
}
