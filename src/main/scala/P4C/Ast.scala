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
  // deparser 专用：pkt.emit(<header 实例>);  —— path 不含 pkt 本身，如 Seq("hdr","ethernet")
  final case class Emit(path: Seq[String], line: Int) extends Stmt
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
    /** 外部表模块的查找延时范围（拍），`// p4c: table X runtime latency=<min>-<max>`。
      * None = 未配置。生成侧写进 IR 通道注释（接口契约）并进报告。 */
    latencyMin: Option[Int] = None,
    latencyMax: Option[Int] = None,
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

  /** 报文重组阶段（标准 P4 的 deparser）：
    *   `control D(packet_out pkt, in headers_t hdr) { pkt.emit(hdr.x); ... }`
    *
    * 子集语义：按 emit 声明序**无条件**拼接各 header 实例的 (valid, 字段…) ——
    * 没有 isValid / 变长类型，因此 invalid header 输出全 0（valid=0）、输出宽度固定。
    * 字段级的"编辑"发生在 control 的 action 里；deparser 只负责把编辑后的字段
    * 重新组装成对外报文。 */
  final case class DeparserDecl(name: String, params: Seq[ControlParam], emits: Seq[Emit], line: Int)

  /** 并行查找组（`// p4c: lookup-group <组名> = 表1, 表2, ...`）：组内 runtime 表
    * **同拍**发出 key、**同拍**收回 rsp 并应用 action —— 用"表间无依赖"换取
    * 查找延时省 N−1 拍（串行时每张 runtime 表占 2 拍）。
    * 约束（后端校验，违反即 P4Error）：组内表都必须是 runtime 表；每张表至多属一个组；
    * 组内各表 action 的写集互斥；组内各表的 key 读集不得依赖组内其他表的写集。 */
  final case class LookupGroup(name: String, tables: Seq[String], line: Int)

  /** runtime 表的**外部接口契约**（A5 对拍 / 外部表模块 EM 对齐用）。
    *
    * 布局与 XlsBackend 生成 IR 时完全一致（单一事实源，避免两处漂移）：
    * {{{
    *   key 通道：各 key 元素按声明序拼接（先声明在高位）
    *   rsp 通道：hit(1) | actId(actW) | args(argW)   —— hit 在最高位
    *   args：各 action 形参按声明序拼接（先声明在高位）
    * }}}
    * @param size        表深（`// p4c: table ... size=N`，缺省 [[Directive.DefaultTableSize]]）
    * @param latencyMin  外部表模块响应延时下界（拍）；None = 未配置
    * @param latencyMax  响应延时上界（拍）
    * @param keyFields   key 元素（字段路径, 位宽），按声明序
    * @param actions     action 名 + 形参（名, LSB 偏移, 位宽），按声明序（actId = 下标）
    */
  final case class TableContract(
    name: String,
    size: Int,
    latencyMin: Option[Int],
    latencyMax: Option[Int],
    keyFields: Seq[(String, Int)],
    actions: Seq[(String, Seq[(String, Int, Int)])],
  ) {
    val keyBits: Int = keyFields.map(_._2).sum
    val actW: Int = math.max(1, BigInt(math.max(0, actions.size - 1)).bitLength)
    val argW: Int = actions.map(_._2.map(_._3).sum).foldLeft(0)(math.max)
    val rspBits: Int = 1 + actW + argW
  }

  final case class P4Program(
    headerTypes: Seq[HeaderType],
    structs: Seq[StructType],
    controls: Seq[ControlDecl],
    parsers: Seq[ParserDecl],
    deparser: Option[DeparserDecl] = None,
    lookupGroups: Seq[LookupGroup] = Seq.empty,
    /** 报文窗口宽度（位）：parser 能看到的报文位宽，顶层指示 `// p4c: pkt-window N`
      * 覆盖（缺省 [[DefaultPktWindowBits]]）。可选封装（多层 VLAN / OpaqueTag）按
      * **固定槽位**解析，窗口必须覆盖最坏情况（所有槽位都占满）的字节数。 */
    pktWindowBits: Int = P4Program.DefaultPktWindowBits,
    /** runtime 表的外部接口契约（解析期算好，供 p4flow 导出 JSON / 生成表 mock TB）。 */
    tableContracts: Seq[TableContract] = Seq.empty,
  )

  object P4Program {
    /** 缺省报文窗口宽度（位）—— 与 Chisel 线 `io.in = Input(UInt(512.W))` 一致。 */
    val DefaultPktWindowBits: Int = 512
  }
}
