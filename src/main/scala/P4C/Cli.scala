package P4C

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** P4C 独立 CLI（打包为 `p4chisel.jar` 的入口）。
  *
  * 子命令：
  *   - `gen <in.p4> <outDir> [copyDir] [opts]`  P4 → Chisel（等价旧 `P4cMain`）
  *   - `stats (--ir <dir|file> | --p4 <in.p4>) [--json]`   IR 统计（X12 `IrStats`）
  *   - `ppal  (--ir <dir|file> | --p4 <in.p4>) [--json]`   PPA+L Tier1 解析估算
  *   - `version`
  *
  * 源码保持 Scala 2.12/2.13 双兼容（同时被 sbt 元构建编译），故不使用
  * `scala.jdk.CollectionConverters` 等 2.13 专属 API。
  */
object P4cCli {

  val Version: String = "0.1.0"

  /** Tier1 PPA 占位常数（工艺无关，仅相对量级；Tier2 综合回填后由实测覆盖）。 */
  private val Nd2PsDefault: Double = 20.0 // 单个 ND2 门延迟（ps）
  private val PowerKCoeff: Double = 0.0015 // mW / (节点·位)
  private val ToggleDefault: Double = 0.1 // 默认翻转率

  def main(args: Array[String]): Unit = System.exit(run(args))

  def run(args: Array[String]): Int = args.toList match {
    case "gen" :: rest    => runGen(rest.toArray)
    case "stats" :: rest  => runReport(rest.toArray, ppal = false)
    case "ppal" :: rest   => runReport(rest.toArray, ppal = true)
    case "version" :: _   => runVersion()
    case Nil | "-h" :: _ | "--help" :: _ | "help" :: _ => usage(); 0
    case other :: _ =>
      System.err.println(s"未知子命令: $other")
      usage()
      1
  }

  private def usage(): Unit =
    System.err.println(
      s"""P4C CLI $Version
         |用法:
         |  p4chisel gen   <in.p4> <outDir> [copyDir] [--stages N] [--clock W] [--sig-dir <dir>]
         |                 [--dump-ir <dir>] [--dump-delay-model <file>] [--delay-model weighted|unit|<json>]
         |  p4chisel stats (--ir <dir|file> | --p4 <in.p4>) [--json] [--delay-model <spec>]
         |  p4chisel ppal  (--ir <dir|file> | --p4 <in.p4>) [--json] [--delay-model <spec>]
         |  p4chisel version
         |""".stripMargin)

  private def runVersion(): Int = {
    println(s"p4chisel $Version")
    0
  }

  // ---------------- gen ----------------

