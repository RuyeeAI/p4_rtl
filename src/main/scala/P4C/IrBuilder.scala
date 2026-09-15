package P4C

import P4C.Ast._
import P4C.Ir.{Cat, Const, InputRef, Mux, NodeId, OutputWrite, Sink, Add, Sub, And, Or, Xor, Shl, Shr, Eq, Neq, Lt, Le, Gt, Ge}

/** AST → ActionDAG 降级，含宽度推断。
  *
  * 宽度规则（P4-16 语义的确定性子集）：
  *   - 带宽字面量取声明宽度；无宽字面量取上下文宽度，无上下文则取最小可容纳宽度；
  *   - `++` 拼接 = 两操作数宽度之和（左侧为高位）；
  *   - 移位结果宽度 = 左操作数宽度（模 2^w 截断，与 P4 一致）；
  *   - 其余双目运算结果宽度 = max(左右)，窄方补 Zext/Trunc 节点；
  *   - 赋值时结果显式 fit 到目标字段宽度。
  */
object IrBuilder {

  /** 路径宽度解析：param.structMember[.headerField] */
  final class WidthResolver(
    headerTypes: Map[String, Ast.HeaderType],
    structs: Map[String, Ast.StructType],
    params: Seq[ControlParam],
  ) {
    def widthOf(path: Seq[String]): Int = path match {
      case Seq(pname, mname) =>
        val p = params.find(_.name == pname).getOrElse(throw new P4Error(s"未知的输出路径 '$pname'"))
        val st = structs.getOrElse(p.typeName, throw new P4Error(s"参数 '$pname' 类型 '${p.typeName}' 不是 struct"))
        st.members.find(_.name == mname) match {
          case Some(m) if m.isBits => m.bitsWidth
          case Some(m) => throw new P4Error(s"路径 '${path.mkString(".")}' 缺少字段名（header 实例）")
          case None => throw new P4Error(s"struct '${st.name}' 无成员 '$mname'")
        }
      case Seq(pname, mname, fname) =>
        val p = params.find(_.name == pname).getOrElse(throw new P4Error(s"未知的输出路径 '$pname'"))
        val st = structs.getOrElse(p.typeName, throw new P4Error(s"参数 '$pname' 类型 '${p.typeName}' 不是 struct"))
        st.members.find(_.name == mname) match {
          case Some(m) if !m.isBits =>
            val ht = headerTypes.getOrElse(m.typeName, throw new P4Error(s"未知 header 类型 '${m.typeName}'"))
            ht.fields.find(_.name == fname).map(_.width).getOrElse(throw new P4Error(s"header '${ht.name}' 无字段 '$fname'"))
          case _ => throw new P4Error(s"路径 '${path.mkString(".")}' 过长")
        }
      case _ => throw new P4Error(s"不支持的路径 '${path.mkString(".")}'（支持两段或三段）")
    }
  }

  /** 绑定环境：action 形参 → 实参节点。 */
  type Bindings = Map[String, (NodeId, Int)]

  private def minBits(v: BigInt): Int = math.max(1, v.bitLength)

  final class ExprLowering(resolver: WidthResolver, b: Ir.Builder, externs: Map[String, ExternInst] = Map.empty) {

    /** 表达式宽度（不降级），用于 RegRead 索引 fit；先查 action 形参绑定。 */
    def exprWidth(e: Expr, binds: Bindings): Int = e match {
      case Name(path, _) if path.length == 1 && binds.contains(path.head) => binds(path.head)._2
      case _ => infer(e, None)
    }

