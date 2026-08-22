import struct
with open("dulo/app/src/main/jniLibs/arm64-v8a/libstockfish.so", "rb") as f:
    header = f.read(64)
    magic = header[:4]
    elf_class = header[4] # 1=32bit, 2=64bit
    elf_type = struct.unpack("<H", header[16:18])[0] # 2=EXEC, 3=DYN (shared obj)
    print(f"Magic: {magic}, Class: {elf_class} (2=64bit), Type: {elf_type} (2=ET_EXEC, 3=ET_DYN)")
