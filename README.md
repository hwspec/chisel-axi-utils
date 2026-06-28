# chisel-axi-utils

A lightweight set of AXI utilities written in **Chisel**.

This repository provides practical building blocks for working with AXI
protocols, without aiming for full specification completeness. The focus
is on simplicity, clarity, and usability in real designs.

It includes AXI usage examples that can serve as starting templates for your
own designs, along with examples of both Chisel testbenches and cocotb-based
integration testbenches. Experimentally, the cocotb testbench can be converted
into an FPGA testbench without modification, targeting the AMD Alveo V80 FPGA
with a modified AVED environment.

Note: Instructions for testing on the V80 FPGA are not documented yet, but
will be provided soon.

### Dependencies

#### Linux distro

We have tested it with Ubuntu 24.04.4 LTS and Fedora 41. We believe that any newer major Linux distro works.

#### JDK 8 or newer

We recommend LTS releases Java 8 and Java 11. You can install the JDK as your operating system recommends, or use the prebuilt binaries from [AdoptOpenJDK](h\
ttps://adoptopenjdk.net/).

#### SBT

SBT is the most common build tool in the Scala community. You can download it [here](https://www.scala-sbt.org/download.html).

#### Verilator

Chisel and cocotb require Verilator installed. Verilator 5.044 has been tested.

To build and install it locally:

```bash
sh misc/build_verilator.sh INSTDIR
```

NOTE: add INSTDIR/bin to PATH

#### cocotb

Tested with Python 3.8+.

Create a Python virtual environment and install required packages:

```bash
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install --upgrade pip
python3 -m pip install -r requirements.txt
```

Deactivate the environment when finished:
```bash
deactivate
```

Please also install axi_test_bridge
```bash
cd python
make install
```


## Examples

Please look at the following files that can be used as templates for your project:

- `src/main/scala/axi_examples/Axi4Lite32Cmd.scala`: a Chisel module example for bridging with your device under test (DUT). It includes soft reset logic.
- `src/test/scala/axi_examples/Axi4Lite32CmdSpec.scala`: a Chisel testbench for `Axi4Lite32Cmd`.
- `tests/Cmd/{CmdSim.py, sim_simple.py, Makefile}`: a cocotb testbench for `Axi4Lite32Cmd`. This testbench can be converted to an FPGA testbench on AMD V80 AVED (modified version) without modification.

## Descriptions

Currently, the repository includes:

-   **AXI4-Lite support**
-   AXI port bundles for easy integration into Chisel modules
-   Simplified bus function models (BFMs)
-   Example AXI-based modules

AXI4-Stream and AXI4-Full support are planned and will be added
incrementally.

Tested on Chisel 7.9.0 and Verilator 5.044

Note: this project is not a full-featured AXI reference
implementation.  Instead, it provides:

-   Minimal and clean AXI interfaces
-   Practical subsets of the protocol
-   Clear structure suitable for integration into real hardware projects
-   Utilities that simplify testbench development

The goal is to support AXI in a lightweight way.

------------------------------------------------------------------------

## Features

### AXI Port Bundles

Predefined AXI bundles that can be directly instantiated in your Chisel
modules:

``` scala
class MyModule(AxiAddrBW: Int = 24) extends Module {
  val io = IO(new Bundle {
    val axi = new AxiLite32IO(AxiAddrBW)
  })
}
```

This allows AXI interfaces to be added cleanly without rewriting channel
wiring logic.

------------------------------------------------------------------------

### Simplified Bus Function Models (BFMs)

Here is an example usage of higher-level interface.

``` scala
  "test AxiList32RevMem" should "pass" in {
    simulate(new Axi4Lite32RevMem) { dut =>
      val bfm = new Axi4Lite32BFM(dut)
      bfm.initMaster()
      val bresp = bfm.write(0x10, 0x123L)  // write 0x123 to the address 0x10
      val (rdata, rresp) = bfm.read(0x10)  // read the adrress 0x10.
	  ...
    }

```

Lower-level channel methods are also available when finer control is
needed:

-   `sendAW(...)`
-   `sendW(...)`
-   `sendSimulAWW(...)`
-   `recvB(...)`
-   `sendAR(...)`
-   `recvR(...)`

This makes testbench code significantly simpler while preserving
flexibility.

------------------------------------------------------------------------

## Roadmap

Planned additions:

-   AXI4-Stream utilities
-   Partial AXI4-Full support
-   Additional adapters and helper components



## Contact

For questions, bug reports, or feature requests, please open an issue
on GitHub. For general discussion, please use GitHub Discussions.