  /** P4 → Chisel。旧 `P4cMain` 的等价实现（返回退出码而非 `System.exit`）。 */
  def runGen(args: Array[String]): Int = {
    val positional = scala.collection.mutable.ArrayBuffer.empty[String]
    var stages = 1
    var stagesGiven = false
    var clockW = 0
    var clockGiven = false
    var sigDir: Option[Path] = None
    var irDir: Option[Path] = None
    var dumpModel: Option[Path] = None
    var modelSpec: Option[String] = None
    var badUsage = false
    var i = 0
    while (i < args.length && !badUsage) {
      args(i) match {
        case "--stages" =>
          if (i + 1 >= args.length) badUsage = true
          else {
            try stages = args(i + 1).toInt
            catch { case _: NumberFormatException => badUsage = true }
            stagesGiven = true
            i += 1
          }
        case "--clock" =>
          if (i + 1 >= args.length) badUsage = true
          else {
            try clockW = args(i + 1).toInt
            catch { case _: NumberFormatException => badUsage = true }
            clockGiven = true
            i += 1
          }
        case "--sig-dir" =>
          if (i + 1 >= args.length) badUsage = true else { sigDir = Some(Paths.get(args(i + 1))); i += 1 }
        case "--dump-ir" =>
          if (i + 1 >= args.length) badUsage = true else { irDir = Some(Paths.get(args(i + 1))); i += 1 }
        case "--dump-delay-model" =>
          if (i + 1 >= args.length) badUsage = true else { dumpModel = Some(Paths.get(args(i + 1))); i += 1 }
        case "--delay-model" =>
          if (i + 1 >= args.length) badUsage = true else { modelSpec = Some(args(i + 1)); i += 1 }
        case a => positional += a
      }
      i += 1
    }
    if (stagesGiven && clockGiven) {
      System.err.println("错误：--stages 与 --clock 互斥，只能指定其一")
      return 1
    }
    if (clockGiven && clockW < 1) {
      System.err.println("错误：--clock 必须 ≥ 1")
      return 1
    }
    if (badUsage || stages < 1 || positional.length < 2 || positional.length > 3) {
      usage()
      return 1
    }
    try {
      val model = modelSpec.map(DelayModels.load).getOrElse(DelayModels.default)
      dumpModel.foreach { p =>
        Files.write(p, DelayModels.toJson(model).getBytes(StandardCharsets.UTF_8))
        println(s"[P4C] 延迟模型已导出: $p")
      }
      val clock = if (clockGiven) Some(clockW) else None
      val copyOpt = if (positional.length > 2) Some(Paths.get(positional(2))) else None
      val r = Generate.compileFile(
        Paths.get(positional(0)), Paths.get(positional(1)), copyOpt, stages, sigDir, clock, model, irDir)
      println(s"[P4C] ${r.p4File} -> ${r.scalaFile} (modules: ${r.modules.mkString(", ")})")
      0
    } catch {
      case e: P4Error =>
        System.err.println(s"[P4C] 编译失败: ${e.getMessage}")
        1
    }
  }

  // ---------------- stats / ppal ----------------

  private final case class RepOpts(
    ir: Option[Path], p4: Option[Path], json: Boolean, modelSpec: Option[String])

  private def parseRepOpts(args: Array[String]): Either[String, RepOpts] = {
    var ir: Option[Path] = None
    var p4: Option[Path] = None
    var json = false
    var modelSpec: Option[String] = None
    var err: Option[String] = None
    var i = 0
    while (i < args.length && err.isEmpty) {
      args(i) match {
        case "--ir" =>
          if (i + 1 >= args.length) err = Some("--ir 需要参数")
          else { ir = Some(Paths.get(args(i + 1))); i += 1 }
        case "--p4" =>
          if (i + 1 >= args.length) err = Some("--p4 需要参数")
          else { p4 = Some(Paths.get(args(i + 1))); i += 1 }
        case "--json" => json = true
        case "--delay-model" =>
          if (i + 1 >= args.length) err = Some("--delay-model 需要参数")
          else { modelSpec = Some(args(i + 1)); i += 1 }
        case a => err = Some(s"未知选项: $a")
      }
      i += 1
    }
    if (err.isEmpty && ir.isEmpty && p4.isEmpty) err = Some("必须指定 --ir <dir|file> 或 --p4 <in.p4>")
    if (err.isEmpty && ir.isDefined && p4.isDefined) err = Some("--ir 与 --p4 互斥")
    err match {
      case Some(m) => Left(m)
      case None => Right(RepOpts(ir, p4, json, modelSpec))
    }
  }

  /** 读取 .ir 文件（目录则按文件名排序）并重建 DAG。 */
  private def readIrDir(p: Path): Seq[(String, Ir.Dag)] = {
    val files: Seq[Path] =
      if (Files.isDirectory(p)) {
        val fs = p.toFile.listFiles()
        if (fs == null) Seq.empty
        else fs.toSeq.map(_.toPath).filter(_.getFileName.toString.endsWith(".ir")).sortBy(_.getFileName.toString)
      } else Seq(p)
    files.map { f =>
      val txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
      val parsed = IrText.parse(txt)
      (parsed.fnName, parsed.dag)
    }
  }

