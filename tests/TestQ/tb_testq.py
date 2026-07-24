import cocotb

from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

import random

@cocotb.test()
async def sim_simple(cocotb_dut):

    tt = COCOTB_Bridge(cocotb_dut) # tt = test top
    await tt.setup()

    await tt.expectWord(tt.p.const1_r, tt.p.const1)
    await tt.expectWord(tt.p.const2_r, tt.p.const2)

    axibw = 32
    npxs = tt.p.npxs
    nrows = tt.p.nrows
    pxbw = tt.p.pxbw
    threshold = tt.p.threshold
    tt.log.info(f"Params: npxs={npxs} nrows={nrows} pxbw={pxbw} threshold={threshold}")

    async def startfeed() -> None:
        inqcnt = await tt.readWord(tt.p.inq_cnt_r)
        assert inqcnt > 0
        await tt.writeWord(tt.p.start_feed_w, 1)
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

    def split2axiwords(value: int, nwords: int, axibw: int = 32) -> list[int]:
        mask = (1 << axibw) - 1
        chunks = [(value >> (axibw * i)) & mask for i in range(nwords)]
        return chunks

    async def commitRow(rowid: int, pxs: list[int], pxbw: int) -> None:
        packedpxs = pack_pixels(pxs, pxbw)
        nwords = (len(pxs) * pxbw) // axibw
        words = split2axiwords(packedpxs, nwords, axibw)
        for i, w in enumerate(words):
            await tt.writeWord(tt.p.fillup_w + (i*4), w)
        await tt.writeWord(tt.p.commit_w, rowid)


    # test start here
    c1 = await tt.readWord(tt.p.const1_r)
    c2 = await tt.readWord(tt.p.const2_r)
    assert c1 == tt.p.const1
    assert c2 == tt.p.const2

    async def checkOutput(nzcnt):
        await startfeed()

        outqcnt = await tt.readWord(tt.p.outq_cnt_r)
        assert outqcnt == 1

        outq = await tt.readWord(tt.p.outq_r)
        assert outq == nzcnt

        outqcnt = await tt.readWord(tt.p.outq_cnt_r)
        assert outqcnt == 0
    
    async def testRndPattern():
        tt.log.info("testRndPattern ...")
        await tt.softReset()

        nzcnt = 0
        for i in range(nrows):
            rowpxs = [threshold + 2 if random.choice([True, False]) else 0 for i in range(npxs)]
            rownzcnt = sum(1 for x in rowpxs if x != 0)
            nzcnt += rownzcnt
            await commitRow(i, rowpxs, pxbw)

        await checkOutput(nzcnt)

    async def testFixPattern():
        tt.log.info("testFixPattern ...")
        await tt.softReset()

        nzcnt = 0
        for i in range(nrows):
            rowpxs = [threshold + 2 if (j%4) == 0 or j <= i else 0 for j in range(npxs)]
            nzcnt += sum(1 for x in rowpxs if x != 0)
            await commitRow(i, rowpxs, pxbw)

        await checkOutput(nzcnt)
        
    for _ in range(0, 5):
        await testFixPattern()

    for _ in range(0, 5):
        await testRndPattern()

    tt.log.info("Done!!")
