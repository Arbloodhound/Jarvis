"""Tiny random-weight qwen3-architecture GGUF for exercising the JNI shim on the host.
Byte-fallback SPM vocab, so any text tokenizes; outputs are noise but deterministic."""
import os, sys
sys.path.insert(0, os.path.join(os.environ.get("LLAMA_SRC", "/tmp/llama.cpp"), "gguf-py"))
import numpy as np
import gguf

out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "tiny-qwen3.gguf")
rng = np.random.default_rng(1234)
n_embd, n_head, n_kv, hd, n_layer, n_ff, ctx = 64, 4, 2, 16, 2, 128, 4096

tokens, scores, types = [], [], []
def tok(t, s, ty): tokens.append(t); scores.append(s); types.append(ty)
T = gguf.TokenType
tok("<unk>", 0.0, T.UNKNOWN); tok("<s>", 0.0, T.CONTROL); tok("</s>", 0.0, T.CONTROL)
for b in range(256): tok(f"<0x{b:02X}>", 0.0, T.BYTE)
for w in ["<think>", "</think>"]: tok(w, 0.0, T.USER_DEFINED)
# a few word pieces so tokenization is not all byte fallback
for i, w in enumerate(["▁the", "▁a", "▁is", "▁you", "▁what", "▁hello", "▁Jarvis", "e", "t", "o", "▁"]):
    tok(w, -1.0 - i, T.NORMAL)
n_vocab = len(tokens)

w = gguf.GGUFWriter(out, "qwen3")
w.add_context_length(ctx); w.add_embedding_length(n_embd); w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff); w.add_head_count(n_head); w.add_head_count_kv(n_kv)
w.add_key_length(hd); w.add_value_length(hd); w.add_rope_freq_base(1e6)
w.add_layer_norm_rms_eps(1e-6); w.add_file_type(gguf.LlamaFileType.ALL_F32)
w.add_tokenizer_model("llama"); w.add_token_list(tokens); w.add_token_scores(scores); w.add_token_types(types)
w.add_bos_token_id(1); w.add_eos_token_id(2); w.add_unk_token_id(0); w.add_add_bos_token(False)

def r(*shape, s=0.08): return (rng.standard_normal(shape) * s).astype(np.float32)
w.add_tensor("token_embd.weight", r(n_vocab, n_embd))
w.add_tensor("output_norm.weight", np.ones(n_embd, np.float32))
w.add_tensor("output.weight", r(n_vocab, n_embd))
for i in range(n_layer):
    p = f"blk.{i}."
    w.add_tensor(p + "attn_norm.weight", np.ones(n_embd, np.float32))
    w.add_tensor(p + "attn_q.weight", r(n_head * hd, n_embd))
    w.add_tensor(p + "attn_k.weight", r(n_kv * hd, n_embd))
    w.add_tensor(p + "attn_v.weight", r(n_kv * hd, n_embd))
    w.add_tensor(p + "attn_output.weight", r(n_embd, n_head * hd))
    w.add_tensor(p + "attn_q_norm.weight", np.ones(hd, np.float32))
    w.add_tensor(p + "attn_k_norm.weight", np.ones(hd, np.float32))
    w.add_tensor(p + "ffn_norm.weight", np.ones(n_embd, np.float32))
    w.add_tensor(p + "ffn_gate.weight", r(n_ff, n_embd))
    w.add_tensor(p + "ffn_up.weight", r(n_ff, n_embd))
    w.add_tensor(p + "ffn_down.weight", r(n_embd, n_ff))
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print(out, n_vocab, "tokens")