  private def loadDags(o: RepOpts, model: DelayModel): Seq[(String, Ir.Dag)] = o.p4 match {
    case Some(p4Path) =>
      // 编译到临时目录，复用 --dump-ir 通道取 DAG
      val tmp = Files.createTempDirectory("p4c-dags")
      Generate.compileFile(p4Path, tmp, None, 1, None, None, model, Some(tmp))
      readIrDir(tmp)
    case None => readIrDir(o.ir.get)
  }

  private def runReport(args: Array[String], ppal: Boolean): Int =
    parseRepOpts(args) match {
      case Left(msg) =>
        System.err.println(s"错误: $msg")
        usage()
        1
      case Right(o) =>
        try {
          val model = o.modelSpec.map(DelayModels.load).getOrElse(DelayModels.default)
          val dags = loadDags(o, model)
          if (dags.isEmpty) {
            System.err.println("[P4C] 未找到任何 .ir 文件")
            2
          } else if (ppal) {
            println(renderPpal(dags, model, o.modelSpec.getOrElse("default"), o.json))
            0
          } else {
            println(renderStats(dags, model, o.json))
            0
          }
        } catch {
          case e: P4Error =>
            System.err.println(s"[P4C] 失败: ${e.getMessage}")
            3
        }
    }

  private def renderStats(dags: Seq[(String, Ir.Dag)], model: DelayModel, json: Boolean): String =
    if (json) {
      val b = new StringBuilder
      b ++= "{"
      b ++= "\"dags\":["
      b ++= dags.map { case (ctx, dag) =>
        val r = IrStats.report(dag, ctx, model)
        val bom = IrStats.bom(dag)
        s"""{"ctx":${jstr(ctx)},"nodes":${r.nodes},"critPathNd2":${r.critPath},""" +
          s""""stageCount":${r.stageCount},"regs":${r.regs},""" +
          s""""byOp":{${r.byOp.toSeq.sortBy(_._1).map { case (k, v) => s"${jstr(k)}:$v" }.mkString(",")}},""" +
          s""""bom":{${bom.map(e => s"${jstr(e.op + "@" + e.width)}:${e.count}").mkString(",")}}}"""
      }.mkString(",")
      b ++= "]}"
      b.toString
    } else {
      val b = new StringBuilder
      dags.foreach { case (ctx, dag) =>
        val r = IrStats.report(dag, ctx, model)
        b ++= IrStats.format(r)
        b ++= s"[ir-stats] bom: ${IrStats.bom(dag).map(e => s"${e.op}@${e.width}x${e.count}").mkString(", ")}\n"
      }
      b.toString.stripLineEnd
    }

  /** Tier1 面积代理（ND2 等效门数）。口径与 `DelayModel.weighted` 的"纯布线=0"一致。 */
  private def areaNd2(op: String, w: Int): Double = {
    val width = if (w > 0) w else 1
    op match {
      case "Const" | "InputRef" => 0.0 // 常量/输入：无逻辑
      case "Zext" | "Trunc" | "Slice" | "Cat" | "Not" => 0.0 // 纯布线
      case "Mux" => 1.5 * width
      case "RegRead" => 4.0 * width // 读 mux 树 + 存储
      case o if o.startsWith("Bin(") =>
        val k = o.stripPrefix("Bin(").stripSuffix(")")
        k match {
          case "Add" | "Sub" => width.toDouble // 行波进位链上界
          case "And" | "Or" | "Xor" => width.toDouble
          case "Shl" | "Shr" => 1.2 * width * math.max(1.0, math.log(width.toDouble) / math.log(2.0))
          case "Eq" | "Neq" => width.toDouble
          case "Lt" | "Le" | "Gt" | "Ge" => width.toDouble
          case _ => width.toDouble
        }
      case _ => 0.0
    }
  }

  private final case class DagPpal(
    ctx: String, nodes: Int, critPathNd2: Double, stageCount: Int, regs: Int,
    maxStageDelayNd2: Double, areaProxyNd2: Double, nodeBits: Int)

