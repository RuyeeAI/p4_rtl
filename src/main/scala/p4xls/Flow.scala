package p4xls

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.sys.process._

// ===========================================================================
// p4flow —— 把整条链路收进一个可执行文件
//
//   P4 源码
//     ├─ 前端解析（P4C frontend）
//     ├─ XLS IR 生成（proc 编排）+ 函数级 IR dump
//     ├─ IR 校验：静态 lint（内嵌 py）+ 真 XLS parser 的 parse/verify/round-trip
//     ├─ **形式验证**：对每个函数 DAG 用 z3 证明
//     │    ① 优化 pass（constFold→simplify→balance→cse→dce）保语义
//     │    ② IR 文本 dump/parse 往返保语义
//     ├─ Verilog 生成（XLS codegen driver）
//     ├─ Chisel BlackBox 生成（内嵌 py）
//     ├─ 仿真模型：RTL + testbench（自带优先 / 否则生成骨架）+ verilator 跑通（iverilog 回退）
//     └─ 资源报告：IR 规模（state 位宽/节点/算子直方图/II）+ RTL 规模（寄存器/线网/端口）
//
// 设计口径
//   - 外部依赖（XLS harness 二进制、verilator、z3）**显式探测**：缺了记 SKIP 并在报告里说明，
//     不静默降级（"跑过了"和"没跑"必须一眼可分）。
//   - 所有产物落 <out>/ 下按类型分目录，报告同时出 Markdown（给人看）与 JSON（给脚本）。
// ===========================================================================
object Flow {

  val Version = "0.3.0"

  private val ANSI_OK = "\u001b[32m"; private val ANSI_WARN = "\u001b[33m"
  private val ANSI_ERR = "\u001b[31m"; private val ANSI_DIM = "\u001b[2m"; private val ANSI_END = "\u001b[0m"

  // ------------------------------------------------------------------
  // 配置与上下文
  // ------------------------------------------------------------------
  final case class Cfg(
      in: Path,
      out: Path,
      top: Option[String] = None,
      stages: Int = 2,
      wct: Option[Int] = None,
      only: Set[String] = Set.empty,
      skip: Set[String] = Set.empty,
      runSim: Boolean = true,
      tb: Option[Path] = None,
      simTimeoutSec: Int = 120,
      /** 样本级行为断言命令（--verify "<cmd>"）；缺省自动发现 scripts/<base>_verify.py。 */
      verifyCmd: Option[String] = None,
      /** golden 对拍用的**官方可编译**变体样本（--golden <p4>）；缺省试 testcases/golden/<base>.p4。 */
      goldenP4: Option[Path] = None,
      /** Chisel 步骤顺带生成 svsim 仿真入口（<Top>Sim.scala，内嵌全部模块 + FST 波形）。 */
      chiselSim: Boolean = false,
      repoRoot: Path,
  ) {
    def stem: String = {
      val n = in.getFileName.toString
      if (n.endsWith(".p4")) n.dropRight(3) else n
    }
    /** 文件名主干 → 合法 XLS package 标识符 */
    def pkg: String = {
      val s = stem.map(c => if (c.isLetterOrDigit) c else '_')
      if (s.headOption.exists(_.isDigit)) "_" + s else s
    }
    def want(step: String): Boolean =
      (only.isEmpty || only.contains(step)) && !skip.contains(step)
  }

  final case class Step(id: String, title: String, status: String, detail: String, ms: Long, log: String)
  object Status {
    val Ok = "OK"; val Skip = "SKIP"; val Fail = "FAIL"; val Warn = "WARN"
  }

  /** IR 文本画像（只靠文本，不依赖 XLS 二进制） */
  final case class IrProfile(
      pkg: String,
      top: String,
      ii: Option[Int],
      ports: Seq[(String, Int, String)],   // (name, width, in/out)
      states: Seq[(String, Int)],          // (name, width)
      nodes: Int,
      opHist: Seq[(String, Int)],          // 按算子计数（降序）
      recv: Int, send: Int, invoke: Int, sel: Int,
  ) {
    def stateBits: Int = states.map(_._2).sum
  }

  private final class Ctx(val cfg: Cfg) {
    val steps = mutable.ArrayBuffer.empty[Step]
    val notes = mutable.ArrayBuffer.empty[String]
    val dirIr: Path    = cfg.out.resolve("ir")
    val dirFn: Path    = cfg.out.resolve("ir_fn")
    val dirV: Path     = cfg.out.resolve("verilog")
    val dirChisel: Path = cfg.out.resolve("chisel")
    val dirSim: Path   = cfg.out.resolve("sim")
    val irFile: Path   = dirIr.resolve(cfg.stem + ".ir")
    var profile: Option[IrProfile] = None
    var topName: String = cfg.top.getOrElse("Top")
    var vFiles: Seq[Path] = Seq.empty   // Verilog 产物：一 module 一文件（文件名=模块名）
    var vText: String  = ""             // 全部模块拼接文本（端口解析/规模统计/引用探测用）
    var prog: Option[P4C.Ast.P4Program] = None   // 前端 AST（表契约/mock TB 的数据源）
    var moduleProfiles: Seq[(String, RtlProfile)] = Seq.empty // 每模块资源画像（一 module 一文件后用）
    var goldenFrames: Seq[String] = Seq.empty            // bmv2 golden 帧（hex 文本行）
    var simLog: String = ""
    var formalLines: Seq[String] = Seq.empty
    var rtl: Option[RtlProfile] = None
    val t0 = System.nanoTime()
    def elapsedMs: Long = (System.nanoTime() - t0) / 1000000L

    def add(step: Step): Step = { steps += step; print(step); step }
  }

  private def print(s: Step): Unit = {
    val (c, tag) = s.status match {
      case Status.Ok   => (ANSI_OK, "OK  ")
      case Status.Warn => (ANSI_WARN, "WARN")
      case Status.Skip => (ANSI_DIM, "SKIP")
      case _           => (ANSI_ERR, "FAIL")
    }
    println(s"  ${c}[$tag]${ANSI_END} ${s.title}${if (s.detail.nonEmpty) " — " + s.detail else ""}")
    s.log.linesIterator.filter(_.trim.nonEmpty).take(12).foreach(l => println(s"         ${ANSI_DIM}$l${ANSI_END}"))
  }

  // ------------------------------------------------------------------
  // 外部工具定位
  // ------------------------------------------------------------------
  private object Tool {
    def which(name: String): Option[Path] = {
      val out = try Seq("bash", "-lc", s"command -v $name").!!.trim catch { case _: Throwable => "" }
      if (out.isEmpty) None else Some(Paths.get(out.linesIterator.next()))
    }
    def harness(name: String, root: Path): Option[Path] = {
      val envDir = sys.env.get("P4XLS_HARNESS_DIR").map(Paths.get(_))
      // third_party 在工程外同级目录（避免 IDE 索引，2026-10-01 外迁）
      val cands = envDir.toSeq ++ Seq(root.getParent.resolve("third_party/xls/bazel-bin/p4xls_harness"))
      cands.map(_.resolve(name)).find(p => Files.isExecutable(p))
    }
  }

  /** 跑外部命令；返回 (退出码, 合并输出)。超时用 waitFor + destroy。 */
  private def sh(cmd: Seq[String], cwd: Path, timeoutSec: Int = 300, env: Map[String, String] = Map.empty): (Int, String) = {
    val pb = new java.lang.ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = new StringBuilder
    val reader = new Thread(() => {
      // ⚠️ 必须按**字节**读并宽容解码：verilator 的 `%0s` 打印中文会产出非法
      // UTF-8 字节，直接 Source("UTF-8") 解码会抛异常 ⇒ 整个日志丢失（表现为
      // "用例 ok=0 fail=0" 的假成功）。这里把非法序列替换成 '?'，日志不丢。
      val dec = java.nio.charset.Charset.forName("UTF-8").newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
      scala.io.Source.fromInputStream(p.getInputStream)(dec).getLines().foreach { l =>
        out.synchronized(out ++= l + "\n")
      }
    })
    reader.setDaemon(true); reader.start()
    val done = p.waitFor(timeoutSec.toLong, java.util.concurrent.TimeUnit.SECONDS)
    if (!done) { p.destroyForcibly(); reader.join(500); (124, out.synchronized(out.toString) + s"\n[timeout ${timeoutSec}s]") }
    else { reader.join(1500); (p.exitValue(), out.synchronized(out.toString)) }
  }

  // ------------------------------------------------------------------
  // IR 文本画像
  // ------------------------------------------------------------------
  private val NodeRe = """,\s*id=\d+\)""".r
  private val OpRe = """^\s*([A-Za-z_][A-Za-z0-9_]*)\.\d+:\s*[^=]+=\s*([A-Za-z_][A-Za-z0-9_]*)""".r

