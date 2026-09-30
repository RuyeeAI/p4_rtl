package em

import _root_.circt.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation
import java.io.PrintWriter
import scala.sys.process._

// ===========================================================================
// EM 模块的 Verilog 产出入口
//
//   sbt "em/runMain em.EmGen <outDir> <preset>"
//
// 流程（chisel 5）：ChiselStage 发射 CHIRRTL（.fir）→ firtool --verilog 出
// 纯 Verilog（给 iverilog 用；chisel5 只有 SystemVerilog 目标）。
// firtool 从 PATH 或 firtool-resolver 缓存里取。
// ===========================================================================
object EmGen {

  /** 预设配置：覆盖"全特性 / 各级裁剪 / CRC 两种形态 / OVFC"的组合 */
  def preset(name: String): EmParams = name match {
    case "basic" => // 三级齐全、无学习无老化
      EmParams(keyWidth = 48, adWidth = 32, htDepth = 1024, htWays = 4,
        numBanks = 1, ktDepth = 4096, adDepth = 1024)

    case "2left" => // d-left(2) + 自学习 + 老化
      EmParams(keyWidth = 48, adWidth = 32, htDepth = 1024, htWays = 4,
        numBanks = 2, dLeftTie = TiePolicy.Random, ktDepth = 8192, adDepth = 2048,
        aging = Some(AgingParams(ageWidth = 16, timeout = 1024, tickDiv = 64)),
        learning = Some(LearningParams(defaultAd = 0, usePortAd = true)))

    case "inline" => // 单级：key 与动作数据都内联进 HT（最快）
      EmParams(keyWidth = 48, adWidth = 32, htDepth = 1024, htWays = 4,
        numBanks = 1, useKt = false, useAd = false)

    case "noad" => // 两级：AD 内联进 KT
      EmParams(keyWidth = 48, adWidth = 32, htDepth = 1024, htWays = 4,
        numBanks = 1, ktDepth = 4096, useAd = false)

    case "crcrt" => // 运行时可配 CRC（串行 LFSR）
      EmParams(keyWidth = 48, adWidth = 32, htDepth = 1024, htWays = 4,
        numBanks = 1, ktDepth = 4096, adDepth = 1024, crc = CrcRuntime.crc32)

    case "tb" => // 功能仿真用小尺寸：HT 打得满，才能仿真 OVFC 溢出路径
      // 容量：HT = 32 桶 x 2 路 x 2 子表 = 64 条；OVFC = 8 条 → 总容量 72
      // 并列仲裁用 RoundRobin：结果确定，便于断言
      EmParams(keyWidth = 16, adWidth = 26, htDepth = 32, htWays = 2,
        numBanks = 2, dLeftTie = TiePolicy.RoundRobin, ktDepth = 128, adDepth = 128,
        ovfcDepth = 8,
        aging = Some(AgingParams(ageWidth = 8, timeout = 4, tickDiv = 8)),
        learning = Some(LearningParams(defaultAd = 0, usePortAd = true)))

    case other => sys.error(s"未知 preset: ${other}（可选：basic / 2left / inline / noad / crcrt / tb）")
  }

  /** firtool 可执行文件：PATH 优先，其次 firtool-resolver 的缓存位置 */
  private def firtoolBin: String = {
    val which = Seq("bash", "-c", "command -v firtool").!!.trim
    if (which.nonEmpty) which
    else s"${sys.env("HOME")}/Library/Caches/org.chipsalliance.llvm-firtool/1.62.0/bin/firtool"
  }

  def main(args: Array[String]): Unit = {
    val outDir = if (args.length > 0) args(0) else "out/a3"
    val name   = if (args.length > 1) args(1) else "basic"
    val p      = preset(name)
    val l      = EmLayout(p)

    println(s"[em] preset=$name")
    println(f"[em]   key=${l.keyW} ad=${l.adW} ht=${p.htDepth}x${l.ways} banks=${l.numBanks} " +
      f"kt=${p.ktDepth} ad=${p.adDepth} ovfc=${p.ovfcDepth}")
    println(s"[em]   useKt=${p.useKt} useAd=${p.useAd} crc=${p.crc.getClass.getSimpleName} " +
      s"aging=${p.aging.isDefined} learning=${p.learning.isDefined} protect=${p.memProtect}")
    println(s"[em]   HT条目=${l.htEntryW}b  KT条目=${l.ktEntryW}b  查找=${l.lookupLatency}拍(不含CRC串行)")

    val dir = s"$outDir/$name"
    // 1) CHIRRTL
    (new ChiselStage).execute(
      Array("--target-dir", dir, "--target", "chirrtl"),
      Seq(ChiselGeneratorAnnotation(() => new ExactMatch(p))))
    // 2) firtool → Verilog
    // disallowLocalVariables：不要把函数内变量生成为 `automatic` —— iverilog 对
    // "Overriding the default variable lifetime" 视为 unsupported（sorry），
    // 会以非零码退出、**不产出 vvp**。
    val fir  = s"$dir/ExactMatch.fir"
    val veri = s"$dir/ExactMatch.v"
    val cmd = Seq(firtoolBin, fir, "--verilog", "-o", veri,
      "--disable-all-randomization", "--strip-debug-info",
      "--lowering-options=disallowLocalVariables")
    val log = cmd.!!
    if (log.trim.nonEmpty) println(log)
    if (!new java.io.File(veri).exists()) sys.error(s"firtool 未产出 $veri")
    println(s"[em] 产出：$veri")
  }
}
