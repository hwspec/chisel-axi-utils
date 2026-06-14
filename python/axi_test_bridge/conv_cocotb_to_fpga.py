#!/usr/bin/env python3

"""
Convert an AXI cocotb testbench into an FPGA testbench.

Assumptions:
  - SIM module name and SIM class name are the same.
    Example: RevMemSIM.py contains class RevMemSIM

  - FPGA module name and FPGA class name are the same.
    Example: RevMemFPGA.py contains class RevMemFPGA

Usage:
  python conv_cocotb_to_fpga.py sim_test.py fpga_test.py \
      --sim RevMemSIM \
      --fpga RevMemFPGA

Overwrite existing output:
  python conv_cocotb_to_fpga.py sim_test.py fpga_test.py \
      --sim RevMemSIM \
      --fpga RevMemFPGA \
      --force
"""

from __future__ import annotations

import argparse
from pathlib import Path

import libcst as cst
import libcst.matchers as m

class CocotbToFpgaTransformer(cst.CSTTransformer):
    def __init__(
        self,
        sim: str,
        fpga: str,
        drop_setup: bool = True,
    ):
        self.sim = sim
        self.fpga = fpga
        self.drop_setup = drop_setup
        self.generated_main = False

    def _module_name(self, node: cst.BaseExpression) -> str:
        """Return dotted module name from LibCST Name/Attribute node."""
        if isinstance(node, cst.Name):
            return node.value

        if isinstance(node, cst.Attribute):
            left = self._module_name(node.value)
            return f"{left}.{node.attr.value}"

        return ""

    # -------------------------
    # Imports
    # -------------------------

    def leave_Import(
        self,
        original_node: cst.Import,
        updated_node: cst.Import,
    ) -> cst.Import | cst.RemovalSentinel:
        kept = []

        for alias in updated_node.names:
            name = alias.name

            # remove: import cocotb
            if isinstance(name, cst.Name) and name.value == "cocotb":
                continue

            kept.append(alias.with_changes(comma=cst.MaybeSentinel.DEFAULT))

        if not kept:
            return cst.RemoveFromParent()

        return updated_node.with_changes(names=kept)

    def leave_ImportFrom(
        self,
        original_node: cst.ImportFrom,
        updated_node: cst.ImportFrom,
    ) -> cst.ImportFrom | cst.RemovalSentinel:
        if updated_node.module is None:
            return updated_node

        module_code = self._module_name(updated_node.module)

        # remove: from cocotb... import ...
        if module_code == "cocotb" or module_code.startswith("cocotb."):
            return cst.RemoveFromParent()

        # from RevMemSIM import RevMemSIM
        # ->
        # from RevMemFPGA import RevMemFPGA
        if module_code == self.sim:
            return updated_node.with_changes(
                module=cst.Name(self.fpga),
                names=[
                    cst.ImportAlias(
                        name=cst.Name(self.fpga),
                        comma=cst.MaybeSentinel.DEFAULT,
                    )
                ],
            )

        return updated_node

    # -------------------------
    # async / await conversion
    # -------------------------

    def leave_Await(
        self,
        original_node: cst.Await,
        updated_node: cst.Await,
    ) -> cst.BaseExpression:
        # await rev.readWord(...)
        # ->
        # rev.readWord(...)
        return updated_node.expression

    # -------------------------
    # Function conversion
    # -------------------------

    def leave_FunctionDef(
        self,
        original_node: cst.FunctionDef,
        updated_node: cst.FunctionDef,
    ) -> cst.FunctionDef:
        decorators = []
        is_cocotb_test = False

        for dec in updated_node.decorators:
            if self._is_cocotb_test_decorator(dec):
                is_cocotb_test = True
                continue
            decorators.append(dec)

        # async def -> def
        new_node = updated_node.with_changes(
            asynchronous=None,
            decorators=decorators,
        )

        if is_cocotb_test:
            self.generated_main = True

            # async def sim_simple(dut):
            # ->
            # def main():
            new_node = new_node.with_changes(
                name=cst.Name("main"),
                params=cst.Parameters(),
            )

        return new_node

    def _is_cocotb_test_decorator(self, dec: cst.Decorator) -> bool:
        expr = dec.decorator

        # @cocotb.test
        if m.matches(
            expr,
            m.Attribute(
                value=m.Name("cocotb"),
                attr=m.Name("test"),
            ),
        ):
            return True

        # @cocotb.test()
        if m.matches(
            expr,
            m.Call(
                func=m.Attribute(
                    value=m.Name("cocotb"),
                    attr=m.Name("test"),
                )
            ),
        ):
            return True

        return False

    # -------------------------
    # Class replacement
    # -------------------------

    def leave_Call(
        self,
        original_node: cst.Call,
        updated_node: cst.Call,
    ) -> cst.Call:
        # RevMemSIM(dut)
        # ->
        # RevMemFPGA()
        if isinstance(updated_node.func, cst.Name):
            if updated_node.func.value == self.sim:
                return updated_node.with_changes(
                    func=cst.Name(self.fpga),
                    args=[],
                )

        return updated_node

    # -------------------------
    # Optional setup removal
    # -------------------------

    def leave_SimpleStatementLine(
        self,
        original_node: cst.SimpleStatementLine,
        updated_node: cst.SimpleStatementLine,
    ) -> cst.SimpleStatementLine | cst.RemovalSentinel:
        if self.drop_setup and len(updated_node.body) == 1:
            stmt = updated_node.body[0]

            # Drop:
            #   await rev.setup()
            #
            # After await-removal, this becomes:
            #   rev.setup()
            if m.matches(
                stmt,
                m.Expr(
                    value=m.Call(
                        func=m.Attribute(
                            attr=m.Name("setup"),
                        )
                    )
                ),
            ):
                return cst.RemoveFromParent()

        return updated_node

    # -------------------------
    # Add main entry point
    # -------------------------

    def leave_Module(
        self,
        original_node: cst.Module,
        updated_node: cst.Module,
    ) -> cst.Module:
        if not self.generated_main:
            return updated_node

        main_block = cst.parse_statement(
            '''
if __name__ == "__main__":
    main()
'''
        )

        return updated_node.with_changes(
            body=list(updated_node.body) + [main_block]
        )


