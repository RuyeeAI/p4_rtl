package p4xls

/** p4xls 顶层 CLI —— P4 → XLS IR → RTL 工具链入口。
  *
  * 子命令：
  *   p4c <args>       转发到 P4C 编译器（gen / stats / ppal / version），
  *                    其后端为 XLS IR 文本发射器（见 P4C.IrText）
  *   lint-ir <files>  XLS IR 文本静态校验（委托 scripts/xls_ir_lint.py）
  *   version          版本信息
  *
  * 用法：
  *   java -jar p4xls.jar p4c gen p4/demo.p4 --dump-ir out/ir
  *   java -jar p4xls.jar lint-ir out/ir
  */
object Main {

  private val Version = "0.1.0"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(args: Array[String]): Int = args.toList match {
    case "p4c" :: rest     => P4C.P4cCli.run(rest.toArray)
    case "lint-ir" :: rest => runLintIr(rest.toArray)
    case "version" :: _    => println(s"p4xls $Version  (P4 -> XLS IR -> RTL)"); 0
    case Nil | "-h" :: _ | "--help" :: _ | "help" :: _ => usage(); 0
    case other :: _ =>
      System.err.println(s"未知子命令: $other")
      usage(); 1
  }

  private def runLintIr(args: Array[String]): Int = {
    if (args.isEmpty) {
      System.err.println("lint-ir 需要至少一个 .ir 文件或目录参数")
      return 2
    }
    val script = new java.io.File("scripts/xls_ir_lint.py")
    if (!script.exists()) {
      System.err.println(s"未找到 ${script.getPath}（请在工程根目录运行，或用 --scripts 指定）")
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
         |  p4c <args>        P4C 编译器（gen | stats | ppal | version）
         |  lint-ir <files>   XLS IR 文本静态校验
         |  version           版本
         |
         |示例:
         |  p4xls p4c gen testcases/p4/demo9-l3forwarder.p4 --dump-ir out/ir
         |  p4xls lint-ir out/ir
         |""".stripMargin
    )
  }
}