  def profileOf(text: String, pkg: String): IrProfile = {
    val lines = text.linesIterator.toVector
    val ii = lines.find(_.contains("initiation_interval")).flatMap { l =>
      """initiation_interval\((\d+)\)""".r.findFirstMatchIn(l).map(_.group(1).toInt)
    }
    val top = lines.find(_.contains("top proc ")).map(_.split("top proc ")(1).takeWhile(c => c != '<' && c != ' ' && c != '(')).getOrElse("Top")
    // 端口表 + 状态表：proc 头 `< ... >( ... )`
    val hdrStart = lines.indexWhere(_.contains("top proc "))
    val portBlock = mutable.ArrayBuffer.empty[(String, Int, String)]
    val stateBlock = mutable.ArrayBuffer.empty[(String, Int)]
    if (hdrStart >= 0) {
      var depth = 0
      var inPorts = false
      var inStates = false
      var i = hdrStart
      while (i < lines.length && i < hdrStart + 4000) {
        val l = lines(i)
        if (l.contains(">(")) { inPorts = false; inStates = true; depth = 1 }
        else if (inStates && l.trim.startsWith(")")) inStates = false
        else if (!inPorts && !inStates && l.trim.endsWith("<")) { inPorts = true }
        val m = """^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*bits\[(\d+)\]\s*(in|out)?""".r.findFirstMatchIn(l)
        if (m.isDefined) {
          val (n, w, d) = (m.get.group(1), m.get.group(2).toInt, Option(m.get.group(3)).getOrElse(""))
          if (inPorts && d.nonEmpty) portBlock += ((n, w, d))
          else if (inStates) stateBlock += ((n, w))
        }
        i += 1
      }
    }
    val body = lines.drop(hdrStart.max(0))
    val nodes = body.count(l => NodeRe.findFirstIn(l).isDefined)
    val hist = mutable.LinkedHashMap.empty[String, Int]
    body.foreach {
      case OpRe(_, op) => hist(op) = hist.getOrElse(op, 0) + 1
      case _           => ()
    }
    val cnt = (kw: String) => body.count(l => l.contains(s"$kw(") || l.contains(s"$kw."))
    IrProfile(pkg, top, ii, portBlock.toSeq, stateBlock.toSeq, nodes,
      hist.toSeq.sortBy(-_._2), cnt("receive"), cnt("send"), cnt("invoke"), cnt("sel"))
  }

  // ------------------------------------------------------------------
  // Verilog 端口解析（给 TB 骨架 / RTL 规模报告用）
  // ------------------------------------------------------------------
  final case class VPort(name: String, width: Int, dir: String, vld: Boolean)

  def portsOf(verilog: String, top: String = null): (String, Seq[VPort]) = {
    val lines = verilog.linesIterator.toVector
    // top 指定 ⇒ 在多模块文件（proc 网络）里选 `module <top>(` 的那个；否则取第一个
    val i0 = if (top != null)
      lines.indexWhere(l => l.trim.startsWith("module ") && l.trim.stripPrefix("module ").takeWhile(_ != '(').trim == top)
    else lines.indexWhere(_.trim.startsWith("module "))
    if (i0 < 0) return (if (top != null) top else "", Seq.empty)
    val mod = lines(i0).trim.stripPrefix("module ").takeWhile(_ != '(').trim
    val buf = mutable.ArrayBuffer.empty[VPort]
    var i = i0
    while (i < lines.length && !lines(i).contains(");")) {
      val m = """(input|output)\s+(?:wire|reg)?\s*(?:\[(\d+):(\d+)\])?\s*([A-Za-z_][A-Za-z0-9_]*)""".r.findFirstMatchIn(lines(i))
      if (m.isDefined) {
        val w = Option(m.get.group(2)).map(_.toInt).getOrElse(0) + 1
        val n = m.get.group(4)
        buf += VPort(n, w, m.get.group(1), n.endsWith("_vld"))
      }
      i += 1
    }
    (mod, buf.toSeq)
  }

  /** RTL 规模：寄存器/线网条数与**寄存器位宽合计**（面积对比里最有用的一列） */
  final case class RtlProfile(mod: String, regs: Int, regBits: Int, wires: Int, wireBits: Int, lines: Int, bytes: Long, ports: Seq[VPort])

  def rtlProfileOf(verilog: String, top: String = null): RtlProfile = {
    val regRe = """^\s*reg\s*(?:\[(\d+):(\d+)\])?\s*([A-Za-z_][A-Za-z0-9_]*)""".r
    val wireRe = """^\s*wire\s*(?:\[(\d+):(\d+)\])?\s*([A-Za-z_][A-Za-z0-9_]*)""".r
    def sum(f: String => Option[Int]): (Int, Int) = {
      var n = 0; var bits = 0
      verilog.linesIterator.foreach { l => f(l).foreach { w => n += 1; bits += w } }
      (n, bits)
    }
    val (regs, regBits) = sum { l => regRe.findFirstMatchIn(l).map(m => Option(m.group(1)).map(_.toInt).getOrElse(0) + 1) }
    val (wires, wireBits) = sum { l => wireRe.findFirstMatchIn(l).map(m => Option(m.group(1)).map(_.toInt).getOrElse(0) + 1) }
    val (mod, ports) = portsOf(verilog, top)
    RtlProfile(mod, regs, regBits, wires, wireBits, verilog.linesIterator.size, verilog.getBytes(StandardCharsets.UTF_8).length.toLong, ports)
  }

  /** 没自带 TB 时生成 smoke testbench：接时钟复位、**mock 掉所有 runtime 表**
    * （见到 `tbl_X_key_vld` 后按契约延时回 `hit=0` 的 miss rsp）、发一包并等
    * `pkt_out_vld` ⇒ 任何样本都能拿到「能跑通」的最小自检，而不是空转骨架。
    *
    * mock 细节：`valid_data` 通道的 proc 会停在收 rsp 的相位等，早到会被 FIFO 缓冲、
    * 晚到只是慢 ⇒ 固定「契约 min+1 拍起、保持 10 拍」对任何 latency 都成立。
    * 回 miss（hit=0）走 default 表项，不需要知道 args 位段。
    *
    * ⚠️ 标识符必须合法：stem 里常有 `-`（demo12-l2l3-switch），直接拼进 module 名是语法错误。
    * ⚠️ 用显式行列表拼接：Scala 三引号字符串不处理 `\n` 转义。
    */
  def tbSkeleton(top: String, ports: Seq[VPort], stem: String,
                 contracts: Seq[P4C.Ast.TableContract] = Seq.empty): String = {
    val ident   = stem.map(c => if (c.isLetterOrDigit || c == '_') c else '_')
    val ins     = ports.filter(p => p.dir == "input" && p.name != "clk" && p.name != "rst")
    val outs    = ports.filter(_.dir == "output")
    val portAll = Seq("clk", "rst") ++ ports.filter(p => p.name != "clk" && p.name != "rst").map(_.name)
    val byName  = ports.map(p => p.name -> p).toMap
    // 表清单：优先用 AST 契约（有名有延时），否则从端口名反推 tbl_<名>_key/_rsp
    val tables: Seq[(String, Int, Int)] = {
      val fromPorts: Seq[(String, Int, Int)] = ports.collect {
        case VPort(n, w, "output", _) if n.startsWith("tbl_") && n.endsWith("_key") =>
          (n.stripPrefix("tbl").stripSuffix("_key"), w, byName.get(n.stripPrefix("tbl").stripSuffix("_key") match {
            case t => s"tbl_${t}_rsp" }).map(_.width).getOrElse(1))
      }.toSeq
      if (contracts.nonEmpty)
        contracts.map(c => (c.name, c.keyBits, c.rspBits)).filter(t => byName.contains(s"tbl_${t._1}_key"))
      else fromPorts
    }
    val pktIn  = byName.get("pkt_in")
    val pktOut = byName.get("pkt_out")
    val L = mutable.ArrayBuffer.empty[String]
    L += "`timescale 1ns/1ps"
    L += s"// 自动生成的 $top smoke testbench（p4flow 生成，p4flow 勿手改）。"
    L += s"// 含 runtime 表 mock：${if (tables.isEmpty) "（本程序无 runtime 表）" else tables.map(_._1).mkString(", ")}"
    L += s"// 断言：发一包 ⇒ pkt_out_vld 在 400 拍内到达（表统一回 miss ⇒ 走 default 表项）。"
    L += s"module tb_$ident;"
    L += "  reg clk = 0; reg rst = 1;"
    L += "  always #5 clk = ~clk;"
    ins.foreach(p => L += f"  reg [${p.width - 1}%d:0] ${p.name} = 0;")
    outs.foreach(p => L += f"  wire [${p.width - 1}%d:0] ${p.name};")
    L += "  integer errors = 0;"
    L += "  integer cyc = 0;"
    L += ""
    L += s"  $top dut ("
    L += portAll.map(n => s"    .$n($n)").mkString(",\n")
    L += "  );"
    L += ""
    L += "  // ---- runtime 表 mock：见 key_vld ⇒ 延时回 hit=0（miss），保持 10 拍 ----"
    tables.foreach { case (t, _, _) =>
      val start = 4
      L += s"  reg [3:0] cnt_$t = 0;"
      L += s"  always @(posedge clk) begin"
      L += s"    if (rst) cnt_$t <= 0;"
      L += s"    else if (tbl_${t}_key_vld) cnt_$t <= 4'd1;"
      L += s"    else if (cnt_$t != 0) cnt_$t <= (cnt_$t == 4'd12) ? 4'd0 : cnt_$t + 4'd1;"
      L += s"  end"
      L += s"  assign tbl_${t}_rsp_vld = (cnt_$t >= 4'd$start);"
      L += s"  assign tbl_${t}_rsp = 0;   // hit=0 ⇒ default 表项"
    }
    L += ""
    L += "  initial begin"
    L += s"""    $$dumpfile("tb_$ident.fst");"""
    L += s"    $$dumpvars(1, tb_$ident);"
    L += "    rst = 1; repeat (5) @(posedge clk); @(negedge clk); rst = 0;"
    // rdy 类输入常置 1（否则 valid_data 之外的反压会卡住出包）
    ins.filter(_.name.endsWith("_rdy")).foreach(p => L += s"    ${p.name} = ${p.width}'d1;")
    pktIn.foreach { p =>
      L += s"    // 发一包（全 0 载荷：验证「不卡住 + 能出包」，不校验内容）"
      L += s"    @(negedge clk); ${p.name} = ${p.width}'d0; pkt_in_vld = 1;"
      L += "    @(negedge clk); pkt_in_vld = 0;   // 一拍撤 vld（挂着会被重复收包）"
    }
    pktOut.foreach { p =>
      L += s"    cyc = 0;"
      L += s"    while (cyc < 400 && !${p.name}_vld) begin @(negedge clk); cyc = cyc + 1; end"
      L += s"    if (${p.name}_vld) $$display(\"  [ok] ${p.name}_vld 在 %0d 拍内到达\", cyc);"
      L += s"    else begin $$display(\"  [FAIL] ${p.name}_vld 400 拍内未到达\"); errors = errors + 1; end"
    }
    L += "    repeat (20) @(posedge clk);"
    L += """    if (errors == 0) $display(" 结果：全部通过"); else $display(" 结果：%0d 处失败", errors);"""
    L += "    $finish;"
    L += "  end"
    L += """  initial begin #200000; $display("[FAIL] 仿真超时"); $finish; end"""
    L += "endmodule"
    L.mkString("\n") + "\n"
  }

