# BindLab XR

ExecuTorch Mobile/XR hackathon project: on-device protein binding (Galaxy S25 Ultra), shown in 3D/360°, handed to the lab, then XR-guided lab work with YOLO (Galaxy XR), approval, a hash-chained evidence packet, and a patent draft.

Plan: `~/.claude/plans/inherited-moseying-blanket.md` · Track guide: https://github.com/aaishikasb/qc-executorch-guide/tree/main/Mobile-XR

| Dir | What |
|---|---|
| `server/` | FastAPI backend + WebSocket hub, workflow state machine, lab handoff, evidence/patent PDFs |
| `web/` | React web app (binding + 3D viewer, edge benchmark, lab live, approval & evidence) and `xr.html` WebXR viewer |
| `ml/` | ExecuTorch exports (ESM2 ladder, YOLO11n), binding head, contacts |
| `android/` | S25 phone app and Galaxy XR lab app |

## Run

```bash
cd web && npm install && npm run build      # backend serves web/dist
cd ../server && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
```

Open http://localhost:8000. To exercise the full workflow without devices (fake phone, headset and PI clients):

```bash
cd server && .venv/bin/python simulate.py --delay 1.5
```

For live web development, run `npm run dev` in `web/` (port 5173). It talks to the backend on :8000.

## Devices

WebXR only works in a secure context, so the headset must load the page as `localhost`. Tunnel the port over adb (this also works with wireless debugging):

```bash
adb -s "$DEVICE" reverse tcp:8000 tcp:8000
```

Then open `http://localhost:8000/xr.html` in the Galaxy XR browser and tap **Start AR**. Pinch to grab and turn the complex. The Android apps can use the same reverse and `http://localhost:8000`.

## Workflow

`BINDING → BIND_OK → SENT_TO_LAB → LAB_ENTERED → STEP_1..n → APPROVAL → BEAMED → EVIDENCE → PATENT_DRAFT`

- Lab protocol steps and the COCO→lab object map live in `server/protocol.json`. Edit them to match what's on the real lab benches.
- Lab handoff (`server/lab_handoff.py`) writes `server/outbox/<run>.json`, and also POSTs to `LAB_WEBHOOK_URL` if set. **The real lab destination is still TBD.**
- Every event is sha256 hash-chained. `GET /runs/{id}` reports `chain_ok`.
- The demo structure is barnase–barstar (PDB 1BRS, chains A/D) in `web/public/structures/demo_complex.pdb`. Interface residues use PDB numbering.

## API (contract shared with the Android apps)

- `POST /bench` · `POST /binding` → `{run_id, state}` · `POST /lab/handoff/{run}` · `POST /lab/enter/{run}`
- `POST /lab/step {run_id, step_id, status: passed|anomaly, detections, snapshot_b64}`
- `POST /approve/{run}` · `POST /evidence/{run}` · `POST /patent/{run}` · `GET /evidence/{run}.pdf` · `GET /patent/{run}.pdf`
- `WS /ws?client=phone|xr|web`
  - Server → client: `state`, `protocol`, `beam`, `haptic`, `anomaly`
  - Client → server: `detections`, `haptic`
