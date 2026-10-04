#!/usr/bin/env python3
"""
Torch-free safetensors -> GGUF converter for Llama-family models (<= 1B).

llama.cpp's own converter needs PyTorch, which has no musl/aarch64 wheels and
therefore cannot run inside the on-device Alpine sandbox. This script covers the
single most common small-model case instead: `architectures: LlamaForCausalLM`
with a GPT-2 style BPE tokenizer (tokenizer.json) - i.e. Llama-3.2-1B and the
TinyLlama/SmolLM family - using only numpy + the standard library.

Usage:
    python3 convert_llama.py <model_dir> <out.gguf> [--dtype f16|f32]

<model_dir> must contain config.json, tokenizer.json (and optionally
tokenizer_config.json, generation_config.json) plus one or more *.safetensors.

Emits GGUF v3 with F16 (default) or F32 tensor data.
"""
from __future__ import annotations

import json
import os
import struct
import sys

import numpy as np

GGUF_MAGIC = 0x46554747
GGUF_VERSION = 3
ALIGNMENT = 32

# ggml tensor types
GGML_F32 = 0
GGML_F16 = 1
GGML_BF16 = 30

# GGUF metadata value types
T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32, T_INT32 = 0, 1, 2, 3, 4, 5
T_FLOAT32, T_BOOL, T_STRING, T_ARRAY, T_UINT64, T_INT64, T_FLOAT64 = 6, 7, 8, 9, 10, 11, 12

# tokenizer.ggml.token_type
TT_NORMAL, TT_UNKNOWN, TT_CONTROL, TT_USER_DEFINED, TT_UNUSED, TT_BYTE = 1, 2, 3, 4, 5, 6


# --------------------------------------------------------------------------
# safetensors (header + raw buffer; no torch, no safetensors package)
# --------------------------------------------------------------------------

DTYPE_TO_GGML = {
    "F32": GGML_F32,
    "F16": GGML_F16,
    "BF16": GGML_BF16,
}


class SafeTensors:
    def __init__(self, path: str):
        self.path = path
        with open(path, "rb") as f:
            (n,) = struct.unpack("<Q", f.read(8))
            self.header = json.loads(f.read(n))
            self.data_start = 8 + n
        self.meta = {k: v for k, v in self.header.items() if k != "__metadata__"}

    def names(self):
        return list(self.meta.keys())

    def tensor(self, name: str) -> np.ndarray:
        info = self.meta[name]
        dtype = info["dtype"]
        shape = list(info["shape"])
        start, end = info["data_offsets"]
        with open(self.path, "rb") as f:
            f.seek(self.data_start + start)
            raw = f.read(end - start)
        if dtype == "F32":
            arr = np.frombuffer(raw, dtype="<f4")
        elif dtype == "F16":
            arr = np.frombuffer(raw, dtype="<f2")
        elif dtype == "BF16":
            u16 = np.frombuffer(raw, dtype="<u2").astype(np.uint32)
            arr = (u16 << 16).view(np.float32)
        elif dtype == "I8":
            arr = np.frombuffer(raw, dtype=np.int8)
        elif dtype == "U8":
            arr = np.frombuffer(raw, dtype=np.uint8)
        elif dtype == "I32":
            arr = np.frombuffer(raw, dtype="<i4")
        else:
            raise ValueError(f"unsupported dtype {dtype} for {name}")
        return arr.reshape(shape)


def load_all_tensors(model_dir: str):
    files = [f for f in sorted(os.listdir(model_dir)) if f.endswith(".safetensors")]
    if not files:
        raise SystemExit("no .safetensors found in " + model_dir)
    tensors = {}
    for fn in files:
        st = SafeTensors(os.path.join(model_dir, fn))
        for name in st.names():
            tensors[name] = (st, name)
    return tensors


# --------------------------------------------------------------------------
# HF -> GGUF name/shape mapping for the llama architecture
# --------------------------------------------------------------------------

def gguf_name(hf_name: str):
    n = hf_name
    if n == "model.embed_tokens.weight":
        return "token_embd.weight"
    if n == "model.norm.weight":
        return "output_norm.weight"
    if n == "lm_head.weight":
        return "output.weight"
    if n.startswith("model.layers."):
        rest = n[len("model.layers."):]
        idx, _, tail = rest.partition(".")
        m = {
            "input_layernorm.weight": "attn_norm.weight",
            "post_attention_layernorm.weight": "ffn_norm.weight",
            "self_attn.q_proj.weight": "attn_q.weight",
            "self_attn.k_proj.weight": "attn_k.weight",
            "self_attn.v_proj.weight": "attn_v.weight",
            "self_attn.o_proj.weight": "attn_output.weight",
            "mlp.gate_proj.weight": "ffn_gate.weight",
            "mlp.up_proj.weight": "ffn_up.weight",
            "mlp.down_proj.weight": "ffn_down.weight",
        }
        if tail in m:
            return f"blk.{idx}.{m[tail]}"
    return None


