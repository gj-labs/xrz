"""Tiny binding-score MLP over ESM-2 embeddings, plus a cosine-threshold fallback.

    python binding_head.py --size 8M
    python binding_head.py --size 35M --epochs 400

Features: [e_t, e_b, |e_t - e_b|, e_t * e_b]  (4*D), standardised with
train-set statistics baked into the model. MLP 4D -> 64 -> 1. The exported
score is order-invariant: p = (sigmoid(f(t, b)) + sigmoid(f(b, t))) / 2.

Data: data/ppi_pairs.tsv (two-entity PDB heterocomplexes, see fetch_data.py)
as positives; negatives by shuffling partners across complexes, skipping
shuffles whose partner resembles the true partner (3-mer Jaccard > 0.3).
The split is by complex. This is a demo-scale dataset (~130 complexes): use the
held-out AUC printed below, not the training accuracy.

Writes artifacts/binding_head_{size}.pte (XNNPACK; inputs emb_target [1, D],
emb_binder [1, D] fp32 -> prob [1, 1]) and artifacts/binding_head_{size}.json
(android/ schema: feature_mean/std + layers[{weight, bias, activation}],
plus cosine threshold and held-out metrics).
"""

from __future__ import annotations

import argparse
import csv
import json
import random
from pathlib import Path

import torch
from torch import nn

from common import ARTIFACTS, DATA, ESM2_MODELS, ML_DIR, build_esm2_wrapper, shapes, tokenize, update_manifest

CACHE = ML_DIR / ".cache"


def load_pairs(path: Path) -> list[dict]:
    if not path.exists():
        raise SystemExit(f"{path} missing - run `python fetch_data.py` first")
    with open(path) as f:
        return list(csv.DictReader(f, delimiter="\t"))


def kmers(s: str, k: int = 3) -> set[str]:
    return {s[i:i + k] for i in range(len(s) - k + 1)}


def similar(a: str, b: str, thr: float = 0.3) -> bool:
    ka, kb = kmers(a), kmers(b)
    return len(ka & kb) / max(1, len(ka | kb)) > thr


@torch.no_grad()
def embed_all(size: str, seqs: list[str]) -> dict[str, torch.Tensor]:
    CACHE.mkdir(exist_ok=True)
    cache = CACHE / f"emb_{size}.pt"
    table = torch.load(cache) if cache.exists() else {}
    todo = [s for s in dict.fromkeys(seqs) if s not in table]
    if todo:
        model = build_esm2_wrapper(size, contacts=False)
        for i, s in enumerate(todo):
            table[s] = model(tokenize(s))[0][0]
            if (i + 1) % 50 == 0:
                print(f"  embedded {i + 1}/{len(todo)}")
        torch.save(table, cache)
    return table


def make_examples(pairs: list[dict], neg_per_pos: int, seed: int):
    rng = random.Random(seed)
    ex = []  # (complex_idx, target_seq, binder_seq, label)
    for i, p in enumerate(pairs):
        ex.append((i, p["target_seq"], p["binder_seq"], 1.0))
        tries, made = 0, 0
        while made < neg_per_pos and tries < 50:
            tries += 1
            j = rng.randrange(len(pairs))
            if j == i:
                continue
            b = pairs[j]["binder_seq"]
            if similar(b, p["binder_seq"]) or similar(pairs[j]["target_seq"], p["target_seq"]):
                continue
            ex.append((i, p["target_seq"], b, 0.0))
            made += 1
    return ex


def features(t: torch.Tensor, b: torch.Tensor) -> torch.Tensor:
    return torch.cat([t, b, (t - b).abs(), t * b], dim=-1)


class BindingHead(nn.Module):
    def __init__(self, dim: int, hidden: int = 64, dropout: float = 0.2):
        super().__init__()
        self.register_buffer("mu", torch.zeros(4 * dim))
        self.register_buffer("sigma", torch.ones(4 * dim))
        self.net = nn.Sequential(nn.Linear(4 * dim, hidden), nn.ReLU(), nn.Dropout(dropout), nn.Linear(hidden, 1))

    def logit(self, t, b):
        return self.net((features(t, b) - self.mu) / self.sigma)

    def forward(self, emb_target, emb_binder):
        return 0.5 * (torch.sigmoid(self.logit(emb_target, emb_binder))
                      + torch.sigmoid(self.logit(emb_binder, emb_target)))


