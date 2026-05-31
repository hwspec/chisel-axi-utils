import os, sys, time
import pyaved

from CmdSim import readAddrMap

class RevMemEMU:
    def aved_write32(self, addr, v):
        self.aved.write32(addr, v)

    def aved_read32(self, addr):
        v = self.aved.read32(addr)
        return v

    def __init__(self, base=0x1100000, dev="b1:00.0", logfn = ""):
        self.base = base

        self.addrmap = self.readAddrMap()
        
        self.aved = pyaved.AVED()
        self.aved.open(dev)

        if len(logfn) > 0:
            self.outputfn = logfn
        else:
            self.outputfn = "output.txt"

        if os.path.exists(self.outputfn):
            os.remove(self.outputfn)

        self.writelog("FPGA Emulation Initialized!\n")
        
    def writelog(self, txt):
        print(txt, end='')
        with open(self.outputfn, "a")  as f:
            f.write(txt)

    def checkOffset(self, offset, align=8):
        assert (offset % align) == 0, f"offset should be aligned with {align} bytes : {offset}"

    def writeWord(self, offset, data):
        self.checkOffset(offset, align=4)
        self.aved_write32(self.base + offset, data)

    def readWord(self, offset):
        self.checkOffset(offset, align=4)
        return self.aved_read32(self.base + offset)

    # for convenience
    def softreset(self):
        self.writeWord(self.addrmap.reset_write_addr, 1)

    def writeDut(self, val):
        self.writeWord(self.addrmap.dut_write_addr, val)

    def readDut(self):
        v = self.readWord(self.addrmap.dut_read_addr)
        return v
