#
# This is Makefile for cocotb
#

SIM ?= verilator
TOPLEVEL_LANG ?= verilog
TOPNAME=user_accel_bd_wrapper
HWMODULENAME=Axi4Lite32Cmd
SRCDIR = $(abspath ../../generated/$(HWMODULENAME))
VERILOG_SOURCES += $(SRCDIR)/user_accel_bd_wrapper.v $(SRCDIR)/*.sv
VERILOG_SOURCES += $(SRCDIR)/verification/*.sv

WAVES = 1
EXTRA_ARGS += --trace --trace-structs

test : $(SRCDIR)/$(HWMODULENAME).sv
	@echo $(VERILOG_SOURCES)
	@ADDRMAPFN=$(SRCDIR)/addrmap.txt $(MAKE) sim MODULE=sim_simple TOPLEVEL=$(TOPNAME)
	@echo -e "\n[Output]"
	@cat output.txt

$(SRCDIR)/$(HWMODULENAME).sv :
	(cd ../../ ; sbt "runMain axiexamples.$(HWMODULENAME)")

include $(shell cocotb-config --makefiles)/Makefile.sim

clean::
	rm -f output.txt results.xml *.vcd fpga_simple.py
	rm -rf __pycache__
