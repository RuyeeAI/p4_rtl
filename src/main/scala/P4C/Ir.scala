package P4C

/** 核心 IR：ActionDAG —— 位向量无循环数据流图（XLS 式 node-based IR）。
  *
  * 不变量：
  *   - 每个节点的 width 即其 Chisel UInt 的精确宽度；
  *   - Bin 运算的两个操作数经 [[fit]] 归一为相同宽度（Zext/Trunc 显式节点）；
  *     **唯一例外**：[[Passes.narrow]] 解包移位量后，Shl/Shr 的右操作数可窄于
  *     节点宽度（移位语义只取移位量数值，发射/求值均按值语义处理，见 X9 ②）；
  *   - 一切位宽不匹配都体现为显式 Cast 节点，绝不静默截断（ParserCore 教训）。
  */
object Ir {

  type NodeId = Int

  sealed trait Op
  case object Add extends Op
  case object Sub extends Op
  case object And extends Op
  case object Or extends Op
  case object Xor extends Op
  case object Shl extends Op
  case object Shr extends Op
  case object Eq extends Op
  case object Neq extends Op
  case object Lt extends Op
  case object Le extends Op
  case object Gt extends Op
  case object Ge extends Op

  sealed trait Node { def width: Int }

  final case class Const(value: BigInt, width: Int) extends Node
  /** 零扩展 */
  final case class Zext(src: NodeId, width: Int) extends Node
  /** 高位截断 */
  final case class Trunc(src: NodeId, width: Int) extends Node
  final case class Bin(op: Op, l: NodeId, r: NodeId, width: Int) extends Node
  /** 位切片 [hi:lo]，width = hi-lo+1 */
  final case class Slice(src: NodeId, hi: Int, lo: Int) extends Node {
    override def width: Int = hi - lo + 1
  }
  /** 拼接，parts[0] 为最高位 */
  final case class Cat(parts: Seq[NodeId], width: Int) extends Node
  final case class Mux(c: NodeId, t: NodeId, f: NodeId, width: Int) extends Node
  final case class Not(src: NodeId, width: Int) extends Node

  /** 状态单元读：reg[old][index]，width = 元素宽度 */
  final case class RegRead(inst: String, index: NodeId, width: Int, size: Int) extends Node

  /** 读取输入（header/metadata 字段或 action 参数），path 为点分路径 */
  final case class InputRef(path: Seq[String], width: Int) extends Node

  /** DAG 汇点：一次赋值 / 一次状态单元写 */
  sealed trait Sink
  final case class OutputWrite(path: Seq[String], value: NodeId, width: Int) extends Sink
  /** 寄存器写（fire 时生效） */
  final case class RegWrite(inst: String, index: NodeId, value: NodeId, width: Int, size: Int) extends Sink
  /** 计数器累加（fire 时生效，delta 通常为 1） */
  final case class CounterAdd(inst: String, index: NodeId, delta: NodeId, width: Int, size: Int) extends Sink

  /** 一次 ActionDAG 构建结果（一个 action / 一个表项 / 一段 apply 直行代码）。
    *
    * @param stages 切拍调度标注：NodeId → 所在级（0 基）。空 map = 未调度（全组合单拍）。
    *               只能由 [[Scheduler]]（在 [[Passes.runAll]] 之后）产出——优化 pass 会
    *               重编号 NodeId，且 CSE 不得在调度后运行（会跨级合并）。
    */
  final case class Dag(nodes: Vector[Node], outputs: Seq[Sink], stages: Map[NodeId, Int] = Map.empty) {
    /** stages 为空 = 未调度（全组合单拍） */
    def isScheduled: Boolean = stages.nonEmpty
    /** 实际级数：未调度 = 1；已调度 = max(stage) + 1（所有 Sink 固定末级，为发射约定，不入 map） */
    def stageCount: Int = if (stages.isEmpty) 1 else stages.values.max + 1
  }

