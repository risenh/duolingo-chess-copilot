import os

arm64_so = r"d:\project\chess\dulo\app\src\main\jniLibs\arm64-v8a\libstockfish.so"
with open(arm64_so, "rb") as f:
    header = f.read(64)

print("Magic:", header[:4])
print("ELF Class:", header[4]) # 1: 32-bit, 2: 64-bit
print("Data:", header[5])      # 1: Little-endian
print("OS ABI:", header[7])
print("Type (e_type):", int.from_bytes(header[16:18], "little")) # 2: ET_EXEC, 3: ET_DYN (PIE executable / shared lib)
print("Machine:", int.from_bytes(header[18:20], "little")) # 183 = 0xB7 (AArch64), 40 = 0x28 (ARM)

# Check strings inside binary for UCI or error symbols
with open(arm64_so, "rb") as f:
    content = f.read()

has_uci = b"uciok" in content
has_stockfish = b"Stockfish" in content
print("Has 'uciok':", has_uci)
print("Has 'Stockfish':", has_stockfish)
