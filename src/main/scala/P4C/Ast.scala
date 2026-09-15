package P4C

/** P4-16 子集 AST。
  *
  * 覆盖范围（M1~M3）：header 类型、struct、control（action/table/apply）、
  * parser（extract/transition/select）。不支持 TNA/PSA 等厂商架构。
  */
object Ast {

  sealed trait P4Type
  case class BitsType(width: Int) extends P4Type
  case class NamedType(name: String) extends P4Type

  final case class HeaderType(name: String, fields: Seq[HeaderField])
  final case class HeaderField(name: String, width: Int)

  /** struct 成员：成员类型（header 类型名或 bit<N>）+ 成员名 */
  final case class StructType(name: String, members: Seq[StructMember])
  final case class StructMember(typeName: String, isBits: Boolean, bitsWidth: Int, name: String)

  // ---------------- 表达式 ----------------

  sealed trait Expr {
    def line: Int
  }
  final case class Num(value: BigInt, width: Option[Int], line: Int) extends Expr
  final case class Name(path: Seq[String], line: Int) extends Expr
  final case class Slice(e: Expr, hi: Int, lo: Int, line: Int) extends Expr
  final case class Cast(width: Int, e: Expr, line: Int) extends Expr
  final case class Ternary(c: Expr, t: Expr, f: Expr, line: Int) extends Expr
  final case class Un(op: String, e: Expr, line: Int) extends Expr // "~"
  final case class Bin(op: String, l: Expr, r: Expr, line: Int) extends Expr
  // op ∈ { + - * & | ^ << >> ++ == != < <= > >= }（- 为双目减；无一元负号）
  /** 方法调用表达式：inst.read(idx) 等 extern 读 */
  final case class Call(path: Seq[String], args: Seq[Expr], line: Int) extends Expr

  // ---------------- 语句 ----------------

  sealed trait Stmt {
    def line: Int
  }
  final case class VarDecl(name: String, width: Int, init: Option[Expr], line: Int) extends Stmt
  final case class Assign(path: Seq[String], expr: Expr, line: Int) extends Stmt
  final case class ActionCall(name: String, args: Seq[Expr], line: Int) extends Stmt
  final case class TableApply(name: String, line: Int) extends Stmt
  /** extern 方法调用语句：stats.write(idx, v); hits.count(idx); */
  final case class MethodCall(inst: String, method: String, args: Seq[Expr], line: Int) extends Stmt

  // parser 专用
  final case class Extract(path: Seq[String], line: Int) extends Stmt
  sealed trait TransStmt extends Stmt
  final case class Goto(target: String, line: Int) extends TransStmt
  final case class Select(value: Expr, cases: Seq[(Expr, String)], default: String, line: Int) extends TransStmt

  // ---------------- 声明 ----------------

  final case class Param(name: String, width: Int, line: Int)

  /** E2：`stagesOpt` = 声明级切拍预算覆盖（来自 `// p4c: stages=N` 编译指示，
    * 紧邻声明行之上；None = 无指示，走全局预算）。
    *   - ActionDecl：作用于该 action 的 DAG；
    *   - ControlDecl：作用于该 control 整体（所有 action/直行/表项 DAG）；
    *   - ParserDecl：当前 parser 不切拍，仅记录不生效（见 ChiselBackend.emitParser）。 */
  final case class ActionDecl(name: String, params: Seq[Param], body: Seq[Stmt], line: Int, stagesOpt: Option[Int] = None)

  final case class KeyElem(expr: Expr, matchKind: String, line: Int) // matchKind: exact

  final case class TableEntry(keys: Seq[Expr], isDefault: Boolean, action: String, args: Seq[Expr], line: Int)

  /** `isRuntime` / `runtimeSize`：来自 `// p4c: table <表名> runtime [size=N]` 指示
    * （缺省：静态融合表，size=0）。运行时表编译期只固化结构（表深 / key 宽 /
    * action 编号与参数宽），条目内容运行时可写。 */
  final case class TableDecl(
    name: String,
    keys: Seq[KeyElem],
    actions: Seq[String],
    entries: Seq[TableEntry],
    line: Int,
    isRuntime: Boolean = false,
    runtimeSize: Int = 0,
  )

  final case class ControlParam(name: String, direction: String, typeName: String, line: Int) // direction: inout|in|out

  /** v1model 状态单元实例：Register(bit<W>, N) name; Counter(bit<W>, N) name; */
  final case class ExternInst(kind: String, width: Int, size: Int, name: String, line: Int)

  final case class ControlDecl(
    name: String,
    params: Seq[ControlParam],
    actions: Seq[ActionDecl],
    tables: Seq[TableDecl],
    externs: Seq[ExternInst],
    applyBody: Seq[Stmt],
    line: Int,
    stagesOpt: Option[Int] = None,
  )

  final case class ParserState(name: String, stmts: Seq[Stmt], line: Int)

  final case class ParserDecl(name: String, params: Seq[ControlParam], states: Seq[ParserState], line: Int, stagesOpt: Option[Int] = None)

  final case class P4Program(
    headerTypes: Seq[HeaderType],
    structs: Seq[StructType],
    controls: Seq[ControlDecl],
    parsers: Seq[ParserDecl],
  )
}
