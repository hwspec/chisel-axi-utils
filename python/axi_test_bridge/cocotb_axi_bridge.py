# See LICENSE file for details.
# written by Kaz Yoshii <kazutomo.yoshii@gmail.com>

import os, sys, random
import json
from types import SimpleNamespace

import cocotb_test.simulator, pytest
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer
from cocotb.regression import TestFactory
from cocotbext.axi import AxiLiteBus, AxiLiteMaster, AxiLiteRam, AxiResp
import warnings
warnings.filterwarnings("ignore", category=DeprecationWarning)

class COCOTB_Bridge:
    async def reset_dut(self, cycles=5):
        self.dut.s_axi_aresetn.value = 0  # assert (active-low)
        for _ in range(cycles):
            await RisingEdge(self.dut.s_axi_aclk)
            self.dut.s_axi_aresetn.value = 1  # deassert
        for _ in range(2):
            await RisingEdge(self.dut.s_axi_aclk)

    async def setup(self):
        cocotb.start_soon(Clock(self.dut.s_axi_aclk, 10, units="ns").start())

        bus = AxiLiteBus.from_prefix(self.dut, "S_AXI")
        self.axi_master = AxiLiteMaster(
            bus,
            self.dut.s_axi_aclk,        # lower-case clock
            self.dut.s_axi_aresetn,     # lower-case reset
            reset_active_level=0   # because aresetn is active-low
        )
        await self.reset_dut()

    def readParams(self):
        fn = os.getenv("PARAMFN")
        if fn is None:
            raise RuntimeError("Environment variable PARAMFN is not set")

        with open(fn) as f:
            data = json.load(f, object_hook=lambda d: SimpleNamespace(**d))

        return data

    def __init__(self, dut, logfn = ""):
        self.dut = dut

        self.p = self.readParams()

        if len(logfn) > 0:
            self.outputfn = logfn
        else:
            self.outputfn = "output.txt"

        if os.path.exists(self.outputfn):
            os.remove(self.outputfn)

        self.writelog("Cocotb Test Initialized!\n")

    def writelog(self, txt):
        with open(self.outputfn, "a")  as f:
            f.write(txt)

    async def writeWord(self, addr, data):
        b = bytearray(data.to_bytes(4, byteorder="little"))
        await self.axi_master.write(addr, b)

    async def readWord(self, addr):
        b = await self.axi_master.read(addr, 4)
        v = int.from_bytes(b, byteorder="little")
        return v