    private def infer(e: Expr, ctx: Option[Int]): Int = e match {
      case Num(v, wOpt, _) => wOpt.orElse(ctx).getOrElse(minBits(v))
      case Name(path, _) => resolver.widthOf(path)
      case Slice(_, hi, lo, _) => hi - lo + 1
      case Cast(w, _, _) => w
      case Un("!", _, _) => 1 // 逻辑非降级为 1 位比较（x == 0），见 lower
      case Un(_, inner, _) => infer(inner, ctx)
      case Bin("++", l, r, _) => infer(l, None) + infer(r, None)
      case Bin(op @ ("<<" | ">>"), l, _, _) => infer(l, ctx)
      case Bin(op @ ("==" | "!=" | "<" | "<=" | ">" | ">="), _, _, _) => 1
      case Bin(op @ ("&&" | "||"), _, _, _) => 1
      case Bin(_, l, r, _) => math.max(infer(l, ctx), infer(r, ctx))
      case Ternary(_, t, f, _) => math.max(infer(t, ctx), infer(f, ctx))
    }

    def lower(e: Expr, ctx: Option[Int], binds: Bindings): (NodeId, Int) = e match {
      case Num(v, wOpt, ln) =>
        val w = wOpt.orElse(ctx).getOrElse(minBits(v))
        val mask = (BigInt(1) << w) - 1
        if (v < 0) throw new P4Error(s"行 $ln：不支持负数字面量")
        if (v > mask) throw new P4Error(s"行 $ln：字面量 $v 超出 ${w} 位范围")
        (b.add(Const(v, w)), w)

      case Name(path, ln) =>
        binds.get(path.head) match {
          case Some((id, w)) =>
            if (path.length == 1) (id, w)
            else throw new P4Error(s"行 $ln：暂不支持对 action 参数再取字段 '$path'")
          case None =>
            val w = resolver.widthOf(path)
            (b.add(InputRef(path, w)), w)
        }

      case Slice(inner, hi, lo, ln) =>
        val (id, w) = lower(inner, None, binds)
        if (hi >= w || lo < 0 || lo > hi) throw new P4Error(s"行 $ln：切片 [$hi:$lo] 超出宽度 $w")
        (b.add(Ir.Slice(id, hi, lo)), hi - lo + 1)

      case Cast(n, inner, _) =>
        val (id, w) = lower(inner, Some(n), binds)
        (b.fit(id, w, n), n)

      case Un("~", inner, ln) =>
        val (id, w) = lower(inner, ctx, binds)
        if (w == 0) throw new P4Error(s"行 $ln：~ 作用于 0 宽表达式")
        (b.add(Ir.Not(id, w)), w)

      case Un("!", inner, ln) =>
        // W0（缺口分析 R4）：逻辑非 ≡ (x == 0)。ctx 置 None——比较值与位宽无关，
        // 直接复用 == 降级路径（宽度 1、值 0/1），口径零漂移、零新增节点类型。
        // 此前 Un("!") 无 case 直接 MatchError。
        lower(Bin("==", inner, Num(0, None, ln), ln), None, binds)

      case Un("-", inner, ln) =>
        // W0：一元负号 = 模 2^w 加法逆元。必须在操作数自身宽度 w 上构造 0 - x——
        // 若让 0 常量吃到上下文宽，(0 - x) mod 2^ctx ≠ (2^w - x) 的零扩
        //（例：-3 按 8 位 = 253，而 (0-3) mod 2^16 = 65533）。
        val (id, w) = lower(inner, None, binds)
        val z = b.add(Const(0, w))
        b.bin(Ir.Sub, (z, w), (id, w))

      case Ternary(c, t, f, ln) =>
        val (cid, cw) = lower(c, None, binds)
        if (cw != 1) throw new P4Error(s"行 $ln：三元条件必须是 1 位")
        val (tid, tw) = lower(t, ctx, binds)
        val (fid, fw) = lower(f, ctx, binds)
        val w = math.max(tw, fw)
        (b.add(Mux(cid, b.fit(tid, tw, w), b.fit(fid, fw, w), w)), w)

      case Call(path, args, ln) =>
        if (path.length == 2 && path(1) == "read" && externs.contains(path.head)) {
          val e = externs(path.head)
          if (e.kind != "Register") throw new P4Error(s"行 $ln：'$path' 不是 Register（无法 read）")
          if (args.length != 1) throw new P4Error(s"行 $ln：read 需要 1 个索引参数")
          val (idx, _) = lower(args.head, None, binds)
          b.regRead(e.name, idx, exprWidth(args.head, binds), e.width, e.size)
        } else throw new P4Error(s"行 $ln：不支持的方法调用 '$path'（仅支持 extern read）")

      case Bin(op, l, r, ln) => op match {
        case "++" =>
          val (lid, lw) = lower(l, None, binds)
          val (rid, rw) = lower(r, None, binds)
          (b.add(Cat(Seq(lid, rid), lw + rw)), lw + rw)
        case "<<" | ">>" =>
          val (lid, lw) = lower(l, ctx, binds)
          val (rid, _) = lower(r, None, binds)
          val irOp = if (op == "<<") Shl else Shr
          (b.add(Ir.Bin(irOp, lid, rid, lw)), lw)
        case "==" | "!=" | "<" | "<=" | ">" | ">=" =>
          val (lid, lw) = lower(l, None, binds)
          val (rid, rw) = lower(r, None, binds)
          val w = math.max(lw, rw)
          val li = b.fit(lid, lw, w)
          val ri = b.fit(rid, rw, w)
          val irOp = op match {
            case "==" => Eq; case "!=" => Neq; case "<" => Lt
            case "<=" => Le; case ">" => Gt; case _ => Ge
          }
          (b.add(Ir.Bin(irOp, li, ri, 1)), 1)
        case "&&" | "||" =>
          val (lid, lw) = lower(l, None, binds)
          val (rid, rw) = lower(r, None, binds)
          val li = b.fit(lid, lw, 1)
          val ri = b.fit(rid, rw, 1)
          (b.add(Ir.Bin(if (op == "&&") And else Or, li, ri, 1)), 1)
        case "+" | "-" | "&" | "|" | "^" =>
          val (lid, lw) = lower(l, ctx, binds)
          val (rid, rw) = lower(r, ctx, binds)
          val irOp = op match {
            case "+" => Add; case "-" => Sub; case "&" => And; case "|" => Or; case _ => Xor
          }
          b.bin(irOp, (lid, lw), (rid, rw))
        case "*" | "/" | "%" =>
          throw new P4Error(s"行 $ln：暂不支持运算符 '$op'（子集限制）")
      }
    }

