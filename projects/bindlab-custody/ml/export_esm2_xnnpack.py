"""Export ESM-2 (8M .. 3B) to an XNNPACK .pte (CPU fallback path; runs on a Mac).

    python export_esm2_xnnpack.py --size 8M
    python export_esm2_xnnpack.py --size 650M          # 8da4w by default
    python export_esm2_xnnpack.py --size 35M --quant 8da4w

Graph: input_ids int64 [1, 256] -> embedding fp32 [1, D] (mean over real
residues; <cls>/<eos>/<pad> masked) and, for sizes <= 150M, contact
probabilities fp32 [1, 254, 254]. Output: artifacts/esm2_{size}_xnnpack.pte
"""

from __future__ import annotations

import argparse
import time

import torch

from common import (
    ARTIFACTS,
    CONTACT_SIZES,
    ESM2_MODELS,
    SEQ_LEN,
    build_esm2_wrapper,
    example_ids,
    human,
    shapes,
    update_manifest,
    write_vocab,
)

DEFAULT_QUANT = {"8M": "fp32", "35M": "fp32", "150M": "fp32", "650M": "8da4w", "3B": "8da4w"}


def apply_8da4w(model: torch.nn.Module, group_size: int) -> None:
    """int8 dynamic activations + int4 grouped weights on every nn.Linear.

    Same torchao config ExecuTorch's own XNNPACK recipe / LLM exporter use, so
    XnnpackPartitioner picks the linears up as QD8-QC4W kernels.
    """
    from torchao.quantization import quantize_
    from torchao.quantization.granularity import PerGroup
    from torchao.quantization.quant_api import Int8DynamicActivationIntxWeightConfig

    quantize_(
        model,
        Int8DynamicActivationIntxWeightConfig(
            weight_dtype=torch.int4,
            weight_granularity=PerGroup(group_size),
        ),
        # Encoder linears only. ESM's contact regression (in = layers*heads, e.g. 660)
        # is not group-divisible and stays fp32.
        filter_fn=lambda m, fqn: isinstance(m, torch.nn.Linear) and m.in_features % group_size == 0,
    )


def export(size: str, quant: str, group_size: int, contacts: bool | None) -> dict:
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower

    t0 = time.time()
    model = build_esm2_wrapper(size, contacts)
    ids = example_ids()
    with torch.no_grad():
        eager = model(ids)
    if quant == "8da4w":
        apply_8da4w(model, group_size)
    print(f"[esm2 {size}] loaded {ESM2_MODELS[size]} ({time.time() - t0:.1f}s), quant={quant}")

    with torch.no_grad():
        ep = torch.export.export(model, (ids,), strict=False)
    prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    out = ARTIFACTS / f"esm2_{size}_xnnpack.pte"
    out.write_bytes(prog.buffer)
    write_vocab()
    names = ["embedding"] + (["contacts"] if model.with_contacts else [])
    entry = update_manifest(
        out,
        kind="esm2",
        model_id=ESM2_MODELS[size],
        size=size,
        backend="xnnpack",
        quant=quant,
        group_size=group_size if quant == "8da4w" else None,
        soc_model=None,
        qairt_version=None,
        inputs=[{"name": "input_ids", **shapes([ids])[0]}],
        outputs=[{"name": n, **s} for n, s in zip(names, shapes(eager))],
        seq_len=SEQ_LEN,
        vocab="esm2_vocab.json",
    )
    print(f"[esm2 {size}] wrote {out} ({human(entry['size_bytes'])}) in {time.time() - t0:.1f}s")
    return entry


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", required=True, choices=list(ESM2_MODELS))
    ap.add_argument("--quant", choices=["auto", "fp32", "8da4w"], default="auto",
                    help="auto = fp32 for <=150M, 8da4w for 650M/3B")
    ap.add_argument("--group_size", type=int, default=32, help="8da4w weight group size")
    ap.add_argument("--contacts", choices=["auto", "on", "off"], default="auto",
                    help=f"export the contact head (auto = on for {sorted(CONTACT_SIZES)})")
    a = ap.parse_args()
    quant = DEFAULT_QUANT[a.size] if a.quant == "auto" else a.quant
    contacts = {"auto": None, "on": True, "off": False}[a.contacts]
    export(a.size, quant, a.group_size, contacts)


if __name__ == "__main__":
    main()
