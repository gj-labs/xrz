# Copy/paste team setup prompts

**Shared instruction for every team member:** Clone `https://github.com/biobitworks/xrz.git`; read `README_HANDOFF.md`, `docs/MVP_ARCHITECTURE.md`, `contracts/FCO_FCG_v2.json`, `docs/REUSE_RIGHTS.md` and `docs/TEST_MATRIX.md`. Create `feature/<role>-<task>` from reviewed main or handoff branch as appropriate. Pin inputs/versions, preserve original bytes and rights, avoid private event data, implement independent unit tests + a rerunnable Jupyter notebook, open a PR with observed/failed/NOT_TESTED items and NEXT_ACTION. Only a validated verifier receipt supports claiming an MMR root; no SIGNED without actual verified signing.

## Zack — XR, molecular visualization, sonification and haptics
You own Samsung XR interfaces and HDMI/stage projection. Consume the canonical atom/coordinate/protocol/claim contracts (do not alter content IDs for display). Show 3HS4 vs 4IWZ ligand and protein coordinates with measured Å geometry, uncertainty, source DOI and source input hash. Map deterministic measurement thresholds and protocol HOLD states to accessible sound and haptic patterns; version each mapping. Test actual XR tracking, audio/haptic feedback and spectator monitor output; record end-to-end latency and any SDK/hardware gaps, including hand/voice privacy. No model-generated hallucinated structure is treated as source. Deliver XR adapter, unit tests, notebook, receipt, and role PR. Your GitHub username/ownership must be verified before assignment.

## Mobile/ExecuTorch — Samsung S25 Ultra / S Pen
You own Android app boot, model export compatibility, on-device offline inference, local encryption, typed model and user action receipts. First validate a deterministic `.pte` canary, then separately evaluate LFM2.5 and Meta ESM-2 feasibility on the S25; document if model requires Ollama, llama.cpp, ONNX or host instead. Capture weights SHA, tokenizer SHA, runtime/backend, Android ABI, perf, battery, model output hash and repetitions. Convert S Pen selection to an exact atom occurrence reference; do not synthesize biological evidence. Mock protocol requires independent human confirmation for PPE and permission. Deliver Android code, security tests, Jupyter experiment and PR. No test PASS until attached device trace.

## Protein / therapeutics science
Import RCSB 3HS4/4IWZ **actual original PDB coordinate bytes** with SHA256, DOI, paper reference and license decision. Preserve atom name, alternate location, occupancy, chain, residue index, ligand identity, units and source occurrence. Define deterministic geometry oracle and controlled comparison; treat ESM2 as embeddings and any folding/docking as independent hypotheses with frozen benchmark and uncertainty. No HET/PRNP and no prohibited cross-event dataset reuse. Deliver source manifest, atom transformations, negative controls, notebook and PR.

## Lab protocol / biosafety reviewer
Design versioned, nonhazardous mock workflow: authorization → PPE human check → sample identity → instrument readiness → measurement → deviation → review → closeout. Every step outputs a decision and an ordered append-only event. Camera recognition only suggests items (including false-positive cases); block skipped steps and require competent human/site review for actual lab use. Define voice and hands-free error states, device decontamination/consent policy, unexpected interruption and recovery. No autonomous BSL-2 clearance, no simulated cost-savings as observed. Deliver protocol DSL/schema, tests, notebook, PR.

## Custody/FCG verifier
Own content-vs-occurrence identities, declared FCG edges, deterministic cross-runtime serialization and transformations, event ordering, duplicate reception and device time drift. Implement MMR domain-separated leaves/peaks/root with an independent verifier, first divergence localization, rollback rejection and successor correction. Preserve signature_state NOT_SIGNED unless actual signed+verified evidence; never confuse SHA with provenance/attribution/truth. Deliver schema, reference vectors, mutation/reorder tests, notebook and PR.

## Orchestrator / release reviewer
Maintain one issue and PR per owner, least-privilege access, source/license admission, reviewer approvals and milestone gates. Link every artifact to inputs, outputs, model/license and hardware receipts. Reconcile previous work by explicit SHA/branch, not chat narrative. Prohibit public disclosure of confidential SPC/AI drug discovery/VCC datasets or private media. Track PROPOSED/IMPLEMENTED/EXECUTED/OBSERVED/FAILED/NOT_TESTED and NEXT_ACTION, and produce a judge-safe claim ceiling.

## Project separation

XRZ team tasks are device/protein/visualization/mock-SOP/custody tasks **only**. Antigence Clean Room is a separate private project with existing FCO/FCG predecessor and successor evidence. Do not open Antigence clean-room work as XRZ tasks or merge the withdrawn XRZ PR #2. Observe `governance/PROJECT_BOUNDARY_XRZ.md` for explicit review gates.
