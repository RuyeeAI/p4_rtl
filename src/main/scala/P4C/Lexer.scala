package P4C

/** P4-16 子集词法分析。
  *
  * 输入文本已由 [[Preprocess]] 去掉注释和预处理行。支持：
  *   - 带宽字面量：`16w255`、`16w0x1f`、`16w0b1010`（W0）
  *   - 普通字面量：`0x0800`、`0b1010`（二进制，W0）、`255`
  *   - 标识符、符号（最长匹配：<< >> == != <= >= ++ && ||）
  */
object Lexer {

  sealed trait TokKind
  case object TIdent extends TokKind
  case object TNum extends TokKind
  case object TSym extends TokKind
  case object TEOF extends TokKind

  final case class Tok(kind: TokKind, text: String, value: Option[BigInt], width: Option[Int], line: Int)

  private val symbolList = Seq(
    "<<", ">>", "==", "!=", "<=", ">=", "++", "&&", "||",
    "{", "}", "(", ")", "[", "]", ";", ",", ":", "=", "<", ">",
    "+", "-", "*", "/", "%", "|", "&", "^", "~", "?", ".", "!", "@",
  )

  private val identRe = "[A-Za-z_][A-Za-z0-9_]*".r
  // 带宽后缀进制：hex / binary（W0）/ dec——bin 必须先于裸 dec 匹配（否则 "0b1010" 被拆成 0 + ident）
  private val widthNumRe = "([0-9]+)w(0[xX][0-9a-fA-F]+|0[bB][01]+|[0-9]+)".r
  private val hexNumRe = "0[xX][0-9a-fA-F]+".r
  private val binNumRe = "0[bB][01]+".r
  private val decNumRe = "[0-9]+".r

  private def parseNum(s: String): BigInt = {
    val low = s.toLowerCase
    if (low.startsWith("0x")) BigInt(s.substring(2), 16)
    else if (low.startsWith("0b")) BigInt(s.substring(2), 2)
    else BigInt(s)
  }

  def tokenize(src: String): Seq[Tok] = {
    val toks = scala.collection.mutable.ArrayBuffer.empty[Tok]
    var line = 1
    var i = 0
    val n = src.length
    def err(msg: String) = throw new P4Error(s"词法错误（行 $line）：$msg")
    while (i < n) {
      val c = src.charAt(i)
      if (c == '\n') { line += 1; i += 1 }
      else if (c.isWhitespace) i += 1
      else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') { while (i < n && src.charAt(i) != '\n') i += 1 }
      else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
        while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) { if (src.charAt(i) == '\n') line += 1; i += 1 }
        if (i + 1 >= n) err("块注释未闭合")
        i += 2
      }
      else {
        val rest = src.substring(i)
        var matched = false
        // 带宽字面量
        widthNumRe.findPrefixMatchOf(rest).foreach { m =>
          toks += Tok(TNum, m.matched, Some(parseNum(m.group(2))), Some(m.group(1).toInt), line)
          i += m.matched.length; matched = true
        }
        if (!matched) hexNumRe.findPrefixOf(rest).foreach { m =>
          toks += Tok(TNum, m, Some(parseNum(m)), None, line); i += m.length; matched = true
        }
        if (!matched) binNumRe.findPrefixOf(rest).foreach { m =>
          toks += Tok(TNum, m, Some(parseNum(m)), None, line); i += m.length; matched = true
        }
        if (!matched) decNumRe.findPrefixOf(rest).foreach { m =>
          toks += Tok(TNum, m, Some(parseNum(m)), None, line); i += m.length; matched = true
        }
        if (!matched) identRe.findPrefixOf(rest).foreach { m =>
          toks += Tok(TIdent, m, None, None, line); i += m.length; matched = true
        }
        if (!matched) {
          symbolList.find(s => rest.startsWith(s)).foreach { s =>
            toks += Tok(TSym, s, None, None, line); i += s.length; matched = true
          }
        }
        if (!matched) err(s"无法识别的字符 '$c'")
      }
    }
    toks += Tok(TEOF, "<eof>", None, None, line)
    toks.toSeq
  }
}

