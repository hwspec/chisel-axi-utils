import cocotb

from axi_test_bridge.cocotb_axi_bridge import COCOTB_Bridge

@cocotb.test()
async def sim_simple(cocotb_dut):

    tt = COCOTB_Bridge(cocotb_dut) # tt = test top
    await tt.setup()

    npxs = tt.p.npxs
    nrows = tt.p.nrows
    pxbw = tt.p.pxbw
    threshold = tt.p.threshold
    axibw = 32

    async def startfeed() -> None:
        await tt.writeWord(tt.p.start_feed_w, 1)
        inqcnt = await tt.readWord(tt.p.inq_cnt_r)
        assert inqcnt > 0
        done = False
        while not done:
            drained = await tt.readWord(tt.p.drained_r)
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
            await tt.writeWord(tt.p.fillup_w + (i*4), w)
        await tt.writeWord(tt.p.commit_w, rowid)


    # test start here
    c1 = await tt.readWord(tt.p.const1_r)
    c2 = await tt.readWord(tt.p.const2_r)
    assert c1 == tt.p.const1
    assert c2 == tt.p.const2

    async def testFixPattern():
        await tt.softReset()

        rowpxs = [threshold + 2 if i == 5 else 0 for i in range(npxs)]
        for i in range(nrows):
            await commitRow(i, rowpxs, pxbw)

        await startfeed()

        outqcnt = await tt.readWord(tt.p.outq_cnt_r)
        assert outqcnt == 1

        outq = await tt.readWord(tt.p.outq_r)
        assert outq == nrows

        outqcnt = await tt.readWord(tt.p.outq_cnt_r)
        assert outqcnt == 0

    for _ in range(0, 3):
        await testFixPattern()

    tt.log.info("Done!!")
