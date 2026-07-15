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
import axi.AxiModuleParamsHelper._
import upickle.default._
import chisel3._
import chisel3.util._

case class TestQModuleParams( // Note: do not put default value here
                              // DefParams
                              soft_reset_rw: Long,
                              // module addr params
                              const1_r : Long,
                              const2_r : Long,
                              commit_w : Long, // takes rowid and commit staging to the input Q
                              start_feed_w : Long, // start feeding data after filling up the input fifo
                              drained_r : Long,
                              inq_cnt_r : Long, // returns the input Q count
                              outq_r : Long,
                              outq_cnt_r : Long,
                              fillup_w : Long, // filling up a staging buf. 0x1008 means 64-bit position.
                              // internal definition
                              reset_cycles : Int, // soft reset
                              const1: Long,
                              const2 : Long,
                              // design params
                              npxs : Int,
                              nrows : Int,
                              pxbw : Int,
                              inqsize : Int,
                              outqsize : Int,
                              threshold : Int,
                            ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "TestQ"
}

object TestQModuleParams {
  implicit val rw: ReadWriter[TestQModuleParams] = macroRW

  def default(const1: Long, const2: Long,
              npxs: Int = 64, nrows: Int = 32, pxbw: Int = 12,
              inqsize: Int = 1024, outqsize : Int = 16, threshold : Int= 20
             ): TestQModuleParams =
    new TestQModuleParams(soft_reset_rw = 0x0,
      const1 = const1, const2 = const2,
      const1_r = 0x10, const2_r = 0x14,
      commit_w = 0x20, start_feed_w = 0x30, drained_r = 0x34, inq_cnt_r = 0x38,
      outq_r = 0x40, outq_cnt_r = 0x44, fillup_w = 0x1000,
      reset_cycles = 8,
      npxs = npxs, nrows = nrows, pxbw = pxbw,
      inqsize = inqsize, outqsize = outqsize, threshold = threshold,
    )
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

class Axi4Lite32TestQ(p : TestQModuleParams,
                      debugprint: Boolean = false)
  extends Module with HasAxiLite32IO {
  val S = IO(new AxiLite32IO())

  val axibw = 32
  val nbitsperrow = p.npxs * p.pxbw
  val nwordsperrow = (nbitsperrow + axibw - 1) / axibw
  val nbytesperrow = nwordsperrow * (axibw / 8)

  if (debugprint) {
    // print params, not RTL
    println(f"nbitsperrow : ${nbitsperrow}")
    println(f"nwordsperrow : ${nwordsperrow}")
    println(f"nbytesperrow : ${nbytesperrow}")
  }

  require(p.threshold < (1 << p.pxbw), f"threshold should be less than ${1 << p.pxbw}: ${p.threshold}")
  require(nbitsperrow < 4096, "npxs*pxbw should be less equal than 4096")

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // soft reset handling logic for dut
  val softResetReg = RegInit(false.B)
  val softResetDoneReg = RegInit(false.B)
  val resetCounterReg = RegInit(0.U(log2Ceil(p.reset_cycles).W))
  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg := false.B
    softResetDoneReg := true.B
  }
  val combinedReset: AsyncReset = (softResetReg || reset.asBool).asAsyncReset

  class RowData(npxs: Int, pxbw: Int) extends Bundle {
    val rowid = UInt(log2Ceil(p.nrows).W)
    val pixels = Vec(npxs, UInt(pxbw.W))
  }

  // instantiate your dut here
  val dut = withReset(combinedReset) {
    Module(new BinImageCount(npxs = p.npxs, pxbw = p.pxbw, nrows = p.nrows, threshold = p.threshold,
      outbw = axibw, debugprint = debugprint))
  }
  dut.io.in := 0.U.asTypeOf(Vec(p.npxs, UInt(p.pxbw.W)))
  dut.io.rowid := 0.U
  dut.io.valid := false.B
  dut.io.out.ready := false.B

  val outputQ = withReset(combinedReset) {
    Module(new Queue(UInt(axibw.W), entries = p.outqsize))
  }
  outputQ.io.enq.valid := false.B
  outputQ.io.enq.bits.asUInt := 0.U
  outputQ.io.deq.ready := false.B
  outputQ.io.enq <> dut.io.out

  val inputQ = withReset(combinedReset) {
    Module(new Queue(new RowData(p.npxs, p.pxbw), entries = p.inqsize))
  }
  inputQ.io.enq.valid := false.B
  inputQ.io.enq.bits := 0.U.asTypeOf(new RowData(p.npxs, p.pxbw))
  inputQ.io.deq.ready := false.B

