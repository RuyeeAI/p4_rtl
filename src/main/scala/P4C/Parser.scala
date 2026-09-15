package P4C

import P4C.Ast._
import P4C.Lexer._

final class P4Error(msg: String) extends RuntimeException(msg)

/** P4-16 子集递归下降语法分析。
  *
  * E2：构造时可传入 [[Directive.scan]] 结果；`control`/`parser`/`action` 声明
  * 解析时按紧邻性（指示行与声明行之间只允许空白行）匹配指示，写入
  * `decl.stagesOpt`。被声明认领的指示行记入 [[claimed]]，解析结束后未认领的
  * 指示由 [[Parser$.parseProgramWithDiagnostics]] 生成忽略告警。
  */
class Parser(toks0: Seq[Tok], scan: Directive.ScanResult = Directive.ScanResult.empty) {
  private val toks = toks0.toArray
  private var pos = 0

  /** 已被某个声明认领的指示行号（1 基），供孤儿指示告警。 */
  val claimed: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set.empty[Int]

  /** 声明行 declLine 上的声明级切拍指示（紧邻性校验见 [[Directive.stageFor]]）。 */
  private def directiveAt(declLine: Int): Option[Int] =
    Directive.stageFor(scan, declLine, claimed)

  private def cur: Tok = toks(pos)
  private def line: Int = cur.line
  private def err(msg: String): Nothing = throw new P4Error(s"语法错误（行 ${cur.line}）：$msg（附近: '${cur.text}'）")

  private def is(s: String): Boolean = cur.text == s && (cur.kind == TSym || cur.kind == TIdent)
  private def isIdent(s: String): Boolean = cur.kind == TIdent && cur.text == s
  private def eat(s: String): Unit = { if (!is(s)) err(s"期望 '$s'"); pos += 1 }
  private def eatIdent(): String = { if (cur.kind != TIdent) err("期望标识符"); val r = cur.text; pos += 1; r }
  private def eatNum(): BigInt = { if (cur.kind != TNum) err("期望数字"); val v = cur.value.get; pos += 1; v }
  private def accept(s: String): Boolean = { if (is(s)) { pos += 1; true } else false }

  private def atTypeStart: Boolean =
    (cur.kind == TIdent && (cur.text == "bit" || cur.text == "int" || cur.text == "varbit")) ||
      (cur.kind == TIdent && toks(pos + 1).kind == TIdent && toks(pos + 2).kind == TSym && toks(pos + 2).text == ";")

  private def parseType(): P4Type = {
    if (isIdent("bit") || isIdent("int") || isIdent("varbit")) {
      val kw = cur.text
      eat(kw)
      eat("<")
      val w = eatNum().toInt
      eat(">")
      // W0 止损（缺口分析 R1/R3）：int<W>/varbit<W> 曾被静默归一为无符号定长
      // BitsType——语义错译比报错危险，改为明确拒绝（有符号见 W5、变长见 W6 立项）
      if (kw == "int")
        throw new P4Error(s"行 $line：暂不支持有符号类型 int<$w>（子集限制：请改用 bit<$w>，支持计划见缺口分析 W5）")
      if (kw == "varbit")
        throw new P4Error(s"行 $line：暂不支持变长类型 varbit<$w>（子集限制：数据面字段定长，支持计划见缺口分析 W6）")
      BitsType(w)
    } else NamedType(eatIdent())
  }

  // ---------------- 程序 ----------------

  def parseProgram(): P4Program = {
    val headerTypes = scala.collection.mutable.ArrayBuffer.empty[HeaderType]
    val structs = scala.collection.mutable.ArrayBuffer.empty[StructType]
    val controls = scala.collection.mutable.ArrayBuffer.empty[ControlDecl]
    val parsers = scala.collection.mutable.ArrayBuffer.empty[ParserDecl]
    while (cur.kind != TEOF) {
      if (isIdent("header")) headerTypes += parseHeaderType()
      else if (isIdent("struct")) structs += parseStruct()
      else if (isIdent("control")) controls += parseControl()
      else if (isIdent("parser")) parsers += parseParser()
      // W0 止损（缺口分析 R5）：typedef 曾被静默跳过，引用处报"未知的输出路径"迷惑用户
      else if (isIdent("typedef"))
        throw new P4Error(s"行 $line：暂不支持 typedef（子集限制）：请直接内联类型名 bit<N>（支持计划见缺口分析 W5/W6）")
      // W0（缺口分析 R5）：const bit<N> 解析期求值入 consts，表达式按名替换
      else if (isIdent("const")) parseConstDecl()
      else skipDecl() // package / instantiation 等架构样板：跳过
    }
    P4Program(headerTypes.toSeq, structs.toSeq, controls.toSeq, parsers.toSeq)
  }

