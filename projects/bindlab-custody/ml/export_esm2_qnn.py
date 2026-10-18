"""Export ESM-2 to a QNN (Hexagon HTP) .pte, 16a8w PTQ. Linux x86_64 + QAIRT only.

    # on the Linux host (see README "Linux / QNN"):
    export QNN_SDK_ROOT=/opt/qcom/aistack/qairt/2.51.0.260929
    python export_esm2_qnn.py --size 35M                    # SM8750 (S25 Ultra, V79)
    python export_esm2_qnn.py --size 3B --num_shards 4      # 4 chained .pte shards
    python export_esm2_qnn.py --size 8M --soc_model SXR2230P

    # anywhere (this Mac included): check the QNN graph (int32 ids, -100 mask,
    # shard chaining) in eager fp32 against the reference wrapper, no QNN needed
    python export_esm2_qnn.py --size 8M --num_shards 2 --cpu_check

Same contract as export_esm2_xnnpack.py (mean-pooled embedding [1, D] and,
when unsharded and <= 150M, contacts [1, 254, 254]), with the pattern of
ExecuTorch v1.5.1 examples/qualcomm/oss_scripts/{bert,roberta}.py:
QnnConfig + make_quantizer(QuantDtype.use_16a8w, eps=2**-20) +
build_executorch_binary(compile_only). Differences for HTP:
  * input_ids are int32 (like the QNN BERT/RoBERTa examples);
  * the additive attention mask uses -100 instead of finfo.min, so 16-bit
    activation ranges around the softmax stay usable;
  * calibration: ~50 real protein sequences from data/calib.fasta.

Sharding (--num_shards N): encoder layers are split evenly into N graphs that
are compiled into N separate .pte files (esm2_{size}_qnn_shard{k}.pte) and
chained on device: the outputs of shard k are, in order, the inputs of k+1.
  shard 0       : (input_ids int32 [1,256])            -> (hidden [1,256,D], keep [1,256])
  shard 1..N-2  : (hidden [1,256,D], keep fp32 [1,256]) -> (hidden [1,256,D], keep [1,256])
  shard N-1     : (hidden [1,256,D], keep fp32 [1,256]) -> embedding [1,D]
`keep` is 1.0 for non-pad tokens (incl. <cls>/<eos>), 0.0 for <pad>; the last
shard derives the residue mask for pooling from it.
Each shard is calibrated on the fp32 activations produced by the previous
shards, so ranges match what it will see at runtime. 3B (36 layers, D=2560,
~2.8 GB at 8-bit weights) needs >= 2 shards to stay under the HTP context
limit; 4 is the default.
"""

from __future__ import annotations

import argparse
import os
import platform
import sys
import textwrap
import time
from pathlib import Path

import torch
from torch import nn

from common import (
    ARTIFACTS,
    CONTACT_SIZES,
    DATA,
    ESM2_MODELS,
    PAD_ID,
    QAIRT_VERSION,
    SEQ_LEN,
    ContactHead,
    build_esm2_wrapper,
    human,
    load_esm2,
    masked_mean,
    read_fasta,
    shapes,
    tokenize,
    update_manifest,
    write_vocab,
)

MASK_NEG = -100.0  # exp(-100) == 0 in fp32; keeps the 16-bit range around the softmax tight
DEFAULT_SHARDS = {"8M": 1, "35M": 1, "150M": 1, "650M": 1, "3B": 4}

INSTRUCTIONS = f"""
QNN lowering is unavailable in this environment ({{why}}).

ESM-2 -> QNN needs a Linux x86_64 host with:
  * ExecuTorch 1.5.1 with the Qualcomm AOT extension (PyQnnManagerAdaptor)
  * QAIRT {QAIRT_VERSION} at $QNN_SDK_ROOT
  * Android NDK r26 at $ANDROID_NDK_ROOT (for the runtime build, not for export)

  python3.12 -m venv .venv-qnn && source .venv-qnn/bin/activate
  pip install -r requirements.txt
  export QNN_SDK_ROOT=/opt/qcom/aistack/qairt/{QAIRT_VERSION}
  export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH
  python -c "import executorch.backends.qualcomm.python.PyQnnManagerAdaptor"   # must succeed
  python export_esm2_qnn.py --size 35M

If that import fails, build the adaptor from source (see ml/README.md, "Linux / QNN").
On a Mac, `python export_esm2_qnn.py --size 8M --cpu_check` validates the graph without QNN.
"""


def qnn_unavailable(err: BaseException | str) -> None:
    why = err if isinstance(err, str) else f"{type(err).__name__}: {err}"
    print(textwrap.dedent(INSTRUCTIONS.format(why=why)), file=sys.stderr)
    raise SystemExit(2)


