package P4C

import scala.collection.mutable

/** A2：XLS `proc` 形态的 IR 文本发射器。
  *
  * 与 [[IrText]]（单 [[Ir.Dag]] → 单 `fn`）互补：本对象负责**时序编排**这一层 ——
  * 一个 proc 里的 FSM 相位、state 读写、通道收发、`invoke` 子函数、token 汇聚。
  *
  * {{{
  * package a2_demo
  *
  * fn add_one(x: bits[32]) -> bits[32] { ... }        // 复用 IrText.dump
  *
  * #[initiation_interval(1)]
  * top proc demo_proc<                                // 本对象负责这一段
  *     data_in: bits[32] in,
  *     data_out: bits[32] out
  * >(
  *     tok: token,
  *     phase: bits[2],
  *     init={token, 0}
  * ) {
  *   chan_interface data_in(direction=receive, ...)
  *   chan_interface data_out(direction=send, ...)
  *
  *   sr_11: token = state_read(state_element=tok, id=11)
  *   ...
  * }
  * }}}
  *
  * == 设计要点（全部来自实测，见 docs/A2-架构设计.md）==
  *
  * 1. **id 必须 package 级全局唯一**（A2-0 实测：多个 fn/proc 内的 id 不能重复，
  *    否则报 `ID N is not unique`）。所以 [[IdGen]] 由上层持有、跨 fn/proc 共用，
  *    本对象的 [[Builder]] 只用不建。
  *
  * 2. **引用必须先于定义**（M0 K6）。[[Builder]] 内部登记已发出的节点名，
  *    引用未登记的名字会立刻抛错 —— 把这类错误挡在生成阶段，
  *    而不是留给后面的 parser 报一句难懂的错。
  *
  * 3. **通道操作不得串链**（P0 定案，II=1 的关键）。本对象不强制这一点
  *    （那需要看 predicate 互斥性），但对外暴露的 API 让"都接同一个 token"
  *    成为最自然的写法：每个 send/receive 都接受显式的 token 参数。
  *
  * 4. `receive` 一次产生 3 个节点（receive + 两个 tuple_index）——
  *    XLS 的 receive 返回 `(token, data)` 元组，取用必须先 tuple_index。
  */
object XlsProc {

  /** 节点 id 分配器。
    *
    * **必须跨 fn/proc 共用**：id 在 XLS 里是 package 级全局唯一的
    * （A2-0 实测：fn 占了 1..2、proc 再从 1 开始 → `ID 1 is not unique`）。
    *
    * @param start 起始 id（默认 1）
    */
  final class IdGen(start: Int = 1) {
    private var nextId = start
    /** 取一个新 id。 */
    def next(): Int = { val v = nextId; nextId += 1; v }
    /** 看一眼下一个 id（不消耗）。 */
    def peek: Int = nextId
  }

  /** 从类型字符串里取 `bits[N]` 的 N（[[Builder.sel]] 判断 cases 是否全覆盖用）。 */
  private val BitsWidthRe = """bits\[(\d+)\]""".r

  /** proc 头的一条通道声明（同时决定端口与 `chan_interface` 行）。 */
  final case class ChanDecl(name: String, direction: String, flowControl: String, width: Int)

  /** proc 的一个 state 元素。 */
  final case class StateDecl(name: String, ty: String, init: String)

  /** proc 体发射器。
    *
    * 用法：`new Builder(pkg, procName, ids)` → 声明通道/state → 逐条发节点
    * → [[render]]。节点的引用只能来自本对象方法返回的名字。
    */
  final class Builder(val pkg: String, val procName: String, ids: IdGen, top: Boolean = true) {

    private val chans = mutable.ArrayBuffer.empty[ChanDecl]
    private val states = mutable.ArrayBuffer.empty[StateDecl]
    private val body = mutable.ArrayBuffer.empty[String]
    /** 已发射的节点：名字 → 类型字符串。
      *
      * 类型用于 [[sel]] 自动判断「cases 是否已覆盖 selector 的全部取值」——
      * XLS 不允许这种情况下出现 default（见 sel 的注释）。 */
    private val defined = mutable.LinkedHashMap.empty[String, String]