  /** 顶层 const 常量表（R5）：name → (值, 声明宽)。[[parsePrimary]] 处按名替换为带宽字面量。 */
  private val consts = scala.collection.mutable.LinkedHashMap.empty[String, (BigInt, Int)]

  /** `const bit<N> NAME = 常量表达式;`（W0，缺口分析 R5）。
    * 可用形式：数字字面量 / ~ / 一元负号 / + - & | ^ << >> / (bit<N>) 转换 / 已声明 const 引用；
    * 非常量初始化（引用字段、切片等）明确报错而非静默跳过。 */
  private def parseConstDecl(): Unit = {
    val ln = line
    eat("const")
    parseType() match {
      case BitsType(w) =>
        val name = eatIdent()
        eat("=")
        val e = parseExpr()
        eat(";")
        constEval(e, w) match {
          case Some(v) => consts(name) = (v, w)
          case None =>
            throw new P4Error(s"行 $ln：const '$name' 初始化必须是常量表达式" +
              "（子集限制：数字/~/一元负号/+/-/&/|/^/<</>>/(bit<N>)/已声明 const）")
        }
      case _ => throw new P4Error(s"行 $ln：const 声明类型必须是 bit<N>")
    }
  }

  /** 常量表达式求值（const 初始化）。返回 None = 不可求值（调用方报错）。
    * 语义 = mod 2^w 环：每个运算前把操作数约简到声明宽（`>>` 必须先约简再移位，
    * 与无符号语义一致；`-` 的补码取逆同理），结果天然落在 [0, 2^w)。
    * 移位量为负报不可求值；移位量 ≥ w 结果为 0（避免巨移位爆内存）。 */
  private def constEval(e: Expr, w: Int): Option[BigInt] = {
    val m = BigInt(1) << w
    def red(v: BigInt): BigInt = v.mod(m)
    e match {
      case Num(v, None, _) => Some(red(v))
      case Num(v, Some(lw), ln) =>
        if (v < 0 || v >= (BigInt(1) << lw))
          throw new P4Error(s"行 $ln：字面量 $v 超出 $lw 位范围")
        Some(red(v))
      case Name(Seq(n), _) => consts.get(n).map { case (v, _) => red(v) }
      case Un("~", inner, _) => constEval(inner, w).map(v => red(~v))
      case Un("-", inner, _) => constEval(inner, w).map(v => red(-v))
      case Cast(nw, inner, _) => constEval(inner, w).map(v => v.mod(BigInt(1) << nw))
      case Bin(op, l, r, _) =>
        constEval(l, w).flatMap { a =>
          constEval(r, w).flatMap { b =>
            op match {
              case "+" => Some(red(a + b))
              case "-" => Some(red(a - b))
              case "&" => Some(a & b)
              case "|" => Some(a | b)
              case "^" => Some(a ^ b)
              case "<<" => if (b < 0) None else Some(if (b >= w) BigInt(0) else red(a << b.toInt))
              case ">>" => if (b < 0) None else Some(if (b >= w) BigInt(0) else a >> b.toInt)
              case _ => None // * / %、比较、拼接、三元等：W0 常量表达式不支持
            }
          }
        }
      case _ => None
    }
  }

  /** 跳过一个顶层声明：前进到与当前位置配对的 ';' 或 '{...}' 结束。 */
  private def skipDecl(): Unit = {
    // 跳过声明头（可能带注解 @...）直到 '{' 或 ';'
    var depth = 0
    var seenBrace = false
    while (cur.kind != TEOF) {
      if (cur.text == "{") { depth += 1; seenBrace = true; pos += 1 }
      else if (cur.text == "}") {
        depth -= 1; pos += 1
        if (seenBrace && depth == 0) return
      } else if (cur.text == ";" && depth == 0) { pos += 1; return }
      else pos += 1
    }
  }