  // staging
  val stagingRowPixelsReg = withReset(combinedReset) {
    RegInit(VecInit(Seq.fill(nwordsperrow)(0.U(axibw.W))))
  }
  val stagingRowIDReg = withReset(combinedReset) {
    RegInit(0.U(log2Ceil(p.nrows).W))
  }
  val commitReg = withReset(combinedReset) {
    RegInit(false.B)
  }
  val stagingbits = stagingRowPixelsReg.asUInt

  inputQ.io.enq.bits.pixels := stagingbits(p.npxs * p.pxbw - 1, 0).asTypeOf(Vec(p.npxs, UInt(p.pxbw.W)))
  inputQ.io.enq.bits.rowid := stagingRowIDReg
  when(commitReg) {
    inputQ.io.enq.valid := true.B // Note: the producer check inq cnt
    commitReg := false.B
  }

  // feeding inq to dut
  object InputFeedSeq extends ChiselEnum {
    val Idle, Feeding, Draining = Value
  }

  val inputFeedStatusReg = withReset(combinedReset) { RegInit(InputFeedSeq.Idle) }
  val drainingCntReg = withReset(combinedReset) { RegInit(0.U(log2Ceil(p.inqsize).W)) }
  val drainedReg = withReset(combinedReset) { RegInit(false.B) }

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
    }.elsewhen(a === p.soft_reset_rw.U) {
      if (debugprint) printf("%d: reset wr\n", cycles)
      softResetReg := true.B
      resetCounterReg := p.reset_cycles.U
      softResetDoneReg := false.B
      bresp := OKAY.U
    }.elsewhen(a === p.commit_w.U) {
      if (debugprint) printf("%d: commit wr: rowid=%d\n", cycles, wHoldDataReg)
      stagingRowIDReg := wHoldDataReg
      commitReg := true.B
    }.elsewhen(a === p.start_feed_w.U) {
      if (debugprint) printf("%d: startfeed wr\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Feeding
      drainingCntReg := p.nrows.U // tentatively.  add "draincycles_wr"
      drainedReg := false.B
    }.elsewhen(a >= p.fillup_w.U &&
      a < (p.fillup_w + nbytesperrow).U) {
      val wordoffset = ((a - p.fillup_w.U) >> 2.U)(log2Ceil(nwordsperrow) - 1,0)
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

    when(araddr === p.const1_r.U) {
      if (debugprint) printf("%d: const1 rd\n", cycles)
      rdataReg := p.const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.const2_r.U) {
      if (debugprint) printf("%d: const2 rd\n", cycles)
      rdataReg := p.const2.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.soft_reset_rw.U) {
      if (debugprint) printf("%d: reset done rd\n", cycles)
      rdataReg := softResetDoneReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.drained_r.U) {
      if (debugprint) printf("%d: drained %d\n", cycles, drainedReg)
      rdataReg := drainedReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.inq_cnt_r.U) {
      if (debugprint) printf("%d: inqcnt rd %d\n", cycles, inputQ.io.count)
      rdataReg := inputQ.io.count
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.outq_r.U) {
      outputQ.io.deq.ready := true.B
      if (debugprint) printf("%d: out rd valid=%d bits=%d\n", cycles
        , outputQ.io.deq.valid, outputQ.io.deq.bits)
      when(outputQ.io.deq.valid) {
        rdataReg := outputQ.io.deq.bits
      }.otherwise {
        rdataReg := p.const1.U
      }
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.outq_cnt_r.U) {
      if (debugprint) printf("%d: outqcnt rd %d\n", cycles, outputQ.io.count)
      rdataReg := outputQ.io.count
      rstate := RState.COMPLETED
    }.otherwise {
      if (debugprint) printf("%d: bad read req %d\n", cycles, araddr)
      // rrespReg := SLVERR.U // with this, the host can only read 0xffffffff for any addresses on AVED
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
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
  val const1 : Long = 0xdeadbeefL // module id
  val const2 : Long = getGitHash   // return githash id (the first 8 chars)
  val npxs : Int = 8
  val nrows : Int = 4
  val pxbw : Int = 12
  val inqsize : Int = 1024
  val outqsize : Int = 16
  val threshold : Int = 20

  val p = checkParamEnv(
    TestQModuleParams.default(const1 = const1, const2 = const2,
      npxs = npxs, nrows = nrows, pxbw = pxbw,
      inqsize = inqsize, outqsize = outqsize, threshold = threshold),
    "TESTQ_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4Lite32TestQ(p), p)
}
