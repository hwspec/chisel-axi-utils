#!/usr/bin/bash

if [ -z "$1" ] ; then
	echo "Usage: $0 install_dir"
	exit 1
fi

install_dir=$1

git clone https://github.com/verilator/verilator.git && cd verilator
git checkout tags/v5.044 -b v5.044build
autoconf
./configure --prefix=$install_dir
make -j `nproc`
make install
