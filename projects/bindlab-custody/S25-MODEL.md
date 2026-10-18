# Verified S25 embedding model

Open **BindLab Custody**, select **Binding**, leave **8M / portable-cpu** selected,
and tap **Embed + score**. The Scan screen accepts sequences; each sequence must
contain at most 254 residues. The model executes locally without the server;
posting results needs the isolated backend at localhost:8011 and the S25 ADB reverse.

This parallel installation uses `com.bindlab.phone.custody`. The original
`com.bindlab.phone`, upstream source archive, and XR browser viewer are preserved.

## Diagnosis and compatibility

Both original phone model directories were empty. Available storage was not the
observed blocker; no original failing model-load call or OOM was demonstrated.
The existing S25 masked-logits export uses a different input/output contract and
cannot substitute for sequence embeddings. XR V69 QNN artifacts are incompatible
with the S25 V79 target and are not used here.

`ml/export_phone_embedding.py` exports local ESM2 8M weights pinned at revision
`c731040fcd8d73dceaa04b0a8e6329b345b0f5df`. The export and Android runtime use
ExecuTorch 1.5.1. Input is int64 `[1,256]`; output is float32 `[1,320]`, mean pooled
over residue tokens, excluding CLS/EOS/PAD. This is portable CPU execution.
The model and its hash/reference manifest are kept under the ignored workspace
directory `models/esm2-8m/s25/bindlab-portable`.

The Android runtime adapter was updated for the 1.5.1 `loadMethod` API. The
embedding adapter checks the exact output shape and rejects overlength sequences.
An interrupted previous run is no longer automatically reported as OOM.

## Actual device verification

On the registered SM8750 S25, the instrumentation test passed model SHA-256,
tokenizer equality, 320 finite outputs, elementwise reference tolerance, the
demo pair, and the actual PhoneViewModel binding/submission flow (HTTP 200).

Observed single-run measurements:

| Measurement | Result |
|---|---:|
| Model/method load | 35 ms |
| Synthetic fixture inference | 2,221 ms |
| Demo pair inference | 4,428 ms |
| Maximum absolute reference error | 8.79e-7 |

These are functional smoke-test timings, not sustained performance benchmarks.
Device identifiers and full output stay in ignored `logs/` files.

No binding head was staged. The score is cosine embedding similarity, explicitly
labelled in the app. The legacy backend's `BIND_OK` workflow state is not evidence
of physical binding. This export does not predict contacts or structures, and
there is no NPU or assay-validation claim.

## Rebuild and verify

Use PowerShell 7, load the workspace `scripts/Enter-Dev.ps1`, and run the project's
own Android wrapper with `:app-phone:assembleDebug :app-phone:assembleDebugAndroidTest`.
Start `python -B -m uvicorn main:app --host 127.0.0.1 --port 8011` from this project's
`server/` directory using an environment with its pinned requirements installed.
Then run `pwsh -NoProfile -File projects/bindlab-custody/Verify-S25.ps1` from the
workspace. The script validates the registered S25 SoC and model hash, installs
the isolated app/test APKs, stages the model, configures only reverse port 8011,
and executes the test. It does not modify port 8000 or the original BindLab route.

The current backend process ID and output are stored in ignored
`logs/bindlab-custody-server.local.pid` and related local log files. Reconnect the
S25 and restore the reverse mapping if ADB disconnects.
