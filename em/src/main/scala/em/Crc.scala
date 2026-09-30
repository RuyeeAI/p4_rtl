package em

import chisel3._
import chisel3.util._

// ===========================================================================
// CRC / 哈希
//
// 两种形态（由参数选择，见 CrcHardwired / CrcRuntime）：
//   固化  —— 多项式在 elaboration 期已知，逐位展开成纯 XOR 树：
//            组合逻辑、0 拍出结果、**无配置寄存器**，面积随 keyWidth*crcWidth 增长。
//   可配  —— 多项式/初值/输出异或放在寄存器里运行时改写，硬件是串行 LFSR：
//            keyWidth 拍出结果，面积小但慢。
//
// 算法形态统一为"左移 MSB 先"：poly 的 bit i 表示 x^i 的系数（x^width 隐含）。
// refin = true 时输入按 LSB 先喂入，refout = true 时输出按位反转，
// 组合起来即标准 reflected CRC（如以太网 CRC-32）。
// ===========================================================================
object Crc {

  /** 固化 CRC：Scala 层逐位展开，产出纯组合逻辑。 */
  def hardwired(
      data: UInt,
      width: Int,
      poly: BigInt,
      init: BigInt,
      xorout: BigInt,
      refin: Boolean,
      refout: Boolean
  ): UInt = {
    var st: Seq[Bool] = (0 until width).map(i => (((init >> i) & 1) == 1).B)
    val inBits: Seq[Bool] =
      if (refin) (0 until data.getWidth).map(i => data(i))
      else (0 until data.getWidth).reverse.map(i => data(i))
    for (b <- inBits) {
      val fb = st(width - 1) ^ b
      st = (0 until width).map { i =>
        val shifted = if (i == 0) false.B else st(i - 1)
        if (((poly >> i) & 1) == 1) (shifted ^ fb) else shifted
      }
    }
    val outBits = if (refout) (0 until width).map(i => st(width - 1 - i)) else st
    val mask    = (BigInt(1) << width) - 1
    VecInit(outBits).asUInt ^ (xorout & mask).U(width.W)
  }
}

/**
 * 运行时可配的串行 CRC（poly / init / xorout 均为输入，可随时改写）。
 *
 * 时序：拉高 start 并保持 din 稳定 → busy 拉高 → keyWidth 拍后 done 拉高一拍、out 有效。
 */
class CrcSerial(width: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val din   = Input(UInt(dataWidth.W))
    val poly  = Input(UInt(width.W))
    val init  = Input(UInt(width.W))
    val xor   = Input(UInt(width.W))
    val busy  = Output(Bool())
    val done  = Output(Bool())
    val out   = Output(UInt(width.W))
  })

  require(dataWidth > 0)

  val state = RegInit(0.U(width.W))
  val shift = RegInit(0.U(dataWidth.W))
  val cnt   = RegInit(0.U(log2Ceil(dataWidth + 1).W))
  val run   = RegInit(false.B)

  val bit   = shift(dataWidth - 1)
  val fb    = state(width - 1) ^ bit
  val next  = VecInit((0 until width).map { i =>
    val s = if (i == 0) false.B else state(i - 1)
    s ^ (io.poly(i) & fb)
  }).asUInt

  io.busy := run
  io.done := false.B
  io.out  := state ^ io.xor

  when(io.start && !run) {
    state := io.init
    shift := io.din
    cnt   := dataWidth.U
    run   := true.B
  }.elsewhen(run) {
    state := next
    shift := (if (dataWidth == 1) 0.U(1.W)
              else chisel3.util.Cat(shift(dataWidth - 2, 0), 0.U(1.W)))
    cnt   := cnt - 1.U
    when(cnt === 1.U) {
      run   := false.B
      io.done := true.B
    }
  }
}
