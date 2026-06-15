.PHONY: sbttest tests

all: sbttest tests

# cocotb tests
tests:
	@make -C tests/Cmd
	@make -C tests/TestQ
	@make -C tests/RevMem

# Chisel test
sbttest:
	@sbt test