def convert_code(
    source: str,
    sim: str,
    fpga: str,
    drop_setup: bool = True,
) -> str:
    module = cst.parse_module(source)

    transformed = module.visit(
        CocotbToFpgaTransformer(
            sim=sim,
            fpga=fpga,
            drop_setup=drop_setup,
        )
    )

    return transformed.code


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert restricted cocotb testbench to FPGA testbench."
    )

    parser.add_argument(
        "input",
        type=Path,
        help="Input cocotb testbench, e.g. sim_test.py",
    )

    parser.add_argument(
        "output",
        type=Path,
        help="Output FPGA testbench, e.g. fpga_test.py",
    )

    parser.add_argument(
        "--sim",
        required=True,
        help="SIM backend class/module name, e.g. RevMemSIM",
    )

    parser.add_argument(
        "--fpga",
        required=True,
        help="FPGA backend class/module name, e.g. RevMemFPGA",
    )

    parser.add_argument(
        "--keep-setup",
        action="store_true",
        help="Keep setup() calls instead of removing them.",
    )

    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite output file if it already exists.",
    )

    args = parser.parse_args()

    if args.output.exists() and not args.force:
        raise FileExistsError(
            f"{args.output} already exists. Use --force to overwrite."
        )

    source = args.input.read_text()

    converted = convert_code(
        source=source,
        sim=args.sim,
        fpga=args.fpga,
        drop_setup=not args.keep_setup,
    )

    args.output.write_text(converted)


if __name__ == "__main__":
    main()
