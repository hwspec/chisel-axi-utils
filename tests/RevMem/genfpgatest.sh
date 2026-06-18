rm -rf fpga_tb_revmem.py

python ../../utils/conv_cocotb_to_fpga.py tb_revmem.py fpga_tb_revmem.py \
      --sim COCOTB_Bridge  --fpga AVED_Bridge


