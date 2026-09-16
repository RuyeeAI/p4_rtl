package p4xls

import P4C.{Parser, XlsBackend}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** [[P4C.XlsBackend]] 的驱动：`.p4` → XLS IR 文本。
  *
  * 用法：
  * {{{
  *   sbt "runMain p4xls.XlsBackendSelfTest testcases/p4/demo3-parser.p4 out/a2/demo3.ir"
  *   scripts/xls_ir_verify.sh --roundtrip out/a2/demo3.ir
  * }}}
  *
  * 这是 A2 的临时入口；等 control 也支持后再并进 `scripts/p4xls`（A2-4）。
  */
object XlsBackendSelfTest {

  /** 文件名主干 → package 名（合法标识符，`-` → `_`）。 */
  private def pkgOf(fileName: String): String = {
    val stem = fileName.stripSuffix(".p4").map(c => if (c.isLetterOrDigit) c else '_')
    if (stem.headOption.exists(_.isDigit)) "_" + stem else stem
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("用法: XlsBackendSelfTest <in.p4> <out.ir>")
      sys.exit(2)
    }
    val in = Paths.get(args(0))
    val out = Paths.get(args(1))
    val src = new String(Files.readAllBytes(in), StandardCharsets.UTF_8)
    val (prog, warnings) = Parser.parseProgramWithDiagnostics(src)
    warnings.foreach(w => println(s"[warn] $w"))

    val pkg = pkgOf(in.getFileName.toString)
    val text = XlsBackend.emitProgram(prog, pkg, in.getFileName.toString)

    Option(out.getParent).foreach(Files.createDirectories(_))
    Files.write(out, text.getBytes(StandardCharsets.UTF_8))
    println(s"✅ $in -> $out（package=$pkg, ${text.length} 字节）")
  }
}
