"""Export pretrained COCO YOLO11n to ExecuTorch (XNNPACK on a Mac, QNN on Linux x86_64).

    python export_yolo.py --backend xnnpack --imgsz 640
    python export_yolo.py --backend xnnpack --imgsz 320
    python export_yolo.py --backend qnn --imgsz 320 --soc_model SXR2230P   # Linux + QAIRT only

Input: float32 [1, 3, imgsz, imgsz], RGB, 0..1, letterboxed (pad value 114/255).
Output (pre-NMS; NMS runs on device): one tensor [1, 84, N]
  rows 0..3  = cx, cy, w, h in input pixels,
  rows 4..83 = per-class sigmoid scores (COCO order, yolo_labels.json).
  N = 8400 at 640, 2100 at 320 (strides 8/16/32).
QNN default is 16a8w: with one per-tensor 16-bit range over 0..640 px the
scores still get ~0.01 resolution (8a8w would give ~2.5, too coarse for scores).

Files: yolo11n_{backend}.pte at 640 (the name the Android apps load),
yolo11n_320_{backend}.pte at 320; QNN for a SoC other than SXR2230P gets a
_{soc} suffix.

XNNPACK first tries ultralytics' own `format="executorch"` exporter and falls
back to a manual torch.export of the same head-in-export-mode graph.
Also writes artifacts/yolo_labels.json and artifacts/coco_to_lab.json.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

# Never let ultralytics pip-install anything (it would fight the pinned ET/torch).
os.environ.setdefault("YOLO_AUTOINSTALL", "false")
os.environ.setdefault("YOLO_OFFLINE", "false")

import torch

from common import ARTIFACTS, ML_DIR, QAIRT_VERSION, shapes, human, update_manifest

WEIGHTS = ML_DIR / "weights"

COCO_TO_LAB = {
    "bottle": "reagent",
    "cup": "beaker",
    "scissors": "tool",
    "cell phone": "scanner",
    "book": "notebook",
    "person": "operator",
}


def load_yolo(weights: str):
    from ultralytics import YOLO

    WEIGHTS.mkdir(exist_ok=True)
    p = Path(weights)
    if not p.is_absolute() and p.parent == Path("."):
        p = WEIGHTS / p.name
    return YOLO(str(p))  # auto-downloads known assets (yolo11n.pt) to that path


def export_mode_model(yolo) -> torch.nn.Module:
    """The DetectionModel with the head in export mode: returns decoded [1, 84, N]."""
    import ultralytics.nn.modules.head as head_mod
    from ultralytics.nn.modules import Detect

    m = yolo.model.float().eval()

    # make_anchors gets strides from a buffer, so torch.full(..., stride) would take a traced
    # value; QNN's op_full needs a Python number. Strides are fixed per model (8/16/32), so read
    # them here, outside tracing, and hand make_anchors those constants.
    fixed = [float(s) for mod in m.modules() if isinstance(mod, Detect) for s in mod.stride.tolist()]
    orig = getattr(head_mod.make_anchors, "_orig", head_mod.make_anchors)

    def make_anchors(feats, strides, offset=0.5):
        return orig(feats, fixed, offset)

    make_anchors._orig = orig
    head_mod.make_anchors = make_anchors

    m = m.fuse() if hasattr(m, "fuse") else m
    for mod in m.modules():
        if isinstance(mod, Detect):
            mod.export = True
            mod.format = "executorch"
            mod.dynamic = False
    for p in m.parameters():
        p.requires_grad_(False)
    return m


class SingleHead(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        y = self.m(x)
        return y[0] if isinstance(y, (tuple, list)) else y


def write_labels(names: dict) -> None:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    labels = [names[i] for i in sorted(names)]
    (ARTIFACTS / "yolo_labels.json").write_text(json.dumps(labels, indent=1) + "\n")
    missing = [k for k in COCO_TO_LAB if k not in labels]
    assert not missing, f"COCO names missing: {missing}"
    (ARTIFACTS / "coco_to_lab.json").write_text(json.dumps({
        "map": COCO_TO_LAB,
        "class_ids": {k: labels.index(k) for k in COCO_TO_LAB},
    }, indent=2) + "\n")


def try_ultralytics_export(yolo, imgsz: int, out: Path) -> bool:
    try:
        path = yolo.export(format="executorch", imgsz=imgsz, batch=1, device="cpu")
        pte = next(Path(path).glob("*.pte"))
        shutil.copyfile(pte, out)
        print(f"[yolo] ultralytics executorch export OK ({pte})")
        return True
    except Exception as e:  # noqa: BLE001
        print(f"[yolo] ultralytics executorch export failed ({type(e).__name__}: {e}); "
              "falling back to manual torch.export", file=sys.stderr)
        return False


def manual_xnnpack(model: torch.nn.Module, x: torch.Tensor, out: Path) -> None:
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower

    ep = torch.export.export(model, (x,), strict=False)
    prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    out.write_bytes(prog.buffer)


def calib_images(imgsz: int, calib_dir: str | None, n: int) -> list[torch.Tensor]:
    """Letterboxed calibration tensors from --calib_dir or ultralytics' bundled samples."""
    import cv2
    import numpy as np
    from ultralytics.data.augment import LetterBox
    from ultralytics.utils import ASSETS

    src = Path(calib_dir) if calib_dir else ASSETS
    files = sorted(p for p in src.rglob("*") if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not files:
        raise SystemExit(f"no calibration images in {src}")
    lb = LetterBox((imgsz, imgsz), auto=False)
    rng = np.random.default_rng(0)
    out = []
    while len(out) < n:
        for f in files:
            im = cv2.imread(str(f))
            if len(out) >= len(files):  # crude augmentation when the pool is tiny
                h, w = im.shape[:2]
                s = rng.uniform(0.5, 1.0)
                y0, x0 = int(rng.uniform(0, h * (1 - s))), int(rng.uniform(0, w * (1 - s)))
                im = im[y0:y0 + int(h * s), x0:x0 + int(w * s)]
                if rng.random() < 0.5:
                    im = im[:, ::-1]
            im = lb(image=np.ascontiguousarray(im))
            t = torch.from_numpy(im[..., ::-1].copy()).permute(2, 0, 1).float().div(255)[None].contiguous()
            out.append(t)
            if len(out) >= n:
                break
    return out


def qnn_export(model: torch.nn.Module, imgsz: int, soc_model: str, quant: str,
               calib_dir: str | None, n_calib: int, out_stem: Path, build_folder: str) -> None:
    from export_esm2_qnn import import_qnn  # prints setup instructions and exits off Linux x86_64

    QnnConfig, build_executorch_binary, make_quantizer, QuantDtype = import_qnn()
    qcfg = QnnConfig(soc_model=soc_model, build_folder=build_folder, compile_only=True)
    quantizer = make_quantizer(
        quant_dtype={"8a8w": QuantDtype.use_8a8w, "16a8w": QuantDtype.use_16a8w}[quant],
        backend=qcfg.backend, soc_model=soc_model,
    )
    data = [(t,) for t in calib_images(imgsz, calib_dir, n_calib)]
    try:
        with torch.no_grad():
            build_executorch_binary(model=model, qnn_config=qcfg, file_name=str(out_stem),
                                    dataset=data, custom_quantizer=quantizer)
    except SystemExit as e:  # build_executorch_binary exits(0) after compile_only
        if e.code not in (0, None):
            raise


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--weights", default="yolo11n.pt")
    ap.add_argument("--imgsz", type=int, choices=[640, 320], default=640)
    ap.add_argument("--backend", choices=["xnnpack", "qnn"], default="xnnpack")
    ap.add_argument("--soc_model", default="SXR2230P", help="QNN target SoC (Galaxy XR)")
    ap.add_argument("--quant", choices=["8a8w", "16a8w"], default="16a8w", help="QNN quantisation")
    ap.add_argument("--calib_dir", default=None, help="folder of lab images for QNN calibration")
    ap.add_argument("--n_calib", type=int, default=32)
    ap.add_argument("--build_folder", default=os.environ.get("ET_BUILD_ANDROID", "build-android"),
                    help="QNN only: ExecuTorch android cmake build dir (QnnConfig requires it)")
    ap.add_argument("--no_ultralytics", action="store_true", help="skip ultralytics' exporter")
    a = ap.parse_args()

    yolo = load_yolo(a.weights)
    write_labels(yolo.names)
    stem = Path(a.weights).stem
    x = torch.zeros(1, 3, a.imgsz, a.imgsz)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)

    size_tag = "" if a.imgsz == 640 else f"_{a.imgsz}"
    if a.backend == "xnnpack":
        out = ARTIFACTS / f"{stem}{size_tag}_xnnpack.pte"
        model = SingleHead(export_mode_model(load_yolo(a.weights)))
        method = "manual"
        if not a.no_ultralytics and try_ultralytics_export(load_yolo(a.weights), a.imgsz, out):
            method = "ultralytics"
        else:
            manual_xnnpack(model, x, out)
        extra = {"soc_model": None, "qairt_version": None}
    else:
        soc_tag = "" if a.soc_model == "SXR2230P" else f"_{a.soc_model}"
        out = ARTIFACTS / f"{stem}{size_tag}_qnn{soc_tag}.pte"
        model = SingleHead(export_mode_model(yolo))
        qnn_export(model, a.imgsz, a.soc_model, a.quant, a.calib_dir, a.n_calib,
                   out.with_suffix(""), a.build_folder)
        method = "qnn"
        extra = {"soc_model": a.soc_model, "qairt_version": QAIRT_VERSION, "quant": a.quant}

    with torch.no_grad():
        eager = model(x)
    eager = (eager,)
    entry = update_manifest(
        out, kind="yolo", model_id=stem, backend=a.backend, export_method=method,
        imgsz=a.imgsz, output_layout="[1, 4+80, N]: cx,cy,w,h px + class sigmoid; NMS on device",
        inputs=[{"name": "images", **shapes([x])[0], "layout": "NCHW RGB 0..1 letterbox"}],
        outputs=[{"name": "detections", **shapes(eager)[0]}],
        labels="yolo_labels.json", lab_map="coco_to_lab.json", **extra,
    )
    print(f"[yolo] wrote {out} ({human(entry['size_bytes'])}) via {method}")


if __name__ == "__main__":
    main()
