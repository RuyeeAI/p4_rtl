package P4C

import scala.collection.mutable

/** IR 抽象求值器（对标 XLS IR interpreter）：给定输入与环境，直接对 ActionDAG 求值。
  *
  * 用途：交叉引擎验证——同一程序分别经「IR 求值」与「生成 RTL 仿真」执行，比对结果
  * （[[CrossEngineFuzzSpec]]）。运算语义必须与 ChiselBackend.nodeExpr 的发射规则
  * 逐条对应：位宽截断、Cat 高位在先、Mux 条件取 LSB、移位后截断等。
  */
object Interp {

  /** 求值环境。
    *   - inputs：InputRef 路径 → 值（按节点宽度再截断，调用方可给超宽值）；
    *   - regs：extern 实例名 → 当前内容（RegRead 读旧值；缺省全 0）。
    */
  final case class Env(
    inputs: Map[Seq[String], BigInt] = Map.empty,
    regs: Map[String, Vector[BigInt]] = Map.empty,
  )

  /** 一次求值的 Sink 结果。
    *   - outputs：OutputWrite 路径 → 值；
    *   - regWrites / counterAdds：(实例名, 生效索引) → 值/增量（fire 提交语义由调用方解释）。
    *     索引已按硬件 fitIdx 口径归一（截断到地址宽度，越界不会出现负值）。
    */
  final case class Result(
    outputs: Map[Seq[String], BigInt] = Map.empty,
    regWrites: Map[(String, BigInt), BigInt] = Map.empty,
    counterAdds: Map[(String, BigInt), BigInt] = Map.empty,
  )

  def mask(w: Int): BigInt = (BigInt(1) << w) - 1

  /** 双目运算语义（Passes 常量折叠与 IR 求值共用同一实现，避免两份漂移）。 */
  def evalOp(op: Ir.Op, l: BigInt, r: BigInt, w: Int): BigInt = {
    val m = mask(w)
    op match {
      case Ir.Add => (l + r) & m
      case Ir.Sub => (l - r) & m
      case Ir.And => l & r
      case Ir.Or => l | r
      case Ir.Xor => l ^ r
      case Ir.Shl => (l << r.toInt.min(4096)) & m
      case Ir.Shr => (l >> r.toInt.min(4096)) & m
      case Ir.Eq => if (l == r) 1 else 0
      case Ir.Neq => if (l != r) 1 else 0
      case Ir.Lt => if (l < r) 1 else 0
      case Ir.Le => if (l <= r) 1 else 0
      case Ir.Gt => if (l > r) 1 else 0
      case Ir.Ge => if (l >= r) 1 else 0
    }
  }

  /** extern 索引归一（与 ChiselBackend.fitIdx 硬件口径一致：截到地址宽度，不回绕）。 */
  def effIdx(idxElt: BigInt, idxWidth: Int, size: Int): BigInt = {
    val idxW = math.max(1, BigInt(math.max(0, size - 1)).bitLength)
    idxElt & mask(math.min(idxWidth, idxW))
  }

  /** 求值 DAG。无状态（RegRead 只读 env.regs；写 Sink 不落地，进 Result）。 */
  def eval(dag: Ir.Dag, env: Env): Result = {
    val vals = mutable.HashMap.empty[Ir.NodeId, BigInt]
    def widthOf(id: Ir.NodeId): Int = dag.nodes(id).width
    def go(id: Ir.NodeId): BigInt = vals.getOrElseUpdate(id, dag.nodes(id) match {
      case Ir.Const(v, _) => v
      case Ir.InputRef(path, w) =>
        env.inputs.getOrElse(path,
          throw new P4Error(s"Interp：输入缺值 '${path.mkString(".")}'")) & mask(w)
      case Ir.Zext(s, w) => go(s) & mask(w)
      case Ir.Trunc(s, w) => go(s) & mask(w)
      case Ir.Not(s, w) => (~go(s)) & mask(w)
      case Ir.Slice(s, hi, lo) => (go(s) >> lo) & mask(hi - lo + 1)
      case Ir.Cat(parts, w) =>
        parts.foldLeft(BigInt(0))((acc, p) => (acc << widthOf(p)) | (go(p) & mask(widthOf(p)))) & mask(w)
      case Ir.Mux(c, t, f, _) => if ((go(c) & 1) == 1) go(t) else go(f)
      case Ir.Bin(op, l, r, w) => evalOp(op, go(l), go(r), w)
      case Ir.RegRead(inst, idx, _, size) =>
        val vec = env.regs.getOrElse(inst, Vector.fill(size)(BigInt(0)))
        val i = effIdx(go(idx), widthOf(idx), size).toInt
        if (i < size) vec(i) else BigInt(0)
    })

    val outs = mutable.LinkedHashMap.empty[Seq[String], BigInt]
    val rw = mutable.LinkedHashMap.empty[(String, BigInt), BigInt]
    val ca = mutable.LinkedHashMap.empty[(String, BigInt), BigInt]
    dag.outputs.foreach {
      case Ir.OutputWrite(path, v, w) => outs(path) = go(v) & mask(w)
      case Ir.RegWrite(inst, idx, v, w, size) =>
        rw((inst, effIdx(go(idx), widthOf(idx), size))) = go(v) & mask(w)
      case Ir.CounterAdd(inst, idx, delta, w, size) =>
        ca((inst, effIdx(go(idx), widthOf(idx), size))) = go(delta) & mask(w)
    }
    Result(outs.toMap, rw.toMap, ca.toMap)
  }
}