  private def parseHeaderType(): HeaderType = {
    eat("header"); val name = eatIdent(); eat("{")
    val fields = scala.collection.mutable.ArrayBuffer.empty[HeaderField]
    while (!is("}")) {
      val t = parseType()
      val fname = eatIdent()
      eat(";")
      t match {
        case BitsType(w) => fields += HeaderField(fname, w)
        case _ => err("header 字段必须是 bit<N>")
      }
    }
    eat("}")
    HeaderType(name, fields.toSeq)
  }

  private def parseStruct(): StructType = {
    eat("struct"); val name = eatIdent(); eat("{")
    val members = scala.collection.mutable.ArrayBuffer.empty[StructMember]
    while (!is("}")) {
      val t = parseType()
      val mname = eatIdent()
      eat(";")
      t match {
        case BitsType(w) => members += StructMember("", isBits = true, w, mname)
        case NamedType(tn) => members += StructMember(tn, isBits = false, 0, mname)
      }
    }
    eat("}")
    StructType(name, members.toSeq)
  }

  // ---------------- parser ----------------

  /** parser 形参：`(packet_in pkt, out headers_t hdr, ...)`。方向可省略（如 packet_in）。 */
  private def parseParser(): ParserDecl = {
    val ln = line
    eat("parser")
    val name = eatIdent()
    eat("(")
    val params = scala.collection.mutable.ArrayBuffer.empty[ControlParam]
    def parseOneParam(): ControlParam = {
      val dir = if (isIdent("inout") || isIdent("out") || isIdent("in")) { val d = cur.text; pos += 1; d }
                else "in" // packet_in pkt：无方向关键字
      val tn = eatIdent()
      val pn = eatIdent()
      ControlParam(pn, dir, tn, line)
    }
    if (!is(")")) {
      params += parseOneParam()
      while (accept(",")) params += parseOneParam()
    }
    eat(")")
    eat("{")
    val states = scala.collection.mutable.ArrayBuffer.empty[ParserState]
    while (isIdent("state")) {
      eat("state")
      val sname = eatIdent()
      eat("{")
      val stmts = parseParserStmtsUntil("}")
      eat("}")
      states += ParserState(sname, stmts.toSeq, line)
    }
    eat("}")
    ParserDecl(name, params.toSeq, states.toSeq, ln, directiveAt(ln))
  }

