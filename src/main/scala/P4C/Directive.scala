package P4C

import scala.collection.mutable

/** E2：P4 注释编译指示扫描与紧邻性匹配。两类指示（正则互斥、互不干扰）：
  *
  *   - `// p4c: stages=N`（N ≥ 1 整数）：作用于紧邻其后的 `control`/`parser`/
  *     `action` 声明；
  *   - `// p4c: table <表名> runtime [size=N]`（主理人 D1/Q1）：作用于紧邻其后的
  *     `table` 声明，N 为表深（缺省 [[DefaultTableSize]]）。表名是冗余校验——
  *     指示贴错表时从"静默错绑"变为显式 [[P4Error]]。
  *
  * 通用语法容忍：大小写与空格差异不敏感，有效载荷后允许空白 + 任意尾巴
  * （尾巴内容被忽略）。
  *
  * 位置语义（紧邻性）：指示必须紧邻其作用的声明行之前——允许指示行与声明行
  * 之间有任意多个空白行，但不允许隔任何代码 / 其他声明（含其他注释行）。
  * 不满足紧邻性的指示**忽略并告警**（选择宽容策略而非报错：指示写错位置不应
  * 阻断整个编译；告警经 Generate 的 log 通道输出，测试固化为 warnings 非空
  * 断言）。
  *
  * 行号约定：本对象在**原始源码**上按行扫描；[[Preprocess]] 保证剥注释后
  * 换行数不变（块注释内每个换行保留为空白行），因此词法/语法产出的
  * `decl.line` 与原始行号一一对应，可直接匹配。
  *
  * 非法值：N < 1 或无法解析的指示 → [[P4Error]]（携带行号）。
  * 块注释边界：借助 [[Preprocess.classify]]（与词法预处理共享同一注释状态机），
  * 块注释内部的 `// p4c:` 样文本被识别为"注释掉的指示"——不生效、不报错、
  * 仅经 Parser 产生忽略告警。
  */
object Directive {

  /** 运行时表指示的载荷：表名 + 表深（size）。 */
  final case class TableDirective(name: String, size: Int)

  /** 运行时表的缺省表深（`size=N` 省略时，主理人 Q1 裁定）。 */
  val DefaultTableSize: Int = 4

  /** 扫描结果。
    *   - directives：指示行号（1 基）→ 指示值 N；
    *   - sourceLines：原始源码按行拆分（index = 行号 - 1），供紧邻性检查；
    *   - suppressedInBlock：位于块注释内的"指示样文本"（行号, 原始行内容）——
    *     不生效、不参与取值校验（不会因此抛 P4Error），由
    *     [[Parser$.parseProgramWithDiagnostics]] 生成忽略告警；
    *   - tableDirectives：运行时表指示行号 → [[TableDirective]]。
    */
  final case class ScanResult(
    directives: Map[Int, Int],
    sourceLines: IndexedSeq[String],
    suppressedInBlock: Seq[(Int, String)] = Seq.empty,
    tableDirectives: Map[Int, TableDirective] = Map.empty,
  )

  object ScanResult {
    /** 无指示（Parser 默认参数用，保持既有调用点零改动）。 */
    val empty: ScanResult = ScanResult(Map.empty, Vector.empty)
  }

  // 触发行：任何以 "// p4c:" 起始（忽略空白/大小写）的行都视为指示行——
  // 哪怕后续写错（如 stages=-1）也按指示行报错，而不是静默当普通注释。
  private val triggerRe = "(?i)^\\s*//\\s*p4c\\s*:".r
  // 取值行：stages = N（N 为非负整数；正负号不匹配 → 报"无法解析"）。
  private val valueRe = "(?i)^\\s*//\\s*p4c\\s*:\\s*stages\\s*=\\s*(\\d+)(?:\\s.*)?$".r
  // 运行时表行：table <表名> runtime [size=N]（表名是紧邻性的冗余校验；size 缺省 DefaultTableSize）。
  private val tableRe = "(?i)^\\s*//\\s*p4c\\s*:\\s*table\\s+([A-Za-z_]\\w*)\\s+runtime(?:\\s+size\\s*=\\s*(\\d+))?(?:\\s.*)?$".r