  /** 已知样本 → top / TB（与 scripts/a2_verify.sh 同表；未知样本按命名扫 testcases/a2） */
  private def knownSample(stem: String): (Option[String], Option[String]) = stem match {
    case "demo3-parser"        => (Some("Top_parser"), Some("tb_demo3_parser.v"))
    case "demo2-match"         => (Some("Ingress_control"), Some("tb_demo2_match.v"))
    case "a2-parser-control"   => (Some("Ingress_pipeline"), Some("tb_a2_parser_control.v"))
    case "demo5-pipeline"      => (Some("Ingress_pipeline"), Some("tb_demo5_pipeline.v"))
    case "demo7-runtime-table" => (Some("Ingress_control"), Some("tb_demo7_runtime_table.v"))
    case "demo9-l3forwarder"   => (Some("Ingress_pipeline"), Some("tb_demo9_l3forwarder.v"))
    case _                     => (None, None)
  }

  // ------------------------------------------------------------------
  // 主流程
  // ------------------------------------------------------------------
  def run(args: Array[String]): Int = {
    val parsed = parseArgs(args)
    if (parsed.isEmpty) return 2
    val cfg = parsed.get
    if (!Files.isReadable(cfg.in)) { System.err.println(s"❌ 读不到 ${cfg.in}"); return 2 }
    Files.createDirectories(cfg.out)
    val ctx = new Ctx(cfg)

    println(s"${ANSI_DIM}p4flow $Version${ANSI_END}  in=${cfg.in}  out=${cfg.out}")
    println(s"${ANSI_DIM}步骤：${(if (cfg.only.isEmpty) "全部" else cfg.only.mkString(","))}${if (cfg.skip.nonEmpty) s"（跳过 ${cfg.skip.mkString(",")}）" else ""}${ANSI_END}")

    stepFrontend(ctx)
    stepIr(ctx)
    if (cfg.want("contract")) stepContract(ctx)
    if (cfg.want("dump-ir")) stepDumpIr(ctx)
    if (cfg.want("xls-verify")) stepXlsVerify(ctx)   // 权威判定（真 XLS parser），放在 lint 前
    if (cfg.want("lint")) stepLint(ctx)
    if (cfg.want("formal")) stepFormal(ctx)
    if (cfg.want("verify")) stepVerify(ctx)
    if (cfg.want("verilog")) stepVerilog(ctx)
    if (cfg.want("vlint")) stepVlint(ctx)
    if (cfg.want("chisel")) stepChisel(ctx)
    if (cfg.want("sim") && cfg.runSim) stepSim(ctx)
    if (cfg.want("golden")) stepGolden(ctx)

    val report = writeReport(ctx)
    println()
    println(s"  报告：$report")
    val failed = ctx.steps.count(_.status == Status.Fail)
    if (failed == 0) {
      println(s"${ANSI_OK}  ✔ 流程完成${ANSI_END}（${ctx.steps.count(_.status == Status.Ok)} 步成功、${ctx.steps.count(_.status == Status.Skip)} 步跳过，用时 ${ctx.elapsedMs / 1000}s）")
      0
    } else {
      println(s"${ANSI_ERR}  ✘ ${failed} 步失败${ANSI_END}（详见报告）")
      1
    }
  }

  // ---- 步骤 1：前端 ----
  private def stepFrontend(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    try {
      val src = new String(Files.readAllBytes(ctx.cfg.in), StandardCharsets.UTF_8)
      val (prog, warnings) = P4C.Parser.parseProgramWithDiagnostics(src)
      ctx.prog = Some(prog)
      val what = Seq(
        if (prog.parsers.nonEmpty) s"parser=${prog.parsers.map(_.name).mkString(",")}" else "",
        if (prog.controls.nonEmpty) s"control=${prog.controls.map(_.name).mkString(",")}" else "",
        s"tables=${prog.controls.map(_.tables.size).sum}",
        s"actions=${prog.controls.map(_.actions.size).sum}",
      ).filter(_.nonEmpty).mkString(" ")
      ctx.add(Step("frontend", "P4 前端解析", if (warnings.isEmpty) Status.Ok else Status.Warn, what,
        ms(t), warnings.map("warn: " + _).mkString("\n")))
    } catch {
      case e: Throwable =>
        ctx.add(Step("frontend", "P4 前端解析", Status.Fail, e.getMessage, ms(t), ""))
    }
  }

  // ---- 步骤 2：XLS IR ----
  private def stepIr(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    try {
      val src = new String(Files.readAllBytes(ctx.cfg.in), StandardCharsets.UTF_8)
      val (prog, _) = P4C.Parser.parseProgramWithDiagnostics(src)
      ctx.prog = Some(prog)
      val text = P4C.XlsBackend.emitProgram(prog, ctx.cfg.pkg, ctx.cfg.in.getFileName.toString)
      Files.createDirectories(ctx.dirIr)
      Files.write(ctx.irFile, text.getBytes(StandardCharsets.UTF_8))
      val p = profileOf(text, ctx.cfg.pkg)
      ctx.profile = Some(p)
      if (ctx.cfg.top.isEmpty) ctx.topName = p.top
      ctx.add(Step("ir", "XLS IR 生成", Status.Ok,
        s"${ctx.irFile.getFileName} 查表口 ${p.ports.count(_._1.startsWith("tbl_"))} 个 · state ${p.states.size} 项/${p.stateBits} 位 · 节点 ${p.nodes} · II=${p.ii.getOrElse("?")}",
        ms(t), ""))
    } catch {
      case e: Throwable =>
        ctx.add(Step("ir", "XLS IR 生成", Status.Fail, e.getMessage, ms(t), stack(e)))
    }
  }

  // ---- 步骤 3：函数级 IR dump（形式验证的输入） ----
  private def stepDumpIr(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    try {
      Files.createDirectories(ctx.dirFn)
      Files.createDirectories(ctx.cfg.out.resolve("chisel_line"))
      // 注意：这里用 stages=1 —— Chisel 线 legacy 路径对 runtime 表不支持切拍，
      // 而本步只需要 action 级的 DAG（形式验证的输入），与 proc 是否切拍无关。
      P4C.Generate.compileFile(ctx.cfg.in, ctx.cfg.out.resolve("chisel_line"), None, 1, irDir = Some(ctx.dirFn))
      val n = Files.list(ctx.dirFn).iterator().asScala.count(_.toString.endsWith(".ir"))
      if (n == 0) ctx.add(Step("dump-ir", "函数级 IR dump", Status.Warn, "没有产出 .ir（该程序可能无 action）", ms(t), ""))
      else ctx.add(Step("dump-ir", "函数级 IR dump", Status.Ok, s"$n 个 fn（供形式验证）", ms(t), ""))
    } catch {
      case e: Throwable => ctx.add(Step("dump-ir", "函数级 IR dump", Status.Skip, e.getMessage, ms(t), ""))
    }
  }

  // ---- 步骤 4：静态 lint（内嵌 py） ----
  private def stepLint(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    pyScript("xls_ir_lint.py", ctx.cfg.repoRoot) match {
      case None => ctx.add(Step("lint", "IR 静态 lint", Status.Skip, "找不到 xls_ir_lint.py（仓库 scripts/ 与 jar 资源都没有）", ms(t), ""))
      case Some((cmd0, cleanup)) =>
        val py = sys.env.getOrElse("P4XLS_PYTHON", "python3")
        // op 契约文件要显式给：脚本默认按 __file__/../config 推，从 jar 解到临时目录后推不出来
        val ctr = contractsPath(ctx.cfg.repoRoot)
        val (rc, out) = sh(Seq(py) ++ cmd0 ++ ctr.map(p => Seq("--contracts", p.toString)).getOrElse(Seq.empty) ++
          Seq(ctx.irFile.toString), ctx.cfg.repoRoot)
        cleanup()
        // lint 已与 ir_parser.cc 对齐（见 scripts/xls_ir_lint_selftest.py 的反向自测 +
        // 官方 768 个 .ir 的门禁实测），因此这里是**真门禁**：报错即 FAIL。
        // 若 lint 报错而真 XLS parser 通过，说明是 lint 的覆盖缺口 —— 报告里会点出来，
        // 此时可用 --skip=lint 绕过（但更该补 lint 规则）。
        val xlsVer = ctx.steps.find(_.id == "xls-verify").map(_.status)
        val st = if (rc == 0) Status.Ok else Status.Fail
        val note = if (rc == 0) "通过"
          else s"$rc 退出码" + (
            if (xlsVer.contains(Status.Ok))
              "；⚠️ XLS parser 却通过了 ⇒ 疑似 lint 覆盖缺口（症状请回报，勿直接绕过）"
            else if (xlsVer.contains(Status.Fail)) "；XLS parser 同样失败 ⇒ IR 确实有问题"
            else "")
        ctx.add(Step("lint", "IR 静态 lint", st, note, ms(t), tail(out, 8)))
    }
  }

