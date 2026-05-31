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

      val const1addr  = axiaddrmap("const1_read_addr")
      val const2addr  = axiaddrmap("const2_read_addr")
      val resetwraddr = axiaddrmap("reset_write_addr")
      val dutwraddr   = axiaddrmap("dut_write_addr")
      val dutrdaddr   = axiaddrmap("dut_read_addr")

      assert(bfm.read(const1addr)._1 == const1val)
      assert(bfm.read(const2addr)._1 == const2val)

      val testval = 1234
      bfm.write(dutwraddr, testval)
      val v1 = bfm.read(dutrdaddr)._1
      assert(v1 == testval)

      bfm.write(resetwraddr, 1) // do softreset
      val v2 = bfm.read(dutrdaddr)._1
      assert(v2 == 0)

      println("Axi4Lite32Cmd test passed!")
    }
  }
}