# --------------------------------------------------------------------------
# GGUF writer
# --------------------------------------------------------------------------

class GgufWriter:
    def __init__(self, fh):
        self.f = fh
        self.kv = []
        self.tensors = []  # (name, np.ndarray, ggml_type)

    def add_kv(self, key, vtype, value):
        self.kv.append((key, vtype, value))

    def kv_str(self, k, v):
        self.add_kv(k, T_STRING, v)

    def kv_u32(self, k, v):
        self.add_kv(k, T_UINT32, int(v))

    def kv_i32(self, k, v):
        self.add_kv(k, T_INT32, int(v))

    def kv_f32(self, k, v):
        self.add_kv(k, T_FLOAT32, float(v))

    def kv_bool(self, k, v):
        self.add_kv(k, T_BOOL, bool(v))

    def kv_str_array(self, k, values):
        self.add_kv(k, T_ARRAY, (T_STRING, list(values)))

    def kv_i32_array(self, k, values):
        self.add_kv(k, T_ARRAY, (T_INT32, [int(v) for v in values]))

    def add_tensor(self, name, arr, ggml_type):
        self.tensors.append((name, arr, ggml_type))

    # ---- encoding helpers
    def _s(self, text: str):
        b = text.encode("utf-8")
        self.f.write(struct.pack("<Q", len(b)))
        self.f.write(b)

    def _value(self, vtype, value):
        f = self.f
        if vtype == T_STRING:
            self._s(value)
        elif vtype == T_BOOL:
            f.write(struct.pack("<?", bool(value)))
        elif vtype == T_UINT32:
            f.write(struct.pack("<I", int(value)))
        elif vtype == T_INT32:
            f.write(struct.pack("<i", int(value)))
        elif vtype == T_FLOAT32:
            f.write(struct.pack("<f", float(value)))
        else:
            raise ValueError(f"unhandled gguf value type {vtype}")

    def _value_array(self, elem_type, values):
        f = self.f
        f.write(struct.pack("<I", elem_type))
        f.write(struct.pack("<Q", len(values)))
        for v in values:
            self._value(elem_type, v)

    def write(self):
        f = self.f
        f.write(struct.pack("<I", GGUF_MAGIC))
        f.write(struct.pack("<I", GGUF_VERSION))
        f.write(struct.pack("<Q", len(self.tensors)))
        f.write(struct.pack("<Q", len(self.kv)))
        for key, vtype, value in self.kv:
            self._s(key)
            f.write(struct.pack("<I", vtype))
            if vtype == T_ARRAY:
                self._value_array(value[0], value[1])
            else:
                self._value(vtype, value)

        # tensor infos, with offsets relative to the (aligned) data section
        offset = 0
        infos = []
        for name, arr, gtype in self.tensors:
            dims = list(arr.shape)[::-1]  # GGUF stores dims reversed vs HF
            nbytes = arr.nbytes
            infos.append((name, dims, gtype, offset, arr))
            offset += nbytes
            pad = (-offset) % ALIGNMENT
            offset += pad

        for name, dims, gtype, off, _ in infos:
            self._s(name)
            f.write(struct.pack("<I", len(dims)))
            for d in dims:
                f.write(struct.pack("<Q", int(d)))
            f.write(struct.pack("<I", gtype))
            f.write(struct.pack("<Q", off))

        # data section: pad the current position to ALIGNMENT
        here = f.tell()
        f.write(b"\x00" * ((-here) % ALIGNMENT))
        for name, dims, gtype, off, arr in infos:
            pos = f.tell()
            expected = here + ((-here) % ALIGNMENT) + off
            if pos != expected:
                raise RuntimeError(f"offset drift for {name}: {pos} != {expected}")
            f.write(arr.tobytes())
            f.write(b"\x00" * ((-arr.nbytes) % ALIGNMENT))


# --------------------------------------------------------------------------
# tokenizer (GPT-2 BPE from tokenizer.json)
# --------------------------------------------------------------------------

def build_tokenizer(model_dir: str, cfg: dict):
    with open(os.path.join(model_dir, "tokenizer.json"), encoding="utf-8") as f:
        tok = json.load(f)
    vocab = tok["model"]["vocab"]
    merges = tok["model"].get("merges", [])
    if merges and isinstance(merges[0], list):
        merges = [" ".join(m) for m in merges]

    added = {a["id"]: a for a in tok.get("added_tokens", [])}
    size = max(max(vocab.values()), max(added.keys(), default=-1)) + 1

    tokens = [""] * size
    types = [TT_NORMAL] * size
    for name, idx in vocab.items():
        if 0 <= idx < size:
            tokens[idx] = name
    for idx, a in added.items():
        if 0 <= idx < size:
            tokens[idx] = a["content"]
            types[idx] = TT_CONTROL if a.get("special") else TT_USER_DEFINED
    # llama.cpp marks empty slots as unknown rather than an empty normal token
    for i in range(size):
        if tokens[i] == "":
            types[i] = TT_UNKNOWN

    tcfg = {}
    tcfg_path = os.path.join(model_dir, "tokenizer_config.json")
    if os.path.exists(tcfg_path):
        with open(tcfg_path, encoding="utf-8") as f:
            tcfg = json.load(f)

    def id_of(key, default):
        v = tcfg.get(key, cfg.get(key, default))
        if isinstance(v, dict):
            v = v.get("content")
        if isinstance(v, str):
            return added_reverse(tok, v, default)
        return default if v is None else int(v)

    bos = id_of("bos_token", 1)
    eos = id_of("eos_token", 2)
    chat_template = tcfg.get("chat_template")
    return tokens, types, merges, bos, eos, chat_template


