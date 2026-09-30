package p4xls

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import P4C.{Interp, IrText}

/** `eval-ir <fn.ir> [--in path=value]... [--reg inst=v0,v1,...] [--json]`
  *
  * 用 [[P4C.Interp]] 对单个 action / 表项的 DAG 做抽象求值 —— **IR 级行为验证**，
  * 不需要 XLS 二进制，也不需要 iverilog。
  *
  * 为什么需要它：`flow` 里的形式验证只证明"优化前后 / 文本往返等价"，属于**自洽性**证明；
  * 要证明"逻辑符合预期"（TTL 递减、校验和增量更新、下一跳 MAC 重写、出端口位图），
  * 必须把具体输入喂进去看输出。这是 [[P4C.Interp]] 的第一个 CLI 入口。
  *
  * 路径口径与 dump 的注释一致（点分路径，如 `hdr.ipv4.ttl`）：
  *   `// p4c-params: in_hdr_ipv4_ttl=in:hdr.ipv4.ttl`
  *   `// p4c-sinks: out:hdr.ipv4.ttl:8,reg:portBytes:8:32,cnt:fwdCnt:4:32`
  *
  * 未显式给出的输入按 0 填充（输入集合从 `// p4c-params:` 自动提取），
  * 因此通常只需写关心的几项。
  */
object EvalIr {

  private val ReParam = """// p4c-params: \S+=in:(\S+)""".r
  private val ReRegSink = """(reg|cnt):([A-Za-z_][A-Za-z0-9_]*):(\d+):(\d+)""".r