  /** 节点操作数（按声明序；叶子节点返回空）。 */
  def operands(n: Node): Seq[NodeId] = n match {
    case z: Zext => Seq(z.src)
    case t: Trunc => Seq(t.src)
    case nt: Not => Seq(nt.src)
    case s: Slice => Seq(s.src)
    case c: Cat => c.parts
    case m: Mux => Seq(m.c, m.t, m.f)
    case b: Bin => Seq(b.l, b.r)
    case rr: RegRead => Seq(rr.index)
    case _: Const | _: InputRef => Seq.empty
  }

  /** Sink 引用的节点遍历（OutputWrite 只有 value；Reg/Counter 写含 index 与 value/delta）。 */
  def visitSink(s: Sink, v: NodeId => Unit): Unit = s match {
    case o: OutputWrite => v(o.value)
    case r: RegWrite => v(r.index); v(r.value)
    case c: CounterAdd => v(c.index); v(c.delta)
  }

  /** 便捷 DAG 构建器 */
  final class Builder {
    private val nodes = scala.collection.mutable.ArrayBuffer.empty[Node]
    def add(n: Node): NodeId = { nodes += n; nodes.length - 1 }
    def apply(id: NodeId): Node = nodes(id)
    def size: Int = nodes.length

    private def log2Ceil(n: Int): Int = math.max(1, BigInt(n - 1).bitLength)

    def fit(id: NodeId, from: Int, to: Int): NodeId =
      if (from == to) id
      else if (from < to) add(Zext(id, to))
      else add(Trunc(id, to))

    def regRead(inst: String, index: NodeId, indexWidth: Int, width: Int, size: Int): (NodeId, Int) = {
      val idxW = math.max(1, log2Ceil(size))
      val idx = fit(index, indexWidth, idxW)
      (add(RegRead(inst, idx, width, size)), width)
    }

    def regWrite(inst: String, index: NodeId, indexWidth: Int, value: NodeId, vWidth: Int, width: Int, size: Int): Sink = {
      val idxW = math.max(1, log2Ceil(size))
      RegWrite(inst, fit(index, indexWidth, idxW), fit(value, vWidth, width), width, size)
    }

    def counterAdd(inst: String, index: NodeId, indexWidth: Int, delta: NodeId, dWidth: Int, width: Int, size: Int): Sink = {
      val idxW = math.max(1, log2Ceil(size))
      CounterAdd(inst, fit(index, indexWidth, idxW), fit(delta, dWidth, width), width, size)
    }

    def bin(op: Op, l: (NodeId, Int), r: (NodeId, Int)): (NodeId, Int) = {
      val w = math.max(l._2, r._2)
      val li = fit(l._1, l._2, w)
      val ri = fit(r._1, r._2, w)
      (add(Bin(op, li, ri, w)), w)
    }

    def finish(outputs: Seq[Sink]): Dag = Dag(nodes.toVector, outputs)
  }
}

/** IR 优化 pass。输入输出均为不可变 [[Ir.Dag]]。 */
object Passes {

  import Ir._

  private def log2Ceil(n: Int): Int = math.max(1, BigInt(n - 1).bitLength)

  private def mapSink(s: Sink, f: NodeId => NodeId): Sink = s match {
    case o: OutputWrite => OutputWrite(o.path, f(o.value), o.width)
    case r: RegWrite => RegWrite(r.inst, f(r.index), f(r.value), r.width, r.size)
    case c: CounterAdd => CounterAdd(c.inst, f(c.index), f(c.delta), c.width, c.size)
  }

  private def visitSink(s: Sink, v: NodeId => Unit): Unit = s match {
    case o: OutputWrite => v(o.value)
    case r: RegWrite => v(r.index); v(r.value)
    case c: CounterAdd => v(c.index); v(c.delta)
  }

