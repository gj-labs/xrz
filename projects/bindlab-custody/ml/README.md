# ml/: model export for BindLab XR

These scripts turn the models into ExecuTorch `.pte` files for the two devices:

| Model | Device | Primary backend | Fallback |
|---|---|---|---|
| ESM-2 8M / 35M / 150M / 650M / 3B (`facebook/esm2_t*`) | Galaxy S25 Ultra, `SM8750` (HTP V79) | QNN 16a8w | XNNPACK (fp32 ≤150M, 8da4w for 650M/3B) |
| YOLO11n, pretrained COCO, no training | Galaxy XR, `SXR2230P` (HTP V69) | QNN 16a8w | XNNPACK fp32 |
| Binding head (tiny MLP over ESM embeddings) | S25 | XNNPACK, or JSON weights | cosine threshold |

Pinned stack: ExecuTorch **1.5.1**, QAIRT **2.51.0.260929**, NDK **r26**. A Mac can only build the XNNPACK
artifacts. QNN AOT compilation needs a **Linux x86_64** host.

## Mac (XNNPACK): tested

```bash
cd ml
python3 -m venv .venv            # Python 3.10–3.14 all have ET 1.5.1 wheels; tested with 3.14
.venv/bin/pip install -r requirements.txt
source .venv/bin/activate

python export_esm2_xnnpack.py --size 8M       # -> artifacts/esm2_8M_xnnpack.pte
python export_esm2_xnnpack.py --size 35M
python export_esm2_xnnpack.py --size 650M     # 8da4w, ~2.6 GB HF download
python export_yolo.py --backend xnnpack --imgsz 640   # -> artifacts/yolo11n_xnnpack.pte
python export_yolo.py --backend xnnpack --imgsz 320   # -> artifacts/yolo11n_320_xnnpack.pte
python binding_head.py --size 8M              # -> binding_head_8M.{pte,json}
python binding_head.py --size 35M
python verify.py                              # runtime vs eager, cosine > 0.98, writes MANIFEST.json
python contacts.py --size 8M --target <SEQ> --binder <SEQ>
python export_esm2_qnn.py --size 8M --cpu_check   # validates the QNN graph without QNN
```

`fetch_data.py` rebuilds `data/ppi_pairs.tsv` (133 two-entity PDB heterocomplexes, mostly from Docking
Benchmark 5) and `data/calib.fasta` (50 chains) from RCSB. Both files are already bundled, so you only
need it to regenerate them.

## Linux x86_64 (QNN)

```bash
# 1. QAIRT 2.51.0.260929 from the Qualcomm Software Center, unpacked e.g. to /opt/qcom/aistack/qairt/
export QNN_SDK_ROOT=/opt/qcom/aistack/qairt/2.51.0.260929
export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH
export ANDROID_NDK_ROOT=/opt/android-ndk-r26d     # runtime build only

# 2. Python env. Setting QNN_SDK_ROOT stops ExecuTorch from auto-downloading its default SDK version.
cd ml
python3.12 -m venv .venv-qnn && source .venv-qnn/bin/activate
pip install -r requirements.txt
python -c "import executorch.backends.qualcomm.python.PyQnnManagerAdaptor" && echo QNN AOT OK
```

If that import fails, the wheel has no Qualcomm AOT extension. Build it from source at the same tag:

```bash
git clone -b v1.5.1 --recursive https://github.com/pytorch/executorch.git ~/executorch
cd ~/executorch && ./install_executorch.sh
./backends/qualcomm/scripts/build.sh          # x86_64 PyQnnManagerAdaptor + android runtime (needs NDK)
cp build-x86/backends/qualcomm/PyQnnManagerAdaptor*.so backends/qualcomm/python/
export PYTHONPATH=$HOME:$PYTHONPATH            # so `import executorch` resolves to ~/executorch
```

Then compile:

```bash
python export_esm2_qnn.py --size 8M                     # -> artifacts/esm2_8M_qnn.pte (SM8750)
python export_esm2_qnn.py --size 35M
python export_esm2_qnn.py --size 150M
python export_esm2_qnn.py --size 650M
python export_esm2_qnn.py --size 3B --num_shards 4      # -> esm2_3B_qnn_shard{0..3}.pte
python export_yolo.py --backend qnn --imgsz 640         # -> yolo11n_qnn.pte (SXR2230P)
python export_yolo.py --backend qnn --imgsz 320 --calib_dir /path/to/lab/photos
python verify.py                                        # records QNN files as "skipped" (device-only)
```

### What we built (InstaBox, Debian 13 x86_64, ExecuTorch v1.5.1 + QAIRT 2.51.0.260929)

| Built | Result |
|---|---|
| `esm2_{8M,35M,150M,650M}_qnn.pte` (SM8750, 16a8w) | OK: 10 MB / 40 MB / 166 MB / 667 MB |
| `yolo11n` QNN (SXR2230P and SM8750, 16a8w and 8a8w) | **Fails**: QNN rejects `view_copy`/`matmul` in the YOLO11 graph (`0xc26`). Use `yolo11n_xnnpack.pte` (CPU) |
| `esm2_3B` QNN | Skipped: the 8 GiB host can't load it |
| `android/libs/executorch-qnn.aar` | arm64 AAR with `libexecutorch.so` + `libqnn_executorch_backend.so` |
| `artifacts/runtime/{s25,xr}/` | `qnn_executor_runner`, backend `.so` and QAIRT libs (V79 for S25, V69 for XR); S25 also has `qnn_llama_runner` |

