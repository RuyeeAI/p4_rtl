package P4C

import scala.collection.mutable

/** X8：XLS IR 文本格式（对标 google/xls 的 `.ir` 文本格式，逐语法对齐）。
  *
  * 一个 [[Ir.Dag]] → 一个 XLS `fn`。语法映射（与 XLS ir_semantics / 真实 IR dump 一致）：
  *
  * {{{
  * // p4c: package demo1
  * // p4c: fn control_Ingress_action_bump
  * // p4c-params: in_x_x=in:x.x
  * // p4c-params: reg_stats=reg:stats
  * // p4c-sinks: out:y.y:16,reg:stats:8:16
  * // p4c-stages: 2=0,3=1
  * package demo1
  *
  * fn control_Ingress_action_bump(in_x_x: bits[16], reg_stats: bits[16][8]) -> (bits[16], bits[3], bits[16]) {
  *   n0: bits[16] = literal(value=1)
  *   n1: bits[16] = array_index(reg_stats, indices=[n0])
  *   n2: bits[16] = add(n1, n0)
  *   ret sinks: (bits[16], bits[3], bits[16]) = tuple(n2, in_x_x, n2)
  * }
  * }}}
  *
  * 节点映射：
  *   - Const → `literal(value=N)`；InputRef → 函数参数（`in_<路径>`，原始点分路径在
  *     `// p4c-params:` 注释中保留供 round-trip 还原）；
  *   - Zext → `zero_ext(x, new_bit_count=N)`；Trunc/Slice → `bit_slice(x, start=N, width=N)`
  *     （XLS 无独立 trunc op；round-trip 时 Trunc 统一规范化为等价的 Slice(w-1,0)）；
  *   - Cat → `concat(...)`（左 = MSB，与 XLS concat 一致）；
  *   - Mux(c,t,f) → `sel(c, cases=[f, t])`（XLS sel 的 selector 按值索引 cases：0 → 假臂在前）；
  *   - Not → `not(x)`；RegRead → `array_index(reg_<inst>, indices=[idx])`（数组参数
  *     `bits[w][size]`；索引必须用关键字形式，位置写法会被 XLS ArgParser 拒绝）；
  *   - Bin：add/sub/and/or/xor/shll/shrl/eq/ne/ult/ule/ugt/uge。
  *
  * Sink：全部汇点打包为单一 tuple 返回值；`// p4c-sinks:` 注释按元素顺序记录语义
  * （**尾字段 = Sink 声明宽度**——与 tuple 元素引用节点宽可不同，掩码语义见
  * [[Interp]] eval；round-trip 靠它精确还原声明宽，2026-09-08 补）：
  *   - `out:点分路径:宽`（1 元素）；
  *   - `reg:实例:深度:宽`（2 元素 = 索引、值）；
  *   - `cnt:实例:深度:宽`（2 元素 = 索引、增量）。
  * 调度标注以 `// p4c-stages:` 注释保留（`名字=级`）。
  *
  * 只发射 outputs 可达节点（与 dce 口径一致）；dump 名称 n0.. 按发射序分配（叶子
  * InputRef 不占名），保证 dump∘parse∘dump 逐字节稳定。
  */
object IrText {

  // ---------------- 名称/类型工具 ----------------

  private def sanitize(s: String): String = {
    val r = s.map(c => if (c.isLetterOrDigit || c == '_') c else '_').mkString
    if (r.isEmpty || (!r.head.isLetter && r.head != '_')) s"_$r" else r
  }

  /** ctx（如 "control Ingress/action bump"）→ 合法标识符。 */
  def fnNameOf(ctx: String): String = sanitize(ctx.replace(" ", "_").replace("/", "_"))

  private def bitsType(w: Int): String = s"bits[$w]"

  private def arrayType(w: Int, size: Int): String = s"bits[$w][$size]"

  private def opName(op: Ir.Op): String = op match {
    case Ir.Add => "add"; case Ir.Sub => "sub"
    case Ir.And => "and"; case Ir.Or => "or"; case Ir.Xor => "xor"
    case Ir.Shl => "shll"; case Ir.Shr => "shrl"
    case Ir.Eq => "eq"; case Ir.Neq => "ne"
    case Ir.Lt => "ult"; case Ir.Le => "ule"
    case Ir.Gt => "ugt"; case Ir.Ge => "uge"
  }

  // ---------------- dump ----------------

