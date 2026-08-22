import subprocess
import os

so_path = "dulo/app/src/main/jniLibs/arm64-v8a/libstockfish.so"
with open(so_path, "rb") as f:
    content = f.read()

# Let's search for string symbols in the binary
keywords = [b"main", b"Java_", b"stockfish", b"uci", b"isready", b"bestmove", b"ucinewgame"]
for kw in keywords:
    count = content.count(kw)
    print(f"Keyword '{kw.decode()}': found {count} times")

# Let's check for JNI function exports
jni_funcs = []
idx = 0
while True:
    pos = content.find(b"Java_", idx)
    if pos == -1: break
    end_pos = content.find(b"\x00", pos)
    if end_pos != -1 and end_pos - pos < 100:
        jni_funcs.append(content[pos:end_pos].decode('utf-8', errors='ignore'))
    idx = pos + 5

print(f"\nFound JNI exports ({len(jni_funcs)}):")
for jf in set(jni_funcs):
    print("  ", jf)