All of these are gitignored, so share them out of band. The AAR doesn't include the QAIRT libs: an app using it
must ship `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`, `libQnnHtpV79Stub.so` and `libQnnHtpV79Skel.so`
(V69 versions for the XR) in `jniLibs/arm64-v8a`, and set `ADSP_LIBRARY_PATH` to its native lib dir before
loading a model. Debian also needs `apt install libc++1` for the QAIRT host libraries.

Quick on-device check (S25):

```bash
adb push artifacts/runtime/s25/* artifacts/esm2_8M_qnn.pte /data/local/tmp/et_qnn/
adb shell "cd /data/local/tmp/et_qnn && LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./qnn_executor_runner --model_path esm2_8M_qnn.pte --warm_up 10 --iteration 50"
```

Off Linux x86_64, `export_esm2_qnn.py` and `export_yolo.py --backend qnn` print these instructions and exit 2.
Other SoCs: `--soc_model SXR2230P` etc. Files for a non-default SoC get a `_{soc}` suffix. ExecuTorch 1.5.1
also knows `SXR2330P` (V79). Check the XR's real SoC with `adb shell getprop ro.soc.model` before you trust
the V69 target.

## Artifacts and I/O contracts

All files land in `artifacts/`. `MANIFEST.json` records each file's backend, soc_model, qairt_version,
sha256, size, I/O shapes and the verify result.

**ESM-2**: `esm2_{size}_{xnnpack|qnn}.pte`, and `esm2_vocab.json` for tokenisation.
- in: `input_ids [1,256]`, laid out as `<cls> residues <eos> <pad>…`. The dtype is int64 for XNNPACK and int32 for QNN, as in the QNN BERT/RoBERTa examples.
- out: `embedding [1,D]` fp32, the mean over real residues (`<cls>`/`<eos>`/`<pad>` masked). D = 320 / 480 / 640 / 1280 / 2560.
- sizes ≤150M also output `contacts [1,254,254]`: sigmoid probabilities from ESM's contact head, indexed by
  residue position. Residues past 254 are truncated.
- 3B QNN shards are `esm2_3B_qnn_shard{k}.pte`. The outputs of shard k are exactly the inputs of shard k+1:
  `shard0(input_ids) → (hidden [1,256,2560], keep [1,256])`, and the last shard returns `embedding`.

**YOLO11n**: `yolo11n_{xnnpack|qnn}.pte` (640) and `yolo11n_320_{…}.pte`.
- in: float32 `[1,3,S,S]`, RGB, 0..1, letterboxed with 114 grey. **It must be a contiguous NCHW buffer.** A
  permuted HWC view gives garbage without any error.
- out: `[1,84,N]`, N = 8400 at 640 or 2100 at 320. Rows 0–3 are cx, cy, w, h in input pixels. Rows 4–83 are COCO
  class sigmoid scores. NMS happens on the device.
- `yolo_labels.json` holds the 80 COCO names. `coco_to_lab.json` maps bottle→reagent, cup→beaker,
  scissors→tool, cell phone→scanner, book→notebook, person→operator, with their class ids.

**Binding head**: `binding_head_{size}.pte` maps `(emb_target [1,D], emb_binder [1,D]) → prob [1,1]` and is
order-invariant. `binding_head_{size}.json` holds the same weights for apps or servers that don't use
ExecuTorch: `x=(f−feature_mean)/feature_std`, then `layers[]`, where f = `[t, b, |t−b|, t*b]`. It also stores
`cosine_threshold` as the fallback. Data comes from `data/ppi_pairs.tsv` (positives) with partners shuffled
across complexes (negatives), split by complex. **Held-out AUC is only about 0.6–0.65**, so read the score as
a demo-quality ranking signal, not a predictor.

**Contacts**: `contacts.py` concatenates `target + GGGGS + binder` (`--linker_repeats` sets the repeat count)
and ranks the target×binder block of the contact map. Pairs closer than `--min_sep` (24) along the chain are
dropped, because otherwise the top hits are the residues on either side of the linker. The output is
`{"target_residues": [...], "binder_residues": [...], "pairs": [...]}` with 1-based residue numbers.
`contacts_from_map()` can be imported to handle a map the device already returned. Small ESM models give
weak inter-chain signal (probabilities around 0.02), so treat the result as a visual hint.

## Measured on this Mac (M2 Pro, ET 1.5.1 Python runtime, XNNPACK)

See `artifacts/MANIFEST.json` for the full record. Cosine is the minimum over the test inputs (4 protein
sequences / 2 images / 16 embedding pairs).

| File | Size | Cosine vs eager | Host latency |
|---|---|---|---|
| esm2_8M_xnnpack.pte | 28.4 MB | emb 1.000000, contacts 0.999978 | 35 ms |
| esm2_35M_xnnpack.pte | 127.1 MB | emb 0.999999, contacts 0.999735 | 98 ms |
| esm2_650M_xnnpack.pte (8da4w) | 350.9 MB | emb 0.988698 (vs fp32 eager) | 390 ms |
| yolo11n_xnnpack.pte (640) | 10.2 MB | 0.999999 (class scores 1.000000) | 28 ms |
| yolo11n_320_xnnpack.pte | 10.1 MB | 0.999999 (class scores 1.000000) | 11 ms |
| binding_head_8M.pte / 35M | 0.3 / 0.5 MB | 1.000000 | <0.1 ms |

650M with 8da4w only just clears the 0.98 bar, and 3B (not exported here, ~11 GB fp32 download) may land lower. If it
fails `verify.py`, try `--group_size 16` or ship 3B through QNN 16a8w.
