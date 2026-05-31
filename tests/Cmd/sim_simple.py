from CmdSIM import *


@cocotb.test()
async def sim_simple(dut):
    cmd = CmdSIM(dut)
    await cmd.setup()

    c1 = await cmd.readWord(cmd.addrmap.const1_read_addr)
    c2 = await cmd.readWord(cmd.addrmap.const2_read_addr)
    assert c1 == cmd.addrmap.const1
    assert c2 == cmd.addrmap.const2

    testval = 0x555aaa
    await cmd.writeDut(testval)
    v = await cmd.readDut()
    cmd.writelog(f"{v:08x}\n")
    assert v == testval

    await cmd.softreset()
    v = await cmd.readDut()
    cmd.writelog(f"{v:08x}\n")
    assert v == 0

    cmd.writelog("Done!!\n")
