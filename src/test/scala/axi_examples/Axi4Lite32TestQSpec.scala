// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axiexamples

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import axi._
import axi_examples.Axi4Lite32TestQ
import axi_examples.TestQAXIDef._

class Axi4Lite32TestQSpec extends AnyFlatSpec with ChiselSim {
  val const1val = 0xbeefcafeL
  val const2val = 0xbad0f00dL
  val npxs = 64
  val nrows = 16
  val pxbw = 12
  val threshold = 20

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

  def updatePixel(currow : BigInt, pos : Int, v : Int) : BigInt = {
    val bpos1 = pos * pxbw
    val bpos2 = bpos1 + pxbw - 1
    updateBits(currow, bpos1, bpos2, v)
  }
  def readPixel(currow : BigInt, pos : Int) : BigInt = {
    val bpos1 = pos * pxbw
    val bpos2 = bpos1 + pxbw - 1
    val fieldMask = mkmask(bpos1, bpos2)
    (currow >> bpos1) & fieldMask
  }

  "foo" should "pass" in {
    var row: BigInt = 0
    for(i <- 0 until npxs) {
      row = updatePixel(row, i, 10 + i)
    }
    for(i <- 0 until npxs) {
      assert( readPixel(row, i) == 10 + i)
    }
  }

  "test AxiList32TestQ" should "pass" in {
    simulate(new Axi4Lite32TestQ(const1 = const1val, const2 = const2val, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM(dut)
      bfm.initMaster()

      def readval(k: String) : BigInt = bfm.read(axiaddrmap(k))._1
      def writeval(k: String, v: BigInt) : BigInt = bfm.write(axiaddrmap(k), v)

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
      resetdut()

      /*
      val testval = 1234
      bfm.write(dutwraddr, testval)
      val v1 = bfm.read(dutrdaddr)._1
      assert(v1 == testval)

      bfm.write(resetwraddr, 1) // do softreset
      val v2 = bfm.read(dutrdaddr)._1
      assert(v2 == 0)
*/
      println("Axi4Lite32TestQ test passed!")
    }
  }
}