  // ---- 步骤 5：真 XLS parser 的 parse/verify/round-trip ----
  private def stepXlsVerify(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    Tool.harness("ir_check_main", ctx.cfg.repoRoot) match {
      case None => ctx.add(Step("xls-verify", "XLS parser 校验（parse+verify+往返）", Status.Skip,
        "找不到 ir_check_main（先跑 scripts/bootstrap_xls_env.sh）", ms(t), ""))
      case Some(bin) =>
        val (rc, out) = sh(Seq(bin.toString, "--roundtrip", "--quiet", ctx.irFile.toString), ctx.cfg.repoRoot)
        val bad = out.linesIterator.count(l => l.contains("error") || l.contains("FAIL"))
        ctx.add(Step("xls-verify", "XLS parser 校验（parse+verify+往返）",
          if (rc == 0 && bad == 0) Status.Ok else Status.Fail,
          if (rc == 0) "IR 良构、往返一致" else s"退出码 $rc", ms(t), out))
    }
  }

  // ---- 步骤 6：形式验证（z3 等价性） ----
  private def stepFormal(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    val files = if (Files.isDirectory(ctx.dirFn))
      Files.list(ctx.dirFn).iterator().asScala.filter(_.toString.endsWith(".ir")).toVector.sortBy(_.toString)
    else Vector.empty
    if (files.isEmpty) {
      ctx.add(Step("formal", "形式验证（优化/往返 等价性）", Status.Skip, "没有函数级 IR 可验（先跑 dump-ir）", ms(t), ""))
      return
    }
    var eq = 0; var neq = 0; var unk = 0
    val lines = mutable.ArrayBuffer.empty[String]
    files.foreach { f =>
      try {
        val p = P4C.IrText.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
        // ① 优化 pass 保语义
        val opt = P4C.Passes.runAll(p.dag)
        val v1 = P4C.Equivalence.check(p.dag, opt)
        // ② IR 文本 dump→parse 往返保语义
        val rt = P4C.IrText.parse(P4C.IrText.dump(p.pkg, p.fnName, p.dag)).dag
        val v2 = P4C.Equivalence.check(p.dag, rt)
        def verdict(v: P4C.Equivalence.Verdict): String = v match {
          case P4C.Equivalence.Equivalent(h)    => eq += 1; s"等价($h)"
          case P4C.Equivalence.NotEquivalent(h, _) => neq += 1; s"**不等价**($h)"
          case P4C.Equivalence.Unknown(r)       => unk += 1; s"未决($r)"
        }
        lines += s"- `${p.fnName}`：优化 ${verdict(v1)} · 往返 ${verdict(v2)}"
      } catch {
        case e: Throwable => unk += 1; lines += s"- `${f.getFileName}`：解析失败 ${e.getMessage}"
      }
    }
    val st = if (neq > 0) Status.Fail else if (unk > 0) Status.Warn else Status.Ok
    ctx.add(Step("formal", "形式验证（优化/往返 等价性）", st,
      s"${files.size} 个 fn：等价 $eq · 不等价 $neq · 未决 $unk" + (if (P4C.Z3.available) "（z3 可用）" else "（z3 不可用→降级穷尽/采样）"),
      ms(t), lines.mkString("\n")))
    ctx.formalLines = lines.toSeq
  }

  // ---- 步骤 3：runtime 表的外部接口契约（JSON） ----
  // 供外部表模块（EM/LPM/TCAM）与 A5 对拍团队对齐：key 位段、actId→action、
  // args 形参偏移、latency 契约，全部与生成 IR 的布局同源（单一事实源）。
  private def stepContract(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    val cs = ctx.prog.toSeq.flatMap(_.tableContracts)
    if (cs.isEmpty) {
      ctx.add(Step("contract", "表接口契约导出", Status.Skip, "程序里没有 runtime 表", ms(t), ""))
      return
    }
    def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    try {
      val dir = ctx.cfg.out.resolve("contract")
      Files.createDirectories(dir)
      val b = new StringBuilder
      b ++= "{\n"
      b ++= "  \"_comment\": \"runtime 表外部接口契约（p4flow 自动导出；布局与生成 IR 同源）\",\n"
      b ++= s"  \"_source\": ${q(ctx.cfg.in.toString)},\n"
      b ++= s"  \"_pkt_window_bits\": ${ctx.prog.map(_.pktWindowBits).getOrElse(0)},\n"
      b ++= "  \"tables\": [\n"
      cs.zipWithIndex.foreach { case (c, i) =>
        b ++= s"    {\"name\": ${q(c.name)}, \"size\": ${c.size}, "
        b ++= s"\"latency_cycles\": [${c.latencyMin.getOrElse(0)}, ${c.latencyMax.getOrElse(0)}], "
        b ++= s"\"key_bits\": ${c.keyBits}, \"act_bits\": ${c.actW}, "
        b ++= s"\"args_bits\": ${c.argW}, \"rsp_bits\": ${c.rspBits},\n"
        b ++= "     \"key_fields\": [" +
          c.keyFields.map { case (n, w) => s"{\"field\": ${q(n)}, \"bits\": $w}" }.mkString(", ") + "],\n"
        b ++= "     \"actions\": [" +
          c.actions.zipWithIndex.map { case ((an, ps), j) =>
            val params = ps.map { case (pn, off, w) =>
              s"{\"name\": ${q(pn)}, \"lsb\": $off, \"bits\": $w}" }.mkString(", ")
            s"{\"act_id\": $j, \"action\": ${q(an)}, \"params\": [$params]}"
          }.mkString(", ") + "]}"
        b ++= (if (i == cs.size - 1) "\n" else ",\n")
      }
      b ++= "  ]\n}\n"
      val f = dir.resolve("tables.json")
      Files.write(f, b.toString.getBytes(StandardCharsets.UTF_8))
      ctx.add(Step("contract", "表接口契约导出", Status.Ok,
        s"${cs.size} 张 runtime 表 → ${cfgOut(ctx)}/contract/tables.json（" +
          cs.map(c => s"${c.name} key${c.keyBits}/rsp${c.rspBits}").mkString("、") + "）",
        ms(t), tail(b.toString, 6)))
    } catch {
      case e: Throwable =>
        ctx.add(Step("contract", "表接口契约导出", Status.Fail, e.getMessage, ms(t), ""))
    }
  }
  private def cfgOut(ctx: Ctx): String = ctx.cfg.out.toString

  // ---- 步骤：样本级行为断言（IR 级 eval-ir 断言脚本） ----
  // 约定：仓库 scripts/<base>_verify.py（base = 文件名主干取第一个 '-' 之前部分），
  // 或 --verify "<cmd>" 显式给。环境变量 P4FLOW_OUT / P4FLOW_IR_FN 传产物位置；
  // 脚本声明了 --ir-dir 时自动补该参数（demo12_verify.py 就是这种）。
  private def stepVerify(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    val base = ctx.cfg.stem.takeWhile(_ != '-')
    val auto = Seq(
      ctx.cfg.repoRoot.resolve(s"scripts/${base}_verify.py"),
      ctx.cfg.repoRoot.resolve(s"scripts/verify_${ctx.cfg.stem.replace('-', '_')}.py")).find(Files.exists(_))
    val py = sys.env.getOrElse("P4XLS_PYTHON", "python3")
    val cmd: Option[Seq[String]] = ctx.cfg.verifyCmd match {
      case Some(c) => Some(c.split("\\s+").filter(_.nonEmpty).toSeq)
      case None => auto.map { f =>
        val wantsIrDir =
          try new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains("--ir-dir")
          catch { case _: Throwable => false }
        Seq(py, f.toString) ++ (if (wantsIrDir) Seq("--ir-dir", ctx.dirFn.toString) else Nil)
      }
    }
    cmd match {
      case None =>
        ctx.add(Step("verify", "样本级行为断言", Status.Skip,
          s"未找到 scripts/${base}_verify.py（可用 --verify \"<cmd>\" 指定）", ms(t), ""))
      case Some(c) =>
        val (rc, log) = sh(c, ctx.cfg.repoRoot, 600,
          Map("P4FLOW_OUT" -> ctx.cfg.out.toAbsolutePath.toString,
              "P4FLOW_IR_FN" -> ctx.dirFn.toAbsolutePath.toString))
        val okN = log.linesIterator.count(_.contains("✅"))
        val failN = log.linesIterator.count(_.contains("❌"))
        val st = if (rc != 0 || failN > 0) Status.Fail else if (okN == 0) Status.Warn else Status.Ok
        ctx.add(Step("verify", "样本级行为断言", st,
          s"${c.mkString(" ")}：通过 $okN 失败 $failN", ms(t), tail(log, 8)))
    }
  }

  // ---- 步骤：verilator lint（codegen 后秒级体检，--skip=sim 时也有编译级检查） ----
  private def stepVlint(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    if (ctx.vFiles.isEmpty) {
      ctx.add(Step("vlint", "Verilog lint（verilator）", Status.Skip, "没有 Verilog 可 lint", ms(t), ""))
      return
    }
    Tool.which("verilator") match {
      case None =>
        ctx.add(Step("vlint", "Verilog lint（verilator）", Status.Skip, "本机没有 verilator", ms(t), ""))
      case Some(_) =>
        // codegen 只发 xls_fifo_wrapper 实例、不发定义 ⇒ lint 也要带上它的模块源
        val extra = if (ctx.vText.contains("xls_fifo_wrapper"))
          fifoWrapperSource(ctx, ctx.dirSim).map(f => Seq(f.toString)).getOrElse(Seq.empty) else Seq.empty
        val (rc, log) = sh(
          Seq("verilator", "--lint-only", "-Wall", "-Wno-fatal", "-Wno-DECLFILENAME",
            "-Wno-UNUSEDSIGNAL", "-Wno-UNUSEDPARAM", "-Wno-VARHIDDEN", "-Wno-PINCONNECTEMPTY",
            "--top-module", ctx.topName) ++ ctx.vFiles.map(_.toString) ++ extra,
          ctx.cfg.repoRoot, 300)
        val warns = log.linesIterator.count(_.contains("%Warning"))
        val st = if (rc != 0) Status.Fail else if (warns > 0) Status.Warn else Status.Ok
        ctx.add(Step("vlint", "Verilog lint（verilator）", st,
          if (rc != 0) s"退出码 $rc" else s"无错误，告警 $warns 条（WIDTHEXPAND 等已放行）",
          ms(t), tail(log, 12)))
    }
  }

