"""Run each .pte through the ExecuTorch Python runtime and compare with eager PyTorch.

    python verify.py                       # every .pte in artifacts/
    python verify.py artifacts/esm2_8M_xnnpack.pte --threshold 0.98

Per output it reports cosine similarity (flattened) between the runtime and the
fp32 eager reference over a few real inputs, and fails if any is below the
threshold. Results go into artifacts/MANIFEST.json (file, backend, soc_model,
qairt_version, sha256, input/output shapes, cosine, host latency).

QNN .pte files cannot execute on the host (they hold HTP context binaries); they
are recorded in the MANIFEST as "skipped" and are checked on device instead
(examples/qualcomm SimpleADB, or the Android app's self-test).
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import statistics
import sys
import time
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "false")

import torch

from common import ARTIFACTS, DATA, MANIFEST, build_esm2_wrapper, example_ids, read_fasta, shapes, tokenize, update_manifest

F = torch.nn.functional


def manifest_entries() -> dict[str, dict]:
    if not MANIFEST.exists():
        return {}
    return {e["file"]: e for e in json.loads(MANIFEST.read_text()).get("entries", [])}


def infer_entry(path: Path) -> dict:
    n = path.name
    if m := re.match(r"esm2_(\w+?)_(xnnpack|qnn)", n):
        return {"kind": "esm2", "size": m.group(1), "backend": m.group(2)}
    if m := re.match(r"binding_head_(\w+)\.pte", n):
        return {"kind": "binding_head", "size": m.group(1), "backend": "xnnpack"}
    if m := re.match(r"(yolo[a-z0-9]+?)(?:_(\d+))?_(xnnpack|qnn)", n):
        return {"kind": "yolo", "model_id": m.group(1), "imgsz": int(m.group(2) or 640), "backend": m.group(3)}
    return {"kind": "unknown", "backend": "qnn" if "_qnn" in n else "xnnpack"}


def esm_cases(entry):
    ids = [example_ids()] + [tokenize(s) for _, s in read_fasta(DATA / "calib.fasta")[:3]]
    ref = build_esm2_wrapper(entry["size"], contacts=len(entry.get("outputs", [])) > 1)
    with torch.no_grad():
        return [((x,), ref(x)) for x in ids], ["embedding", "contacts"]


def yolo_cases(entry):
    import cv2
    import numpy as np
    from ultralytics.data.augment import LetterBox
    from ultralytics.utils import ASSETS

    from export_yolo import SingleHead, export_mode_model, load_yolo

    imgsz = entry["imgsz"]
    ref = SingleHead(export_mode_model(load_yolo(f"{entry.get('model_id', 'yolo11n')}.pt")))
    lb = LetterBox((imgsz, imgsz), auto=False)
    cases = []
    for f in sorted(ASSETS.glob("*.jpg")):
        im = lb(image=cv2.imread(str(f)))
        x = torch.from_numpy(np.ascontiguousarray(im[..., ::-1])).permute(2, 0, 1).float().div(255)[None].contiguous()
        with torch.no_grad():
            cases.append(((x,), (ref(x),)))
    return cases, ["detections"]


def binding_cases(entry):
    from binding_head import BindingHead

    w = json.loads((ARTIFACTS / entry.get("weights_json", f"binding_head_{entry['size']}.json")).read_text())
    l1, l2 = w["layers"]
    head = BindingHead(w["dim"], len(l1["bias"])).eval()
    sd = {"mu": w["feature_mean"], "sigma": w["feature_std"], "net.0.weight": l1["weight"], "net.0.bias": l1["bias"],
          "net.3.weight": l2["weight"], "net.3.bias": l2["bias"]}
    head.load_state_dict({k: torch.tensor(v) for k, v in sd.items()})
    g = torch.Generator().manual_seed(0)
    cases = []
    with torch.no_grad():
        for _ in range(16):
            t, b = torch.randn(1, w["dim"], generator=g) * 0.2, torch.randn(1, w["dim"], generator=g) * 0.2
            cases.append(((t, b), (head(t, b),)))
    return cases, ["prob"]


CASES = {"esm2": esm_cases, "yolo": yolo_cases, "binding_head": binding_cases}


def run_pte(path: Path, cases):
    from executorch.runtime import Runtime

    method = Runtime.get().load_program(str(path)).load_method("forward")
    outs, times = [], []
    for inputs, _ in cases:
        t0 = time.perf_counter()
        o = method.execute(list(inputs))
        times.append((time.perf_counter() - t0) * 1e3)
        outs.append(o)
    return outs, times


def verify(path: Path, entry: dict, threshold: float) -> bool:
    if entry.get("backend") == "qnn":
        update_manifest(path, verify={"status": "skipped", "reason": "QNN HTP binary; verify on device"})
        print(f"SKIP {path.name}: QNN .pte runs only on a Snapdragon device")
        return True
    if entry.get("kind") not in CASES:
        print(f"SKIP {path.name}: unknown model kind")
        return True

    cases, names = CASES[entry["kind"]](entry)
    outs, times = run_pte(path, cases)
    if entry["kind"] == "binding_head":  # scalar outputs: compare the vector of all calls
        got = torch.cat([o[0].flatten() for o in outs])
        exp = torch.cat([r[0].flatten() for _, r in cases])
        cos = {"prob": F.cosine_similarity(got, exp, dim=0).item()}
        extra = {"max_abs_diff": (got - exp).abs().max().item()}
    else:
        cos, extra = {}, {}
        for k in range(len(cases[0][1])):
            vals = [F.cosine_similarity(o[k].flatten().float(), r[k].flatten().float(), dim=0).item()
                    for o, (_, r) in zip(outs, cases)]
            cos[names[k]] = min(vals)
        if entry["kind"] == "yolo":  # also check class scores alone; boxes dominate the flat vector
            vals = [F.cosine_similarity(o[0][:, 4:].flatten(), r[0][:, 4:].flatten(), dim=0).item()
                    for o, (_, r) in zip(outs, cases)]
            cos["class_scores"] = min(vals)
    ok = all(v > threshold for v in cos.values())
    lat = statistics.median(times[1:] if len(times) > 1 else times)
    update_manifest(
        path, **{k: v for k, v in entry.items()
                 if k not in ("file", "verify", "sha256", "size_bytes", "updated", "inputs", "outputs")},
        inputs=[{"name": i.get("name"), **s} for i, s in zip(entry.get("inputs", [{}] * 9), shapes(cases[0][0]))],
        outputs=[{"name": n, **s} for n, s in zip(names, shapes(outs[0]))],
        verify={"status": "pass" if ok else "fail", "threshold": threshold,
                "cosine_min": {k: round(v, 6) for k, v in cos.items()}, **extra,
                "n_inputs": len(cases), "host": f"{platform.system()} {platform.machine()}",
                "host_latency_ms_median": round(lat, 2)},
    )
    size_mb = path.stat().st_size / 2**20
    print(f"{'PASS' if ok else 'FAIL'} {path.name:34s} {size_mb:8.1f} MB  "
          + "  ".join(f"cos[{k}]={v:.6f}" for k, v in cos.items()) + f"  host {lat:.1f} ms")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help=".pte files (default: all in artifacts/)")
    ap.add_argument("--threshold", type=float, default=0.98)
    a = ap.parse_args()

    files = [Path(f) for f in a.files] or sorted(ARTIFACTS.glob("*.pte"))
    known = manifest_entries()
    ok = True
    for f in files:
        entry = {**infer_entry(f), **known.get(f.name, {})}
        try:
            ok &= verify(f, entry, a.threshold)
        except Exception as e:  # noqa: BLE001
            ok = False
            update_manifest(f, verify={"status": "error", "error": f"{type(e).__name__}: {e}"})
            print(f"ERROR {f.name}: {type(e).__name__}: {e}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
