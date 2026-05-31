package axi_examples

import axi._
import axi.AxiLiteResp._
import chisel3._
import chisel3.util._
import firrtl.ir.BundleType

case object CmdAXIDef extends AxiAddrMapBase {
  // definition to export
  val addrMapEntries = Seq(
    AddrMapEntry("CONST1_read_addr", 0x0),
    AddrMapEntry("CONST2_read_addr", 0x4),
    AddrMapEntry("RESET_write_addr", 0x8),
    AddrMapEntry("dut_write_addr",   0x10),
    AddrMapEntry("dut_read_addr",    0x14),
  )
  checkaddr(addrMapEntries) // sanity check

  // internal definition
  val RESET_CYCLES = 8 // soft reset
}

// replace Dut with your actual dut
class Dut(bw : Int = 32) extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(bw.W))
    val invalid = Input(Bool())
    val out = Output(UInt(bw.W))
  })
  val valReg = RegInit(0.U(bw.W))
  when(io.invalid) {
    valReg := io.in
  }
  io.out := valReg
}

class Axi4Lite32Cmd (const1: Long = 0xdeadbeefL, const2: Long = 0xfeedcafeL, bw: Int = 32,
                     debugprint: Boolean = false)
  extends Module with HasAxiLite32IO {

  val S = IO(new AxiLite32IO())

  import CmdAXIDef._

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // soft reset handling logic for dut
  val softResetReg = RegInit(false.B)
  val resetCounterReg = RegInit(0.U(5.W))
  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg := false.B
  }
  val combinedReset: AsyncReset = (softResetReg || reset.asBool).asAsyncReset

  // instantiate your dut here
  val dut = withReset(combinedReset) {
    Module(new Dut())
  }
  dut.io.in := 0.U
  dut.io.invalid := false.B

  // -----------------------------
  // AXI-lite regs
  // -----------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg = Reg(UInt(bw.W))
  val wHoldValidReg = RegInit(false.B)
  val wHoldDataReg = Reg(UInt(32.W))
  val wHoldStrbReg = Reg(UInt(4.W))

  val bvalidReg = RegInit(false.B)
  val brespReg = RegInit(0.U(2.W))

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready := !wHoldValidReg && !bvalidReg
  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire = S.AXI.wvalid && S.AXI.wready
  when(awFire) {
    awHoldValidReg := true.B;
    awHoldAddrReg := S.AXI.awaddr(19, 0) // in case MMIO range is 1MB
  }
  when(wFire) {
    wHoldValidReg := true.B
    wHoldDataReg := S.AXI.wdata
    wHoldStrbReg := S.AXI.wstrb
  }

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg
  val addrHoldReg = RegInit(0.U(bw.W))

  when(doWrite) {
    val a = awHoldAddrReg
    val fullWrite = (wHoldStrbReg === "b1111".U)

    val bresp = WireDefault(OKAY.U)

    when(!fullWrite) { // support full write only for this example
      bresp := SLVERR.U
    }.elsewhen(a === axiaddrmap("RESET_write_addr").U) {
      softResetReg := true.B
      resetCounterReg := RESET_CYCLES.U
      bresp := OKAY.U
    }.elsewhen(a === axiaddrmap("dut_write_addr").U) {
      dut.io.invalid := true.B
      dut.io.in := wHoldDataReg
    }.otherwise {
      brespReg := AxiLiteResp.SLVERR.U
    }
    brespReg := bresp
    bvalidReg := true.B
    awHoldValidReg := false.B
    wHoldValidReg := false.B
  }

  when(bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }
  S.AXI.bvalid := bvalidReg
  S.AXI.bresp := brespReg

  // -----------------------------
  // Read path: AR -> R
  // -----------------------------
  val rdataReg = Reg(UInt(32.W))
  val rrespReg = RegInit(0.U(2.W))

  object RState extends ChiselEnum {
    val READY2READ, COMPLETED = Value
  }

  val rstateReg = RegInit(RState.READY2READ)

  S.AXI.arready := rstateReg === RState.READY2READ
  S.AXI.rvalid := rstateReg === RState.COMPLETED
  S.AXI.rdata := rdataReg
  S.AXI.rresp := rrespReg

  val arFire = S.AXI.arvalid && S.AXI.arready

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0) // 1MB range
    rrespReg := OKAY.U

    val rstate = WireDefault(RState.READY2READ)

    when(araddr === axiaddrmap("CONST1_read_addr").U) {
      rdataReg := const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("CONST2_read_addr").U) {
      rdataReg := const2.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("dut_read_addr").U) {
      rdataReg := dut.io.out
      rstate := RState.COMPLETED
    }.otherwise {
      if (debugprint) printf("%d: bad read req %d\n", cycles, araddr)
      rrespReg := SLVERR.U
      rdataReg := S.AXI.araddr(31, 0) // debug purpose
      if (debugprint) printf(cf"arFire otherwise: addr=${S.AXI.araddr}%16x\n")
      rstate := RState.COMPLETED
    }
    rstateReg := rstate
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object Axi4Lite32Cmd extends App {
  import CmdAXIDef._
  val const1 : Long = 0xdeadbeefL // module id
  val const2 : Long = githash()   // return githash id (the first 8 chars)

 EmitVerilog.generate(new Axi4Lite32Cmd(const1 = const1, const2 = const2, debugprint=true),
   opts = Map("vivado" -> "v80", "axiwrapper" -> "bd"),
   addrmap = Some(CmdAXIDef)
 )
}