/** 去掉块注释、行注释和预处理指令行（#include、#define 等）。
  *
  * 行号保持不变量（E2 依赖）：剥除注释/指令后**换行数与原文一致**——行注释与
  * 预处理行本就保留行尾换行；块注释内每个换行改写为一个空白换行保留。
  * 因此词法/语法侧的行号与原始源码行号一一对应，[[Directive]] 可直接按原始
  * 行号匹配指示。剥除的内容对词法器均为不可见（换行 = 空白），token 流不变。
  */
object Preprocess {

  /** 注释/预处理行分类状态机（**唯一实现，供两处消费**）。
    * 返回与 src 等长的分类数组：0 = Code，1 = 行注释或预处理行，2 = 块注释。
    *   - [[apply]]：剥除一切非 Code 字符（保留其中换行）→ 词法视图；
    *   - [[Directive.scan]]：触发段落进 **2（块注释）** → "注释掉的指示"抑制
    *     （不生效、不报错、仅告警）；落进 1（行注释）是指示的正常所在，不抑制。
    *
    * 分类规则（与历史 apply 分支逐一对应，含 `lineStart` 怪癖——只有代码路径上
    * 的换行会刷新 lineStart，因此紧随块注释之后的 '#/…' 仍按预处理行处理）：
    *   - '#/…' 整行（lineStart 起）→ 1（词法上整行剥除；# 行首字符是 '#'，
    *     不可能匹配 Directive 的行首双斜杠触发锚点，两侧无歧义）；
    *   - 行注释（双斜杠）起至行尾 → 1；
    *   - 块注释（斜杠-星 … 星-斜杠）起止定界符与内部 → 2；未闭合 → 至文末。
    */
  private[P4C] def classify(src: String): Array[Byte] = {
    val n = src.length
    val code = Array.fill(n)(0.toByte)
    var i = 0
    var lineStart = true
    while (i < n) {
      val c = src.charAt(i)
      if (lineStart && c == '#') { // 预处理行：整行按 1（apply 整行剥除）
        while (i < n && src.charAt(i) != '\n') { code(i) = 1.toByte; i += 1 }
        lineStart = false
      } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
        while (i < n && src.charAt(i) != '\n') { code(i) = 1.toByte; i += 1 }
      } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
        code(i) = 2.toByte; code(i + 1) = 2.toByte; i += 2
        while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
          code(i) = 2.toByte; i += 1
        }
        if (i + 1 < n) { code(i) = 2.toByte; code(i + 1) = 2.toByte; i += 2 }
        else if (i < n) { code(i) = 2.toByte; i += 1 } // 未闭合块注释：2 至文末
      } else {
        lineStart = c == '\n'
        i += 1
      }
    }
    code
  }

  /** 去掉块注释、行注释和预处理指令行（#include、#define 等）。
    *
    * 行号保持不变量（E2 依赖）：剥除注释/指令后**换行数与原文一致**——行注释与
    * 预处理行本就保留行尾换行；块注释内每个换行改写为一个空白换行保留。
    * 因此词法/语法侧的行号与原始源码行号一一对应，[[Directive]] 可直接按原始
    * 行号匹配指示。剥除的内容对词法器均为不可见（换行 = 空白），token 流不变。
    *
    * 实现即 [[classify]] 的消费端：Code 字符照抄，其余仅保留换行。
    */
  def apply(src: String): String = {
    val code = classify(src)
    val sb = new StringBuilder(src.length)
    var i = 0
    val n = src.length
    while (i < n) {
      val c = src.charAt(i)
      if (code(i) == 0 || c == '\n') sb.append(c) // 换行恒保留：行号不变量
      i += 1
    }
    sb.toString
  }
}