  // ---- 步骤：golden 对拍（bmv2，第一级；需官方可编译变体样本） ----
  private def stepGolden(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    val script = ctx.cfg.repoRoot.resolve("scripts/golden_sim.py")
    if (!Files.exists(script)) {
      ctx.add(Step("golden", "golden 对拍（bmv2）", Status.Skip, "没有 scripts/golden_sim.py", ms(t), ""))
      return
    }
    val base = ctx.cfg.stem.takeWhile(_ != '-')
    val p4 = ctx.cfg.goldenP4.orElse {
      Seq(ctx.cfg.repoRoot.resolve(s"testcases/golden/${base}.p4")).find(Files.exists(_))
    }
    p4 match {
      case None =>
        ctx.add(Step("golden", "golden 对拍（bmv2）", Status.Skip,
          s"未找到官方可编译变体样本（--golden <p4> 或 testcases/golden/${base}.p4）；" +
            "注意本工程 testcases/p4 的样本是自研子集，官方 p4c 编译不过", ms(t), ""))
      case Some(f) =>
        val py = sys.env.getOrElse("P4XLS_PYTHON", "python3")
        val outDir = ctx.cfg.out.resolve("golden")
        val (rc, log) = sh(Seq(py, script.toString, f.toString, "-o", outDir.toString),
          ctx.cfg.repoRoot, 300)
        val frames = log.linesIterator.filter(l => l.contains(" out") && l.contains(":")).map(_.trim).toSeq
        ctx.goldenFrames = frames
        val st = if (rc != 0) Status.Fail else if (frames.isEmpty) Status.Warn else Status.Ok
        ctx.add(Step("golden", "golden 对拍（bmv2）", st,
          if (rc != 0) s"退出码 $rc" else s"${f.getFileName}：bmv2 出 ${frames.size} 帧（golden 基准）",
          ms(t), tail(log, 8)))
    }
  }

  // ---- 步骤 7：Verilog ----
  private def stepVerilog(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    Tool.harness("verilog_codegen_main", ctx.cfg.repoRoot) match {
      case None => ctx.add(Step("verilog", "Verilog 生成", Status.Skip,
        "找不到 verilog_codegen_main（先跑 scripts/bootstrap_xls_env.sh）", ms(t), ""))
      case Some(bin) =>
        Files.createDirectories(ctx.dirV)
        val combined = ctx.dirV.resolve(ctx.cfg.stem + ".v")
        val base = Seq(bin.toString, ctx.irFile.toString, s"--top=${ctx.topName}",
          s"--stages=${ctx.cfg.stages}", s"--out=$combined")
        val (rc, log) = sh(base ++ ctx.cfg.wct.map(w => Seq(s"--wct=$w")).getOrElse(Seq.empty), ctx.cfg.repoRoot)
        if (rc == 0 && Files.exists(combined)) {
          // 按 v3 结构拆分：Parser / Deparser / 每个 lookup group（表组+Action）/
          // Top 各一个 module，一 module 一文件（文件名=模块名），便于独立查看与集成。
          val files = splitVerilogModules(
            new String(Files.readAllBytes(combined), StandardCharsets.UTF_8), ctx.dirV)
          Files.delete(combined)
          ctx.vFiles = files
          ctx.vText = files.map(f => new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).mkString("\n")
          val r = rtlProfileOf(ctx.vText, ctx.topName)
          ctx.rtl = Some(r)
          // 每模块画像（一 module 一文件后，资源膨胀能定位到具体段）
          ctx.moduleProfiles = ctx.vFiles.map { f =>
            val txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
            val isTop = f.getFileName.toString == s"${ctx.topName}.v"
            f.getFileName.toString.stripSuffix(".v") -> rtlProfileOf(txt, if (isTop) ctx.topName else null)
          }
          ctx.add(Step("verilog", "Verilog 生成", Status.Ok,
            s"${files.size} 个模块文件（一 module 一文件）：${files.map(f => f.getFileName.toString.stripSuffix(".v")).mkString("、")}" +
              s" · 寄存器 ${r.regs} 个/${r.regBits} 位 · 线网 ${r.wires}", ms(t), tail(log, 6)))
        } else {
          ctx.add(Step("verilog", "Verilog 生成", Status.Fail, s"退出码 $rc（top=${ctx.topName} 是否写对？）", ms(t), tail(log, 12)))
        }
    }
  }