    // ---------------- 声明 ----------------

    /** 声明一条通道。`direction` ∈ {send, receive}；`flowControl` ∈ {valid_data, ready_valid}。 */
    def declareChan(name: String, direction: String, width: Int,
                    flowControl: String = "valid_data"): Unit = {
      require(direction == "send" || direction == "receive",
        s"XlsProc.declareChan：direction 必须是 send/receive（got '$direction'）")
      require(flowControl == "valid_data" || flowControl == "ready_valid",
        s"XlsProc.declareChan：flowControl 必须是 valid_data/ready_valid（got '$flowControl'）")
      require(!chans.exists(_.name == name), s"XlsProc.declareChan：通道名重复 '$name'")
      chans += ChanDecl(name, direction, flowControl, width)
    }

    /** 声明一个 state 元素（`tok` 由 [[render]] 自动加，不要在这里声明）。 */
    def declareState(name: String, ty: String, init: String = "0"): Unit = {
      require(name != "tok", "XlsProc.declareState：tok 是隐式 state，不要重复声明")
      require(!states.exists(_.name == name), s"XlsProc.declareState：state 名重复 '$name'")
      states += StateDecl(name, ty, init)
    }

    /** 已声明的 state 元素名（供上层拼 `next_value` 用）。 */
    def stateNames: Seq[String] = states.map(_.name).toSeq

    // ---------------- 内部工具 ----------------

    private def emit(line: String): Unit = body += ("  " + line)

    private def fresh(hint: String, ty: String): (String, Int) = {
      val id = ids.next()
      val nm = s"${hint}_$id"
      defined(nm) = ty
      (nm, id)
    }

    private def check(name: String): String = {
      require(defined.contains(name),
        s"XlsProc: 引用未定义的节点 '$name'（XLS 要求引用先于定义；" +
          "只能引用本 Builder 方法返回的名字）")
      name
    }

    private def needChan(name: String, dir: String): Unit =
      require(chans.exists(c => c.name == name && c.direction == dir),
        s"XlsProc: '$name' 不是已声明的 $dir 通道")

    // ---------------- 节点发射 ----------------

    /** `state_read`。返回节点名。 */
    def stateRead(elem: String, ty: String, hint: String = "sr"): String = {
      val (nm, id) = fresh(hint, ty)
      emit(s"$nm: $ty = state_read(state_element=$elem, id=$id)")
      nm
    }

    /** `literal`。`ty` 形如 `bits[32]`。 */
    def literal(value: BigInt, ty: String, hint: String = "lit"): String = {
      val (nm, id) = fresh(hint, ty)
      emit(s"$nm: $ty = literal(value=$value, id=$id)")
      nm
    }

    /** 二元运算（add/sub/and/or/xor/shll/shrl/eq/ne/ult/ule/ugt/uge）。 */
    def binOp(op: String, l: String, r: String, ty: String, hint: String = "bin"): String = {
      val (nm, id) = fresh(hint, ty)
      emit(s"$nm: $ty = $op(${check(l)}, ${check(r)}, id=$id)")
      nm
    }

    /** `eq`，返回 `bits[1]`（谓词常用）。 */
    def eq(l: String, r: String, hint: String = "eq"): String =
      binOp("eq", l, r, "bits[1]", hint)

    /** `tuple_index`。 */
    def tupleIndex(t: String, index: Int, ty: String, hint: String = "ti"): String = {
      val (nm, id) = fresh(hint, ty)
      emit(s"$nm: $ty = tuple_index(${check(t)}, index=$index, id=$id)")
      nm
    }

