// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axi_examples

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import axi._
import axi_examples.Axi4Lite32TestQ
import axi_examples.TestQAXIDef._

class Axi4Lite32TestQSpec extends AnyFlatSpec with ChiselSim {
  val const1val = 0xbeefcafeL
  val const2val = 0xbad0f00dL
  val npxs = 8
  val nrows = 4
  val pxbw = 12
  val threshold = 20
  val axibw = 32

  def mkmask(p1: Int, p2: Int) : BigInt = {
    val width = p2 - p1 + 1
    (BigInt(1) << width) - 1
  }
  def updateBits(x: BigInt, pos1: Int, pos2: Int, value: BigInt): BigInt = {
    require(pos1 >= 0 && pos2 >= pos1)
    val fieldMask = mkmask(pos1, pos2)
    val mask = fieldMask << pos1                  // positioned mask
    val cleared = x & ~mask                       // clear target bits
    val inserted = (value & fieldMask) << pos1    // truncate and position value
    cleared | inserted
  }
  def updateField(currow : BigInt, pos : Int, v : Int, bw : Int) : BigInt = {
    val bpos1 = pos * bw
    val bpos2 = bpos1 + bw - 1
    updateBits(currow, bpos1, bpos2, v)
  }
  def readField(currow : BigInt, pos : Int, bw : Int) : BigInt = {
    val bpos1 = pos * bw
    val bpos2 = bpos1 + bw - 1
    val fieldMask = mkmask(bpos1, bpos2)
    (currow >> bpos1) & fieldMask
  }

  "foo" should "pass" in {
    var row: BigInt = 0
    for(i <- 0 until npxs) {
      row = updateField(row, i, 10 + i, pxbw)
    }
    for(i <- 0 until npxs) {
      assert(readField(row, i, pxbw) == 10 + i)
    }
  }

  "test AxiList32TestQ" should "pass" in {
    simulate(new Axi4Lite32TestQ(const1 = const1val, const2 = const2val,
      npxs = npxs, nrows = nrows,  pxbw = pxbw,  threshold = threshold,
      debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM(dut)
      bfm.initMaster()

      def readval(k: String) : BigInt = bfm.read(axiaddrmap(k))._1
      def writeval(k: String, v: BigInt, offset : Int = 0) : BigInt = bfm.write(axiaddrmap(k)+offset, v)

      assert(readval("const1_rd") == const1val)
      assert(readval("const2_rd") == const2val)

      def resetdut(): Unit = {
        writeval("reset_wr", 1) // initiate reset
        var reset_done = false
        val maxloopcnt = 1000
        var loopcnt = 0
        while (!reset_done) {
          reset_done = readval("reset_done_rd") > 0
          if (loopcnt > maxloopcnt) {
            throw new RuntimeException("Reached maxloopcnt. Something wrong")
          }
        }
      }
      def startfeed() : Unit = {
        writeval("startfeed_wr", 1)
        assert(readval("inqcnt_rd") > 0)
        var done = false
        while(!done) {
          if (readval("drained_rd") > 0)
            done = true
        }
      }

      def commitRow(rowid : Int, pxs: List[Int]) : Unit = {
        var tmp : BigInt = 0
        for (e <- pxs.zipWithIndex) {
          tmp = updateField(tmp, pos=e._2, v=e._1, bw=pxbw)
        }
        val nwords = ((npxs*pxbw) + axibw - 1)/axibw
        for (i <- 0 until nwords) {
          val v = readField(tmp, i, axibw)
          writeval("fillup_wr", v, offset = i*4)
        }
        writeval("commit_wr", rowid)
      }

      resetdut()

      val rowpxs = List.tabulate(npxs) { i => if (i==5) threshold+2 else 0}
      for (i <- 0 until nrows) {     commitRow(i, rowpxs)     }
      val inqcnt = readval("inqcnt_rd")
      assert(inqcnt == nrows)

      startfeed()

      assert(readval("outqcnt_rd")==1)
      assert(readval("outq_rd")==nrows)
      assert(readval("outqcnt_rd")==0)

      println("Axi4Lite32TestQ test passed!")
    }
  }
}
