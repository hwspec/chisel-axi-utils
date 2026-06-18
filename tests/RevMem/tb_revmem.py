import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

def rev32bits(x: int):
    x &= 0xFFFFFFFF
    x = ((x & 0x55555555) << 1)  | ((x >> 1)  & 0x55555555)
    x = ((x & 0x33333333) << 2)  | ((x >> 2)  & 0x33333333)
    x = ((x & 0x0F0F0F0F) << 4)  | ((x >> 4)  & 0x0F0F0F0F)
    x = ((x & 0x00FF00FF) << 8)  | ((x >> 8)  & 0x00FF00FF)
    x = ((x & 0x0000FFFF) << 16) | ((x >> 16) & 0x0000FFFF)
    return x & 0xFFFFFFFF

async def validateRevWord(rev, addr, input):
    v = await rev.readWord(0)
    ref = rev32bits(input)

    rev.writelog(f"{input:08x} => {v:08x}\n")
    
    assert v == ref, f"v={v:08x} ref={ref:08x}"

@cocotb.test()
async def tb_revmem(dut):
    rev = COCOTB_Bridge(dut)
    await rev.setup()

    addr = 0
    val = 0x1364e
    await rev.writeWord(addr, val)
    
    await validateRevWord(rev, addr, val)

    rev.writelog("Done!!\n")
