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
    AddrMapEntry("const1_rd",     0x0),
    AddrMapEntry("const2_rd",     0x4),
    AddrMapEntry("reset_wr",      0x8),
    AddrMapEntry("reset_done_rd", 0xc),
    AddrMapEntry("commit_wr",     0x20), // takes rowid and commit staging to the input Q
    AddrMapEntry("startfeed_wr",  0x30), // start feeding data after filling up the input fifo
    AddrMapEntry("drained_rd",    0x34),
    AddrMapEntry("inqcnt_rd",     0x38), // returns the input Q count
    AddrMapEntry("outq_rd",       0x40),
    AddrMapEntry("outqcnt_rd",    0x44),
    AddrMapEntry("fillup_wr",     0x1000), // filling up a staging buf. 0x1008 means 64-bit position.
  )
  checkaddr(addrMapEntries) // sanity check

  // internal definition
  val RESET_CYCLES = 8 // soft reset
}

// dut: an image processor example
//
// The DUT consumes one image row per cycle.
// For each valid row, it binarizes pixels using a threshold,
// counts the number of 1s in the row, and accumulates the count.
//
// rowid = 0 starts a new image accumulation using the current row count.
// For middle rows, the current row count is added to the running total.
// rowid = nrows - 1 adds the current row count, stores the final image count
// into the output register, and raises out.valid.
//
class BinImageCount(npxs: Int, pxbw : Int, nrows: Int, threshold : Int,
                    outbw : Int = 32, debugprint : Boolean = false) extends Module {
  val io = IO(new Bundle {
    val in    = Input(Vec(npxs, UInt(pxbw.W)))
    val rowid = Input(UInt(log2Ceil(nrows).W))
    val valid = Input(Bool())
    val out   = Decoupled(UInt(outbw.W))
  })
  require(nrows > 3, "nrows must be greater than 3")

  io.out.valid := false.B
  io.out.bits := 0.U

  val bin = Wire(Vec(npxs, Bool()))
  for (i <- 0 until npxs) { bin(i) := io.in(i) >= threshold.U }
  val rowPopCount = PopCount(bin.asUInt)

  val totalPopCountReg = RegInit(0.U(outbw.W))

  val outTotalReg = RegInit(0.U(outbw.W))
  val outTotalValidReg = RegInit(false.B)
  io.out.valid := outTotalValidReg
  when(io.out.fire) {
    io.out.bits := outTotalReg
    outTotalReg := 0.U
    outTotalValidReg := false.B
  }

  when(io.valid) {
    when(io.rowid === 0.U) {
      printf("dut: first row\n")
      totalPopCountReg := rowPopCount
    }.elsewhen(io.rowid === (nrows-1).U) {
      printf("dut: last row\n")
      outTotalReg := totalPopCountReg + rowPopCount
      outTotalValidReg := true.B
      totalPopCountReg := 0.U
    }.otherwise {
      printf("dut: %d row\n", io.rowid)
      totalPopCountReg :=  totalPopCountReg + rowPopCount
    }
  }
}