  /** parser 状态内语句：extract / transition / select / 普通赋值。 */
  private def parseParserStmtsUntil(end: String): Seq[Stmt] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Stmt]
    while (!is(end) && cur.kind != TEOF) {
      val ln = line
      if (isIdent("transition")) {
        eat("transition")
        if (isIdent("select")) {
          eat("select")
          eat("(")
          val value = parseExpr()
          eat(")")
          eat("{")
          val cases = scala.collection.mutable.ArrayBuffer.empty[(Expr, String)]
          var deft = "accept"
          while (!is("}") && cur.kind != TEOF) {
            if (isIdent("default")) {
              eat("default"); eat(":"); deft = eatIdent(); eat(";")
            } else {
              val pat = parseExpr()
              eat(":")
              val tgt = eatIdent()
              eat(";")
              cases += ((pat, tgt))
            }
          }
          eat("}")
          out += Select(value, cases.toSeq, deft, ln)
        } else {
          val target = eatIdent()
          eat(";")
          out += Goto(target, ln)
        }
      } else {
        // pkt.extract(hdr.x); / hdr.setValid(); / 普通赋值
        val first = eatIdent()
        if (accept(".")) {
          val seg = eatIdent()
          if (accept("(")) {
            if (seg == "extract") {
              // 参数：hdr.instance
              val p0 = eatIdent()
              val ppath = scala.collection.mutable.ArrayBuffer(p0)
              while (accept(".")) ppath += eatIdent()
              eat(")")
              eat(";")
              out += Extract(ppath.toSeq, ln)
            } else {
              // W0 止损（缺口分析 R6）：setValid() 等无参方法曾被静默跳过——
              // 子集无 header valid 位模型，静默丢失语义比报错危险（正式支持见 W6）
              if (!is(")")) err(s"parser 中不支持带参方法 '$seg'")
              throw new P4Error(
                s"行 $ln：暂不支持 '$first.$seg()'（子集限制：无 header valid 位模型，支持计划见缺口分析 W6）")
            }
          } else {
            // 字段访问开头，回落到普通赋值解析
            val path = scala.collection.mutable.ArrayBuffer(first, seg)
            while (accept(".")) {
              val nx = eatIdent()
              // W0 止损（缺口分析 R6）：路径中段方法调用 x.hh.setValid() 形态——
              // 此前落到赋值解析误报"期望 '='"，现与单段形态同口径明确拒绝
              if (accept("(")) {
                if (!is(")")) err(s"parser 中不支持带参方法 '$nx'")
                throw new P4Error(
                  s"行 $ln：暂不支持 '${path.mkString(".")}.$nx()'（子集限制：无 header valid 位模型，支持计划见缺口分析 W6）")
              }
              path += nx
            }
            eat("=")
            val e = parseExpr()
            eat(";")
            out += Assign(path.toSeq, e, ln)
          }
        } else {
          err(s"parser 状态中不支持的语句开头 '$first'")
        }
      }
    }
    out.toSeq
  }

  // ---------------- control ----------------

  private def parseControl(): ControlDecl = {
    val declLine = line
    eat("control")
    val name = eatIdent()
    // 可选类型参数 <...>：跳过
    if (accept("<")) { var d = 1; while (d > 0 && cur.kind != TEOF) { if (cur.text == "<") d += 1 else if (cur.text == ">") d -= 1; pos += 1 } }
    eat("(")
    val params = scala.collection.mutable.ArrayBuffer.empty[ControlParam]
    if (!is(")")) {
      params += {
        val dir = eatIdent() // inout / in / out
        val tn = eatIdent()
        val pn = eatIdent()
        ControlParam(pn, dir, tn, line)
      }
      while (accept(",")) {
        val dir = eatIdent()
        val tn = eatIdent()
        val pn = eatIdent()
        params += ControlParam(pn, dir, tn, line)
      }
    }
    eat(")")
    eat("{")
    val actions = scala.collection.mutable.ArrayBuffer.empty[ActionDecl]
    val tables = scala.collection.mutable.ArrayBuffer.empty[TableDecl]
    val externs = scala.collection.mutable.ArrayBuffer.empty[ExternInst]
    var applyBody: Seq[Stmt] = Seq.empty
    while (!is("}")) {
      if (isIdent("action")) actions += parseAction()
      else if (isIdent("table")) tables += parseTable()
      else if (isIdent("Register") || isIdent("Counter")) externs += parseExternInst()
      else if (isIdent("apply")) {
        eat("apply"); eat("{"); applyBody = parseStmtsUntil("}")
        eat("}") // 消费 apply 块自己的 '}'（修复：此前遗留给了 control 的 eat("}")，
                 // 导致 control 真正的收尾 '}' 被 skipDecl 连同后续声明一并吞掉）
      }
      else skipStmt() // 未知语句（注解、default_action 残留等）
    }
    eat("}")
    ControlDecl(name, params.toSeq, actions.toSeq, tables.toSeq, externs.toSeq, applyBody, line, directiveAt(declLine))
  }

  /** `Register(bit<16>, 8) name;` / `Counter(bit<32>, 8) name;` */
  private def parseExternInst(): ExternInst = {
    val ln = line
    val kind = eatIdent()
    if (kind != "Register" && kind != "Counter") err(s"未知 extern 类型 '$kind'")
    eat("(")
    val w = parseType() match {
      case BitsType(w) => w
      case _ => err("extern 元素类型必须是 bit<N>")
    }
    eat(",")
    val size = eatNum().toInt
    eat(")")
    val name = eatIdent()
    eat(";")
    ExternInst(kind, w, size, name, ln)
  }

  private def skipStmt(): Unit = {
    var depth = 0
    while (cur.kind != TEOF) {
      val t = cur.text
      pos += 1
      if (t == "{") depth += 1
      else if (t == "}") { depth -= 1; if (depth <= 0) return }
      else if (t == ";" && depth == 0) return
    }
  }

  private def parseAction(): ActionDecl = {
    val ln = line
    eat("action")
    val name = eatIdent()
    eat("(")
    val params = scala.collection.mutable.ArrayBuffer.empty[Param]
    if (!is(")")) {
      params += {
        val t = parseType() match {
          case BitsType(w) => w
          case _ => err("action 参数必须是 bit<N>")
        }
        val pn = eatIdent()
        Param(pn, t, line)
      }
      while (accept(",")) {
        val t = parseType() match {
          case BitsType(w) => w
          case _ => err("action 参数必须是 bit<N>")
        }
        val pn = eatIdent()
        params += Param(pn, t, line)
      }
    }
    eat(")")
    eat("{")
    val body = parseStmtsUntil("}")
    eat("}")
    ActionDecl(name, params.toSeq, body, ln, directiveAt(ln))
  }

  private def parseTable(): TableDecl = {
    val ln = line
    eat("table")
    val name = eatIdent()
    // 运行时表指示（// p4c: table <表名> runtime [size=N]）：紧邻性匹配 + 表名冗余校验
    val rt = Directive.tableFor(scan, ln, claimed)
    rt.foreach { d =>
      if (d.name != name)
        throw new P4Error(
          s"行 $ln：'// p4c: table ${d.name} runtime' 指示的表名与声明 '$name' 不一致")
    }
    eat("{")
    val keys = scala.collection.mutable.ArrayBuffer.empty[KeyElem]
    val actions = scala.collection.mutable.ArrayBuffer.empty[String]
    val entries = scala.collection.mutable.ArrayBuffer.empty[TableEntry]
    while (!is("}")) {
      if (isIdent("key")) {
        eat("key"); eat("="); eat("{")
        while (!is("}")) {
          val e = parseExpr()
          eat(":")
          val kind = eatIdent()
          // W0 止损（缺口分析 R2）：未知 match kind 曾被静默按 exact 处理——错译比报错危险
          if (kind != "exact")
            throw new P4Error(s"行 $ln：暂不支持 match kind '$kind'（子集限制：仅 exact，支持计划见缺口分析 W3）")
          eat(";")
          keys += KeyElem(e, kind, ln)
        }
        eat("}")
      } else if (isIdent("actions")) {
        eat("actions"); eat("="); eat("{")
        while (!is("}")) { actions += eatIdent(); eat(";") }
        eat("}")
      } else if (isIdent("const") && toks(pos + 1).text == "entries") {
        eat("const"); eat("entries"); eat("="); eat("{")
        while (!is("}")) entries += parseTableEntry()
        eat("}")
      } else skipStmt()
    }
    eat("}")
    TableDecl(name, keys.toSeq, actions.toSeq, entries.toSeq, ln,
      isRuntime = rt.isDefined, runtimeSize = rt.map(_.size).getOrElse(0))
  }

  private def parseTableEntry(): TableEntry = {
    val ln = line
    val isDefault = isIdent("default")
    val keys = scala.collection.mutable.ArrayBuffer.empty[Expr]
    if (isDefault) eat("default")
    else {
      keys += parseExpr()
      while (accept(",")) keys += parseExpr()
    }
    eat(":")
    val act = eatIdent()
    val args = scala.collection.mutable.ArrayBuffer.empty[Expr]
    if (accept("(")) {
      if (!is(")")) {
        args += parseExpr()
        while (accept(",")) args += parseExpr()
      }
      eat(")")
    }
    eat(";")
    TableEntry(keys.toSeq, isDefault, act, args.toSeq, ln)
  }

  // ---------------- 语句 ----------------

  private def parseStmtsUntil(end: String): Seq[Stmt] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Stmt]
    while (!is(end) && cur.kind != TEOF) out += parseStmt()
    out.toSeq
  }

  private def parseStmt(): Stmt = {
    val ln = line
    // 变量声明：bit<N> name = expr?; 或 TypeName name = expr?;
    if (atTypeStart && !(cur.kind == TIdent && toks(pos+1).kind == TSym && toks(pos+1).text == ".")) {
      parseType() match {
        case BitsType(w) =>
          val n = eatIdent()
          val init = if (accept("=")) Some(parseExpr()) else None
          eat(";")
          return VarDecl(n, w, init, ln)
        case _ => err("局部变量必须是 bit<N>")
      }
    }
    // 路径开头
    val first = eatIdent()
    if (accept("(")) { // action 调用
      val args = scala.collection.mutable.ArrayBuffer.empty[Expr]
      if (!is(")")) {
        args += parseExpr()
        while (accept(",")) args += parseExpr()
      }
      eat(")")
      eat(";")
      return ActionCall(first, args.toSeq, ln)
    }
    val path = scala.collection.mutable.ArrayBuffer(first)
    while (accept(".")) {
      val seg = eatIdent()
      if (accept("(")) { // tbl.apply() / inst.write(...)/inst.count(...)
        val args = scala.collection.mutable.ArrayBuffer.empty[Expr]
        if (!is(")")) {
          args += parseExpr()
          while (accept(",")) args += parseExpr()
        }
        eat(")")
        eat(";")
        if (seg == "apply") return TableApply(first, ln)
        return MethodCall(first, seg, args.toSeq, ln)
      }
      path += seg
    }
    // 切片左值？hdr.f[3:0] = x —— M1 不支持，报错
    if (is("[")) err("暂不支持对切片赋值")
    eat("=")
    val e = parseExpr()
    eat(";")
    Assign(path.toSeq, e, ln)
  }

  // ---------------- 表达式（优先级：三元 < 拼接 < || < && < | < ^ < & < 相等 < 关系 < 移位 < 加减 < 乘除 < 一元） ----------------

  def parseExpr(): Expr = parseTernary()

  private def parseTernary(): Expr = {
    val c = parseConcat()
    if (accept("?")) {
      val ln = line
      val t = parseExpr()
      eat(":")
      val f = parseTernary()
      Ternary(c, t, f, ln)
    } else c
  }

  private def parseConcat(): Expr = {
    var l = parseOr()
    while (is("++")) { val ln = line; pos += 1; val r = parseOr(); l = Bin("++", l, r, ln) }
    l
  }

  private def parseOr(): Expr = {
    var l = parseAnd()
    while (is("||")) { val ln = line; pos += 1; val r = parseAnd(); l = Bin("||", l, r, ln) }
    l
  }

  private def parseAnd(): Expr = {
    var l = parseBitor()
    while (is("&&")) { val ln = line; pos += 1; val r = parseBitor(); l = Bin("&&", l, r, ln) }
    l
  }

  private def parseBitor(): Expr = {
    var l = parseBitxor()
    while (is("|") && !is("||")) { val ln = line; pos += 1; val r = parseBitxor(); l = Bin("|", l, r, ln) }
    l
  }

  private def parseBitxor(): Expr = {
    var l = parseBitand()
    while (is("^")) { val ln = line; pos += 1; val r = parseBitand(); l = Bin("^", l, r, ln) }
    l
  }

  private def parseBitand(): Expr = {
    var l = parseEq()
    while (is("&") && !is("&&")) { val ln = line; pos += 1; val r = parseEq(); l = Bin("&", l, r, ln) }
    l
  }

  private def parseEq(): Expr = {
    var l = parseRel()
    while (is("==") || is("!=")) { val ln = line; val op = cur.text; pos += 1; val r = parseRel(); l = Bin(op, l, r, ln) }
    l
  }

  private def parseRel(): Expr = {
    var l = parseShift()
    while (is("<") && !is("<<") || is(">") && !is(">>") || is("<=") || is(">=")) {
      val ln = line; val op = cur.text; pos += 1; val r = parseShift(); l = Bin(op, l, r, ln)
    }
    l
  }

  private def parseShift(): Expr = {
    var l = parseAdd()
    while (is("<<") || is(">>")) { val ln = line; val op = cur.text; pos += 1; val r = parseAdd(); l = Bin(op, l, r, ln) }
    l
  }

  private def parseAdd(): Expr = {
    var l = parseMul()
    while (is("+") || is("-")) { val ln = line; val op = cur.text; pos += 1; val r = parseMul(); l = Bin(op, l, r, ln) }
    l
  }

  private def parseMul(): Expr = {
    var l = parseUnary()
    while (is("*") || is("/") || is("%")) { val ln = line; val op = cur.text; pos += 1; val r = parseUnary(); l = Bin(op, l, r, ln) }
    l
  }

  private def parseUnary(): Expr = {
    if (is("~")) { val ln = line; pos += 1; Un("~", parseUnary(), ln) }
    else if (is("!")) { val ln = line; pos += 1; Un("!", parseUnary(), ln) }
    // W0（缺口分析 W0）：一元负号——语义 = 模 2^w 加法逆元（IrBuilder 在操作数自身宽上构造 0-x）
    else if (is("-")) { val ln = line; pos += 1; Un("-", parseUnary(), ln) }
    else parsePostfix()
  }

  private def parsePostfix(): Expr = {
    var e = parsePrimary()
    while (is("[")) {
      val ln = line
      pos += 1
      val hi = eatNum().toInt
      eat(":")
      val lo = eatNum().toInt
      eat("]")
      e = Slice(e, hi, lo, ln)
    }
    e
  }

  private def parsePrimary(): Expr = {
    val ln = line
    cur match {
      case Tok(TNum, _, Some(v), w, _) => pos += 1; Num(v, w, ln)
      case Tok(TSym, "(", _, _, _) =>
        pos += 1
        if (isIdent("bit") || isIdent("int")) { // 类型转换 (bit<N>)expr
          val kw = cur.text
          eat(kw); eat("<")
          val w = eatNum().toInt
          eat(">"); eat(")")
          // W0 止损（缺口分析 R1）：有符号转换与类型声明同口径拒绝
          if (kw == "int")
            throw new P4Error(s"行 $ln：暂不支持有符号类型转换 (int<$w>)（子集限制，支持计划见缺口分析 W5）")
          Cast(w, parseUnary(), ln)
        } else {
          val e = parseExpr(); eat(")"); e
        }
      case Tok(TIdent, _, _, _, _) =>
        val first = eatIdent()
        val path = scala.collection.mutable.ArrayBuffer(first)
        while (accept(".")) path += eatIdent()
        if (is("(")) { // extern 读方法：inst.read(idx)
          pos += 1
          val args = scala.collection.mutable.ArrayBuffer.empty[Expr]
          if (!is(")")) {
            args += parseExpr()
            while (accept(",")) args += parseExpr()
          }
          eat(")")
          Call(path.toSeq, args.toSeq, ln)
        } else {
          // W0（缺口分析 R5）：顶层 const 按名替换为带宽字面量（保留声明宽；
          // 单段名此前必然 widthOf 报错，替换无回归风险）。自身初始化器内引用
          // 自引用 const 时 consts 尚未登记 → constEval 报"必须是常量表达式"。
          if (path.length == 1 && consts.contains(first)) {
            val (v, w) = consts(first)
            return Num(v, Some(w), ln)
          }
          Name(path.toSeq, ln)
        }
      case _ => err("期望表达式")
    }
  }
}

