rm -rf fpga_tb_testq.py

python -m axi_test_bridge.conv_cocotb_to_fpga tb_testq.py fpga_tb_testq.py \
      --sim COCOTB_Bridge  --fpga AVED_Bridge

