// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axiexamples

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import axi._
import axi_examples.Axi4Lite32Cmd
import axi_examples.CmdAXIDef._

class Axi4Lite32CmdSpec extends AnyFlatSpec with ChiselSim {
  val const1val = 0xbeefcafeL
  val const2val = 0xbad0f00dL
  "test AxiList32Cmd" should "pass" in {
    simulate(new Axi4Lite32Cmd(const1 = const1val, const2 = const2val, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM(dut)
      bfm.initMaster()

      def readval(k: String) : BigInt = bfm.read(axiaddrmap(k))._1
      def writeval(k: String, v: BigInt, offset : Int = 0) : BigInt = bfm.write(axiaddrmap(k)+offset, v)
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
      val const1addr  = axiaddrmap("const1_rd")
      val const2addr  = axiaddrmap("const2_rd")
      val resetwraddr = axiaddrmap("reset_wr")
      val dutwraddr   = axiaddrmap("dut_wr")
      val dutrdaddr   = axiaddrmap("dut_rd")

      assert(readval("const1_rd") == const1val)
      assert(readval("const2_rd") == const2val)

      val testval = 1234
      writeval("dut_wr", testval)
      val v1 = readval("dut_rd")
      assert(v1 == testval)

      resetdut()

      val v2 = readval("dut_rd")
      assert(v2 == 0)

      println("Axi4Lite32Cmd test passed!")
    }
  }
}