    /** `sel`。注意 XLS 的语义与一条硬规则：
      *
      * - `cases` 按 selector 的**取值**索引：`cases(i)` 即 `selector == i` 时的结果；
      * - 所有元素必须是已定义的节点引用（不能写字面量）；
      * - ⚠️ '''当 `cases` 数量 = `2^selector位宽` 时不允许给 default'''
      *   —— 报 `Select has useless default value: selector has N bits with M cases`。
      *   典型场景：`bits[1]` 的谓词选择器 + 2 个 cases（真假两臂）已全覆盖。
      *   所以 `default` 是 `Option`。
      */
    def sel(selector: String, cases: Seq[String], default: Option[String], ty: String,
            hint: String = "sel"): String = {
      val (nm, id) = fresh(hint, ty)
      val cs = cases.map(check).mkString(", ")
      // XLS 硬规则：**当 cases 覆盖了 selector 的全部取值时不允许有 default**
      // （报 `Select has useless default value: selector has N bits with M cases`）。
      // 这里按 selector 的位宽**自动判断并省略**，调用方不必关心这条规则 ——
      // 实测中 12 处 sel 里有 6 处踩了这个坑（凡以 bits[1] 谓词为 selector 的
      // 「旧值/新值」二选一，都是 2^1 = 2 个 case）。
      val coversAll = defined.get(selector).exists {
        case BitsWidthRe(n) => cases.size == (1 << n.toInt)
        case _ => false
      }
      val d = if (coversAll) "" else default.map(x => s", default=${check(x)}").getOrElse("")
      emit(s"$nm: $ty = sel(${check(selector)}, cases=[$cs]$d, id=$id)")
      nm
    }

    /** `concat`（左 = MSB，与 XLS 一致）。 */
    def concat(parts: Seq[String], ty: String, hint: String = "cat"): String = {
      val (nm, id) = fresh(hint, ty)
      emit(s"$nm: $ty = concat(${parts.map(check).mkString(", ")}, id=$id)")
      nm
    }

    /** `bit_slice`。 */
    def bitSlice(src: String, start: Int, width: Int, hint: String = "slc"): String = {
      val (nm, id) = fresh(hint, s"bits[$width]")
      emit(s"$nm: bits[$width] = bit_slice(${check(src)}, start=$start, width=$width, id=$id)")
      nm
    }

    /** `zero_ext`：零扩展。XLS 无独立 trunc op，截断一律用 [[bitSlice]]。 */
    def zeroExt(src: String, newWidth: Int, hint: String = "zext"): String = {
      val (nm, id) = fresh(hint, s"bits[$newWidth]")
      emit(s"$nm: bits[$newWidth] = zero_ext(${check(src)}, new_bit_count=$newWidth, id=$id)")
      nm
    }

    /** `not`（按位取反）。
      *
      * 与 [[IrText]] 的发射口径一致：**不带 `id=` 参数**（IrText 的全部节点都不带 id，
      * 其产物已过官方 parser + codegen 验证）。名字仍用 id 后缀保证唯一 ——
      * 这里消耗一个 id 只是为了让节点名不撞车，不影响 IR 语义。 */
    def notNode(src: String, width: Int, hint: String = "not"): String = {
      val id = ids.next()
      val nm = s"${hint}_$id"
      defined(nm) = s"bits[$width]"
      emit(s"$nm: bits[$width] = not(${check(src)})")
      nm
    }

    /** `send`。返回新的 token 节点名。 */
    def send(tok: String, data: String, chan: String, predicate: Option[String] = None,
             hint: String = "snd"): String = {
      needChan(chan, "send")
      val (nm, id) = fresh(hint, "token")
      val p = predicate.map(x => s", predicate=${check(x)}").getOrElse("")
      emit(s"$nm: token = send(${check(tok)}, ${check(data)}$p, channel=$chan, id=$id)")
      nm
    }

    /** `receive`。返回 `(新 token 名, 数据节点名)`。
      *
      * 展开成 3 个节点：`rcv_N: (token, T) = receive(...)` + 两个 `tuple_index`。 */
    def receive(tok: String, chan: String, dataTy: String, predicate: Option[String] = None,
                hint: String = "rcv"): (String, String) = {
      needChan(chan, "receive")
      val (nm, id) = fresh(hint, s"(token, $dataTy)")
      val p = predicate.map(x => s", predicate=${check(x)}").getOrElse("")
      emit(s"$nm: (token, $dataTy) = receive(${check(tok)}$p, channel=$chan, id=$id)")
      val t = tupleIndex(nm, 0, "token", s"${hint}_tok")
      val d = tupleIndex(nm, 1, dataTy, s"${hint}_dat")
      (t, d)
    }

