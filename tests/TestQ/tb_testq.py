import cocotb

from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

@cocotb.test()
async def sim_simple(dut):
    tt = COCOTB_Bridge(dut) # tt = test top
    await tt.setup()

    # helper functions
    async def readval(k):
        addr = getattr(tt.addrmap, k)
        return await tt.readWord(addr)

    async def writeval(k, v):
        addr = getattr(tt.addrmap, k)
        await tt.writeWord(addr, v)

    async def softreset(cycles=5):
        await writeval("reset_wr", 1)
        reset_done = 0
        maxloopcnt = 1000
        loopcnt = 0
        while reset_done == 0:
            reset_done = await readval("reset_done_rd")
            if loopcnt > maxloopcnt:
                raise RuntimeError("Reached maxloopcnt. Something wrong")

    # test start here
    c1 = await readval("const1_rd")
    c2 = await readval("const2_rd")
    assert c1 == tt.addrmap.const1
    assert c2 == tt.addrmap.const2

    softreset()

    tt.writelog("Done!!\n")