  def run(args: Array[String]): Int = {
    if (args.isEmpty) { usage(); return 2 }
    if (args.contains("-h") || args.contains("--help")) { usage(); return 0 }

    var file: Option[String] = None
    val inputs = scala.collection.mutable.LinkedHashMap.empty[Seq[String], BigInt]
    val regs = scala.collection.mutable.LinkedHashMap.empty[String, Vector[BigInt]]
    var json = false

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--in" if i + 1 < args.length =>
          splitKv(args(i + 1)) match {
            case Some((k, v)) => inputs += (k.split("\\.").toSeq -> parseBig(v))
            case None => System.err.println(s"--in 参数应为 path=value，实际 '${args(i + 1)}'"); return 2
          }
          i += 2
        case "--reg" if i + 1 < args.length =>
          splitKv(args(i + 1)) match {
            case Some((k, v)) =>
              regs += (k -> v.split(",").map(s => parseBig(s.trim)).toVector)
            case None => System.err.println(s"--reg 参数应为 inst=v0,v1,...，实际 '${args(i + 1)}'"); return 2
          }
          i += 2
        case "--json" => json = true; i += 1
        case f if !f.startsWith("-") && file.isEmpty => file = Some(f); i += 1
        case other => System.err.println(s"未知参数: $other"); return 2
      }
    }

    val path = file.getOrElse { System.err.println("缺少 <fn.ir> 参数"); return 2 }
    val p = Paths.get(path)
    if (!Files.exists(p)) { System.err.println(s"找不到文件 $path"); return 2 }

    val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

    // 输入集合与 extern 实例都从 dump 注释里自动提取，未给值的填 0 —— 调用方只需写关心的项
    val allIns: Seq[Seq[String]] = ReParam.findAllMatchIn(text).map(m => m.group(1).split("\\.").toSeq).toSeq
    val allRegs: Seq[(String, Int)] =
      ReRegSink.findAllMatchIn(text).map(m => (m.group(2), m.group(3).toInt)).toSeq

    val fullInputs = allIns.map(pth => pth -> inputs.getOrElse(pth, BigInt(0))).toMap
    val fullRegs = allRegs.map { case (inst, size) =>
      inst -> regs.getOrElse(inst, Vector.fill(size)(BigInt(0)))
    }.toMap

    val parsed =
      try IrText.parse(text)
      catch { case e: Throwable => System.err.println(s"IR 解析失败：${e.getMessage}"); return 1 }

    val res =
      try Interp.eval(parsed.dag, Interp.Env(fullInputs, fullRegs))
      catch { case e: Throwable => System.err.println(s"求值失败：${e.getMessage}"); return 1 }

    val outW: Map[Seq[String], Int] = parsed.dag.outputs.collect {
      case P4C.Ir.OutputWrite(pth, _, w) => pth -> w
    }.toMap

    if (json) {
      def jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      val b = new StringBuilder
      b ++= "{\n"
      b ++= s"""  "fn": ${jstr(parsed.fnName)},\n"""
      b ++= s"""  "package": ${jstr(parsed.pkg)},\n"""
      b ++= """  "outputs": {""" + "\n"
      b ++= res.outputs.map { case (pth, v) =>
        val w = outW.getOrElse(pth, math.max(1, v.bitLength))
        s"""    ${jstr(pth.mkString("."))}: {"value": ${v.toString}, "hex": "${hex(v, w)}", "width": $w}"""
      }.mkString(",\n")
      b ++= "\n  },\n"
      b ++= """  "regWrites": {""" + res.regWrites.map { case ((inst, idx), v) =>
        s"""\n    ${jstr(inst + "[" + idx + "]")}: ${v.toString}"""
      }.mkString(",") + "\n  },\n"
      b ++= """  "counterAdds": {""" + res.counterAdds.map { case ((inst, idx), v) =>
        s"""\n    ${jstr(inst + "[" + idx + "]")}: ${v.toString}"""
      }.mkString(",") + "\n  }\n"
      b ++= "}\n"
      print(b.toString)
    } else {
      println(s"fn ${parsed.fnName}   (package ${parsed.pkg})")
      println(s"— 输入 ${fullInputs.size} 项（未给的填 0）→ 输出 ${res.outputs.size} 项")
      println("")
      res.outputs.toSeq.sortBy(_._1.mkString(".")).foreach { case (pth, v) =>
        val w = outW.getOrElse(pth, math.max(1, v.bitLength))
        println(f"  ${pth.mkString(".")}%-30s [${w}%3d] = ${hex(v, w)}")
      }
      if (res.regWrites.nonEmpty) {
        println("")
        println("— extern 写：")
        res.regWrites.foreach { case ((inst, idx), v) => println(f"  $inst[$idx] = $v") }
      }
      if (res.counterAdds.nonEmpty) {
        println("")
        println("— counter 累加：")
        res.counterAdds.foreach { case ((inst, idx), d) => println(f"  $inst[$idx] += $d") }
      }
    }
    0
  }

  private def hex(v: BigInt, w: Int): String = {
    val n = math.max(1, (w + 3) / 4)
    val s = v.toString(16)
    "0x" + ("0" * math.max(0, n - s.length)) + s
  }

  private def splitKv(s: String): Option[(String, String)] = {
    val i = s.indexOf('=')
    if (i <= 0) None else Some((s.substring(0, i).trim, s.substring(i + 1).trim))
  }

  /** 支持十进制 / 0x / 0b（与 P4 字面量习惯一致）。 */
  private def parseBig(s: String): BigInt = {
    val t = s.trim
    if (t.startsWith("0x") || t.startsWith("0X")) BigInt(t.drop(2), 16)
    else if (t.startsWith("0b") || t.startsWith("0B")) BigInt(t.drop(2), 2)
    else BigInt(t)
  }

  private def usage(): Unit = {
    println(
      """p4xls eval-ir —— 对单个 IR 函数做抽象求值（IR 级行为验证，不依赖 iverilog）
        |
        |用法: p4xls eval-ir <fn.ir> [--in <点分路径>=<值>]... [--reg <实例>=<v0,v1,...>] [--json]
        |
        |说明:
        |  输入路径口径见 IR 文件里的 `// p4c-params: <名>=in:<路径>`；
        |  输出见 `// p4c-sinks: out:<路径>:<宽度>,reg:<实例>:<深度>:<宽度>`。
        |  未显式给出的输入自动填 0，因此通常只需写关心的几项。
        |  值支持十进制 / 0x / 0b 写法。
        |
        |示例:
        |  # 验证 L3 路由动作：TTL 64 → 63，并观察下一跳 MAC 与校验和
        |  p4xls eval-ir out/flow/demo12/ir_fn/*route_table_l3_forward.ir \
        |        --in hdr.ipv4.ttl=64 --in hdr.ipv4.hdrChecksum=0x1234 --in meta.ipLen=100
        |""".stripMargin)
  }
}
