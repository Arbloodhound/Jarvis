"""Tiny random-weight Qwen2.5-VL pair for exercising jarvis_vlm.cpp on the host.
  text:   arch qwen2vl (M-RoPE, rope.dimension_sections), gpt2 byte-level BPE vocab with the
          Qwen special tokens (<|im_start|>, <|im_end|>, <|vision_start|>, <|vision_end|>, ...)
  mmproj: clip, projector_type qwen2.5vl_merger, the tensor names/shapes clip.cpp (8345f33)
          loads for PROJECTOR_TYPE_QWEN25VL (conversion/qwenvl.py's layout), patch 14 and
          merge 2 as the real model, so the image token count is the real one.
Weights are random: outputs are noise. Usage: make_vlm.py <outdir>"""
import sys, os
sys.path.insert(0, "/tmp/llama.cpp/gguf-py")
import numpy as np
import gguf

outdir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/jarvis-harness/vlm"
rng = np.random.default_rng(4321)
def r(*shape, s=0.08): return (rng.standard_normal(shape) * s).astype(np.float32)

# ---------------- text model ----------------
n_embd, n_head, n_kv, hd, n_layer, n_ff, ctx = 64, 4, 2, 16, 2, 128, 32768
sections = [2, 3, 3, 0]     # sums to hd/2, as Qwen2.5-VL's [16,24,24,0] does for hd=128

def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b); cs.append(256 + n); n += 1
    return dict(zip(bs, [chr(c) for c in cs]))
b2u = bytes_to_unicode()
tokens = [b2u[b] for b in range(256)]
types = [gguf.TokenType.NORMAL] * 256
merges = ["Ġ t", "h e", "i s", "Ġ a", "Ġt he", "o n", "e r"]
for m in merges:
    tokens.append(m.replace(" ", "")); types.append(gguf.TokenType.NORMAL)
specials = ["<|endoftext|>", "<|im_start|>", "<|im_end|>", "<|vision_start|>", "<|vision_end|>",
            "<|vision_pad|>", "<|image_pad|>", "<|video_pad|>"]
sid = {}
for s in specials:
    sid[s] = len(tokens); tokens.append(s); types.append(gguf.TokenType.CONTROL)
n_vocab = len(tokens)

# Qwen2-VL / Qwen2.5-VL style jinja template (reconstructed; llama_chat_apply_template only
# needs it to contain <|im_start|> to pick chatml).
tmpl = ("{% set image_count = namespace(value=0) %}{% set video_count = namespace(value=0) %}"
        "{% for message in messages %}{% if loop.first and message['role'] != 'system' %}<|im_start|>system\n"
        "You are a helpful assistant.<|im_end|>\n{% endif %}<|im_start|>{{ message['role'] }}\n"
        "{% if message['content'] is string %}{{ message['content'] }}<|im_end|>\n{% else %}"
        "{% for content in message['content'] %}{% if content['type'] == 'image' or 'image' in content or 'image_url' in content %}"
        "{% set image_count.value = image_count.value + 1 %}<|vision_start|><|image_pad|><|vision_end|>"
        "{% elif 'text' in content %}{{ content['text'] }}{% endif %}{% endfor %}<|im_end|>\n{% endif %}{% endfor %}"
        "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")