    /** `invoke` 一个**纯数据** fn，返回其返回值节点名。
      *
      * 依据 A2-0 实测：被调函数没有 token 形参时，invoke 直接返回该函数的
      * 返回类型，**不加 token**。若被调函数接受 token 形参（官方样本里那类
      * `__itok__` 前缀函数），写法是 `(token, T) = invoke(tok, args...)`。 */
    def invoke(fnName: String, args: Seq[String], retTy: String, hint: String = "inv"): String = {
      val (nm, id) = fresh(hint, retTy)
      emit(s"$nm: $retTy = invoke(${args.map(check).mkString(", ")}, to_apply=$fnName, id=$id)")
      nm
    }

    /** `invoke` 一个**带 token 形参**的 fn，返回 `(token, 返回值)` 两个节点名。 */
    def invokeWithTok(fnName: String, tok: String, args: Seq[String], retTy: String,
                      hint: String = "invt"): (String, String) = {
      val (nm, id) = fresh(hint, s"(token, $retTy)")
      emit(s"$nm: (token, $retTy) = invoke(${check(tok)}, ${args.map(check).mkString(", ")}, " +
        s"to_apply=$fnName, id=$id)")
      val t = tupleIndex(nm, 0, "token", s"${hint}_tok")
      val d = tupleIndex(nm, 1, retTy, s"${hint}_dat")
      (t, d)
    }

    /** `after_all`。注意：XLS 要求至少 1 个操作数，且都是 token。 */
    def afterAll(toks: Seq[String], hint: String = "aa"): String = {
      require(toks.nonEmpty, "XlsProc.afterAll：至少需要一个操作数")
      val (nm, id) = fresh(hint, "token")
      emit(s"$nm: token = after_all(${toks.map(check).mkString(", ")}, id=$id)")
      nm
    }

    /** `next_value`（写 state）。节点名用 `next_value.<id>` 形式 ——
      * 带点的名字要求后缀等于 id（M0 K6），这里自动保证。 */
    def nextValue(elem: String, value: String): Unit = {
      val id = ids.next()
      emit(s"next_value.$id: () = next_value(state_element=$elem, value=${check(value)}, id=$id)")
    }

    /** 直接插入一行原始文本（逃生口：临时实验用，正常路径不要用）。 */
    def raw(line: String): Unit = emit(line)

    // ---------------- 渲染 ----------------

    /** 渲染整个 proc 定义（不含 `package` 行 —— 由上层与 fn 一起拼）。 */
    def render: String = {
      val b = new StringBuilder
      val kw = if (top) "top proc" else "proc"
      b ++= "#[initiation_interval(1)]\n"
      b ++= s"$kw $procName<\n"
      // 通道端口：send → out，receive → in
      val portLines = chans.map { c =>
        val dir = if (c.direction == "send") "out" else "in"
        s"    ${c.name}: bits[${c.width}] $dir"
      }
      b ++= portLines.mkString(",\n") + "\n"
      b ++= ">(\n"
      // state 形参：tok 固定第一
      val stateLines = ("    tok: token" +: states.map(s => s"    ${s.name}: ${s.ty}"))
      b ++= stateLines.mkString(",\n") + ",\n"
      b ++= s"    init={${("token" +: states.map(_.init)).mkString(", ")}}\n"
      b ++= ") {\n"
      // chan_interface 声明
      chans.foreach { c =>
        b ++= s"  chan_interface ${c.name}(direction=${c.direction}, kind=streaming, " +
          s"strictness=proven_mutually_exclusive, flow_control=${c.flowControl}, flop_kind=none)\n"
      }
      if (chans.nonEmpty) b ++= "\n"
      body.foreach(l => b ++= l + "\n")
      b ++= "}\n"
      b.toString
    }
  }
}
