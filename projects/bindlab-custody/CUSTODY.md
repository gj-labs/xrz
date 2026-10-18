# BindLab streamed evidence implementation

Isolated source snapshot of `gj-labs/BindLabXR-ExecuTorch-hackathon` commit
`f0d3da480695725d7b90c7ed274abe3a3c4984b7`. The original investigation and device apps are untouched.
Contract reference: `biobitworks/xrz`, commit `d11fcf020b02c6355c1c6144eb265bf1f3000689`,
`contracts/FCO_FCG_v1.json` and `docs/COPILOT_CHAIN_OF_CUSTODY.md`.

## Operation

Use a project-local Python environment, install `server/requirements.txt`, then from `server/`:

```powershell
python -m uvicorn main:app --host 127.0.0.1 --port 8011 --ws-max-size 8388608
```

Use a separate port from the existing investigation. This prototype is local-only and has no authentication;
do not bind it to LAN/public interfaces. Human reviewer names are self-asserted. No deployment or installation
on connected devices is part of this change. The copied Android Gradle wrapper and pinned Android dependencies
belong to this project; runtime/model assets must be obtained and validated separately.

`/ws` detection messages retain the existing wire format (`run_id`, `step_id`, `detections`).
Each accepted observation and derived assessment is journaled before broadcast. Malformed and wrong-step
messages are rejected. These are receiver occurrences, not exactly-once source events: retransmission creates
a new occurrence. There is no device sequence reconciliation or capture-time attestation.

`POST /lab/step` requires a photo and records the client verdict as a claim. It never advances the protocol.
`POST /lab/review/{run_id}` with `step_id`, `reviewer`, `note`, and `accepted` records a local human decision;
an accepted decision advances one step. A photo-backed report is required. Review may accept a suspected
deviation, so its note must explain that decision. This is not safety authorization or scientific validation.
The custody journal is authoritative for accepted reviews; the old UI run index recovers from it after a crash.
`GET /runs/{run_id}/fcg` exports typed Experiment, Run, ProtocolStep, observation, claim, review nodes and
declared custody edges, plus every checkpoint. Run IDs currently define one experiment each.

## Integrity and variance

`xrz-json-v1`: UTF-8, NFC strings, sorted compact JSON, safe integers, no floats; not JCS.
Detection numbers are preserved as Python-decoded decimal strings, with normalized cx/cy/w/h units.
Original wire byte spelling is not preserved. Photo bytes are stored in the same SQLite transaction as their
SHA-256 and length. JPEG markers are checked; this is not a full image decoder or proof the photo matches
the detections. Raw photo content is not served by the graph endpoint.

Leaves/nodes/roots use the reference XRZ/MMR/v1 domain separators and big-endian lengths/heights.
Each checkpoint covers the full ordered run prefix and binds the preceding checkpoint root. Peaks are
left-to-right binary mountains; the root includes parent tag, leaf count, peak count and height/hash pairs.
Recursive subtree reconstruction checks the iterative accumulator on the same host. Empty journals have no
committed checkpoint. Export and append reverify predecessor history and photo bytes. SQLite atomically
commits each observation/report, assessment and checkpoints; failed transactions roll back.
There are no compact inclusion proofs yet: full-prefix replay is the verification format. Full-history
verification is deliberately simple and expensive; this prototype is suitable for short demo runs only.

The last eight received frames yield presence counts and population variance for all four box coordinates,
using the highest-confidence instance per class. All eight required-class observations, center variance
at most 0.01, gaps at most 1500ms and age at most 3000ms support an observation candidate. Three consecutive
forbidden-class frames yield SUSPECTED_DEVIATION. Missing/unstable observations yield HOLD.
Thresholds are provisional engineering policy, hashed with each assessment. They are not calibrated lab
error rates; camera movement, instance switching, replay and spoofing can confound them. Detector classes
are demo proxies; a cup does not establish a beaker or a performed procedure.

The protocol snapshot/hash is frozen at first receipt. Changes require a new run. Identical payloads share
content IDs but retain distinct occurrence IDs. Model/source provenance remains UNKNOWN. Root verification
shows local consistency only: all checkpoints remain NOT_SIGNED. No external FCG publication, independent
host attestation, hardware execution, NPU performance, or proven physical protocol error is claimed.
An attacker able to rewrite the whole local journal can forge a consistent history. Detecting valid-prefix
truncation needs a previously retained trusted head. Legacy binding/benchmark/PDF logs are not included in
this custody stream and their old hash-chain labels must not be interpreted as MMR receipts.

## Focused verification

From `server/`, run `python verify_custody.py`. Synthetic fixtures check temporal findings, replay,
reordering, photo mutation, non-finite rejection and the valid-prefix truncation limitation. Generated
`canonical-vectors.json` pins a small encoding vector for a future Kotlin/independent-host verifier.
Android builds and physical-device verification remain unperformed.
