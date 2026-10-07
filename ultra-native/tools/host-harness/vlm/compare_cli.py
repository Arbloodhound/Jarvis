"""Compare llama-mtmd-cli's raw stdout bytes with ultra_vlm.cpp's answer (UTF-8 of the Java string).
The cli prints raw token bytes; the shim converts the whole answer with its own UTF-8 -> UTF-16
rule (invalid byte -> U+FFFD, incomplete final character dropped). Apply the same rule to the cli
bytes, then compare. Usage: compare_cli.py <cli stdout> <shim out>"""
import sys

def shim_decode(s: bytes) -> str:
    out, i, n = [], 0, len(s)
    while i < n:
        c = s[i]
        ln = 1 if c < 0x80 else 2 if (c >> 5) == 0x6 else 3 if (c >> 4) == 0xE else 4 if (c >> 3) == 0x1E else 0
        if ln == 0:
            out.append("�"); i += 1; continue
        if i + ln > n:
            if all((s[k] & 0xC0) == 0x80 for k in range(i + 1, n)):
                break
            out.append("�"); i += 1; continue
        cp = c if ln == 1 else (c & 0x1F) if ln == 2 else (c & 0x0F) if ln == 3 else (c & 0x07)
        bad = False
        for k in range(1, ln):
            cc = s[i + k]
            if (cc & 0xC0) != 0x80:
                bad = True; break
            cp = (cp << 6) | (cc & 0x3F)
        if not bad and (cp < (0, 0, 0x80, 0x800, 0x10000)[ln] or 0xD800 <= cp <= 0xDFFF or cp > 0x10FFFF):
            bad = True   # overlong, surrogate, past U+10FFFF (the shims' rule since verify-native)
        if bad:
            out.append("�"); i += 1; continue
        out.append(chr(cp)); i += ln
    return "".join(out)

cli = open(sys.argv[1], "rb").read()
# mtmd-cli: LOG("\n") after the prompt is evaluated and "\n"s after generation (stripped on both
# sides); it prints each piece with LOG("%s"), so a piece that is a NUL byte prints nothing:
# NULs are dropped from the shim's answer for the comparison (and counted).
if cli.startswith(b"\n"): cli = cli[1:]
cli = cli.rstrip(b"\n")
shim = open(sys.argv[2], "rb").read().decode("utf-8", "surrogatepass")
print("NUL chars in shim answer (unprintable by the cli):", shim.count("\0"))
shim = shim.replace("\0", "").rstrip("\n")
cli_s = shim_decode(cli)
print("cli bytes:", len(cli), "-> chars", len(cli_s), "| shim chars:", len(shim))
print("cli :", repr(cli_s))
print("shim:", repr(shim))
print("IDENTICAL" if cli_s == shim else "DIFFERENT")
sys.exit(0 if cli_s == shim else 1)