  /** 扫描原始源码中的全部编译指示。
    *   - 触发段（`// p4c:`）落在块注释内的行 → 抑制（记入 suppressedInBlock）：
    *     注释掉的指示不生效、不阻断编译（非法值也不抛 P4Error）；
    *   - 其余触发行：N < 1 或语法错误 → P4Error（带行号）。
    * 行号与原始源码一一对应（[[Preprocess.classify]] 换行不变量）。 */
  def scan(src: String): ScanResult = {
    val code = Preprocess.classify(src) // 共享注释状态机（与 Preprocess.apply 同一实现）
    val lines = src.split("\n", -1).toIndexedSeq
    val map = mutable.LinkedHashMap.empty[Int, Int]
    val tmap = mutable.LinkedHashMap.empty[Int, TableDirective]
    val suppressed = mutable.ArrayBuffer.empty[(Int, String)]
    var off = 0 // 当前行首字符在 src/code 中的偏移（'\n' 占 1，与 split("\n") 对齐）
    lines.zipWithIndex.foreach { case (ln, i) =>
      val lineNo = i + 1
      triggerRe.findFirstMatchIn(ln).foreach { m =>
        // 触发段（'// p4c:'）只要有字符落在**块注释**分类（classify=2）内，
        // 即视为"注释掉的指示" → 抑制（不取值、不报错、不生效）。
        // 注意：落进行注释（classify=1）是指示的正常所在，不抑制。
        val inBlock = (m.start until m.end).exists(o => code(off + o) == 2.toByte)
        if (inBlock) {
          suppressed += ((lineNo, ln.trim))
        } else valueRe.findFirstMatchIn(ln) match {
          case Some(v) =>
            val n = v.group(1).toInt
            if (n < 1)
              throw new P4Error(s"行 $lineNo：p4c: stages 指示值必须 ≥ 1（got $n）")
            map(lineNo) = n
          case None => tableRe.findFirstMatchIn(ln) match {
            case Some(t) =>
              val tname = t.group(1)
              val size = Option(t.group(2)).map(_.toInt).getOrElse(DefaultTableSize)
              if (size < 1)
                throw new P4Error(s"行 $lineNo：p4c: table $tname runtime 的 size 必须 ≥ 1（got $size）")
              tmap(lineNo) = TableDirective(tname, size)
            case None =>
              throw new P4Error(
                s"行 $lineNo：无法解析的 p4c 编译指示（期望 '// p4c: stages=N'（N ≥ 1）" +
                  s"或 '// p4c: table <表名> runtime [size=N]'）：'${ln.trim}'")
          }
        }
      }
      off += ln.length + 1
    }
    ScanResult(map.toMap, lines, suppressed.toSeq, tmap.toMap)
  }

  /** 声明行 `declLine` 的生效切拍指示（[[tableFor]] 的同构运行时表版本共用
    * [[adjacentDirective]]）。 */
  def stageFor(scan: ScanResult, declLine: Int, claimed: mutable.Set[Int] = mutable.Set.empty): Option[Int] =
    adjacentDirective(scan.directives, scan.sourceLines, declLine, claimed)

  /** 声明行 `declLine` 的生效运行时表指示：取 < declLine 的最大 table 指示行 L，
    * 且 (L, declLine) 开区间内全为空白行（紧邻性）。命中时把 L 记入 `claimed`。
    * 表名与声明名的一致性校验在 [[Parser]] 侧（错误信息需含声明行号）。 */
  def tableFor(scan: ScanResult, declLine: Int, claimed: mutable.Set[Int] = mutable.Set.empty): Option[TableDirective] =
    adjacentDirective(scan.tableDirectives, scan.sourceLines, declLine, claimed)

  /** 紧邻性匹配的泛型实现（stages / table 两类指示共用，语义逐字一致）：
    * 取 < declLine 的最大指示行 L（更早的指示行必然隔着它或代码，不可能紧邻），
    * 且 (L, declLine) 开区间内全为空白行。命中时把 L 记入 `claimed`（供解析结束
    * 后生成"未紧邻被忽略"告警）；未命中返回 None。 */
  private def adjacentDirective[T](
    map: Map[Int, T], sourceLines: IndexedSeq[String], declLine: Int, claimed: mutable.Set[Int],
  ): Option[T] = {
    if (map.isEmpty) return None
    val below = map.keys.filter(_ < declLine)
    if (below.isEmpty) return None
    val l = below.max
    val adjacent = (l + 1 until declLine).forall { k =>
      k - 1 < sourceLines.length && sourceLines(k - 1).trim.isEmpty
    }
    if (!adjacent) None
    else {
      claimed += l
      Some(map(l))
    }
  }
}
