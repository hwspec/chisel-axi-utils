package axi_examples

//
// This example buffers image rows written through AXI4-Lite and injects them
// into a DUT at one row per cycle.
//
// Each image row has:
//
//   ROW_BITS = W * P
//
// Since ROW_BITS is usually wider than the AXI4-Lite data width, the host fills
// one row using multiple AXI4-Lite writes:
//
//   N_WRITES = ceil(ROW_BITS / AXI_DATA_BITS)
//
// The host writes these words into a row staging register. After one complete
// row is staged, the host writes a commit register, which enqueues the staged
// row into the input row FIFO.
//
// This sequence is repeated until the FIFO contains the required input rows:
//
//   fill row staging register
//   commit row into FIFO
//   repeat
//
// After the FIFO is prepared, the host writes an injection-start register.
// The injector then dequeues one FIFO entry per cycle and drives the DUT with
// one complete row per cycle.
//
// This separates slow AXI4-Lite row loading from fast row-per-cycle DUT input.
//

import axi._
import axi.AxiLiteResp._
import chisel3._
import chisel3.util._
import firrtl.ir.BundleType

case object TestQAXIDef extends AxiAddrMapBase {
  // definition to export
  val addrMapEntries = Seq(
    AddrMapEntry("const1_read_addr", 0x0),
    AddrMapEntry("const2_read_addr", 0x4),
    AddrMapEntry("reset_write_addr", 0x8),

    AddrMapEntry("stage_write_base_addr", 0x100), // up to 4096 bits per row. e.g., 0x108 means 64-bit position.
    AddrMapEntry("rowid_write_addr", 0x300),

    AddrMapEntry("startfeed_write_addr", 0x400), // start feeding data after filling up the input fifo

    AddrMapEntry("outq_read_addr", 0x500)
  )
  checkaddr(addrMapEntries) // sanity check

  // internal definition
  val RESET_CYCLES = 8 // soft reset
}

// dut: an image processor example
//
// The DUT consumes one image row per cycle.
// For each valid row, it binarizes pixels using a threshold,
// counts the number of 1s, and accumulates the count.
//
// firstrow resets the accumulation with the current row count.
// Otherwise, the current row count is added to the running total.
class BinImageCount(npxs: Int, pxbw : Int) extends Module {
  val io = IO(new Bundle {
    val in        = Input(Vec(npxs, UInt(pxbw.W)))
    val firstrow  = Input(Bool())
    val threshold = Input(UInt(pxbw.W))
    val valid     = Input(Bool())

    val out = Output(UInt(32.W))
  })

  val bin = Wire(Vec(npxs, Bool()))
  for (i <- 0 until npxs) {
    bin(i) := io.in(i) >= io.threshold
  }
  val rowPopCount = PopCount(bin.asUInt)

  val totalPopCountReg = RegInit(0.U(32.W))
  io.out := totalPopCountReg

  when(io.valid) {
    when(io.firstrow) {
      totalPopCountReg := rowPopCount
    }.otherwise {
      totalPopCountReg :=  totalPopCountReg + rowPopCount
    }
  }
}


class Axi4Lite32TestQ (const1: Long = 0xdeadbeefL, const2: Long = 0xfeedcafeL,
                       bw: Int = 32,
                       npxs : Int = 128,
                       pxbw : Int = 12,
                       debugprint: Boolean = false)
  extends Module with HasAxiLite32IO {

  val S = IO(new AxiLite32IO())

  import TestQAXIDef._

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
    Module(new BinImageCount(npxs, pxbw))
  }
  dut.io.in := 0.U
  dut.io.valid := false.B

  class RowData(npxs: Int, pxbw: Int) extends Bundle {
    val rowid  = UInt(8.W)
    val pixels = Vec(npxs, UInt(pxbw.W))
  }
  val stagingPixelsReg = RegInit(0.U((npxs*pxbw).W))
  val stagingRowidReg = RegInit(0.U(8.W))

  val inputQ = Module(new Queue(new RowData(npxs, pxbw), entries = 256))
  inputQ.io.enq.valid := false.B
  inputQ.io.enq.bits := 0.U
  inputQ.io.deq.ready := false.B

  val outputQ = Module(new Queue(UInt(32.W), entries = 32))
  outputQ.io.enq.valid := false.B
  outputQ.io.enq.bits := 0.U
  outputQ.io.deq.ready := false.B

  object InputFeedSeq extends ChiselEnum {
    val Idle, Feeding, Completed = Value
  }
  val inputFeedStatusReg = RegInit(InputFeedSeq.Idle)
  val imageid = RegInit(0.U(8.W))

  when(inputFeedStatusReg === InputFeedSeq.Feeding) {
    when(inputQ.io.count === 0.U) {
      inputFeedStatusReg := InputFeedSeq.Completed
    }.otherwise {


    }
  }.elsewhen(inputFeedStatusReg === InputFeedSeq.Completed) {
    outputQ.io.enq.valid := true.B
    when(outputQ.io.enq.ready) {
      outputQ.io.enq.bits := dut.io.out
      inputFeedStatusReg := InputFeedSeq.Idle
    }
  }



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
    }.elsewhen(a === axiaddrmap("reset_write_addr").U) {
      softResetReg := true.B
      resetCounterReg := RESET_CYCLES.U
      bresp := OKAY.U
    }.elsewhen(a === axiaddrmap("dut_write_addr").U) {
      dut.io.valid := true.B
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

    when(araddr === axiaddrmap("const1_read_addr").U) {
      rdataReg := const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("const2_read_addr").U) {
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

object Axi4Lite32TestQ extends App {
  import TestQAXIDef._
  val const1 : Long = 0xdeadbeefL // module id
  val const2 : Long = githash()   // return githash id (the first 8 chars)

  val consts = Map("const1" -> const1, "const2" -> const2)
 EmitVerilog.generate(
   new Axi4Lite32TestQ(const1 = const1, const2 = const2, debugprint=true),
   addrmap = Some(TestQAXIDef),
   constmap = Some(consts)
 )
}