  /** 把 XLS codegen 的单文件产物按 module 拆成「一 module 一文件」（文件名 = module 名）。
    * XLS 产物顶层平铺、无嵌套 module，逐行按 module/endmodule 切分即可。 */
  private def splitVerilogModules(text: String, dirV: Path): Seq[Path] = {
    val modRe = """^\s*module\s+(\w+)""".r
    val out = mutable.ArrayBuffer.empty[Path]
    val buf = mutable.ArrayBuffer.empty[String]
    var cur: Option[String] = None
    def flush(): Unit = cur.foreach { m =>
      val f = dirV.resolve(s"$m.v")
      val header =
        s"""// Generated by p4flow（XLS codegen 产物按模块拆分，一 module 一文件）. DO NOT EDIT.
           |// 本文件只含 module `$m`；与其余模块文件一起参与编译即可。
           |""".stripMargin
      Files.write(f, (header + buf.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
      out += f
    }
    for (l <- text.linesIterator) {
      l.trim.take(6) match {
        case "module" =>
          modRe.findFirstMatchIn(l).foreach { m =>
            cur = Some(m.group(1)); buf.clear(); buf += l
          }
        case "endmod" =>
          buf += l; flush(); cur = None; buf.clear()
        case _ if cur.isDefined => buf += l
        case _ => () // module 外的内容（codegen 注释/空行）不落入任何文件
      }
    }
    if (out.isEmpty) throw new IllegalStateException("Verilog 拆分失败：未找到任何 module")
    out.toSeq
  }

  // ---- 步骤 8：Chisel BlackBox（每模块一文件） ----
  private def stepChisel(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    if (ctx.vFiles.isEmpty) {
      ctx.add(Step("chisel", "Chisel BlackBox 生成", Status.Skip, "没有 Verilog 可包", ms(t), ""))
    } else pyScript("gen_chisel_wrapper.py", ctx.cfg.repoRoot) match {
      case None => ctx.add(Step("chisel", "Chisel BlackBox 生成", Status.Skip, "找不到 gen_chisel_wrapper.py", ms(t), ""))
      case Some((cmd0, cleanup)) =>
        Files.createDirectories(ctx.dirChisel)
        val py = sys.env.getOrElse("P4XLS_PYTHON", "python3")
        // vFiles 已是一 module 一文件 —— 每个文件各生成一个 .scala（BlackBox + Shell）
        val extraArgs: Seq[String] =
          if (!ctx.cfg.chiselSim) Seq.empty
          else {
            // --with-sim：追加 svsim 仿真入口（全部模块 + xls_fifo_wrapper 内嵌，chisel 7 出 FST）
            val fifo =
              if (ctx.vText.contains("xls_fifo_wrapper")) fifoWrapperSource(ctx, ctx.dirSim).map(_.toString).toSeq
              else Seq.empty
            Seq("--with-sim", "--top", ctx.topName, "--sim-stem", ctx.cfg.stem) ++
              fifo.flatMap(f => Seq("--sim-extra", f))
          }
        val (rc, out) = sh(Seq(py) ++ cmd0 ++
          Seq("-o", ctx.dirChisel.toString) ++ ctx.vFiles.map(_.toString) ++ extraArgs, ctx.cfg.repoRoot)
        cleanup()
        if (rc == 0) {
          val n = Files.list(ctx.dirChisel).iterator().asScala.count(_.toString.endsWith(".scala"))
          ctx.add(Step("chisel", "Chisel BlackBox 生成", Status.Ok,
            s"$n 个 .scala（每模块一文件，BlackBox + Shell）" +
              (if (ctx.cfg.chiselSim) "，含 svsim 仿真入口" else ""), ms(t), tail(out, 6)))
        } else ctx.add(Step("chisel", "Chisel BlackBox 生成", Status.Fail, s"退出码 $rc", ms(t), tail(out, 10)))
    }
  }

  // ---- 步骤 9：仿真模型（TB + verilator 主 / iverilog 回退） ----
  private def stepSim(ctx: Ctx): Unit = {
    val t = System.nanoTime()
    if (ctx.vFiles.isEmpty) {
      ctx.add(Step("sim", "仿真模型", Status.Skip, "没有 Verilog 可仿", ms(t), ""))
    } else {
      Files.createDirectories(ctx.dirSim)
      // 一 module 一文件：全部模块文件都拷进 sim 目录参与编译
      val simVFiles: Seq[Path] = ctx.vFiles.map { f =>
        val d = ctx.dirSim.resolve(f.getFileName)
        Files.copy(f, d, StandardCopyOption.REPLACE_EXISTING)
        d
      }
      val text = ctx.vText
      // ⚠️ 多 proc 网络的 .v 含全部子模块 —— TB 例化 / --top-module 必须用**顶层**
      val (mod, ports) = portsOf(text, ctx.topName)

      // TB：命令行指定 > 已知样本 > testcases/a2 命名扫描 > **自动生成 smoke TB（含表 mock）**
      val explicit = ctx.cfg.tb
      val known = knownSample(ctx.cfg.stem)._2.map(n => ctx.cfg.repoRoot.resolve("testcases/a2").resolve(n))
      val scanned = Seq(ctx.cfg.repoRoot.resolve("testcases/a2").resolve(s"tb_${ctx.cfg.stem.replace('-', '_')}.v"),
        ctx.cfg.repoRoot.resolve("testcases/a2").resolve(s"tb_${ctx.cfg.stem}.v")).find(Files.exists(_))
      val tbSrc = explicit.filter(Files.exists(_)).orElse(known.filter(Files.exists(_))).orElse(scanned)
      val tbFile = ctx.dirSim.resolve(s"tb_${ctx.cfg.stem}.v")
      val tbIsSkeleton = tbSrc.isEmpty
      if (tbIsSkeleton)
        Files.write(tbFile,
          tbSkeleton(mod, ports, ctx.cfg.stem, ctx.prog.toSeq.flatMap(_.tableContracts)).getBytes(StandardCharsets.UTF_8))
      else Files.copy(tbSrc.get, tbFile, StandardCopyOption.REPLACE_EXISTING)

        // 引擎选择：**verilator 主**（本机 iverilog 已移除；verilator 5 的
        // --binary --timing 直接编译 Verilog TB + DUT 成可执行），iverilog 回退。
        val hasVl = Tool.which("verilator").isDefined
        val hasIv = Tool.which("iverilog").isDefined && Tool.which("vvp").isDefined
        if (!hasVl && !hasIv) {
          ctx.add(Step("sim", "仿真模型（RTL + TB）", Status.Skip,
            "缺 verilator/iverilog，只产出模型未运行", ms(t), ""))
          return
        }

        // 运行仿真二进制并按 [ok]/[FAIL] 计数判定
        def runAndJudge(rc2: Int, log2: String, engine: String): Unit = {
          ctx.simLog = log2
          val okN = log2.linesIterator.count(_.contains("[ok]"))
          val failN = log2.linesIterator.count(_.contains("[FAIL]"))
          val verdict = log2.linesIterator.find(l => l.contains("结果：")).map(_.trim).getOrElse("")
          val st = if (rc2 != 0 || failN > 0) Status.Fail else Status.Ok
          val wave =
            if (engine != "verilator") ""
            else s" · FST 波形 tb_${ctx.cfg.stem}.fst（sim 目录）"
          // 骨架 TB 现在是**带表 mock 的 smoke 自检**（不再是空转），通过即 OK
          val what = if (tbIsSkeleton) s"smoke 自检（自动表 mock）ok=$okN fail=$failN · 功能断言待补 TB"
                     else s"用例 ok=$okN fail=$failN"
          ctx.add(Step("sim", if (tbIsSkeleton) s"仿真模型（$engine，smoke）" else s"仿真模型（$engine）", st,
            what + (if (verdict.nonEmpty) s" · $verdict" else "") + wave,
            ms(t), tail(log2, 8)))
        }

        if (hasVl) {
          val objDir = ctx.dirSim.resolve("obj_dir")
          val binName = s"sim_${ctx.cfg.stem}"
          // ⚠️ verilator 的 top 是**TB**（含时钟/激励、例化 DUT 的那个 module）——
          // 传 DUT 名会把 TB 剔出例化树，仿真 0 时刻就结束（实测踩过）。
          // TB 顶层按约定取 `tb_` 前缀的 module（骨架与已知样本都遵守）；否则取第一个。
          val tbMod = """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_]*)""".r
            .findAllMatchIn(new String(Files.readAllBytes(tbFile), StandardCharsets.UTF_8))
            .map(_.group(1)).toList
          val simTop = tbMod.find(_.startsWith("tb_")).orElse(tbMod.headOption).getOrElse("tb")
          // proc 网络的 .v 引用 xls_fifo_wrapper（codegen 对 FIFO 通道只发实例不发定义）——
          // 自动带上 XLS 的模块源一起编译
          val extraSrc: Seq[Path] =
            if (text.contains("xls_fifo_wrapper")) fifoWrapperSource(ctx, ctx.dirSim) match {
              case Some(f) => Seq(f)
              case None =>
                ctx.add(Step("sim", "仿真模型（verilator 编译）", Status.Fail,
                  "Verilog 含 xls_fifo_wrapper 实例但找不到模块源（third_party/xls 与 jar 资源均无）", ms(t), ""))
                return
            }
            else Seq.empty
          val srcs = (simVFiles :+ tbFile).map(_.toString) ++ extraSrc.map(_.toString)
          val runScript = ctx.dirSim.resolve("run.sh")
          Files.write(runScript, s"""#!/usr/bin/env bash
# 一键跑这个仿真模型（由 p4flow 生成；引擎 verilator --binary --timing，FST 波形）
set -euo pipefail
cd "$$(dirname "$$0")"
verilator --binary --timing --trace-fst --top-module $simTop -Wno-fatal \\
  --Mdir obj_dir -o $binName ${srcs.map(p => new java.io.File(p).getName).mkString(" ")}
./obj_dir/$binName
""".getBytes(StandardCharsets.UTF_8))
          // 编译（含 C++ 模型构建，首次较慢 → 单独长超时）
          val (rc1, log1) = sh(Seq("verilator", "--binary", "--timing", "--trace-fst", "--top-module", simTop,
            "-Wno-fatal", "--Mdir", objDir.toString, "-o", binName) ++ srcs, ctx.cfg.repoRoot, 600)
          if (rc1 != 0) {
            ctx.add(Step("sim", "仿真模型（verilator 编译）", Status.Fail, s"verilator 退出码 $rc1", ms(t), tail(log1, 12)))
            return
          }
          val simBin = objDir.resolve(binName)
          if (!Files.exists(simBin)) {
            // 编译退出码 0 但二进制缺失（偶发的 make 中断态）：如实报 Fail，可重跑
            ctx.add(Step("sim", "仿真模型（verilator 编译）", Status.Fail,
              "verilator 退出码 0 但仿真二进制未产出（构建中断？请重跑）", ms(t), tail(log1, 12)))
            return
          }
          // ⚠️ cwd 换成 sim 目录后，程序路径必须**绝对**（相对路径会按新 cwd 解析）
          // cwd = sim 目录：骨架 TB 的 $dumpfile("tb_<stem>.fst") 落在本目录
          val (rc2, log2) = sh(Seq(simBin.toAbsolutePath.toString), ctx.dirSim.toAbsolutePath, ctx.cfg.simTimeoutSec)
          runAndJudge(rc2, log2, "verilator")
        } else {
          val runScript = ctx.dirSim.resolve("run.sh")
          // proc 网络的 .v 引用 xls_fifo_wrapper —— 同样补上模块源（iverilog 回退路径）
          val extraSrc: Seq[Path] =
            if (text.contains("xls_fifo_wrapper")) fifoWrapperSource(ctx, ctx.dirSim) match {
              case Some(f) => Seq(f)
              case None =>
                ctx.add(Step("sim", "仿真模型（编译）", Status.Fail,
                  "Verilog 含 xls_fifo_wrapper 实例但找不到模块源", ms(t), ""))
                return
            }
            else Seq.empty
          val srcs = (simVFiles :+ tbFile).map(_.toString) ++ extraSrc.map(_.toString)
          Files.write(runScript, s"""#!/usr/bin/env bash
# 一键跑这个仿真模型（由 p4flow 生成）
set -euo pipefail
cd "$$(dirname "$$0")"
iverilog -g2012 -o tb_${ctx.cfg.stem}.vvp ${srcs.map(p => new java.io.File(p).getName).mkString(" ")}
vvp tb_${ctx.cfg.stem}.vvp
""".getBytes(StandardCharsets.UTF_8))
          val vvp = ctx.dirSim.resolve(s"tb_${ctx.cfg.stem}.vvp")
          val (rc1, log1) = sh(Seq("iverilog", "-g2012", "-o", vvp.toString) ++ srcs, ctx.cfg.repoRoot)
          if (rc1 != 0) {
            ctx.add(Step("sim", "仿真模型（编译）", Status.Fail, s"iverilog 退出码 $rc1", ms(t), tail(log1, 12)))
            return
          }
          val (rc2, log2) = sh(Seq("vvp", vvp.toString), ctx.cfg.repoRoot, ctx.cfg.simTimeoutSec)
          runAndJudge(rc2, log2, "iverilog")
        }
    }
  }

  /** Verilog 引用了 `xls_fifo_wrapper`（codegen 对 FIFO 通道只发实例）时，
    * 找到 XLS 的模块源：工程外同级 third_party/ 优先，否则从 jar 资源解到 dstDir。 */
  private def fifoWrapperSource(ctx: Ctx, dstDir: Path): Option[Path] = {
    val name = "xls_fifo_wrapper.sv"
    Files.createDirectories(dstDir)   // 调用方可能没建（--only=... chisel --chisel-sim）
    val dst = dstDir.resolve(name)
    val inRepo = ctx.cfg.repoRoot.getParent.resolve("third_party/xls/xls/modules/zstd/rtl").resolve(name)
    if (Files.exists(inRepo)) { Files.copy(inRepo, dst, StandardCopyOption.REPLACE_EXISTING); return Some(dst) }
    val res = getClass.getResourceAsStream(s"/p4xls/$name")
    if (res == null) return None
    Files.copy(res, dst, StandardCopyOption.REPLACE_EXISTING)
    res.close()
    Some(dst)
  }

  private def ms(t0: Long): Long = (System.nanoTime() - t0) / 1000000L
  private def tail(s: String, n: Int): String = s.linesIterator.toVector.takeRight(n).mkString("\n")
  private def stack(e: Throwable): String = e.getStackTrace.take(4).map("  at " + _).mkString("\n")

  /** 输入文件指纹（前 16 位十六进制）：报告可追溯到具体源码版本。 */
  private def sha256Of(p: Path): String = try {
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(Files.readAllBytes(p)).map(b => f"${b & 0xff}%02x").mkString.take(16)
  } catch { case _: Throwable => "?" }

  /** 代码版本（非 git 仓库 / 无 git 时如实标注，不猜）。 */
  private def gitCommit(ctx: Ctx): String = {
    val (rc, out) = sh(Seq("git", "-C", ctx.cfg.repoRoot.toString, "rev-parse", "--short", "HEAD"),
      ctx.cfg.repoRoot, 20)
    if (rc == 0 && out.trim.nonEmpty) out.trim.take(40) else "(非 git 仓库或 git 不可用)"
  }

  // ------------------------------------------------------------------
  // 报告
  // ------------------------------------------------------------------
  private def writeReport(ctx: Ctx): Path = {
    val b = new StringBuilder
    val cfg = ctx.cfg
    b ++= s"# p4flow 报告 —— ${cfg.stem}\n\n"
    b ++= s"- 输入：`${cfg.in}`\n- 输出目录：`${cfg.out}`\n- 工具版本：p4flow $Version\n- 用时：${ctx.elapsedMs / 1000}s\n"
    // ---- 可追溯性：结果要能对应回「哪份源码 + 哪个版本」 ----
    b ++= s"- 生成时间：${java.time.LocalDateTime.now().toString}\n"
    b ++= s"- 输入指纹：`${sha256Of(cfg.in)}`\n"
    b ++= s"- 代码版本：`${gitCommit(ctx)}`\n"
    b ++= s"- 关键配置：stages=${cfg.stages} · pkt-window=${ctx.prog.map(_.pktWindowBits).getOrElse(512)} · " +
      s"wct=${cfg.wct.map(_.toString).getOrElse("未设（⇒ 要求 II=1）")} · sim-timeout=${cfg.simTimeoutSec}s\n\n"

    b ++= "## 一、流程结果\n\n| 步骤 | 状态 | 说明 | 用时 |\n|---|---|---|---|\n"
    ctx.steps.foreach(s => b ++= s"| ${s.title} | ${s.status} | ${s.detail.replace("|", "\\|")} | ${s.ms}ms |\n")
    b ++= "\n"
    val skipped = ctx.steps.filter(_.status == Status.Skip)
    if (skipped.nonEmpty) {
      b ++= "> 跳过项说明（**不是通过**）：\n"
      skipped.foreach(s => b ++= s"> - ${s.title}：${s.detail}\n")
      b ++= "\n"
    }

    b ++= "## 二、接口（proc 端口）\n\n| 端口 | 位宽 | 方向 | 类型 |\n|---|---|---|---|\n"
    ctx.profile.foreach { p =>
      p.ports.foreach { case (n, w, d) =>
        val kind = if (n.startsWith("tbl_")) (if (n.endsWith("_key")) "runtime 表 请求" else "runtime 表 响应") else "-"
        b ++= s"| `$n` | $w | $d | $kind |\n"
      }
    }

    b ++= "\n## 三、资源报告\n\n### 3.1 IR 侧\n\n"
    ctx.profile match {
      case Some(p) =>
        val iiHow = cfg.wct.map(w => s"在 `--wct=$w` 约束下达成").getOrElse("未给 `--wct` ⇒ codegen 要求 II=1")
        b ++= s"- package：`${p.pkg}`，top：`${p.top}`，II：`${p.ii.getOrElse("?")}`（$iiHow）\n"
        b ++= s"- **state 项数 ${p.states.size}，合计 ${p.stateBits} 位**（时序面积的主要来源）\n"
        b ++= s"- 节点数 ${p.nodes}；通道操作：receive ${p.recv} · send ${p.send} · invoke ${p.invoke} · sel ${p.sel}\n\n"
        b ++= "| 算子 | 次数 |\n|---|---|\n"
        p.opHist.take(15).foreach { case (k, v) => b ++= s"| `$k` | $v |\n" }
        b ++= "\n"
        if (p.states.nonEmpty) {
          b ++= "| state 元素 | 位宽 |\n|---|---|\n"
          p.states.sortBy(-_._2).take(15).foreach { case (n, w) => b ++= s"| `$n` | $w |\n" }
          b ++= "\n"
        }
      case None => b ++= "_（IR 未生成）_\n\n"
    }
    b ++= "### 3.2 RTL 侧\n\n"
    ctx.rtl match {
      case Some(r) =>
        b ++= s"- 模块：`${r.mod}`；端口 ${r.ports.size} 个\n"
        b ++= s"- **寄存器 ${r.regs} 个、合计 ${r.regBits} 位**；线网 ${r.wires} 个（${r.wireBits} 位）\n"
        b ++= s"- 规模：${r.lines} 行 / ${r.bytes} 字节\n\n"
        b ++= "> 无工艺库，**不做门级面积**；`state 位宽合计` 与 `寄存器位宽合计` 是可比的时序面积代理指标。\n\n"
        if (ctx.moduleProfiles.size > 1) {
          b ++= "#### 3.2.1 分模块画像（一 module 一文件）\n\n| 模块 | 行 | 寄存器 | 寄存器位宽 | 线网 |\n|---|---|---|---|---|\n"
          ctx.moduleProfiles.foreach { case (n, p) =>
            b ++= s"| `$n` | ${p.lines} | ${p.regs} | ${p.regBits} | ${p.wires} |\n"
          }
          b ++= "\n"
        }
      case None => b ++= "_（Verilog 未生成）_\n\n"
    }

    b ++= "## 四、形式验证\n\n"
    if (ctx.formalLines.isEmpty) b ++= "_未执行（缺函数级 IR 或步骤被跳过）_\n\n"
    else {
      b ++= s"策略：z3 可用时走 SMT（unsat=等价）；否则降级为穷尽枚举（≤16 位输入）或固定种子采样。当前 z3：**${if (P4C.Z3.available) "可用" else "不可用"}**\n\n"
      ctx.formalLines.foreach(l => b ++= l + "\n")
      b ++= "\n"
    }

    b ++= "## 五、仿真\n\n"
    if (ctx.simLog.isEmpty) b ++= "_未执行_\n\n"
    else {
      val fails = ctx.simLog.linesIterator.filter(_.contains("[FAIL]")).take(10).toVector
      val oks = ctx.simLog.linesIterator.count(_.contains("[ok]"))
      b ++= s"- 通过用例 ${oks} 个；失败 ${fails.size} 条" + (if (fails.nonEmpty) "：" else "。") + "\n"
      fails.foreach(f => b ++= s"  - `$f`\n")
      b ++= "\n"
    }

    // ---- 表接口契约 + golden 对拍 ----
    val contracts = ctx.prog.toSeq.flatMap(_.tableContracts)
    if (contracts.nonEmpty) {
      b ++= "## 六、外部表接口契约\n\n"
      b ++= s"完整机器可读版本：`${cfg.out.relativize(cfg.out.resolve("contract/tables.json"))}`\n\n"
      b ++= "| 表 | key | rsp = hit(1)+actId+args | latency(拍) | actId → action |\n|---|---|---|---|---|\n"
      contracts.foreach { c =>
        val acts = c.actions.zipWithIndex.map { case ((an, _), j) => s"$j→`$an`" }.mkString("、")
        val lat = (c.latencyMin, c.latencyMax) match {
          case (Some(a), Some(z)) => s"$a..$z"
          case _ => "未配置"
        }
        b ++= s"| `${c.name}` | ${c.keyBits} 位（${c.keyFields.map(kf => s"`${kf._1}`:${kf._2}").mkString(" + ")}） " +
          s"| ${c.rspBits} 位（act ${c.actW} + args ${c.argW}） | $lat | $acts |\n"
      }
      b ++= "\n"
    }
    if (ctx.goldenFrames.nonEmpty) {
      b ++= "## 七、golden 对拍（bmv2）\n\n"
      b ++= s"- bmv2 产出 ${ctx.goldenFrames.size} 帧（协议语义基准，供与 RTL 侧 pkt_out 比对）\n\n"
      ctx.goldenFrames.take(6).foreach(f => b ++= s"- `$f`\n")
      b ++= "\n"
    }

    b ++= s"## ${if (ctx.goldenFrames.nonEmpty) "八" else if (contracts.nonEmpty) "七" else "六"}、产物清单\n\n"
    def listDir(d: Path, cap: Int = 40): Unit = {
      if (Files.isDirectory(d)) {
        val fs = Files.list(d).iterator().asScala.toVector.sortBy(_.toString)
        fs.take(cap).foreach(f => b ++= s"- `${cfg.out.relativize(f)}`" + (if (Files.isDirectory(f)) "/" else s"  (${Files.size(f)} 字节)") + "\n")
        if (fs.size > cap) b ++= s"- … 共 ${fs.size} 项\n"
      }
    }
    Seq(ctx.dirIr, ctx.dirFn, ctx.dirV, ctx.dirChisel, ctx.dirSim).foreach(listDir(_))

    // ---- 边界与未验证项（固定小节，避免读报告的人误判覆盖面） ----
    b ++= "\n## 边界与未验证项\n\n"
    b ++= "- **P4 子集**：表只支持 `exact`（无 LPM/ternary）；无 header stack / setValid / 变长 extract" +
      "（可选封装按固定槽位实现）；无 `if/else` 与 apply 内局部变量；单 parser + 单 control（跨 proc 互连未验证）\n"
    b ++= "- **无工艺库** ⇒ 不做门级面积；用 `IR state 位宽合计` 与 `RTL 寄存器位宽合计` 作代理指标\n"
    b ++= "- **形式验证**只覆盖 action 级函数（优化 pass 保语义 + IR 文本往返），**不含**跨模块/端到端性质\n"
    b ++= "- **外部表**（runtime 表）由控制面 / EM 模块实现；本流程只固化 key/rsp 布局与 latency 契约" +
      "（见上文契约小节 / `contract/tables.json`）\n"
    b ++= "- **Chisel 产物**是 XLS 生成 Verilog 的 BlackBox 封装（不含 P4 语义）；Chisel 线不发射 deparser\n"
    b ++= "- **golden 对拍**需官方 v1model 可编译的变体样本；本工程 `testcases/p4/` 的样本是自研子集，官方 p4c 编译不过\n"

    val md = ctx.cfg.out.resolve("report.md")
    Files.write(md, b.toString.getBytes(StandardCharsets.UTF_8))

    // JSON（给脚本消费）
    def esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val j = new StringBuilder
    j ++= "{\n"
    j ++= s"""  "stem": "${esc(cfg.stem)}",\n"""
    j ++= s"""  "input": "${esc(cfg.in.toString)}",\n"""
    j ++= s"""  "top": "${esc(ctx.topName)}",\n"""
    j ++= s"""  "steps": [\n"""
    j ++= ctx.steps.map(s => s"""    {"id":"${esc(s.id)}","status":"${s.status}","detail":"${esc(s.detail)}","ms":${s.ms}}""").mkString(",\n")
    j ++= "\n  ],\n"
    ctx.profile.foreach { p =>
      j ++= s"""  "ir": {"package":"${esc(p.pkg)}","top":"${esc(p.top)}","ii":${p.ii.getOrElse("null")},""" +
        s""""nodes":${p.nodes},"stateItems":${p.states.size},"stateBits":${p.stateBits},"ports":${p.ports.size},""" +
        s""""receive":${p.recv},"send":${p.send},"invoke":${p.invoke}},\n"""
    }
    ctx.rtl.foreach { r =>
      j ++= s"""  "rtl": {"module":"${esc(r.mod)}","regs":${r.regs},"regBits":${r.regBits},"wires":${r.wires},"lines":${r.lines},"bytes":${r.bytes},"ports":${r.ports.size}},\n"""
    }
    val okSteps = ctx.steps.count(_.status == Status.Ok)
    j ++= s"""  "summary": {"ok":$okSteps,"skip":${ctx.steps.count(_.status == Status.Skip)},"fail":${ctx.steps.count(_.status == Status.Fail)}}\n"""
    j ++= "}\n"
    Files.write(ctx.cfg.out.resolve("report.json"), j.toString.getBytes(StandardCharsets.UTF_8))
    md
  }

  // ------------------------------------------------------------------
  // 参数解析
  // ------------------------------------------------------------------
  private def parseArgs(args0: Array[String]): Option[Cfg] = {
    // 统一支持 `--k=v` 与 `--k v` 两种写法（帮助里两种都出现了，别只实现一种）
    val args = args0.flatMap { a =>
      if (a.startsWith("--") && a.contains("=")) {
        val i = a.indexOf('='); Seq(a.substring(0, i), a.substring(i + 1))
      } else Seq(a)
    }
    if (args.isEmpty) { usage(); return None }
    var in: Path = null
    var out: Path = null
    var top: Option[String] = None
    var stages = 2
    var wct: Option[Int] = None
    var only = Set.empty[String]
    var skip = Set.empty[String]
    var runSim = true
    var tb: Option[Path] = None
    var timeout = 120
    var verifyCmd: Option[String] = None
    var goldenP4: Option[Path] = None
    var chiselSim = false
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "-o" | "--out" if i + 1 < args.length   => out = Paths.get(args(i + 1)); i += 2
        case "--top" if i + 1 < args.length          => top = Some(args(i + 1)); i += 2
        case "--stages" if i + 1 < args.length       => stages = args(i + 1).toInt; i += 2
        case "--wct" if i + 1 < args.length          => wct = Some(args(i + 1).toInt); i += 2
        case "--only" if i + 1 < args.length         => only = args(i + 1).split(",").map(_.trim).filter(_.nonEmpty).toSet; i += 2
        case "--skip" if i + 1 < args.length         => skip = args(i + 1).split(",").map(_.trim).filter(_.nonEmpty).toSet; i += 2
        case "--tb" if i + 1 < args.length           => tb = Some(Paths.get(args(i + 1))); i += 2
        case "--no-sim"                              => runSim = false; i += 1
        case "--sim-timeout" if i + 1 < args.length  => timeout = args(i + 1).toInt; i += 2
        case "--verify" if i + 1 < args.length      => verifyCmd = Some(args(i + 1)); i += 2
        case "--golden" if i + 1 < args.length      => goldenP4 = Some(Paths.get(args(i + 1))); i += 2
        case "--chisel-sim"                          => chiselSim = true; i += 1
        case "-h" | "--help"                         => usage(); return None
        case a if a.startsWith("-")                  => System.err.println(s"未知参数 $a"); usage(); return None
        case a if in == null                         => in = Paths.get(a); i += 1
        case a                                       => System.err.println(s"多余参数 $a"); return None
      }
    }
    if (in == null) { usage(); return None }
    val repo = findRepoRoot()
    val outDir = if (out != null) out else in.getParent match {
      case null => Paths.get("out/flow").resolve(stripExt(in.getFileName.toString))
      case p    => p.resolve("flow_" + stripExt(in.getFileName.toString))
    }
    Some(Cfg(in = in, out = outDir, top = top, stages = stages, wct = wct, only = only,
      skip = skip, runSim = runSim, tb = tb, simTimeoutSec = timeout, repoRoot = repo,
      verifyCmd = verifyCmd, goldenP4 = goldenP4, chiselSim = chiselSim))
  }