def auc(scores: torch.Tensor, labels: torch.Tensor) -> float:
    pos, neg = scores[labels == 1], scores[labels == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    gt = (pos[:, None] > neg[None, :]).float().mean()
    eq = (pos[:, None] == neg[None, :]).float().mean()
    return float(gt + 0.5 * eq)


def best_threshold(scores: torch.Tensor, labels: torch.Tensor) -> tuple[float, float]:
    best = (0.0, 0.0)
    for thr in torch.unique(scores):
        acc = float(((scores >= thr).float() == labels).float().mean())
        if acc > best[1]:
            best = (float(thr), acc)
    return best


def train(head: BindingHead, T, B, y, epochs: int, lr: float, wd: float, seed: int):
    torch.manual_seed(seed)
    f = torch.cat([features(T, B), features(B, T)])
    head.mu.copy_(f.mean(0))
    head.sigma.copy_(f.std(0).clamp(min=1e-4))
    T2, B2, y2 = torch.cat([T, B]), torch.cat([B, T]), torch.cat([y, y])  # symmetric augmentation
    opt = torch.optim.AdamW(head.parameters(), lr=lr, weight_decay=wd)
    lossf = nn.BCEWithLogitsLoss()
    head.train()
    for _ in range(epochs):
        opt.zero_grad()
        loss = lossf(head.logit(T2, B2).squeeze(-1), y2)
        loss.backward()
        opt.step()
    head.eval()
    return float(loss)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", required=True, choices=list(ESM2_MODELS))
    ap.add_argument("--pairs", default=str(DATA / "ppi_pairs.tsv"))
    ap.add_argument("--neg_per_pos", type=int, default=2)
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--wd", type=float, default=1e-2)
    ap.add_argument("--hidden", type=int, default=64)
    ap.add_argument("--test_frac", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()

    pairs = load_pairs(Path(a.pairs))
    ex = make_examples(pairs, a.neg_per_pos, a.seed)
    table = embed_all(a.size, [s for _, t, b, _ in ex for s in (t, b)])
    dim = next(iter(table.values())).shape[-1]

    idx = list(range(len(pairs)))
    random.Random(a.seed).shuffle(idx)
    test_c = set(idx[: max(1, int(len(idx) * a.test_frac))])

    def tensors(rows):
        T = torch.stack([table[t] for _, t, _, _ in rows])
        B = torch.stack([table[b] for _, _, b, _ in rows])
        return T, B, torch.tensor([y for *_, y in rows])

    tr = [e for e in ex if e[0] not in test_c]
    te = [e for e in ex if e[0] in test_c]
    Ttr, Btr, ytr = tensors(tr)
    Tte, Bte, yte = tensors(te)

    # cosine fallback: threshold chosen on train, evaluated on test
    cos_tr = torch.nn.functional.cosine_similarity(Ttr, Btr)
    cos_te = torch.nn.functional.cosine_similarity(Tte, Bte)
    cos_thr, cos_acc_tr = best_threshold(cos_tr, ytr)
    cos_metrics = {"threshold": cos_thr, "train_acc": cos_acc_tr,
                   "test_acc": float(((cos_te >= cos_thr).float() == yte).float().mean()),
                   "test_auc": auc(cos_te, yte)}

    head = BindingHead(dim, a.hidden)
    train(head, Ttr, Btr, ytr, a.epochs, a.lr, a.wd, a.seed)
    with torch.no_grad():
        p_te = head(Tte, Bte).squeeze(-1)
        p_tr = head(Ttr, Btr).squeeze(-1)
    mlp_metrics = {"train_auc": auc(p_tr, ytr), "test_auc": auc(p_te, yte),
                   "test_acc@0.5": float(((p_te >= 0.5).float() == yte).float().mean())}
    use = "mlp" if mlp_metrics["test_auc"] >= max(0.6, cos_metrics["test_auc"]) else "cosine"
    print(f"[binding {a.size}] n_train={len(tr)} n_test={len(te)} (complexes test={len(test_c)})")
    print(f"[binding {a.size}] MLP    test AUC={mlp_metrics['test_auc']:.3f} acc@0.5={mlp_metrics['test_acc@0.5']:.3f}")
    print(f"[binding {a.size}] cosine test AUC={cos_metrics['test_auc']:.3f} thr={cos_thr:.3f} acc={cos_metrics['test_acc']:.3f}")
    print(f"[binding {a.size}] recommended scorer: {use}")

    # final fit on everything (same hyper-params) for the shipped weights
    T, B, y = tensors(ex)
    final = BindingHead(dim, a.hidden)
    train(final, T, B, y, a.epochs, a.lr, a.wd, a.seed)
    final.eval()

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    stem = ARTIFACTS / f"binding_head_{a.size}"
    lin1, lin2 = final.net[0], final.net[3]
    stem.with_suffix(".json").write_text(json.dumps({
        # schema shared with android/ (see android/README.md): x = (f - feature_mean) / feature_std,
        # then the layers in order; the final scalar is a logit -> sigmoid.
        "size": a.size, "dim": dim,
        "features": "concat_absdiff_prod",  # [e_t, e_b, |e_t - e_b|, e_t * e_b]
        "feature_mean": final.mu.tolist(), "feature_std": final.sigma.tolist(),
        "layers": [
            {"weight": lin1.weight.tolist(), "bias": lin1.bias.tolist(), "activation": "relu"},
            {"weight": lin2.weight.tolist(), "bias": lin2.bias.tolist(), "activation": "none"},
        ],
        "threshold": 0.5,
        # the .pte averages both chain orders; apps doing one pass get f(t, b) only
        "symmetric": True,
        "cosine_threshold": cos_thr,
        "cosine_fallback": cos_metrics, "mlp_holdout": mlp_metrics, "recommended": use,
        "data": {"pairs": Path(a.pairs).name, "n_complexes": len(pairs), "n_examples": len(ex),
                 "neg_per_pos": a.neg_per_pos},
    }) + "\n")

    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower

    ex_in = (torch.randn(1, dim), torch.randn(1, dim))
    with torch.no_grad():
        ep = torch.export.export(final, ex_in, strict=False)
        ref = final(*ex_in)
    prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    pte = stem.with_suffix(".pte")
    pte.write_bytes(prog.buffer)
    update_manifest(
        pte, kind="binding_head", size=a.size, backend="xnnpack", soc_model=None, qairt_version=None,
        inputs=[{"name": "emb_target", **shapes(ex_in[:1])[0]}, {"name": "emb_binder", **shapes(ex_in[1:])[0]}],
        outputs=[{"name": "prob", **shapes([ref])[0]}],
        weights_json=stem.with_suffix(".json").name, recommended=use,
        holdout_auc={"mlp": mlp_metrics["test_auc"], "cosine": cos_metrics["test_auc"]},
    )
    print(f"[binding {a.size}] wrote {pte} and {stem.with_suffix('.json')}")


if __name__ == "__main__":
    main()
