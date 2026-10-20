"""Tiny random-weight whisper ggml model for exercising jarvis_whisper.cpp on the host.
Takes whisper.cpp's own models/for-tests-ggml-tiny.en.bin (real hparams, mel filters and the
English vocab, no tensors), shrinks the hparams (state 64, 2 heads, 1 encoder + 1 decoder layer,
f32) and appends every tensor whisper_model_load (60c0be6) creates, with random values, in the
file layout it reads: n_dims, name length, ttype, ne[], name, data. The decoder then emits real
(random) tokens, so the text/word-timing path of the shim runs. Usage: make_whisper.py <src> <out>"""
import struct, sys
import numpy as np

src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/whisper.cpp/models/for-tests-ggml-tiny.en.bin"
out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/jarvis-harness/whisper/tiny-random.en.bin"
d = open(src, "rb").read()
magic = d[:4]
hp = list(struct.unpack("<11i", d[4:48]))
n_vocab, n_audio_ctx, _, _, _, n_text_ctx, _, _, _, n_mels, _ = hp
p = 48
n_mel, n_fft = struct.unpack("<2i", d[p:p + 8]); p += 8 + 4 * n_mel * n_fft
nv = struct.unpack("<i", d[p:p + 4])[0]; p += 4
for _ in range(nv):
    ln = struct.unpack("<I", d[p:p + 4])[0]; p += 4 + ln
assert p == len(d), (p, len(d))   # a for-tests model has no tensors
filters_vocab = d[48:p]

S, H, LA, LT = 64, 2, 1, 1
hp_new = [n_vocab, n_audio_ctx, S, H, LA, n_text_ctx, S, H, LT, n_mels, 0]   # ftype 0 = f32
rng = np.random.default_rng(7)

f = open(out, "wb")
f.write(magic + struct.pack("<11i", *hp_new) + filters_vocab)

def t(name, ne, kind="w"):
    n = int(np.prod(ne))
    if kind == "one": a = np.ones(n, np.float32)
    elif kind == "zero": a = np.zeros(n, np.float32)
    else: a = (rng.standard_normal(n) * 0.05).astype(np.float32)
    nb = name.encode()
    f.write(struct.pack("<3i", len(ne), len(nb), 0) + struct.pack("<%di" % len(ne), *ne) + nb + a.tobytes())

def block(pre, cross=False):
    t(pre + "mlp_ln.weight", [S], "one"); t(pre + "mlp_ln.bias", [S], "zero")
    t(pre + "mlp.0.weight", [S, 4 * S]); t(pre + "mlp.0.bias", [4 * S], "zero")
    t(pre + "mlp.2.weight", [4 * S, S]); t(pre + "mlp.2.bias", [S], "zero")
    for a in (["attn"] + (["cross_attn"] if cross else [])):
        t(pre + a + "_ln.weight", [S], "one"); t(pre + a + "_ln.bias", [S], "zero")
        t(pre + a + ".query.weight", [S, S]); t(pre + a + ".query.bias", [S], "zero")
        t(pre + a + ".key.weight", [S, S])
        t(pre + a + ".value.weight", [S, S]); t(pre + a + ".value.bias", [S], "zero")
        t(pre + a + ".out.weight", [S, S]); t(pre + a + ".out.bias", [S], "zero")

t("encoder.positional_embedding", [S, n_audio_ctx])
t("encoder.conv1.weight", [3, n_mels, S]); t("encoder.conv1.bias", [1, S], "zero")
t("encoder.conv2.weight", [3, S, S]); t("encoder.conv2.bias", [1, S], "zero")
t("encoder.ln_post.weight", [S], "one"); t("encoder.ln_post.bias", [S], "zero")
for i in range(LA): block(f"encoder.blocks.{i}.")
t("decoder.positional_embedding", [S, n_text_ctx])
t("decoder.token_embedding.weight", [S, n_vocab], "w")
t("decoder.ln.weight", [S], "one"); t("decoder.ln.bias", [S], "zero")
for i in range(LT): block(f"decoder.blocks.{i}.", cross=True)
f.close()
print(out, "n_vocab", n_vocab, "hparams", hp_new)
