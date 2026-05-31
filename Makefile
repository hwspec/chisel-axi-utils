.PHONY: sbttest cocotbtest

all: sbttest cocotbtest

# Chisel test
sbttest:
	@sbt test

# cocotb test
cocotbtest:
	@make -C tests/Cmd


