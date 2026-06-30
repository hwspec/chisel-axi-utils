TARGETS=cocotb chiselsim
.PHONY: $(TARGETS) clean

VENV := .venv
PYTHON := $(VENV)/bin/python3

export PATH := $(abspath $(VENV)/bin):$(PATH)

COCOTBTESTS=RevMem Cmd TestQ

all: $(TARGETS)

# cocotb tests
cocotb:
	@source $(VENV)/bin/activate; \
	for tt in $(COCOTBTESTS); do \
		$(MAKE) -C tests/$$tt || exit $$?; \
	done

# Chisel test
chiselsim:
	@sbt test

#
# for setup
#
.PHONY: venv install install-bridge clean

venv:
	@python3 -m venv $(VENV)
	@$(PYTHON) -m pip install --upgrade pip

install: venv
	@$(PYTHON) -m pip install -r requirements.txt

install-bridge:
	@cd python && $(MAKE) install

setup: install install-bridge

clean:
	@source $(VENV)/bin/activate; \
	for tt in $(COCOTBTESTS); do \
		$(MAKE) -C tests/$$tt clean || exit $$?; \
	done
	@rm -rf generated/*
	@sbt clean

distclean: clean
	@rm -rf $(VENV)
