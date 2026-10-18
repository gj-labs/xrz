"""Shared helpers for the BindLab XR ml/ pipeline.

ESM2 wrapper, tokenisation, FASTA I/O and MANIFEST bookkeeping live here so
that the XNNPACK exporter, the QNN exporter, verify.py, binding_head.py and
contacts.py all agree on exactly the same graph and input format.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
import os
from pathlib import Path
from typing import Iterable

import torch
from torch import nn

ML_DIR = Path(__file__).resolve().parent
ARTIFACTS = ML_DIR / "artifacts"
DATA = ML_DIR / "data"
MANIFEST = ARTIFACTS / "MANIFEST.json"

EXECUTORCH_VERSION = "1.5.1"
QAIRT_VERSION = "2.51.0.260929"

SEQ_LEN = 256  # fixed token length incl. <cls> and <eos>
MAX_RESIDUES = SEQ_LEN - 2

ESM2_MODELS = {
    "8M": "facebook/esm2_t6_8M_UR50D",
    "35M": "facebook/esm2_t12_35M_UR50D",
    "150M": "facebook/esm2_t30_150M_UR50D",
    "650M": "facebook/esm2_t33_650M_UR50D",
    "3B": "facebook/esm2_t36_3B_UR50D",
}
# Contact head is exported only up to 150M: the stacked attention maps grow as
# layers*heads*L*L and stop being worth shipping on-device past that.
CONTACT_SIZES = {"8M", "35M", "150M"}

# ESM-2 alphabet (fair-esm / HF EsmTokenizer order). Shipped to the app as
# artifacts/esm2_vocab.json so the Android side can tokenise without HF.
ESM_VOCAB = [
    "<cls>", "<pad>", "<eos>", "<unk>",
    "L", "A", "G", "V", "S", "E", "R", "T", "I", "D", "P", "K",
    "Q", "N", "F", "Y", "M", "H", "W", "C", "X", "B", "U", "Z", "O",
    ".", "-", "<null_1>", "<mask>",
]
CLS_ID, PAD_ID, EOS_ID, UNK_ID = 0, 1, 2, 3
MASK_ID = ESM_VOCAB.index("<mask>")
_TOK = {t: i for i, t in enumerate(ESM_VOCAB)}


# --------------------------------------------------------------------------
# Tokenisation / FASTA
# --------------------------------------------------------------------------

def tokenize(seq: str, seq_len: int = SEQ_LEN) -> torch.Tensor:
    """Residue string -> int64 [1, seq_len]: <cls> seq <eos> <pad>..."""
    seq = seq.strip().upper().replace(" ", "")
    if len(seq) > seq_len - 2:
        seq = seq[: seq_len - 2]
    ids = [CLS_ID] + [_TOK.get(c, UNK_ID) for c in seq] + [EOS_ID]
    ids += [PAD_ID] * (seq_len - len(ids))
    return torch.tensor([ids], dtype=torch.long)


def read_fasta(path: str | os.PathLike) -> list[tuple[str, str]]:
    recs, name, buf = [], None, []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith(">"):
            if name is not None:
                recs.append((name, "".join(buf)))
            name, buf = line[1:].strip(), []
        else:
            buf.append(line)
    if name is not None:
        recs.append((name, "".join(buf)))
    return recs


def write_vocab() -> Path:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    p = ARTIFACTS / "esm2_vocab.json"
    p.write_text(json.dumps({
        "tokens": ESM_VOCAB, "cls_id": CLS_ID, "pad_id": PAD_ID, "eos_id": EOS_ID,
        "unk_id": UNK_ID, "mask_id": MASK_ID, "seq_len": SEQ_LEN,
        "format": "<cls> residues <eos> then <pad> to seq_len; residues beyond seq_len-2 are truncated",
    }, indent=2))
    return p


# --------------------------------------------------------------------------
# ESM2 wrapper
# --------------------------------------------------------------------------

def load_esm2(size: str):
    from transformers import EsmModel

    if size not in ESM2_MODELS:
        raise SystemExit(f"unknown size {size}; choose from {list(ESM2_MODELS)}")
    # Eager attention: needed for attention maps (contacts) and lowers to
    # plain matmul/softmax on both XNNPACK and QNN.
    model = EsmModel.from_pretrained(
        ESM2_MODELS[size], add_pooling_layer=False, attn_implementation="eager"
    )
    return model.eval()


def residue_mask(input_ids: torch.Tensor) -> torch.Tensor:
    """1.0 for real residues, 0.0 for <cls>/<eos>/<pad>."""
    return ((input_ids != PAD_ID) & (input_ids != CLS_ID) & (input_ids != EOS_ID)).to(torch.float32)


def masked_mean(hidden: torch.Tensor, input_ids: torch.Tensor) -> torch.Tensor:
    m = residue_mask(input_ids).unsqueeze(-1)  # [1, L, 1]
    return (hidden * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)


class ContactHead(nn.Module):
    """Re-implementation of HF EsmContactPredictionHead + predict_contacts.

    Takes the stacked attention maps [B, layers, heads, L, L], strips
    <cls>/<eos>, applies APC + symmetrisation and the pretrained logistic
    regression. Output is a sigmoid probability map [B, L-2, L-2] over residue
    positions (index i = residue i of the padded token row, minus <cls>).
    """

    def __init__(self, hf_head):
        super().__init__()
        self.regression = hf_head.regression

    @staticmethod
    def _apc(x):
        a1 = x.sum(-1, keepdims=True)
        a2 = x.sum(-2, keepdims=True)
        a12 = x.sum((-1, -2), keepdims=True)
        return x - (a1 * a2) / a12

    def forward(self, input_ids, attentions):
        tok = (input_ids != PAD_ID).to(attentions.dtype)
        eos = (input_ids != EOS_ID).to(attentions.dtype)
        pair = (tok * eos)  # zero out pads and <eos>
        pm = pair.unsqueeze(1) * pair.unsqueeze(2)  # [B, L, L]
        attentions = attentions * pm[:, None, None, :, :]
        attentions = attentions[..., 1:-1, 1:-1]  # drop <cls> and final slot
        b, layers, heads, l, _ = attentions.shape
        attentions = attentions.reshape(b, layers * heads, l, l)
        attentions = attentions + attentions.transpose(-1, -2)
        attentions = self._apc(attentions)
        attentions = attentions.permute(0, 2, 3, 1)
        return torch.sigmoid(self.regression(attentions).squeeze(3))


class ESM2Embedder(nn.Module):
    """input_ids [1, 256] int64 -> (embedding [1, D], contacts [1, 254, 254]?)."""

    def __init__(self, esm, with_contacts: bool):
        super().__init__()
        self.esm = esm
        self.with_contacts = with_contacts
        if with_contacts:
            self.contact = ContactHead(esm.contact_head)

    def forward(self, input_ids: torch.Tensor):
        attn_mask = (input_ids != PAD_ID).to(torch.long)
        out = self.esm(
            input_ids=input_ids,
            attention_mask=attn_mask,
            output_attentions=self.with_contacts,
            return_dict=True,
        )
        emb = masked_mean(out.last_hidden_state, input_ids)
        if not self.with_contacts:
            return (emb,)
        attns = torch.stack(out.attentions, dim=1)  # [B, layers, heads, L, L]
        return emb, self.contact(input_ids, attns)


def build_esm2_wrapper(size: str, contacts: bool | None = None) -> ESM2Embedder:
    if contacts is None:
        contacts = size in CONTACT_SIZES
    return ESM2Embedder(load_esm2(size), contacts).eval()


def example_ids() -> torch.Tensor:
    # Ubiquitin: a stable, well-known 76-aa sequence for tracing/verification.
    return tokenize("MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG")


# --------------------------------------------------------------------------
# MANIFEST
# --------------------------------------------------------------------------

def sha256(path: str | os.PathLike) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def shapes(tensors: Iterable[torch.Tensor]) -> list[dict]:
    return [{"shape": list(t.shape), "dtype": str(t.dtype).replace("torch.", "")} for t in tensors]


def update_manifest(file: str | os.PathLike, **fields) -> dict:
    """Insert or merge the entry for `file` (keyed by basename) in MANIFEST.json."""
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    file = Path(file)
    data = {"executorch_version": EXECUTORCH_VERSION, "entries": []}
    if MANIFEST.exists():
        data = json.loads(MANIFEST.read_text())
    entries = {e["file"]: e for e in data.get("entries", [])}
    entry = entries.get(file.name, {"file": file.name})
    entry.update(fields)
    if file.exists():
        entry["size_bytes"] = file.stat().st_size
        entry["sha256"] = sha256(file)
    entry.setdefault("soc_model", None)
    entry.setdefault("qairt_version", QAIRT_VERSION if entry.get("backend") == "qnn" else None)
    entry["updated"] = _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds")
    entries[file.name] = entry
    data["entries"] = sorted(entries.values(), key=lambda e: e["file"])
    MANIFEST.write_text(json.dumps(data, indent=2) + "\n")
    return entry


def human(nbytes: float) -> str:
    for unit in ("B", "KB", "MB"):
        if nbytes < 1024:
            return f"{nbytes:.1f} {unit}"
        nbytes /= 1024
    return f"{nbytes:.2f} GB"