  /** 常量折叠：Const 参与的纯运算直接算出。
    *
    * 实现口径：`go(id)` 返回**新图**中的节点 id；判断某操作数折叠后是否为
    * 常量一律查 `b(新id)`（新 Builder），绝不以新 id 索引旧 `dag.nodes`
    * （新旧 id 空间不同，混用会导致错乱/KeyNotFound——2026-09-08 修复）。
    */
  def constFold(dag: Dag): Dag = {
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]

    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, {
      def src(sid: NodeId): (NodeId, Node) = { val ns = go(sid); (ns, b(ns)) }
      val nn: Node = dag.nodes(id) match {
        case c: Const => c
        case z: Zext => src(z.src) match {
          case (ns, Const(v, w)) if w < z.width => Const(v, z.width)
          case (ns, _) => Zext(ns, z.width)
        }
        case t: Trunc => src(t.src) match {
          case (ns, Const(v, w)) if w > t.width => Const(v & ((BigInt(1) << t.width) - 1), t.width)
          case (ns, _) => Trunc(ns, t.width)
        }
        case n: Not => src(n.src) match {
          case (_, Const(v, w)) => Const((~v) & ((BigInt(1) << w) - 1), w)
          case (ns, _) => Not(ns, n.width)
        }
        case sl: Slice => src(sl.src) match {
          case (_, Const(v, _)) => Const((v >> sl.lo) & ((BigInt(1) << (sl.hi - sl.lo + 1)) - 1), sl.hi - sl.lo + 1)
          case (ns, _) => Slice(ns, sl.hi, sl.lo)
        }
        case cat: Cat =>
          val ps = cat.parts.map(go)
          if (ps.forall(p => b(p).isInstanceOf[Const])) {
            var v = BigInt(0)
            ps.foreach { p => b(p) match { case Const(pv, pw) => v = (v << pw) | pv } }
            Const(v, cat.width)
          } else Cat(ps, cat.width)
        case m: Mux =>
          val c = go(m.c); val t = go(m.t); val f = go(m.f)
          b(c) match {
            case Const(cv, _) => b(if ((cv & 1) == 1) t else f)
            case _ =>
              if (t == f) b(t)
              else Mux(c, t, f, m.width)
          }
        case bin: Bin =>
          val lid = go(bin.l)
          val rid = go(bin.r)
          val w = bin.width
          def zeroOf(id: NodeId): Boolean = b(id) match { case Const(v, _) => v == 0; case _ => false }
          (b(lid), b(rid)) match {
            case (Const(lv, _), Const(rv, _)) => Const(evalOp(bin.op, lv, rv, w), binOpWidth(bin.op, w))
            case _ =>
              val lz = zeroOf(lid)
              val rz = zeroOf(rid)
              bin.op match {
                case And => if (lz || rz) Const(0, w) else Bin(bin.op, lid, rid, w)
                case Or => if (lz) b(rid) else if (rz) b(lid) else Bin(bin.op, lid, rid, w)
                case Xor => if (lz) b(rid) else if (rz) b(lid) else Bin(bin.op, lid, rid, w)
                case Add => if (lz) b(rid) else if (rz) b(lid) else Bin(bin.op, lid, rid, w)
                case _ => Bin(bin.op, lid, rid, w)
              }
          }
        case ref: InputRef => ref
        case rr: RegRead => RegRead(rr.inst, go(rr.index), rr.width, rr.size)
      }
      b.add(nn)
    })

    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  private def evalOp(op: Op, l: BigInt, r: BigInt, w: BigInt): BigInt =
    Interp.evalOp(op, l, r, w.toInt)

  private def binOpWidth(op: Op, w: Int): Int = op match {
    case Eq | Neq | Lt | Le | Gt | Ge => 1
    case _ => w
  }

  /** 公共子表达式消除：结构相同的纯节点合并。 */
  def cse(dag: Dag): Dag = {
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]
    val seen = scala.collection.mutable.HashMap.empty[Node, NodeId]

    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, {
      val n0 = dag.nodes(id) match {
        case z: Zext => Zext(go(z.src), z.width)
        case t: Trunc => Trunc(go(t.src), t.width)
        case n: Not => Not(go(n.src), n.width)
        case s: Slice => Slice(go(s.src), s.hi, s.lo)
        case c: Cat => Cat(c.parts.map(go), c.width)
        case m: Mux => Mux(go(m.c), go(m.t), go(m.f), m.width)
        case bin: Bin => Bin(bin.op, go(bin.l), go(bin.r), bin.width)
        case rr: RegRead => RegRead(rr.inst, go(rr.index), rr.width, rr.size)
        case other => other // Const / InputRef
      }
      // 比较/移位以外的纯运算可 CSE；操作数 ID 先递归替换，所以 key 稳定
      val canon = n0
      val cached = seen.get(canon)
      val out = cached.getOrElse { val nid = b.add(canon); seen(canon) = nid; nid }
      out
    })

    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  /** 死代码消除：从 outputs 可达的节点才保留。 */
  def dce(dag: Dag): Dag = {
    val keep = scala.collection.mutable.BitSet.empty
    def visit(id: NodeId): Unit = {
      if (!keep(id)) {
        keep(id) = true
        dag.nodes(id) match {
          case z: Zext => visit(z.src)
          case t: Trunc => visit(t.src)
          case n: Not => visit(n.src)
          case s: Slice => visit(s.src)
          case c: Cat => c.parts.foreach(visit)
          case m: Mux => visit(m.c); visit(m.t); visit(m.f)
          case bin: Bin => visit(bin.l); visit(bin.r)
          case rr: RegRead => visit(rr.index)
          case _ =>
        }
      }
    }
    dag.outputs.foreach(s => visitSink(s, visit))
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]
    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, {
      val nn = dag.nodes(id) match {
        case z: Zext => Zext(go(z.src), z.width)
        case t: Trunc => Trunc(go(t.src), t.width)
        case n: Not => Not(go(n.src), n.width)
        case s: Slice => Slice(go(s.src), s.hi, s.lo)
        case c: Cat => Cat(c.parts.map(go), c.width)
        case m: Mux => Mux(go(m.c), go(m.t), go(m.f), m.width)
        case bin: Bin => Bin(bin.op, go(bin.l), go(bin.r), bin.width)
        case rr: RegRead => RegRead(rr.inst, go(rr.index), rr.width, rr.size)
        case other => other
      }
      b.add(nn)
    })
    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  /** X5 语义简化 pass（borrow XLS 优化 pass 思路），全部为等价变换：
    *   ① slice-of-concat 直切：Slice(Cat(...)) 直接切到 parts（消除 Cat+Slice 两层）；
    *   ② 布尔恒等：And(x,全1)=x、Or(x,全1)=全1、Xor(x,全1)=Not(x)、Not(Not(x))=x、
    *      零位移 Shl/Shr(x,0)=x（补 constFold 只覆盖 0 侧的对称情形）；
    *   ③ 自反消除：Sub(x,x)=0、Xor(x,x)=0、And/Or(x,x)=x。
    * 语义零回归由交叉引擎 fuzzer（CrossEngineFuzzSpec）与 staged 等价测试兜底。
    */
  def simplify(dag: Dag): Dag = {
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]

    def isOnes(n: Node): Boolean = n match {
      case Const(v, w) => v == (BigInt(1) << w) - 1
      case _ => false
    }
    def isZeroN(n: Node): Boolean = n match { case Const(v, _) => v == 0; case _ => false }

    /** Slice(Cat(parts)) → 直接切 parts：全盖 part 保留原 part，部分盖生成
      * 子 Slice，跨多 part 重建 Cat（保持 part 顺序，先声明在高位）。 */
    def sliceOfCat(cat: Cat, hi: Int, lo: Int): NodeId = {
      val widths = cat.parts.map(p => b(p).width)
      val total = widths.sum
      val bounds = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)] // (hi, lo) per part
      var top = total - 1
      widths.foreach { w => bounds += ((top, top - w + 1)); top -= w }
      val newParts = bounds.zip(cat.parts).collect {
        case ((phi, plo), pid) if phi >= lo && plo <= hi =>
          val oh = math.min(phi, hi)
          val ol = math.max(plo, lo)
          if (oh == phi && ol == plo) pid
          else b.add(Ir.Slice(pid, oh - plo, ol - plo))
      }.toVector
      newParts match {
        case Seq(single) => single
        case multi => b.add(Cat(multi, hi - lo + 1))
      }
    }

    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, dag.nodes(id) match {
      case _: Const | _: InputRef => b.add(dag.nodes(id))
      case z: Zext => b.add(Zext(go(z.src), z.width))
      case t: Trunc => b.add(Trunc(go(t.src), t.width))
      case n: Not =>
        val s = go(n.src)
        b(s) match {
          case Not(inner, _) => inner // Not(Not(x)) → x
          case _ => b.add(Not(s, n.width))
        }
      case sl: Ir.Slice =>
        val srcId = go(sl.src)
        b(srcId) match {
          case cat: Cat if cat.parts.nonEmpty => sliceOfCat(cat, sl.hi, sl.lo)
          case _ => b.add(Ir.Slice(srcId, sl.hi, sl.lo))
        }
      case cat: Cat => b.add(Cat(cat.parts.map(go), cat.width))
      case m: Mux => b.add(Mux(go(m.c), go(m.t), go(m.f), m.width))
      case rr: RegRead => b.add(RegRead(rr.inst, go(rr.index), rr.width, rr.size))
      case bin: Bin =>
        val lid = go(bin.l)
        val rid = go(bin.r)
        val w = bin.width
        val lv = b(lid)
        val rv = b(rid)
        bin.op match {
          case Ir.And if lid == rid => lid
          case Ir.Or if lid == rid => lid
          case Ir.Xor if lid == rid => b.add(Const(0, w))
          case Ir.Sub if lid == rid => b.add(Const(0, w))
          case Ir.And if isOnes(lv) => rid
          case Ir.And if isOnes(rv) => lid
          case Ir.Or if isOnes(lv) || isOnes(rv) => b.add(Const((BigInt(1) << w) - 1, w))
          case Ir.Xor if isOnes(lv) => b.add(Not(rid, w))
          case Ir.Xor if isOnes(rv) => b.add(Not(lid, w))
          case Ir.Shl if isZeroN(rv) => lid
          case Ir.Shr if isZeroN(rv) => lid
          case _ => b.add(Bin(bin.op, lid, rid, w))
        }
    })

    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  /** X9：再平衡（XLS boolean unflattening + reassociation 合一）。
    *
    * Add/And/Or/Xor 在模 2^w 下满足结合律（无符号位向量语义下重排不改变结果），
    * 将同 op 左结合长链展平后重建**平衡二叉树**：关键路径 O(n) → O(log n)。
    * 叶子顺序保持原链顺序（确定性：左半取 ceil(n/2) 个）。
    * 等价性由穷尽/SMT 三重检查兜底（PassesBalanceNarrowSpec / EquivalenceSpec）。
    */
  def balance(dag: Dag): Dag = {
    val assoc: Set[Op] = Set(Add, And, Or, Xor)
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]

    /** 展平原 DAG 中同 op 连续链（宽度不变量保证链上宽度一致），收集映射后的叶子。 */
    def flatten(id: NodeId, op: Op): Seq[NodeId] = dag.nodes(id) match {
      case Bin(o, l, r, _) if o == op => flatten(l, op) ++ flatten(r, op)
      case _ => Seq(go(id))
    }

    def build(leaves: Seq[NodeId], op: Op, w: Int): NodeId = leaves match {
      case Seq(single) => single
      case _ =>
        val half = (leaves.length + 1) / 2
        b.add(Bin(op, build(leaves.take(half), op, w), build(leaves.drop(half), op, w), w))
    }

    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, dag.nodes(id) match {
      case bin: Bin if assoc(bin.op) =>
        build(flatten(bin.l, bin.op) ++ flatten(bin.r, bin.op), bin.op, bin.width)
      case z: Zext => b.add(Zext(go(z.src), z.width))
      case t: Trunc => b.add(Trunc(go(t.src), t.width))
      case n: Not => b.add(Not(go(n.src), n.width))
      case s: Slice => b.add(Slice(go(s.src), s.hi, s.lo))
      case c: Cat => b.add(Cat(c.parts.map(go), c.width))
      case m: Mux => b.add(Mux(go(m.c), go(m.t), go(m.f), m.width))
      case other: Bin => b.add(Bin(other.op, go(other.l), go(other.r), other.width))
      case rr: RegRead => b.add(RegRead(rr.inst, go(rr.index), rr.width, rr.size))
      case leaf => b.add(leaf) // Const / InputRef
    })

    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  /** X9：比较位缩减（XLS narrowing 规则子集），全部为等价变换：
    *   ① 比较两侧同为 Zext 且源宽相同 → 直接比较低 w' 位（比较器更窄更快）；
    *      零扩展保序 ⇒ zext(a)⋈zext(b) ⟺ a⋈b（无符号）；
    *   ② 移位量为 Zext → 解包（移位语义只取移位量的**数值**，与位宽无关；
    *      这是 IR 中 Bin 操作数等宽不变量的唯一例外，见 [[Ir]] 头注释）；
    *   ③ Slice(Zext(x))：`lo ≥ w'` → 常量 0；`hi < w'` → 直切 x；跨界 → Cat(切 x, 0)。
    */
  def narrow(dag: Dag): Dag = {
    val b = new Builder
    val map = scala.collection.mutable.HashMap.empty[NodeId, NodeId]
    val isCmp: Op => Boolean = {
      case Eq | Neq | Lt | Le | Gt | Ge => true; case _ => false
    }

    def go(id: NodeId): NodeId = map.getOrElseUpdate(id, {
      val node: Node = dag.nodes(id) match {
        case bin: Bin if isCmp(bin.op) =>
          (dag.nodes(bin.l), dag.nodes(bin.r)) match {
            case (Zext(ls, _), Zext(rs, _)) if dag.nodes(ls).width == dag.nodes(rs).width =>
              // ① 低 w' 位直接比较（源宽相同，两源已同为无符号语义）
              Bin(bin.op, go(ls), go(rs), dag.nodes(ls).width)
            case _ =>
              Bin(bin.op, go(bin.l), go(bin.r), bin.width)
          }
        case bin: Bin if bin.op == Shl || bin.op == Shr =>
          dag.nodes(bin.r) match {
            case z: Zext => Bin(bin.op, go(bin.l), go(z.src), bin.width) // ② 解包移位量
            case _ => Bin(bin.op, go(bin.l), go(bin.r), bin.width)
          }
        case sl: Slice =>
          dag.nodes(sl.src) match {
            case z: Zext =>
              val srcW = dag.nodes(z.src).width
              if (sl.lo >= srcW) Const(0, sl.width)
              else if (sl.hi < srcW) Slice(go(z.src), sl.hi, sl.lo)
              else { // 跨界：高位 [hi:srcW] 为 0，低位 [srcW-1:lo] 取自 x
                val low = if (sl.lo == srcW - 1) go(z.src) else b.add(Slice(go(z.src), srcW - 1, sl.lo))
                Cat(Seq(b.add(Const(0, sl.width - (srcW - sl.lo))), low), sl.width)
              }
            case _ => Slice(go(sl.src), sl.hi, sl.lo)
          }
        case z: Zext => Zext(go(z.src), z.width)
        case t: Trunc => Trunc(go(t.src), t.width)
        case n: Not => Not(go(n.src), n.width)
        case c: Cat => Cat(c.parts.map(go), c.width)
        case m: Mux => Mux(go(m.c), go(m.t), go(m.f), m.width)
        case other: Bin => Bin(other.op, go(other.l), go(other.r), other.width)
        case rr: RegRead => RegRead(rr.inst, go(rr.index), rr.width, rr.size)
        case leaf => leaf // Const / InputRef
      }
      b.add(node)
    })

    val outs = dag.outputs.map(s => mapSink(s, go))
    b.finish(outs)
  }

  /** 标准优化链（X9：balance/narrow 插入 simplify 与 cse 之间）。 */
  def runAll(dag: Dag): Dag = dce(cse(narrow(balance(simplify(constFold(dag))))))
}
