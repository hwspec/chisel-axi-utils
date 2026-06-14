rm -rf fpga_simple.py

python -m axi_test_bridge.conv_cocotb_to_fpga sim_simple.py fpga_simple.py \
      --sim COCOTB_Bridge  --fpga AVED_Bridge

