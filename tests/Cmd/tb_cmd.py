import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

@cocotb.test()
async def sim_cmd(dut):
    cmd = COCOTB_Bridge(dut)
    await cmd.setup()
    
    c1 = await cmd.readWord(cmd.addrmap.const1_rd)
    c2 = await cmd.readWord(cmd.addrmap.const2_rd)
    assert c1 == cmd.addrmap.const1
    assert c2 == cmd.addrmap.const2

    async def softreset(cycles=5):
        await cmd.writeWord(cmd.addrmap.reset_wr, 1)
        reset_done = 0
        maxloopcnt = 1000
        loopcnt = 0
        while reset_done == 0:
            reset_done = await cmd.readWord(cmd.addrmap.reset_done_rd)
            if loopcnt > maxloopcnt:
                raise RuntimeError("Reached maxloopcnt. Something wrong")
        
    async def writeDut(val):
        await cmd.writeWord(cmd.addrmap.dut_wr, val)

    async def readDut():
        v = await cmd.readWord(cmd.addrmap.dut_rd)
        return v

    testval = 0x555aaa
    await writeDut(testval)
    v = await readDut()
    cmd.writelog(f"{v:08x}\n")
    assert v == testval

    await softreset()
    
    v = await readDut()
    cmd.writelog(f"{v:08x}\n")
    assert v == 0

    cmd.writelog("Done!!\n")
