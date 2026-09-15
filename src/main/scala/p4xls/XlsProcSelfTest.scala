package p4xls

import P4C.XlsProc.{Builder, IdGen}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** [[P4C.XlsProc]] 的自测：用发射器**重建 M0 的 proc**，与手写版本对照。
  *
  * 为什么用 M0 做标尺：`testcases/m0/m0_key_rsp_loop.ir` 是手写的、已被官方
  * parser 与 iverilog 双重验证过的基准。发射器若能产出**语义等价**的 IR，
  * 就说明它的 API 与转义都没问题。
  *
  * 用法：
  * {{{
  *   sbt "runMain p4xls.XlsProcSelfTest out/a2/m0_regen.ir"
  *   scripts/xls_ir_verify.sh --roundtrip out/a2/m0_regen.ir
  * }}}
  */
object XlsProcSelfTest {

  /** 用发射器重建 M0 的三态机 proc。 */
  def buildM0(): String = {
    // id 从 1 开始（这个 package 里没有 fn，所以 proc 可以独占）
    val ids = new IdGen(1)
    val b = new Builder("m0_key_rsp", "key_rsp_loop", ids)

    // 通道：与接口裁剪后的 M0 一致 —— key/rsp 走 valid_data（无 rdy），result 保留 rdy
    b.declareChan("key_out", "send", 64, "valid_data")
    b.declareChan("rsp_in", "receive", 32, "valid_data")
    b.declareChan("result_out", "send", 32, "ready_valid")

    b.declareState("phase", "bits[2]", "0")
    b.declareState("rsp_hold", "bits[32]", "0")

    // ---- 读状态 ----
    val tok = b.stateRead("tok", "token", "tok")
    val ph = b.stateRead("phase", "bits[2]", "phase")
    val hold = b.stateRead("rsp_hold", "bits[32]", "hold")

    // ---- 相位判定 ----
    val pIdle = b.literal(0, "bits[2]", "p_idle")
    val pWait = b.literal(1, "bits[2]", "p_wait")
    val pDisp = b.literal(2, "bits[2]", "p_disp")
    val isIdle = b.eq(ph, pIdle, "is_idle")
    val isWait = b.eq(ph, pWait, "is_wait")
    val isDisp = b.eq(ph, pDisp, "is_disp")

    // ---- 通道操作：都接同一个 tok（P0 定案：不得串链）----
    val key = b.literal(BigInt("a5a5a5a5a5a5a5a5", 16), "bits[64]", "key")
    val sKey = b.send(tok, key, "key_out", Some(isIdle), "s_key")
    val (tokR, rsp) = b.receive(tok, "rsp_in", "bits[32]", Some(isWait), "recv")
    val sOut = b.send(tok, hold, "result_out", Some(isDisp), "s_out")

    // ---- 相位推进 ----
    val nWait = b.literal(1, "bits[2]", "n_wait")
    val nDisp = b.literal(2, "bits[2]", "n_disp")
    val nIdle = b.literal(0, "bits[2]", "n_idle")
    val nextPhase = b.sel(ph, Seq(nWait, nDisp, nIdle), nIdle, "bits[2]", "next_phase")

    // ---- 保存 response（只在 WAIT 拍采样）----
    val nextHold = b.sel(ph, Seq(hold, rsp, hold), hold, "bits[32]", "next_hold")

    // ---- token 汇聚 + 写回 ----
    val tokAll = b.afterAll(Seq(sKey, tokR, sOut), "next_tok")
    b.nextValue("tok", tokAll)
    b.nextValue("phase", nextPhase)
    b.nextValue("rsp_hold", nextHold)

    "package m0_key_rsp\n\n" + b.render
  }

  def main(args: Array[String]): Unit = {
    val out = if (args.nonEmpty) Paths.get(args(0)) else Paths.get("out/a2/m0_regen.ir")
    Option(out.getParent).foreach(Files.createDirectories(_))
    val text = buildM0()
    Files.write(out, text.getBytes(StandardCharsets.UTF_8))
    println(s"✅ 已生成 $out（${text.length} 字节）")
  }
}
