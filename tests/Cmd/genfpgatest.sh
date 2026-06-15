rm -rf fpga_tb_cmd.py

python ../../utils/conv_cocotb_to_fpga.py tb_cmd.py fpga_tb_cmd.py \
      --sim COCOTB_Bridge  --fpga AVED_Bridge


