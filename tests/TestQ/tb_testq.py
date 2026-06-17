import cocotb

from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

@cocotb.test()
async def sim_simple(dut):

    tt = COCOTB_Bridge(dut) # tt = test top
    await tt.setup()

    npxs = tt.addrmap.npxs
    nrows = tt.addrmap.nrows
    pxbw = tt.addrmap.pxbw
    threshold = tt.addrmap.threshold
    axibw = 32

    # helper functions
    async def readval(k):
        addr = getattr(tt.addrmap, k)
        return await tt.readWord(addr)

    async def writeval(k, v, offset = 0):
        addr = getattr(tt.addrmap, k)
        await tt.writeWord(addr + offset, v)

    async def softreset(cycles=5):
        await writeval("reset_wr", 1)
        reset_done = 0
        maxloopcnt = 1000
        loopcnt = 0
        while reset_done == 0:
            reset_done = await readval("reset_done_rd")
            if loopcnt > maxloopcnt:
                raise RuntimeError("Reached maxloopcnt. Something wrong")

    async def startfeed() -> None:
        await writeval("startfeed_wr", 1)
        inqcnt = await readval("inqcnt_rd")
        assert inqcnt > 0
        done = False
        while not done:
            drained = await readval("drained_rd")
            if drained > 0:
                done = True

    def pack_pixels(pxs: list[int], pxbw: int) -> int:
        mask = (1 << pxbw) - 1
        packed = 0
        for p in pxs:
            packed = (packed << pxbw) | (p & mask)
        return packed

    def split2axiwords(value: int, axibw: int = 32) -> list[int]:
        mask = (1 << axibw) - 1
        chunks = []
        while value:
            chunks.append(value & mask)
            value >>= axibw
        return chunks

    async def commitRow(rowid: int, pxs: list[int], pxbw: int) -> None:
        packedpxs = pack_pixels(pxs, pxbw)
        words = split2axiwords(packedpxs)
        for i, w in enumerate(words):
            await writeval("fillup_wr", w, offset = i * 4)
        await writeval("commit_wr", rowid)


    # test start here
    c1 = await readval("const1_rd")
    c2 = await readval("const2_rd")
    assert c1 == tt.addrmap.const1
    assert c2 == tt.addrmap.const2

    softreset()

    rowpxs = [threshold + 2 if i == 5 else 0 for i in range(npxs)]
    for i in range(nrows):
        await commitRow(i, rowpxs, pxbw)

    await startfeed()


    outqcnt = await readval("outqcnt_rd")
    assert outqcnt == 1

    outq = await readval("outq_rd")
    assert outq == nrows

    outqcnt = await readval("outqcnt_rd")
    assert outqcnt == 0

    tt.writelog("Done!!\n")
