from AXISIM import *

@cocotb.test()
async def sim_simple(dut):
    tt = AXISIM(dut) # tt = test top
    await tt.setup()

    c1 = await tt.readWord(tt.addrmap.const1_rd)
    c2 = await tt.readWord(tt.addrmap.const2_rd)
    assert c1 == tt.addrmap.const1
    assert c2 == tt.addrmap.const2

    tt.writelog("Done!!\n")