  private def stripExt(n: String): String = if (n.endsWith(".p4")) n.dropRight(3) else n

  /** 仓库根：优先环境变量，其次从 cwd 往上找含 scripts/p4xls 的目录。 */
  def findRepoRoot(): Path = {
    sys.env.get("P4XLS_ROOT").map(Paths.get(_)).getOrElse {
      var p = Paths.get("").toAbsolutePath
      var guard = 0
      while (p != null && guard < 8) {
        if (Files.exists(p.resolve("scripts/p4xls")) || Files.exists(p.resolve("build.sbt"))) return p
        p = p.getParent; guard += 1
      }
      Paths.get("").toAbsolutePath
    }
  }

  private def usage(): Unit = {
    println(s"""p4flow $Version —— P4 源码 → (仿真模型 | Verilog | Chisel BlackBox | 形式验证 | 资源报告) 一站式

用法:
  p4flow <in.p4> [-o <outDir>] [--top=NAME] [--stages=N] [--wct=N]
         [--only=a,b] [--skip=a,b] [--no-sim] [--sim-timeout=SEC]
         [--verify "<cmd>"] [--golden <official-variant.p4>] [--chisel-sim]

步骤 id（--only/--skip 用）:
  frontend      P4 前端解析
  ir            XLS IR 生成（proc 编排）+ IR 画像
  contract      runtime 表外部接口契约 JSON（contract/tables.json）
  dump-ir       函数级 IR dump（形式验证的输入）
  lint          内嵌静态 lint（xls_ir_lint.py）
  xls-verify    真 XLS parser 的 parse/verify/往返
  formal        z3 等价性：优化 pass 保语义 + IR 文本往返保语义
  verify        样本级行为断言（自动发现 scripts/<base>_verify.py，或 --verify）
  verilog       Verilog 生成（XLS codegen，一 module 一文件）
  vlint         verilator --lint-only 体检（秒级）
  chisel        Chisel BlackBox + Shell 生成（--chisel-sim 追加 svsim 入口）
  sim           RTL + TB + verilator 跑通（无自带 TB 时自动生成带表 mock 的 smoke TB）
  golden        bmv2 无头仿真产出 golden 基准帧（需官方可编译变体样本）

外部依赖（缺哪个就 SKIP 哪步，报告里显式标注）:
  XLS harness: ../third_party/xls/bazel-bin/p4xls_harness/{ir_check_main,verilog_codegen_main}
               （可用 P4XLS_HARNESS_DIR 覆盖；构建：scripts/bootstrap_xls_env.sh）
  python3 / verilator / z3 / ../third_party/dist（bmv2，仅 golden 步需要）

示例:
  p4flow testcases/p4/demo12-l2l3-switch.p4
  p4flow testcases/p4/demo12-l2l3-switch.p4 --only=ir,xls-verify,verilog,sim
  p4flow testcases/p4/demo12-l2l3-switch.p4 --chisel-sim --skip=sim
  p4flow testcases/p4/demo12-l2l3-switch.p4 --golden testcases/golden/demo12.p4
""")
  }

