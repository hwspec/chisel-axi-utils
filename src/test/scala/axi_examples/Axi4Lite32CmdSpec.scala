// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axiexamples

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import axi._
import axi.AxiModuleParamsHelper._
import axi_examples.{Axi4Lite32Cmd, CmdModuleParams}

class Axi4Lite32CmdSpec extends AnyFlatSpec with ChiselSim {
  val const1val = 0xbeefcafeL
  val const2val = getGitHash()
  val p = CmdModuleParams.default(const1val, const2val)

  "test AxiList32Cmd" should "pass" in {
    simulate(new Axi4Lite32Cmd(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM(dut)
      bfm.initMaster()

      def readval(addr: Long) : BigInt = bfm.read(addr)._1
      def writeval(addr: Long, v: BigInt, offset : Int = 0) : BigInt = bfm.write(addr+offset, v)
      def resetdut(): Unit = {
        writeval(p.reset_wr, 1) // initiate reset
        var reset_done = false
        val maxloopcnt = 1000
        var loopcnt = 0
        while (!reset_done) {
          reset_done = readval(p.reset_done_rd) > 0
          if (loopcnt > maxloopcnt) {
            throw new RuntimeException("Reached maxloopcnt. Something wrong")
          }
        }
      }

      assert(readval(p.const1_rd) == const1val)
      assert(readval(p.const2_rd) == const2val)

      val testval = 1234
      writeval(p.dut_wr, testval)
      val v1 = readval(p.dut_rd)
      assert(v1 == testval)

      // testing softreset
      resetdut()
      val v2 = readval(p.dut_rd)
      assert(v2 == 0)

      println("Axi4Lite32Cmd test passed!")
    }
  }
}