  /** DAG → XLS IR 文本。@param fnName 建议 [[fnNameOf]](ctx) 的产物。 */
  def dump(pkg: String, fnName: String, dag: Ir.Dag): String = {
    val reach = mutable.BitSet.empty
    def visit(id: Ir.NodeId): Unit =
      if (!reach(id)) {
        reach(id) = true
        Ir.operands(dag.nodes(id)).foreach(visit)
      }
    dag.outputs.foreach(s => Ir.visitSink(s, visit))

    // 参数收集：InputRef 路径 + RegRead 实例（首个出现序）
    case class InParam(name: String, path: Seq[String], w: Int)
    case class RegParam(name: String, inst: String, w: Int, size: Int)
    val inParams = mutable.ArrayBuffer.empty[InParam]
    val inParamOf = mutable.HashMap.empty[Seq[String], String]
    val regParams = mutable.ArrayBuffer.empty[RegParam]
    val regParamOf = mutable.HashMap.empty[String, String]
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) dag.nodes(id) match {
        case Ir.InputRef(path, w) if !inParamOf.contains(path) =>
          var nm = "in_" + sanitize(path.mkString("_"))
          while (inParams.exists(_.name == nm)) nm += "_"
          inParams += InParam(nm, path, w); inParamOf(path) = nm
        case Ir.RegRead(inst, _, w, size) if !regParamOf.contains(inst) =>
          var nm = "reg_" + sanitize(inst)
          while (regParams.exists(_.name == nm)) nm += "_"
          regParams += RegParam(nm, inst, w, size); regParamOf(inst) = nm
        case _ =>
      }
    }

    // 节点行：n0.. 按发射序（跳过 InputRef 叶子）
    val nodeName = mutable.HashMap.empty[Ir.NodeId, String]
    def ref(id: Ir.NodeId): String = dag.nodes(id) match {
      case Ir.InputRef(p, _) => inParamOf(p)
      case _ => nodeName.getOrElse(id, throw new P4Error(s"IrText.dump：节点 $id 未命名（内部错误）"))
    }
    val nodeLines = mutable.ArrayBuffer.empty[String]
    var seq = 0
    (0 until dag.nodes.length).foreach { id =>
      if (reach(id)) dag.nodes(id) match {
        case _: Ir.InputRef => // 参数引用，不占节点行
        case n =>
          val nm = s"n$seq"; seq += 1
          nodeName(id) = nm
          nodeLines += (n match {
            case Ir.Const(v, w) => s"$nm: ${bitsType(w)} = literal(value=$v)"
            case Ir.Zext(s, w) => s"$nm: ${bitsType(w)} = zero_ext(${ref(s)}, new_bit_count=$w)"
            case Ir.Trunc(s, w) => s"$nm: ${bitsType(w)} = bit_slice(${ref(s)}, start=0, width=$w)"
            case Ir.Slice(s, hi, lo) => s"$nm: ${bitsType(hi - lo + 1)} = bit_slice(${ref(s)}, start=$lo, width=${hi - lo + 1})"
            case Ir.Cat(parts, w) => s"$nm: ${bitsType(w)} = concat(${parts.map(ref).mkString(", ")})"
            case Ir.Not(s, w) => s"$nm: ${bitsType(w)} = not(${ref(s)})"
            case Ir.Mux(c, t, f, w) => s"$nm: ${bitsType(w)} = sel(${ref(c)}, cases=[${ref(f)}, ${ref(t)}])"
            case Ir.Bin(op, l, r, w) => s"$nm: ${bitsType(w)} = ${opName(op)}(${ref(l)}, ${ref(r)})"
            case Ir.RegRead(inst, idx, w, _) => s"$nm: ${bitsType(w)} = array_index(${regParamOf(inst)}, indices=[${ref(idx)}])"
            case other => throw new P4Error(s"IrText.dump：未知节点 $other")
          })
      }
    }

    // Sink tuple：元素 = 各汇点引用（out 1 个；reg/cnt 各 2 个：索引、值/增量）。
    // 元素类型 = 引用节点的宽度（与 XLS ret tuple 元素类型 = 节点类型一致）；
    // Sink 自身的声明宽度（掩码语义，Interp eval 按 mask(w) 截）在 p4c-sinks 描述中保留。
    def sinkElems(s: Ir.Sink): Seq[(Ir.NodeId, Int)] = s match {
      case Ir.OutputWrite(_, v, _) => Seq((v, dag.nodes(v).width))
      case Ir.RegWrite(_, idx, v, _, _) => Seq((idx, dag.nodes(idx).width), (v, dag.nodes(v).width))
      case Ir.CounterAdd(_, idx, d, _, _) => Seq((idx, dag.nodes(idx).width), (d, dag.nodes(d).width))
    }
    val elems = dag.outputs.flatMap(sinkElems)
    val retType = "(" + elems.map(e => bitsType(e._2)).mkString(", ") + ")"
    val retLine = if (elems.isEmpty) "ret sinks: () = tuple()"
    else s"ret sinks: $retType = tuple(${elems.map(e => ref(e._1)).mkString(", ")})"

    // 语义描述：值/增量按 Sink 声明宽度掩码（Interp 口径），width 字段供 parse 精确还原
    val sinkDesc = dag.outputs.map {
      case Ir.OutputWrite(path, _, w) => s"out:${path.mkString(".")}:$w"
      case Ir.RegWrite(inst, _, _, w, size) => s"reg:$inst:$size:$w"
      case Ir.CounterAdd(inst, _, _, w, size) => s"cnt:$inst:$size:$w"
    }

    val b = new StringBuilder
    b ++= s"// p4c: package $pkg\n"
    b ++= s"// p4c: fn $fnName\n"
    inParams.foreach(p => b ++= s"// p4c-params: ${p.name}=in:${p.path.mkString(".")}\n")
    regParams.foreach(p => b ++= s"// p4c-params: ${p.name}=reg:${p.inst}\n")
    if (sinkDesc.nonEmpty) b ++= s"// p4c-sinks: ${sinkDesc.mkString(",")}\n"
    if (dag.isScheduled) {
      val sts = nodeName.toSeq.sortBy(_._2.substring(1).toInt).map { case (id, nm) => s"$nm=${dag.stages(id)}" }
      b ++= s"// p4c-stages: ${sts.mkString(",")}\n"
    }
    b ++= s"package $pkg\n\n"
    val params = inParams.map(p => s"${p.name}: ${bitsType(p.w)}") ++
      regParams.map(p => s"${p.name}: ${arrayType(p.w, p.size)}")
    b ++= s"fn $fnName(${params.mkString(", ")}) -> $retType {\n"
    nodeLines.foreach(l => b ++= s"  $l\n")
    b ++= s"  $retLine\n}\n"
    b.toString
  }

  // ---------------- parse ----------------

  private sealed trait ParamMeta
  private final case class InMeta(path: Seq[String]) extends ParamMeta
  private final case class RegMeta(inst: String) extends ParamMeta

  private val BinOps: Map[String, Ir.Op] = Map(
    "add" -> Ir.Add, "sub" -> Ir.Sub, "and" -> Ir.And, "or" -> Ir.Or, "xor" -> Ir.Xor,
    "shll" -> Ir.Shl, "shrl" -> Ir.Shr, "eq" -> Ir.Eq, "ne" -> Ir.Neq,
    "ult" -> Ir.Lt, "ule" -> Ir.Le, "ugt" -> Ir.Gt, "uge" -> Ir.Ge,
  )

  final case class Parsed(pkg: String, fnName: String, dag: Ir.Dag)

  private val bitsRe = """bits\[(\d+)\]""".r
  private val arrRe = """bits\[(\d+)\]\[(\d+)\]""".r

  private def wOf(ty: String): Int = ty match {
    case arrRe(w, _) => w.toInt
    case bitsRe(w) => w.toInt
    case _ => throw new P4Error(s"IrText.parse：未知类型 '$ty'")
  }

  private def sizeOf(ty: String): Int = ty match {
    case arrRe(_, s) => s.toInt
    case _ => 0
  }

  /** 解析 [[dump]] 产物（含 p4c-* 元数据注释），重建 [[Ir.Dag]]（含 stages）。 */
  def parse(text: String): Parsed = {
    var pkg = "p4c"
    var fnName = "f"
    final case class ParamDecl(name: String, spec: String)
    val paramDecls = mutable.ArrayBuffer.empty[ParamDecl]
    val sinkDesc = mutable.ArrayBuffer.empty[String]
    var stagesByName = Map.empty[String, Int]

    val body = mutable.ArrayBuffer.empty[String]
    text.split("\n").foreach { ln =>
      val t = ln.trim
      if (t.startsWith("// p4c: package ")) pkg = t.stripPrefix("// p4c: package ").trim
      else if (t.startsWith("// p4c: fn ")) fnName = t.stripPrefix("// p4c: fn ").trim
      else if (t.startsWith("// p4c-params: ")) {
        val kv = t.stripPrefix("// p4c-params: ").trim
        val eq = kv.indexOf('=')
        paramDecls += ParamDecl(kv.substring(0, eq).trim, kv.substring(eq + 1).trim)
      } else if (t.startsWith("// p4c-sinks: ")) {
        t.stripPrefix("// p4c-sinks: ").trim.split(",").filter(_.nonEmpty).foreach(sinkDesc += _)
      } else if (t.startsWith("// p4c-stages: ")) {
        t.stripPrefix("// p4c-stages: ").trim.split(",").filter(_.nonEmpty).foreach { kv =>
          val Array(n, s) = kv.split("=", 2); stagesByName += n.trim -> s.trim.toInt
        }
      } else if (t.nonEmpty && !t.startsWith("//")) body += t
    }

    // 语法行分类
    val nodeDecls = mutable.ArrayBuffer.empty[(String, String, String, String)] // (name, type, op, args)
    var retElems: Seq[String] = Seq.empty
    var seenFn = false
    body.foreach { t =>
      if (t.startsWith("package ")) ()
      else if (t.startsWith("fn ")) { seenFn = true; fnName = t.drop(3).trim.takeWhile(_ != '(').trim }
      else if (t.startsWith("ret ")) {
        val rhs = t.substring(t.indexOf('=') + 1).trim
        if (rhs.startsWith("tuple(")) {
          val inner = rhs.substring(6, rhs.length - 1).trim
          retElems = if (inner.isEmpty) Seq.empty else inner.split(",").map(_.trim).toSeq
        } else throw new P4Error(s"IrText.parse：ret 行必须是 tuple（got '$t'）")
      } else if (t == "{" || t == "}") ()
      else if (seenFn) {
        """^(\w+):\s*(.+?)\s*=\s*(\w+)\((.*)\)$""".r.findAllMatchIn(t).toList match {
          case m :: Nil => nodeDecls += ((m.group(1), m.group(2), m.group(3), m.group(4).trim))
          case _ => throw new P4Error(s"IrText.parse：无法解析节点行 '$t'")
        }
      } else throw new P4Error(s"IrText.parse：fn 前的非法行 '$t'")
    }

    // fn 签名的参数类型表
    val fnLine = body.find(_.startsWith("fn ")).getOrElse(throw new P4Error("IrText.parse：缺少 fn 行"))
    val sigParams = fnLine.substring(fnLine.indexOf('(') + 1, fnLine.indexOf(") ->"))
    val sigTypes: Map[String, String] =
      if (sigParams.trim.isEmpty) Map.empty
      else sigParams.split(",").map { p =>
        val c = p.indexOf(':'); (p.substring(0, c).trim, p.substring(c + 1).trim)
      }.toMap

    // 参数元数据登记（先于 refOf 定义，避免前向引用）
    val paramMeta = mutable.LinkedHashMap.empty[String, (ParamMeta, Int, Int)] // name -> (meta, w, size)
    def paramMetaOf(nm: String): (ParamMeta, Int, Int) = paramMeta.getOrElse(nm,
      throw new P4Error(s"IrText.parse：引用了未声明的名字 '$nm'"))
    paramDecls.foreach { d =>
      val ty = sigTypes.getOrElse(d.name, throw new P4Error(s"IrText.parse：签名缺少参数 '${d.name}'"))
      d.spec match {
        case s if s.startsWith("in:") =>
          paramMeta(d.name) = (InMeta(s.stripPrefix("in:").split("\\.").toSeq), wOf(ty), 0)
        case s if s.startsWith("reg:") => paramMeta(d.name) = (RegMeta(s.stripPrefix("reg:")), wOf(ty), sizeOf(ty))
        case s => throw new P4Error(s"IrText.parse：未知参数元数据 '$s'")
      }
    }

    // 构建 DAG
    val b = new Ir.Builder
    val nameToId = mutable.HashMap.empty[String, Ir.NodeId]
    def refOf(nm: String): Ir.NodeId = nameToId.getOrElseUpdate(nm, {
      val (meta, w, _) = paramMetaOf(nm)
      meta match {
        case InMeta(path) => b.add(Ir.InputRef(path, w))
        case RegMeta(_) => throw new P4Error(s"IrText.parse：寄存器参数 '$nm' 不能直接引用（须 array_index）")
      }
    })

    def intArg(args: String, key: String): Int =
      args.split(",").map(_.trim).filter(_.startsWith(s"$key=")).map(_.stripPrefix(s"$key=").trim.toInt)
        .headOption.getOrElse(throw new P4Error(s"IrText.parse：缺少命名参数 $key"))

    nodeDecls.foreach { case (nm, ty, op, args) =>
      val pos = args.split(",").map(_.trim).filterNot(_.contains('=')).filter(_.nonEmpty).toSeq
      val node: Ir.Node = op match {
        case "literal" => Ir.Const(BigInt(intArg(args, "value")), wOf(ty))
        case "zero_ext" => Ir.Zext(refOf(pos(0)), intArg(args, "new_bit_count"))
        case "bit_slice" =>
          val st = intArg(args, "start"); val wd = intArg(args, "width")
          Ir.Slice(refOf(pos(0)), st + wd - 1, st) // Trunc 规范化为 Slice(w-1,0)
        case "concat" => Ir.Cat(pos.map(refOf), wOf(ty))
        case "not" => Ir.Not(refOf(pos(0)), wOf(ty))
        case "sel" =>
          """^(\w+), cases=\[(\w+), (\w+)\]$""".r.findAllMatchIn(args).toList match {
            case m :: Nil =>
              Ir.Mux(refOf(m.group(1)), refOf(m.group(3)), refOf(m.group(2)), wOf(ty)) // cases=[假, 真]
            case _ => throw new P4Error(s"IrText.parse：无法解析 sel 参数 '$args'")
          }
        case "array_index" =>
          paramMetaOf(pos(0))._1 match {
            case RegMeta(inst) =>
              val (_, w, size) = paramMetaOf(pos(0))
              // XLS 要求索引写作关键字参数 indices=[x]：kArrayIndex 的 ArgParser
              // arity=1 且 indices 是 mandatory keyword，位置写法会被拒绝
              // （见 xls/ir/ir_parser.cc ArgParser::Run / case Op::kArrayIndex）。
              val idxRef = """indices=\[(\w+)\]""".r
                .findFirstMatchIn(args)
                .map(_.group(1))
                .getOrElse(throw new P4Error(
                  s"IrText.parse：array_index 缺少 indices=[...] 关键字参数：'$args'"))
              Ir.RegRead(inst, refOf(idxRef), w, size)
            case _ => throw new P4Error(s"IrText.parse：array_index 首参不是寄存器参数 '${pos(0)}'")
          }
        case o if BinOps.contains(o) => Ir.Bin(BinOps(o), refOf(pos(0)), refOf(pos(1)), wOf(ty))
        case o => throw new P4Error(s"IrText.parse：未知算子 '$o'")
      }
      nameToId(nm) = b.add(node)
    }

    // Sink 重建（ret 元素按 p4c-sinks 描述顺序解释）
    def elemsOf(desc: String): Int = if (desc.startsWith("out:")) 1 else 2
    if (retElems.size != sinkDesc.map(elemsOf).sum)
      throw new P4Error("IrText.parse：ret tuple 元素数与 p4c-sinks 描述不符")
    val outputs = mutable.ArrayBuffer.empty[Ir.Sink]
    var i = 0
    sinkDesc.foreach { desc =>
      val a = desc.split(":")
      if (a(0) == "out") {
        val id = refOf(retElems(i))
        // 声明宽度取自描述尾字段（与节点宽可不同——掩码语义，Interp eval 按此截）
        outputs += Ir.OutputWrite(a(1).split("\\.").toSeq, id, a.last.toInt)
        i += 1
      } else {
        val idx = refOf(retElems(i)); val v = refOf(retElems(i + 1))
        val size = a(2).toInt
        val w = a.last.toInt
        a(0) match {
          case "reg" => outputs += Ir.RegWrite(a(1), idx, v, w, size)
          case "cnt" => outputs += Ir.CounterAdd(a(1), idx, v, w, size)
          case o => throw new P4Error(s"IrText.parse：未知 sink 种类 '$o'")
        }
        i += 2
      }
    }

    val stages = stagesByName.flatMap { case (nm, st) => nameToId.get(nm).map(id => id -> st) }.toMap
    Parsed(pkg, fnName, b.finish(outputs.toSeq).copy(stages = stages))
  }
}