  /** op 契约 JSON：仓库 config/ 优先，否则从 jar 资源解出来 */
  def contractsPath(root: Path): Option[Path] = {
    val inRepo = root.resolve("config/op_contracts.json")
    if (Files.exists(inRepo)) return Some(inRepo)
    val res = getClass.getResourceAsStream("/p4xls/op_contracts.json")
    if (res == null) return None
    val tmp = Files.createTempFile("op_contracts_", ".json")
    Files.copy(res, tmp, StandardCopyOption.REPLACE_EXISTING)
    res.close()
    Some(tmp)
  }

  // ------------------------------------------------------------------
  // 内嵌 Python 工具：优先仓库 scripts/，否则从 jar 资源解到临时目录
  // ------------------------------------------------------------------
  /** 返回 (命令前缀, 清理函数)。命令前缀形如 Seq(py, scriptPath)。 */
  def pyScript(name: String, root: Path): Option[(Seq[String], () => Unit)] = {
    val inRepo = root.resolve("scripts").resolve(name)
    if (Files.exists(inRepo)) return Some((Seq(inRepo.toString), () => ()))
    val res = getClass.getResourceAsStream(s"/p4xls/$name")
    if (res == null) return None
    val tmpDir = Files.createTempDirectory("p4xls-tool")
    val dst = tmpDir.resolve(name)
    Files.copy(res, dst, StandardCopyOption.REPLACE_EXISTING)
    res.close()
    Some((Seq(dst.toString), () => {
      try { Files.deleteIfExists(dst); Files.deleteIfExists(tmpDir) } catch { case _: Throwable => () }
    }))
  }
}
