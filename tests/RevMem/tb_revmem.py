import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge
import random

def rev32bits(x: int):
    x &= 0xFFFFFFFF
    x = ((x & 0x55555555) << 1)  | ((x >> 1)  & 0x55555555)
    x = ((x & 0x33333333) << 2)  | ((x >> 2)  & 0x33333333)
    x = ((x & 0x0F0F0F0F) << 4)  | ((x >> 4)  & 0x0F0F0F0F)
    x = ((x & 0x00FF00FF) << 8)  | ((x >> 8)  & 0x00FF00FF)
    x = ((x & 0x0000FFFF) << 16) | ((x >> 16) & 0x0000FFFF)
    return x & 0xFFFFFFFF

@cocotb.test
async def tb_revmem(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    for i in range(0, dut.p.n_words):
        addr = i*4
        inp = random.getrandbits(32)
        ref = rev32bits(inp)
        await dut.writeWord(addr, inp)
        v = await dut.readWord(addr)
        # dut.log.info(f"in={inp:08x} ref={ref:08x} dut={v:08x}")
        assert v == ref, f"in={inp:08x} ref={ref:08x} dut={v:08x}"

    dut.log.info("RevMem Verified!!\n")