    /** 赋值降级：结果 fit 到目标字段宽度。 */
    def lowerAssign(path: Seq[String], expr: Expr, binds: Bindings): OutputWrite = {
      val tw = resolver.widthOf(path)
      val (id, w) = lower(expr, Some(tw), binds)
      OutputWrite(path, b.fit(id, w, tw), tw)
    }

    /** extern 方法调用语句降级：write / count。 */
    def lowerMethodCall(mc: MethodCall, binds: Bindings): Sink = {
      val e = externs.getOrElse(mc.inst, throw new P4Error(s"行 ${mc.line}：未知 extern 实例 '${mc.inst}'"))
      mc.method match {
        case "write" =>
          if (e.kind != "Register") throw new P4Error(s"行 ${mc.line}：'${mc.inst}' 不是 Register（无法 write）")
          if (mc.args.length != 2) throw new P4Error(s"行 ${mc.line}：write 需要 (index, value) 两个参数")
          val (idx, _) = lower(mc.args(0), None, binds)
          val (v, vw) = lower(mc.args(1), Some(e.width), binds)
          b.regWrite(e.name, idx, exprWidth(mc.args(0), binds), v, vw, e.width, e.size)
        case "count" =>
          if (e.kind != "Counter") throw new P4Error(s"行 ${mc.line}：'${mc.inst}' 不是 Counter（无法 count）")
          if (mc.args.length != 1) throw new P4Error(s"行 ${mc.line}：count 需要 1 个索引参数")
          val (idx, _) = lower(mc.args(0), None, binds)
          val d = b.add(Const(1, e.width))
          b.counterAdd(e.name, idx, exprWidth(mc.args(0), binds), d, e.width, e.width, e.size)
        case m => throw new P4Error(s"行 ${mc.line}：extern '${e.kind}' 不支持方法 '$m'")
      }
    }
  }
}
