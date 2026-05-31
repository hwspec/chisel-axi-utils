
rm -rf fpga_simple.py

python ../../utils/conv_cocotb_to_fpga.py sim_simple.py fpga_simple.py \
      --sim RevMemSIM \
      --fpga RevMemFPGA

