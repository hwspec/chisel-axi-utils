
TARGETS=cocotb chiselsim
.PHONY: $(TARGETS) clean

COCOTBTESTS=RevMem Cmd TestQ

all: $(TARGETS)

# cocotb tests
cocotb:
	@for tt in $(COCOTBTESTS); do \
		$(MAKE) -C tests/$$tt || exit $$?; \
	done

# Chisel test
chiselsim:
	@sbt test

clean:
	@for tt in $(COCOTBTESTS); do \
		$(MAKE) -C tests/$$tt clean || exit $$?; \
	done
	@rm -rf generated/*
	@sbt clean
