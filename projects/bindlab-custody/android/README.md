# BindLab XR — Android

## Phone to headset viewer (Windows USB demo)

After computation, **Open in XR** saves the result once and calls
`POST /runs/{id}/open-xr` on every tap, including retries. It opens the physics
viewer in `protein-xr-integration/viewer`, with that run's ID and hub address.
Tap **Enter XR** in Chrome to start the immersive session.

For the USB-connected Windows setup, start the hub with `server/run.ps1 -XrUsb`
(or set `BINDLAB_XR_USB=1` before starting uvicorn). This explicitly enables the
local ADB launcher: it reads the registered `xr` target from ignored
`config/devices.local.json`, verifies SXR2230P, restores ports 8000/8080, then
opens Chrome. Keep the headset connected by USB and the viewer server running
on port 8080. No device IDs are stored in source. Without USB mode, the hub
requires the headset BindLab receiver to be connected and sends it a launch
event; Android background-launch restrictions may apply.

The phone's **Sent** status means the launch request succeeded, not that an
immersive session or hand interaction has been verified. Disconnected-device
and launch errors are shown on the phone rather than reported as live in XR.

For this isolated S25 installation, follow [S25-MODEL.md](../S25-MODEL.md).
It uses **BindLab Custody** (`com.bindlab.phone.custody`), ExecuTorch **1.5.1**,
and the verified **portable-cpu** embedding export. The historical upstream
instructions below describe the original apps and are not the S25 setup recipe.

One Gradle project, three modules:

| module | what |
|---|---|
| `:shared` | `InferenceEngine` (+ `ExecuTorchModule`, `ProcessRunner`, `ShardedEngine`), `ModelStore`, OkHttp REST/WS client, `BenchRecorder`, `Haptics`, Settings screen, `assets/config.json` |
| `:app-phone` (`com.bindlab.phone`, S25 Ultra) | QR scan (CameraX + ML Kit) → sequences, ESM2 ladder → `POST /bench`, binding → `POST /binding`, vibrates on WS `haptic` |
| `:app-xr` (`com.bindlab.xr`, Galaxy XR) | CameraX → YOLO11n (letterbox 640, NCHW) → Kotlin decode + NMS, overlay, WS-driven protocol step card (lock/key gate), SoundPool panning + vibration + WS `haptic`, `POST /lab/step` with a JPEG |

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35). On this Mac they came from Homebrew
(`openjdk@17`, `android-commandlinetools`), and the SDK is in `~/Library/Android/sdk` (`local.properties` points there):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd android
./gradlew assembleDebug
```

If the track team's AAR already bundles fbjni and the build reports duplicate classes, remove the
`fbjni`/`nativeloader` lines in `app-*/build.gradle.kts`.

APKs: `app-phone/build/outputs/apk/debug/app-phone-debug.apk`, `app-xr/build/outputs/apk/debug/app-xr-debug.apk`.

**ExecuTorch runtime:** if `android/libs/executorch.aar` (or `<repo>/libs/executorch.aar`) exists, both apps link it
(plus fbjni / soloader). Otherwise they use Maven `org.pytorch:executorch-android:1.0.0`, which has XNNPACK but **no QNN delegate**.
Gradle prints which one it picked. Use the track team's AAR for any `*_qnn.pte`.

**QNN runtime libs** (needed with the AAR for `*_qnn.pte`, and by the runner): copy them from the QAIRT 2.51 bundle into
`app-*/src/main/jniLibs/arm64-v8a/`:

- S25 Ultra (V79): `libQnnHtp.so libQnnHtpV79Stub.so libQnnHtpV79Skel.so libQnnSystem.so libQnnHtpPrepare.so`
- Galaxy XR (V69): `libQnnHtp.so libQnnHtpV69Stub.so libQnnHtpV69Skel.so libQnnSystem.so libQnnHtpPrepare.so`
- Galaxy XR runner fallback: `qnn_executor_runner` renamed to **`libqnn_executor_runner.so`** (the installer only extracts `lib*.so`)

Libraries are extracted (`useLegacyPackaging`), and `ADSP_LIBRARY_PATH` / `LD_LIBRARY_PATH` point at `nativeLibraryDir` before loading.

## Model files (adb push)

The apps read models from app-specific storage. Start each app once so the folder exists, then push:

```bash
# phone: ESM2 ladder, esm2_{8M,35M,150M,650M,3B}_{qnn,xnnpack}.pte
adb push ml/artifacts/esm2_8M_qnn.pte     /sdcard/Android/data/com.bindlab.phone/files/models/
adb push ml/artifacts/esm2_8M_xnnpack.pte /sdcard/Android/data/com.bindlab.phone/files/models/
# ...same for 35M, 150M, 650M, 3B
# 3B split for the HTP context limit: esm2_3B_qnn_shard0.pte, esm2_3B_qnn_shard1.pte, ... (outputs of shard i feed shard i+1)
# binding head weights (optional; cosine similarity is the fallback)
adb push ml/artifacts/binding_head.json   /sdcard/Android/data/com.bindlab.phone/files/models/