object Parser {
  /** 解析 + 编译指示诊断：返回（程序, 告警列表）。告警两类：
    * ① 位于块注释内的指示样文本（注释掉的指示，不生效——宽容策略：不报错，
    * 不阻断编译）；② 未紧邻任何 control/parser/action 声明而被忽略的指示。 */
  def parseProgramWithDiagnostics(src: String): (P4Program, Seq[String]) = {
    val scan = Directive.scan(src)
    val p = new Parser(Lexer.tokenize(Preprocess(src)), scan)
    val prog = p.parseProgram()
    val suppressed = scan.suppressedInBlock.map { case (l, txt) =>
      s"[P4C] 警告：行 $l 的 '// p4c:' 指示样文本位于块注释内（'$txt'），已忽略"
    }
    val orphanStages = scan.directives.toList.sortBy(_._1).collect {
      case (l, n) if !p.claimed.contains(l) =>
        s"[P4C] 警告：行 $l 的 '// p4c: stages=$n' 指示未紧邻 control/parser/action 声明" +
          "（中间隔了代码/注释行），已忽略"
    }
    val orphanTables = scan.tableDirectives.toList.sortBy(_._1).collect {
      case (l, d) if !p.claimed.contains(l) =>
        s"[P4C] 警告：行 $l 的 '// p4c: table ${d.name} runtime' 指示未紧邻 table 声明" +
          "（中间隔了代码/注释行），已忽略"
    }
    (prog, suppressed ++ orphanStages ++ orphanTables)
  }

  /** 便捷入口：忽略告警（历史签名，行为不变）。 */
  def parseProgram(src: String): P4Program = parseProgramWithDiagnostics(src)._1
}
