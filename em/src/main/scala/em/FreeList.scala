package em

import chisel3._
import chisel3.util._

// ===========================================================================
// 空闲条目栈（free list）
//
// 初始化为恒等映射 stack(i) = i，栈指针 sp = depth（即"全部空闲"）：
//   alloc（pop）：sp-- ，给出 stack(sp-1)
//   free （push）：stack(sp) = addr，sp++
//
// 实现用寄存器数组（行为模型）。真做硅时这里应换成 bitmap + 优先编码器，
// 否则 depth 大时寄存器开销不可接受 —— 这一点不影响功能验证。
// ===========================================================================
class FreeList(depth: Int) extends Module {
  require(depth >= 1, "FreeList depth 必须 >= 1")
  val addrW = math.max(1, log2Ceil(depth))
  val cntW  = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val alloc = Input(Bool())            // 请求分配
    val addr  = Output(UInt(addrW.W))    // 分配到的地址（alloc 当拍有效）
    val ok    = Output(Bool())           // 有空闲条目
    val free  = Input(Bool())            // 归还
    val faddr = Input(UInt(addrW.W))     // 归还的地址
    val count = Output(UInt(cntW.W))     // 当前空闲条目数
  })

  val stack = RegInit(VecInit((0 until depth).map(i => i.U(addrW.W))))
  val sp    = RegInit(depth.U(cntW.W))   // sp = 空闲条目数

  io.count := sp
  io.ok    := sp =/= 0.U
  io.addr  := stack(sp - 1.U)

  // 同一拍既 alloc 又 free：净数量不变，先写后读（把归还的地址直接塞进刚挖出的坑）
  when(io.alloc && io.free) {
    stack(sp - 1.U) := io.faddr
  }.elsewhen(io.alloc && io.ok) {
    sp := sp - 1.U
  }.elsewhen(io.free && sp =/= depth.U) {
    stack(sp) := io.faddr
    sp := sp + 1.U
  }
}