w = gguf.GGUFWriter(os.path.join(outdir, "tiny-qwen25vl.gguf"), "qwen2vl")
w.add_context_length(ctx); w.add_embedding_length(n_embd); w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff); w.add_head_count(n_head); w.add_head_count_kv(n_kv)
w.add_rope_freq_base(1e6); w.add_layer_norm_rms_eps(1e-6)
w.add_rope_dimension_sections(sections)
w.add_file_type(gguf.LlamaFileType.ALL_F32)
w.add_tokenizer_model("gpt2"); w.add_tokenizer_pre("qwen2")
w.add_token_list(tokens); w.add_token_types(types); w.add_token_merges(merges)
w.add_eos_token_id(sid["<|im_end|>"]); w.add_eot_token_id(sid["<|im_end|>"])
w.add_bos_token_id(sid["<|endoftext|>"]); w.add_pad_token_id(sid["<|endoftext|>"])
w.add_add_bos_token(False)
w.add_chat_template(tmpl)
w.add_tensor("token_embd.weight", r(n_vocab, n_embd))
w.add_tensor("output_norm.weight", np.ones(n_embd, np.float32))
w.add_tensor("output.weight", r(n_vocab, n_embd))
for i in range(n_layer):
    p = f"blk.{i}."
    w.add_tensor(p + "attn_norm.weight", np.ones(n_embd, np.float32))
    w.add_tensor(p + "attn_q.weight", r(n_head * hd, n_embd)); w.add_tensor(p + "attn_q.bias", r(n_head * hd))
    w.add_tensor(p + "attn_k.weight", r(n_kv * hd, n_embd));   w.add_tensor(p + "attn_k.bias", r(n_kv * hd))
    w.add_tensor(p + "attn_v.weight", r(n_kv * hd, n_embd));   w.add_tensor(p + "attn_v.bias", r(n_kv * hd))
    w.add_tensor(p + "attn_output.weight", r(n_embd, n_head * hd))
    w.add_tensor(p + "ffn_norm.weight", np.ones(n_embd, np.float32))
    w.add_tensor(p + "ffn_gate.weight", r(n_ff, n_embd))
    w.add_tensor(p + "ffn_up.weight", r(n_ff, n_embd))
    w.add_tensor(p + "ffn_down.weight", r(n_embd, n_ff))
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()

# ---------------- mmproj ----------------
v_embd, v_head, v_ff, v_layer, patch, img = 32, 2, 48, 4, 14, 560
m = gguf.GGUFWriter(os.path.join(outdir, "tiny-qwen25vl-mmproj.gguf"), "clip")
m.add_type("mmproj")
m.add_file_type(gguf.LlamaFileType.ALL_F32)
m.add_bool("clip.has_vision_encoder", True)
m.add_string("clip.projector_type", "qwen2.5vl_merger")
m.add_bool("clip.use_silu", True)
m.add_uint32("clip.vision.projection_dim", n_embd)
m.add_uint32("clip.vision.image_size", img)
m.add_uint32("clip.vision.patch_size", patch)
m.add_uint32("clip.vision.embedding_length", v_embd)
m.add_uint32("clip.vision.feed_forward_length", v_ff)
m.add_uint32("clip.vision.block_count", v_layer)
m.add_uint32("clip.vision.attention.head_count", v_head)
m.add_float32("clip.vision.attention.layer_norm_epsilon", 1e-6)
m.add_uint32("clip.vision.n_wa_pattern", 2)        # fullatt_block_indexes [1, 3]: windowed + full layers
m.add_array("clip.vision.image_mean", [0.48145466, 0.4578275, 0.40821073])
m.add_array("clip.vision.image_std", [0.26862954, 0.26130258, 0.27577711])
m.add_tensor("v.patch_embd.weight", r(v_embd, 3, patch, patch))     # Conv3D split into two Conv2Ds
m.add_tensor("v.patch_embd.weight.1", r(v_embd, 3, patch, patch))
for i in range(v_layer):
    p = f"v.blk.{i}."
    m.add_tensor(p + "ln1.weight", np.ones(v_embd, np.float32))
    m.add_tensor(p + "ln2.weight", np.ones(v_embd, np.float32))
    for t in ("attn_q", "attn_k", "attn_v", "attn_out"):
        m.add_tensor(p + t + ".weight", r(v_embd, v_embd)); m.add_tensor(p + t + ".bias", r(v_embd))
    m.add_tensor(p + "ffn_gate.weight", r(v_ff, v_embd)); m.add_tensor(p + "ffn_gate.bias", r(v_ff))
    m.add_tensor(p + "ffn_up.weight", r(v_ff, v_embd));   m.add_tensor(p + "ffn_up.bias", r(v_ff))
    m.add_tensor(p + "ffn_down.weight", r(v_embd, v_ff)); m.add_tensor(p + "ffn_down.bias", r(v_embd))
m.add_tensor("v.post_ln.weight", np.ones(v_embd, np.float32))
m.add_tensor("mm.0.weight", r(4 * v_embd, 4 * v_embd)); m.add_tensor("mm.0.bias", r(4 * v_embd))
m.add_tensor("mm.2.weight", r(n_embd, 4 * v_embd));     m.add_tensor("mm.2.bias", r(n_embd))
m.write_header_to_file(); m.write_kv_data_to_file(); m.write_tensors_to_file(); m.close()
print("vocab", n_vocab, "specials", sid)