def import_qnn():
    if not (platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64")):
        qnn_unavailable(f"host is {platform.system()} {platform.machine()}, QNN AOT needs Linux x86_64")
    try:
        from executorch.backends.qualcomm.export_utils import QnnConfig, build_executorch_binary, make_quantizer
        from executorch.backends.qualcomm.quantizer.quantizer import QuantDtype
    except Exception as e:  # noqa: BLE001  (ImportError, missing .so, missing SDK libs)
        qnn_unavailable(e)
    return QnnConfig, build_executorch_binary, make_quantizer, QuantDtype


# --------------------------------------------------------------------------
# HTP-friendly ESM-2 graph, optionally a slice of the encoder
# --------------------------------------------------------------------------

class ESM2QnnShard(nn.Module):
    def __init__(self, esm, start: int, end: int, first: bool, last: bool, contacts: bool = False):
        super().__init__()
        self.esm = esm
        self.start, self.end, self.first, self.last = start, end, first, last
        self.contacts = contacts and first and last
        if self.contacts:
            self.contact = ContactHead(esm.contact_head)
        self.register_buffer("position_ids", torch.arange(SEQ_LEN).unsqueeze(0), persistent=False)

    @staticmethod
    def residue_mask_from_keep(keep):
        # real residues = non-pad tokens that are neither <cls> (pos 0) nor <eos>
        # (the last non-pad token, i.e. the one whose successor is pad / end).
        nxt = torch.cat([keep[:, 1:], torch.zeros_like(keep[:, :1])], dim=1)
        first = torch.cat([torch.zeros_like(keep[:, :1]), torch.ones_like(keep[:, 1:])], dim=1)
        return keep * nxt * first

    def forward(self, *args):
        if self.first:
            (input_ids,) = args
            keep = (input_ids != PAD_ID).to(torch.float32)
            h = self.esm.embeddings(input_ids=input_ids, attention_mask=keep.to(torch.long))
        else:
            h, keep = args
        mask = ((1.0 - keep) * MASK_NEG)[:, None, None, :]  # additive, [1, 1, 1, L]
        if self.contacts:  # whole model in one graph: let HF collect the attention maps
            out = self.esm(inputs_embeds=h, attention_mask=mask, output_attentions=True, return_dict=True)
            attns = torch.stack(out.attentions, dim=1)
            return masked_mean(out.last_hidden_state, input_ids), self.contact(input_ids, attns)
        pos = self.esm.rotary_embeddings(h, self.position_ids)
        for layer in self.esm.encoder.layer[self.start:self.end]:
            h = layer(h, attention_mask=mask, position_embeddings=pos)
        if not self.last:
            return h, keep
        h = self.esm.encoder.emb_layer_norm_after(h)
        m = self.residue_mask_from_keep(keep).unsqueeze(-1)
        return ((h * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0),)


def make_shards(esm, num_shards: int, contacts: bool) -> list[ESM2QnnShard]:
    n = esm.config.num_hidden_layers
    if not 1 <= num_shards <= n:
        raise SystemExit(f"--num_shards must be in [1, {n}]")
    bounds = [round(i * n / num_shards) for i in range(num_shards + 1)]
    return [
        ESM2QnnShard(esm, bounds[i], bounds[i + 1], i == 0, i == num_shards - 1, contacts and num_shards == 1).eval()
        for i in range(num_shards)
    ]


def calibration_ids(path: Path, n: int, dtype) -> list[torch.Tensor]:
    recs = read_fasta(path)
    if not recs:
        raise SystemExit(f"no sequences in {path} - run `python fetch_data.py`")
    return [tokenize(s).to(dtype) for _, s in recs[:n]]


@torch.no_grad()
def shard_datasets(shards, ids_list):
    """Per-shard calibration tuples, feeding each shard the fp32 output of the previous one."""
    datasets = [[] for _ in shards]
    for ids in ids_list:
        h = None
        for k, s in enumerate(shards):
            inp = (ids,) if s.first else h
            datasets[k].append(inp)
            h = s(*inp)
    return datasets


def pte_name(size: str, soc: str, k: int, n: int) -> str:
    # SM8750 (S25 Ultra) is the ESM target, so it gets the plain name the app loads.
    base = f"esm2_{size}_qnn" + ("" if soc == "SM8750" else f"_{soc}")
    return base if n == 1 else f"{base}_shard{k}"


@torch.no_grad()
def cpu_check(size: str, shards, ids_list) -> None:
    ref = build_esm2_wrapper(size, contacts=shards[0].contacts)
    worst = 1.0
    for ids in ids_list:
        out = (ids,)
        for s in shards:
            out = s(*out)
        r = ref(ids.to(torch.long))
        cos = torch.nn.functional.cosine_similarity(out[0], r[0]).item()
        worst = min(worst, cos)
        if len(out) > 1:
            worst = min(worst, torch.nn.functional.cosine_similarity(out[1].flatten(), r[1].flatten(), dim=0).item())
    print(f"[qnn cpu_check {size}] {len(shards)} shard(s), {len(ids_list)} seqs: worst cosine vs reference = {worst:.6f}")
    for k, s in enumerate(shards):
        ex = (ids_list[0],) if s.first else (torch.zeros(1, SEQ_LEN, ref.esm.config.hidden_size),
                                             (ids_list[0] != PAD_ID).float())
        torch.export.export(s, ex, strict=False)
        print(f"[qnn cpu_check {size}] shard {k}: layers [{s.start}, {s.end}) torch.export OK")
    if worst < 0.999:
        raise SystemExit("cpu_check FAILED: QNN graph diverges from the reference wrapper")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", required=True, choices=list(ESM2_MODELS))
    ap.add_argument("--soc_model", default="SM8750", help="SM8750 = S25 Ultra (V79); SXR2230P = XR (V69)")
    ap.add_argument("--num_shards", type=int, default=None, help="split encoder layers into N .pte (default: 4 for 3B, else 1)")
    ap.add_argument("--calib", default=str(DATA / "calib.fasta"))
    ap.add_argument("--n_calib", type=int, default=50)
    ap.add_argument("--contacts", choices=["auto", "on", "off"], default="auto")
    ap.add_argument("--ids_dtype", choices=["int32", "int64"], default="int32")
    ap.add_argument("--build_folder", default=os.environ.get("ET_BUILD_ANDROID", "build-android"),
                    help="ExecuTorch android cmake build dir (QnnConfig requires the field; not used with compile_only)")
    ap.add_argument("--cpu_check", action="store_true", help="validate the graph in eager fp32; no QNN needed")
    a = ap.parse_args()

    num_shards = a.num_shards or DEFAULT_SHARDS[a.size]
    contacts = {"auto": a.size in CONTACT_SIZES, "on": True, "off": False}[a.contacts]
    if contacts and num_shards > 1:
        print("[qnn] contacts need every layer's attention in one graph; disabled for sharded export")
        contacts = False
    ids_dtype = getattr(torch, a.ids_dtype)

    if not a.cpu_check:
        QnnConfig, build_executorch_binary, make_quantizer, QuantDtype = import_qnn()

    t0 = time.time()
    esm = load_esm2(a.size)
    shards = make_shards(esm, num_shards, contacts)
    ids_list = calibration_ids(Path(a.calib), a.n_calib, ids_dtype)
    if a.cpu_check:
        cpu_check(a.size, shards, ids_list[:8])
        return

    datasets = shard_datasets(shards, ids_list)
    qcfg = QnnConfig(soc_model=a.soc_model, build_folder=a.build_folder, compile_only=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    write_vocab()
    for k, (shard, data) in enumerate(zip(shards, datasets)):
        stem = ARTIFACTS / pte_name(a.size, a.soc_model, k, num_shards)
        quantizer = make_quantizer(quant_dtype=QuantDtype.use_16a8w, eps=2**-20,
                                   backend=qcfg.backend, soc_model=a.soc_model)
        print(f"[qnn {a.size}] shard {k + 1}/{num_shards}: layers [{shard.start}, {shard.end}), "
              f"calibrating on {len(data)} seqs -> {stem}.pte")
        try:
            # Grad must already be off at capture: HF's rotary embedding toggles it internally,
            # which otherwise lands in the graph as WrapWithSetGradEnabled and breaks QNN passes.
            with torch.no_grad():
                build_executorch_binary(model=shard, qnn_config=qcfg, file_name=str(stem),
                                        dataset=data, custom_quantizer=quantizer)
        except SystemExit as e:  # build_executorch_binary calls sys.exit(0) when compile_only
            if e.code not in (0, None):
                raise
        with torch.no_grad():
            outs = shard(*data[0])
        in_names = ["input_ids"] if shard.first else ["hidden", "keep"]
        out_names = (["embedding"] + (["contacts"] if shard.contacts else [])) if shard.last else ["hidden", "keep"]
        entry = update_manifest(
            stem.with_suffix(".pte"), kind="esm2", model_id=ESM2_MODELS[a.size], size=a.size,
            backend="qnn", soc_model=a.soc_model, qairt_version=QAIRT_VERSION, quant="16a8w",
            shard={"index": k, "count": num_shards, "layers": [shard.start, shard.end]} if num_shards > 1 else None,
            inputs=[{"name": n, **s} for n, s in zip(in_names, shapes(data[0]))],
            outputs=[{"name": n, **s} for n, s in zip(out_names, shapes(outs))],
            seq_len=SEQ_LEN, vocab="esm2_vocab.json", calibration=f"{Path(a.calib).name} x{len(data)}",
        )
        print(f"[qnn {a.size}] wrote {entry['file']} ({human(entry.get('size_bytes', 0))})")
    print(f"[qnn {a.size}] done in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