# headset: YOLO11n (QNN tried first, then XNNPACK)
adb push ml/artifacts/yolo11n_qnn.pte     /sdcard/Android/data/com.bindlab.xr/files/models/
adb push ml/artifacts/yolo11n_xnnpack.pte /sdcard/Android/data/com.bindlab.xr/files/models/
```

If `adb push` into `Android/data` is refused, use internal storage instead (debug builds only); the apps check both:

```bash
adb push esm2_8M_qnn.pte /data/local/tmp/
adb shell run-as com.bindlab.phone sh -c 'mkdir -p files/models && cp /data/local/tmp/esm2_8M_qnn.pte files/models/'
```

With no models, both apps still run: the ladder and binding rows show **model missing**, and the XR panel shows
`model missing: push yolo11n_*.pte …` with the camera preview running. The XR app picks up a model pushed while it is running (it retries every 3 s).
"Demo steps" plus "Sim pass" / "Sim anomaly" in the XR panel exercise the step card, audio, haptics and `/lab/step` without a model.

## Server address

`shared/src/main/assets/config.json` → `{"server": "http://192.168.x.x:8000"}`. Override it at runtime in **Settings** (saved
in SharedPreferences; **Reset** goes back to the asset). **Test** calls `GET /health`.

## Behaviour notes

- **Tokens:** ESM2 vocab from `ml/common.py`, `<cls> seq <eos>` padded/truncated to 256, int64 `[1,256]` (retries once as int32 if a QNN graph wants int32).
- **Outputs:** embedding = the `[1, D]` output; contacts = a square `[1, 254, 254]` output (≤150M exports).
- **Bench:** load → 10 warm-up → 50 timed (p50/p95) → 2-minute sustained loop (can be switched off on the Ladder screen). Peak memory = max of sampled
  `Debug.getMemoryInfo` total PSS and `/proc/self/status` VmRSS. `thermal_headroom` = `PowerManager.getThermalHeadroom(10)` after the
  loop (`null` if the device returns NaN). `battery_delta` = percent change (negative = drain), from the charge counter when available.
  OOM / load errors become `status: "oom" | "load_failed"`. If the process is killed mid-model (native OOM / LMK), the next launch posts that model as `oom`.
- **Binding:** `binding_head_<size>.json` or `binding_head.json` (single head or `{"heads": {"8M": {...}}}`), format documented in
  `app-phone/.../BindingHead.kt`; no head or a dim mismatch → cosine similarity mapped to 0..1. Interface residues (1-based) come from the
  contact map of `target + 25×G + binder` when the model has a contact output and the pair fits in 254 residues; otherwise empty lists.
- **QR payload:** JSON `{"target": "...", "binder": "..."}`, two-record FASTA (a record named `binder` is the binder), or `TARGET|BINDER`.
- **Lock/key gate:** a step passes when every required label is seen at conf > 0.5 for 8 consecutive frames. A forbidden label at conf > 0.5
  for 3 consecutive frames is an anomaly (3, not 1, to avoid one-frame false positives; `StepGate.forbiddenFrames`).
  The current step follows the server's `state` (`STEP_n` / `step_index`); the app also advances locally if no state arrives within 1.5 s.
- **Detection boxes** (`/lab/step`, WS `detections`): `[x, y, w, h]`, top-left origin, **normalised 0..1** to the camera frame.
- **Haptics:** the XR app sends `{type:"haptic", pattern:"success"}` on a pass. For an anomaly the server already buzzes the phone after
  `POST /lab/step`, so the XR app only sends `pattern:"anomaly"` itself if that POST failed.
- **Galaxy XR:** runs as a 2D panel in home space (no Jetpack XR dependency). The camera selector falls back from back → front → first available camera.
