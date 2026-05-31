from RevMemFPGA import RevMemFPGA

def rev32bits(x: int):
    x &= 0xFFFFFFFF
    x = ((x & 0x55555555) << 1)  | ((x >> 1)  & 0x55555555)
    x = ((x & 0x33333333) << 2)  | ((x >> 2)  & 0x33333333)
    x = ((x & 0x0F0F0F0F) << 4)  | ((x >> 4)  & 0x0F0F0F0F)
    x = ((x & 0x00FF00FF) << 8)  | ((x >> 8)  & 0x00FF00FF)
    x = ((x & 0x0000FFFF) << 16) | ((x >> 16) & 0x0000FFFF)
    return x & 0xFFFFFFFF

def validateRevWord(rev, addr, input):
    v = rev.readWord(0)
    ref = rev32bits(input)

    rev.writelog(f"{input:08x} => {v:08x}\n")
    
    assert v == ref, f"v={v:08x} ref={ref:08x}"

def main():
    rev = RevMemFPGA()

    addr = 0
    val = 0x1364e
    rev.writeWord(addr, val)
    
    validateRevWord(rev, addr, val)

    rev.writelog("Done!!\n")

if __name__ == "__main__":
    main()