  private def dagPpal(ctx: String, dag: Ir.Dag, model: DelayModel): DagPpal = {
    val r = IrStats.report(dag, ctx, model)
    val bom = IrStats.bom(dag)
    val area = bom.map(e => areaNd2(e.op, e.width) * e.count).sum
    val bits = bom.map(e => e.width * e.count).sum
    DagPpal(ctx, r.nodes, r.critPath, r.stageCount, r.regs,
      if (r.stageDelays.isEmpty) r.critPath else r.stageDelays.max, area, bits)
  }

  private def renderPpal(
    dags: Seq[(String, Ir.Dag)], model: DelayModel, modelName: String, json: Boolean): String = {
    val per = dags.map { case (ctx, dag) => dagPpal(ctx, dag, model) }
    val totalArea = per.map(_.areaProxyNd2).sum
    val totalRegs = per.map(_.regs).sum
    val totalNodes = per.map(_.nodes).sum
    val totalBits = per.map(_.nodeBits).sum
    val maxStageDelay = if (per.isEmpty) 0.0 else per.map(_.maxStageDelayNd2).max
    val latencyCycles = if (per.isEmpty) 0 else per.map(_.stageCount).max
    val periodNs = maxStageDelay * Nd2PsDefault / 1000.0
    val freqMhz = if (periodNs > 0) 1000.0 / periodNs else 0.0
    val powerMw = PowerKCoeff * totalBits * ToggleDefault

    if (json) {
      val b = new StringBuilder
      b ++= "{"
      b ++= s""""tool":{"p4chisel":${jstr(Version)},"delayModel":${jstr(modelName)}},"""
      b ++= s""""tech":{"nd2Ps":$Nd2PsDefault,"powerK":$PowerKCoeff,"toggle":$ToggleDefault},"""
      b ++= "\"dags\":["
      b ++= per.map { d =>
        s"""{"ctx":${jstr(d.ctx)},"nodes":${d.nodes},"critPathNd2":${d.critPathNd2},""" +
          s""""stageCount":${d.stageCount},"regs":${d.regs},""" +
          s""""maxStageDelayNd2":${d.maxStageDelayNd2},"areaProxyNd2":${round3(d.areaProxyNd2)}}"""
      }.mkString(",")
      b ++= "],"
      b ++= "\"total\":{"
      b ++= s""""nodes":$totalNodes,"areaProxyNd2":${round3(totalArea)},"regs":$totalRegs,"""
      b ++= s""""latencyCycles":$latencyCycles,""" +
        s""""maxStageDelayNd2":${round3(maxStageDelay)},""" +
        s""""clockPeriodNsEst":${round3(periodNs)},"freqMhzEst":${round3(freqMhz)},""" +
        s""""powerProxyMwEst":${round3(powerMw)}}"""
      b ++= "}"
      b.toString
    } else {
      val b = new StringBuilder
      b ++= s"[ppal] tier=1 analytic, 占位工艺 ND2=${Nd2PsDefault}ps、未含布线/扇出 —— 仅作相对比较，Tier2 综合回填后覆盖\n"
      b ++= s"[ppal] delayModel=$modelName\n"
      per.foreach { d =>
        b ++= s"[ppal] ${d.ctx}: nodes=${d.nodes} area=${round3(d.areaProxyNd2)} ND2 " +
          s"critPath=${d.critPathNd2} ND2 stages=${d.stageCount} regs=${d.regs}\n"
      }
      b ++= s"[ppal] total: nodes=$totalNodes area=${round3(totalArea)} ND2 regs=$totalRegs " +
        s"latency=$latencyCycles cyc period=${round3(periodNs)} ns freq=${round3(freqMhz)} MHz " +
        s"power(proxy)=${round3(powerMw)} mW"
      b.toString
    }
  }

  private def round3(d: Double): Double = math.rint(d * 1000.0) / 1000.0

  private def jstr(s: String): String =
    "\"" + s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\t' => "\\t"
      case '\r' => "\\r"
      case c => c.toString
    } + "\""
}