def added_reverse(tok: dict, content: str, default: int):
    for a in tok.get("added_tokens", []):
        if a["content"] == content:
            return int(a["id"])
    return default


# --------------------------------------------------------------------------

def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    model_dir, out_path = sys.argv[1], sys.argv[2]
    dtype = "f16"
    if "--dtype" in sys.argv:
        dtype = sys.argv[sys.argv.index("--dtype") + 1]

    with open(os.path.join(model_dir, "config.json"), encoding="utf-8") as f:
        cfg = json.load(f)

    archs = cfg.get("architectures") or []
    if archs and not any("Llama" in a or "Mistral" in a for a in archs):
        raise SystemExit(f"unsupported architecture {archs} (this converter handles llama/mistral)")

    n_layer = int(cfg["num_hidden_layers"])
    n_head = int(cfg["num_attention_heads"])
    n_kv = int(cfg.get("num_key_value_heads", n_head))
    n_embd = int(cfg["hidden_size"])
    n_ff = int(cfg.get("intermediate_size", 4 * n_embd))
    head_dim = int(cfg.get("head_dim", n_embd // n_head))
    ctx = int(cfg.get("max_position_embeddings", 2048))
    eps = float(cfg.get("rms_norm_eps", 1e-5))
    rope_theta = float(cfg.get("rope_theta", 10000.0))
    rope_dim = int(cfg.get("rope_dim", head_dim))
    tied = bool(cfg.get("tie_word_embeddings", False))

    tokens, types, merges, bos, eos, chat_template = build_tokenizer(model_dir, cfg)
    print(f"model: {n_layer} layers, {n_head} heads ({n_kv} kv), dim {n_embd}, vocab {len(tokens)}")

    tensors = load_all_tensors(model_dir)
    np_dtype = np.float16 if dtype == "f16" else np.float32
    gtype = GGML_F16 if dtype == "f16" else GGML_F32

    with open(out_path, "wb") as f:
        w = GgufWriter(f)
        w.kv_str("general.architecture", "llama")
        w.kv_str("general.name", os.path.basename(os.path.abspath(model_dir)))
        w.kv_u32("general.file_type", 1 if dtype == "f16" else 0)
        w.kv_u32("llama.context_length", ctx)
        w.kv_u32("llama.embedding_length", n_embd)
        w.kv_u32("llama.block_count", n_layer)
        w.kv_u32("llama.feed_forward_length", n_ff)
        w.kv_u32("llama.attention.head_count", n_head)
        w.kv_u32("llama.attention.head_count_kv", n_kv)
        w.kv_f32("llama.attention.layer_norm_rms_epsilon", eps)
        w.kv_u32("llama.rope.dimension_count", rope_dim)
        w.kv_f32("llama.rope.freq_base", rope_theta)
        w.kv_str("tokenizer.ggml.model", "gpt2")
        w.kv_str_array("tokenizer.ggml.tokens", tokens)
        w.kv_i32_array("tokenizer.ggml.token_type", types)
        w.kv_str_array("tokenizer.ggml.merges", merges)
        w.kv_u32("tokenizer.ggml.bos_token_id", bos)
        w.kv_u32("tokenizer.ggml.eos_token_id", eos)
        w.kv_bool("tokenizer.ggml.add_bos_token", True)
        w.kv_bool("tokenizer.ggml.add_eos_token", False)
        if chat_template:
            w.kv_str("tokenizer.chat_template", chat_template)

        written = 0
        has_lm_head = any(gguf_name(n) == "output.weight" for n in tensors)
        for hf_name in sorted(tensors):
            gname = gguf_name(hf_name)
            if gname is None:
                continue
            st, name = tensors[hf_name]
            arr = st.tensor(name)
            arr = arr.astype(np_dtype)
            w.add_tensor(gname, arr, gtype)
            written += 1
            if hf_name.endswith("embed_tokens.weight") and not has_lm_head and tied:
                w.add_tensor("output.weight", arr, gtype)
                written += 1
                print("tied embeddings -> duplicated token_embd as output.weight")

        if not has_lm_head and not tied:
            print("warning: no lm_head.weight and tie_word_embeddings is false")

        print(f"writing {written} tensors -> {out_path}")
        w.write()

    print("done:", os.path.getsize(out_path), "bytes")


if __name__ == "__main__":
    main()