class Axi4Lite32TestQ (const1: Long = 0xdeadbeefL, const2: Long = 0xfeedcafeL,
                       npxs : Int = 128,
                       nrows : Int = 32,
                       pxbw : Int = 12,
                       inqsize : Int = 1024,
                       outqsize : Int = 16,
                       threshold : Int = 20,
                       debugprint: Boolean = false)
  extends Module with HasAxiLite32IO {
  val S = IO(new AxiLite32IO())

  val axibw = 32
  val nbitsperrow = npxs * pxbw
  val nwordsperrow = (nbitsperrow + axibw - 1) / axibw
  val nbytesperrow = nwordsperrow * (axibw / 8)

  if (debugprint) {
    // print params, not RTL
    println(f"nbitsperrow : ${nbitsperrow}")
    println(f"nwordsperrow : ${nwordsperrow}")
    println(f"nbytesperrow : ${nbytesperrow}")
  }

  require(threshold < (1 << pxbw), f"threshold should be less than ${1 << pxbw}: ${threshold}")
  require(nbitsperrow < 4096, "npxs*pxbw should be less equal than 4096")

  import TestQAXIDef._

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // soft reset handling logic for dut
  val softResetReg = RegInit(false.B)
  val softResetDoneReg = RegInit(false.B)
  val resetCounterReg = RegInit(0.U(log2Ceil(RESET_CYCLES).W))
  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg := false.B
    softResetDoneReg := true.B
  }
  val combinedReset: AsyncReset = (softResetReg || reset.asBool).asAsyncReset

  // instantiate your dut here
  val dut = withReset(combinedReset) {
    Module(new BinImageCount(npxs = npxs, pxbw = pxbw, nrows = nrows, threshold = threshold,
      outbw = axibw, debugprint = debugprint))
  }
  dut.io.in := 0.U.asTypeOf(Vec(npxs, UInt(pxbw.W)))
  dut.io.rowid := 0.U
  dut.io.valid := false.B
  dut.io.out.ready := false.B

  val outputQ = Module(new Queue(UInt(axibw.W), entries = outqsize))
  outputQ.io.enq.valid := false.B
  outputQ.io.enq.bits.asUInt := 0.U
  outputQ.io.deq.ready := false.B
  outputQ.io.enq <> dut.io.out

  class RowData(npxs: Int, pxbw: Int) extends Bundle {
    val rowid = UInt(log2Ceil(nrows).W)
    val pixels = Vec(npxs, UInt(pxbw.W))
  }

  val stagingPixelsReg = RegInit(0.U((npxs * pxbw).W))

  val inputQ = Module(new Queue(new RowData(npxs, pxbw), entries = inqsize))
  inputQ.io.enq.valid := false.B
  inputQ.io.enq.bits := 0.U.asTypeOf(new RowData(npxs, pxbw))
  inputQ.io.deq.ready := false.B

  // staging
  val stagingRowPixelsReg = RegInit(VecInit(Seq.fill(nwordsperrow)(0.U(axibw.W))))
  val stagingRowIDReg = RegInit(0.U(log2Ceil(nrows).W))
  val commitReg = RegInit(false.B)
  val stagingbits = stagingRowPixelsReg.asUInt

  inputQ.io.enq.bits.pixels := stagingbits(npxs * pxbw - 1, 0).asTypeOf(Vec(npxs, UInt(pxbw.W)))
  inputQ.io.enq.bits.rowid := stagingRowIDReg
  when(commitReg) {
    inputQ.io.enq.valid := true.B // Note: the producer check inq cnt
    commitReg := false.B
  }

  // feeding inq to dut
  object InputFeedSeq extends ChiselEnum {
    val Idle, Feeding, Draining = Value
  }

  val inputFeedStatusReg = RegInit(InputFeedSeq.Idle)
  val drainingCntReg = RegInit(0.U(log2Ceil(inqsize).W))
  val drainedReg = RegInit(false.B)

  when(inputFeedStatusReg === InputFeedSeq.Feeding) {
    when(inputQ.io.count === 0.U) {
      if (debugprint) printf("%d: move to draining\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Draining
    }.otherwise {
      inputQ.io.deq.ready := true.B
      when(inputQ.io.deq.valid) {
        if (debugprint) printf("%d: feeding rowid=%d cnt=%d\n", cycles, inputQ.io.deq.bits.rowid,
          inputQ.io.count)
        dut.io.in := inputQ.io.deq.bits.pixels
        dut.io.rowid := inputQ.io.deq.bits.rowid
        dut.io.valid := true.B
      }
    }
  }.elsewhen(inputFeedStatusReg === InputFeedSeq.Draining) {
    when (drainingCntReg === 0.U) {
      if (debugprint) printf("%d: drained\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Idle
      drainedReg := true.B
    }.otherwise {
      drainingCntReg := drainingCntReg - 1.U
    }
  }

  // -----------------------------
  // AXI-lite regs
  // -----------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg = Reg(UInt(axibw.W))
  val wHoldValidReg = RegInit(false.B)
  val wHoldDataReg = Reg(UInt(axibw.W))
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
  val addrHoldReg = RegInit(0.U(axibw.W))

  when(doWrite) {
    val a = awHoldAddrReg
    val fullWrite = (wHoldStrbReg === "b1111".U)

    val bresp = WireDefault(OKAY.U)

    when(!fullWrite) { // support full write only for this example
      bresp := SLVERR.U
    }.elsewhen(a === axiaddrmap("reset_wr").U) {
      if (debugprint) printf("%d: reset wr\n", cycles)
      softResetReg := true.B
      resetCounterReg := RESET_CYCLES.U
      softResetDoneReg := false.B
      bresp := OKAY.U
    }.elsewhen(a === axiaddrmap("commit_wr").U) {
      if (debugprint) printf("%d: commit wr: rowid=%d\n", cycles, wHoldDataReg)
      stagingRowIDReg := wHoldDataReg
      commitReg := true.B
    }.elsewhen(a === axiaddrmap("startfeed_wr").U) {
      if (debugprint) printf("%d: startfeed wr\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Feeding
      drainingCntReg := nrows.U // tentatively.  add "draincycles_wr"
      drainedReg := false.B
    }.elsewhen(a >= axiaddrmap("fillup_wr").U &&
      a < (axiaddrmap("fillup_wr") + nbytesperrow).U) {
      val wordoffset = ((a - axiaddrmap("fillup_wr").U) >> 2.U)(log2Ceil(nwordsperrow) - 1,0)
      if (debugprint) printf("%d: filling staging pixels: %x at %d\n", cycles, wHoldDataReg, wordoffset )
      stagingRowPixelsReg(wordoffset) := wHoldDataReg
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

    when(araddr === axiaddrmap("const1_rd").U) {
      if (debugprint) printf("%d: const1 rd\n", cycles)
      rdataReg := const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("const2_rd").U) {
      if (debugprint) printf("%d: const2 rd\n", cycles)
      rdataReg := const2.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("reset_done_rd").U) {
      if (debugprint) printf("%d: reset done rd\n", cycles)
      rdataReg := softResetDoneReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("drained_rd").U) {
      if (debugprint) printf("%d: drained %d\n", cycles, drainedReg)
      rdataReg := drainedReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("inqcnt_rd").U) {
      if (debugprint) printf("%d: inqcnt rd %d\n", cycles, inputQ.io.count)
      rdataReg := inputQ.io.count
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("outq_rd").U) {
      outputQ.io.deq.ready := true.B
      if (debugprint) printf("%d: out rd valid=%d bits=%d\n", cycles
        , outputQ.io.deq.valid, outputQ.io.deq.bits)
      when(outputQ.io.deq.valid) {
        rdataReg := outputQ.io.deq.bits
      }.otherwise {
        rdataReg := const1.U
      }
      rstate := RState.COMPLETED
    }.elsewhen(araddr === axiaddrmap("outqcnt_rd").U) {
      if (debugprint) printf("%d: outqcnt rd %d\n", cycles, outputQ.io.count)
      rdataReg := outputQ.io.count
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
  val npxs : Int = 8
  val nrows : Int = 4
  val pxbw : Int = 12
  val inqsize : Int = 1024
  val outqsize : Int = 16
  val threshold : Int = 20

  val consts = Map("const1" -> const1, "const2" -> const2,
    "npxs" -> npxs.toLong, "nrows" -> nrows.toLong, "pxbw" -> pxbw.toLong,
    "inqsize" -> inqsize.toLong, "outqsize" -> outqsize.toLong, "threshold" -> threshold.toLong)
  EmitVerilog.generate(
    new Axi4Lite32TestQ(const1 = const1, const2 = const2, debugprint=true,
      npxs = npxs, nrows = nrows, pxbw = pxbw, inqsize = inqsize, outqsize = outqsize,
      threshold = threshold),
    addrmap = Some(TestQAXIDef),
    constmap = Some(consts)
  )
}
