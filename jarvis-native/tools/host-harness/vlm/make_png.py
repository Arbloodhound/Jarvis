"""The 300x600 RGB test pattern VlmHarness.image(300, 600, 0) builds, as a PNG for llama-mtmd-cli."""
import struct, sys, zlib
out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/jarvis-harness/vlm/img300x600.png"
w, h = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (300, 600)
rows = []
for y in range(h):
    row = bytearray(b"\x00")
    for x in range(w):
        row += bytes([x * 255 // w, y * 255 // h, 230 if (x // 20 + y // 20) % 2 == 0 else 25])
    rows.append(bytes(row))
def chunk(t, d):
    c = struct.pack(">I", len(d)) + t + d
    return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
with open(out, "wb") as f:
    f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
