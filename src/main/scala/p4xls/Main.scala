package p4xls

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** p4xls 顶层 CLI —— P4 → XLS IR → RTL 工具链入口。
  *
  * 子命令：
  *   p4c <args>       转发到 P4C 编译器（gen / stats / ppal / version），
  *                    其后端为 Chisel 线（见 P4C.ChiselBackend）
  *   xls <in> <out>   '''XLS 线'''：P4 → XLS IR 文本（proc 编排，见 P4C.XlsBackend）；
  *                    IR → Verilog → 仿真由 scripts/a2_verify.sh 串（依赖外部
  *                    XLS codegen 驱动与 iverilog，不进本 CLI）
  *   wrap-chisel      把 XLS 生成的 Verilog 封装成 Chisel BlackBox + Shell
  *                    （路线 3：产物原样集成进下游 Chisel 工程）
  *   lint-ir <files>  XLS IR 文本静态校验（委托 scripts/xls_ir_lint.py）
  *   version          版本信息
  *
  * 用法：
  *   p4xls p4c gen p4/demo.p4 --dump-ir out/ir
  *   p4xls xls testcases/p4/demo9-l3forwarder.p4 out/a2/demo9.ir
  *   p4xls lint-ir out/ir
  */
object Main {

  private val Version = "0.2.0"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(args: Array[String]): Int = args.toList match {
    case "p4c" :: rest     => P4C.P4cCli.run(rest.toArray)
    case "xls" :: rest     => runXls(rest.toArray)
    case "wrap-chisel" :: rest => runWrappedPy("wrap-chisel", "scripts/gen_chisel_wrapper.py", rest.toArray)
    case "lint-ir" :: rest => runWrappedPy("lint-ir", "scripts/xls_ir_lint.py", rest.toArray)
    case "version" :: _    => println(s"p4xls $Version  (P4 -> XLS IR -> RTL)"); 0
    case Nil | "-h" :: _ | "--help" :: _ | "help" :: _ => usage(); 0
    case other :: _ =>
      System.err.println(s"未知子命令: $other")
      usage(); 1
  }

  /** `xls <in.p4> <out.ir>`：P4 → XLS IR 文本（A2 的 proc 编排线）。
    *
    * package 名取文件主干（非标识符字符 → `_`，数字开头补 `_`）——
    * 与 A2-4 之前的临时入口口径一致，保证既有产物可复现。
    */
  private def runXls(args: Array[String]): Int = {
    if (args.length != 2) {
      System.err.println("用法: p4xls xls <in.p4> <out.ir>")
      return 2
    }
    try {
      val src = new String(Files.readAllBytes(Paths.get(args(0))), StandardCharsets.UTF_8)
      val (prog, warnings) = P4C.Parser.parseProgramWithDiagnostics(src)
      warnings.foreach(w => System.err.println(s"[warn] $w"))

      val pkg = pkgOf(Paths.get(args(0)).getFileName.toString)
      val text = P4C.XlsBackend.emitProgram(prog, pkg, Paths.get(args(0)).getFileName.toString)

      val out = Paths.get(args(1))
      Option(out.getParent).foreach(Files.createDirectories(_))
      Files.write(out, text.getBytes(StandardCharsets.UTF_8))
      println(s"✅ ${args(0)} -> ${args(1)}（package=$pkg, ${text.length} 字节）")
      0
    } catch {
      case e: P4C.P4Error =>
        System.err.println(s"[error] ${e.getMessage}")
        1
    }
  }

  /** 文件名主干 → 合法 XLS package 标识符（`-` → `_`，数字开头补 `_`）。 */
  private def pkgOf(fileName: String): String = {
    val stem = fileName.stripSuffix(".p4").map(c => if (c.isLetterOrDigit) c else '_').mkString
    if (stem.headOption.exists(_.isDigit)) "_" + stem else stem
  }

  /** 委托给 Python 脚本的子命令（lint-ir / wrap-chisel 同模式）。 */
  private def runWrappedPy(name: String, scriptPath: String, args: Array[String]): Int = {
    if (name == "lint-ir" && args.isEmpty) {
      System.err.println("lint-ir 需要至少一个 .ir 文件或目录参数")
      return 2
    }
    val script = new java.io.File(scriptPath)
    if (!script.exists()) {
      System.err.println(s"未找到 ${script.getPath}（请在工程根目录运行）")
      return 2
    }
    val py = sys.env.getOrElse("P4XLS_PYTHON", "python3")
    scala.sys.process.Process(Seq(py, script.getPath) ++ args).!
  }

  private def usage(): Unit = {
    println(
      s"""p4xls $Version —— P4 -> XLS IR -> RTL 工具链
         |
         |用法: p4xls <子命令> [参数...]
         |
         |子命令:
         |  p4c <args>        P4C 编译器（gen | stats | ppal | version）—— Chisel 线
         |  xls <in.p4> <out.ir>
         |                    XLS 线：P4 → XLS IR 文本（proc 编排；
         |                    IR → Verilog → 仿真见 scripts/a2_verify.sh）
         |  wrap-chisel -o <dir> <verilog...>
         |                    把 XLS 生成的 Verilog 封装成 Chisel BlackBox + Shell
         |                    （产物集成进下游 Chisel 工程；用法见脚本头注释）
         |  lint-ir <files>   XLS IR 文本静态校验
         |  version           版本
         |
         |示例:
         |  p4xls p4c gen testcases/p4/demo9-l3forwarder.p4 --dump-ir out/ir
         |  p4xls xls testcases/p4/demo9-l3forwarder.p4 out/a2/demo9.ir
         |  p4xls wrap-chisel -o out/a2/chisel out/a2/*.v
         |  p4xls lint-ir out/ir
         |""".stripMargin
    )
  }
}
