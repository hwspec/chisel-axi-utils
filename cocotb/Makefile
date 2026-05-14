#SIM ?= icarus
SIM ?= verilator
TOPLEVEL_LANG ?= verilog
TOPNAME=user_accel_bd_wrapper
HWMODULENAME=Axi4Lite32RevMem
SRCDIR = $(abspath ../generated/$(HWMODULENAME))
VERILOG_SOURCES += $(SRCDIR)/user_accel_bd_wrapper.v $(SRCDIR)/mem_128x32.sv $(SRCDIR)/$(HWMODULENAME).sv
WAVES = 1
EXTRA_ARGS += --trace --trace-structs

$(SRCDIR)/$(HWMODULENAME).sv :
	(cd ../ ; sbt "runMain axiexamples.Axi4Lite32RevMem")

test : $(SRCDIR)/$(HWMODULENAME).sv
	@echo $(VERILOG_SOURCES)
	@$(MAKE) sim MODULE=sim_simple TOPLEVEL=$(TOPNAME)
	@cat output.txt

include $(shell cocotb-config --makefiles)/Makefile.sim
