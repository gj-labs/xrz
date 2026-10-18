"""Inter-chain contact residues from ESM-2 contact maps (target + G4S linker + binder).

    python contacts.py --target MKT... --binder AQV... [--size 8M] [--pte artifacts/esm2_8M_xnnpack.pte]
    python contacts.py --fasta pair.fasta --top_k 15 --out contacts.json

The two chains are concatenated as target + (GGGGS)*n + binder (n = --linker_repeats,
default 1) and run through the ESM-2 contact head, either eagerly (HF weights) or
through an exported XNNPACK .pte. The target x binder block of the
[254, 254] map is ranked, giving the top-k residue pairs.

Output JSON (1-based residue numbers within each chain):
  {"target_residues": [...], "binder_residues": [...],
   "pairs": [{"target": i, "binder": j, "prob": p}, ...], "size": "8M", "linker": "GGGGS"}

`contacts_from_map()` is importable for the server, which already has a contact
map from the device.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from common import CONTACT_SIZES, MAX_RESIDUES, build_esm2_wrapper, read_fasta, tokenize

G4S = "GGGGS"


def concat(target: str, binder: str, linker_repeats: int = 1) -> tuple[str, int, int]:
    linker = G4S * linker_repeats
    seq = target + linker + binder
    if len(seq) > MAX_RESIDUES:
        raise ValueError(f"target+linker+binder = {len(seq)} residues > {MAX_RESIDUES} window; "
                         "trim the target to the binding domain")
    return seq, len(target), len(linker)


def contacts_from_map(cmap, len_target: int, len_linker: int, len_binder: int, top_k: int = 10,
                      min_sep: int = 24) -> dict:
    """cmap: [L, L] (or [1, L, L]) residue contact probs for the concatenated sequence.

    Pairs closer than `min_sep` residues along the concatenated chain, not counting
    the linker, are dropped. Otherwise the top hits are just the residues either
    side of the linker (ESM sees them as sequence-local).
    """
    cmap = torch.as_tensor(cmap, dtype=torch.float32)
    if cmap.dim() == 3:
        cmap = cmap[0]
    b0 = len_target + len_linker
    block = cmap[:len_target, b0:b0 + len_binder]
    block = 0.5 * (block + cmap[b0:b0 + len_binder, :len_target].T)  # symmetrise
    i = torch.arange(len_target)[:, None]
    j = torch.arange(len_binder)[None, :]
    block = torch.where((len_target - i) + j >= min_sep, block, torch.zeros_like(block))
    k = min(top_k, block.numel())
    vals, flat = torch.topk(block.flatten(), k)
    pairs = [{"target": int(i // len_binder) + 1, "binder": int(i % len_binder) + 1, "prob": round(float(v), 4)}
             for v, i in zip(vals, flat)]
    return {
        "target_residues": sorted({p["target"] for p in pairs}),
        "binder_residues": sorted({p["binder"] for p in pairs}),
        "pairs": pairs,
    }


def predict_map(seq: str, size: str = "8M", pte: str | None = None) -> torch.Tensor:
    ids = tokenize(seq)
    if pte:
        from executorch.runtime import Runtime

        out = Runtime.get().load_program(pte).load_method("forward").execute([ids])
        if len(out) < 2:
            raise SystemExit(f"{pte} has no contact output (exported without the contact head)")
        return out[1][0]
    if size not in CONTACT_SIZES:
        raise SystemExit(f"contacts are only exported for {sorted(CONTACT_SIZES)}")
    with torch.no_grad():
        return build_esm2_wrapper(size, contacts=True)(ids)[1][0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target")
    ap.add_argument("--binder")
    ap.add_argument("--fasta", help="FASTA with two records: target then binder")
    ap.add_argument("--size", default="8M", choices=sorted(CONTACT_SIZES))
    ap.add_argument("--pte", help="use this exported .pte instead of eager PyTorch")
    ap.add_argument("--top_k", type=int, default=10)
    ap.add_argument("--linker_repeats", type=int, default=1)
    ap.add_argument("--min_sep", type=int, default=24,
                    help="ignore pairs closer than this along the chain (linker excluded)")
    ap.add_argument("--out", help="write JSON here (default: stdout)")
    a = ap.parse_args()

    if a.fasta:
        recs = read_fasta(a.fasta)
        if len(recs) < 2:
            raise SystemExit("--fasta needs two records (target, binder)")
        target, binder = recs[0][1], recs[1][1]
    elif a.target and a.binder:
        target, binder = a.target, a.binder
    else:
        raise SystemExit("give --target and --binder, or --fasta")

    seq, lt, ll = concat(target.upper(), binder.upper(), a.linker_repeats)
    res = contacts_from_map(predict_map(seq, a.size, a.pte), lt, ll, len(binder), a.top_k, a.min_sep)
    res.update({"size": a.size, "linker": G4S * a.linker_repeats, "backend": "pte" if a.pte else "eager"})
    text = json.dumps(res, indent=2)
    if a.out:
        Path(a.out).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
