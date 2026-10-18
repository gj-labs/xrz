"""BindLab XR backend: REST + WebSocket hub tying phone, headset and web together.

Run locally:   python -m uvicorn main:app --host 127.0.0.1 --port 8011
LAN demo:      python -m uvicorn main:app --host 0.0.0.0 --port 8000 --ws-max-size 8388608
               (or .\\run.ps1 next to this file; --ws-max-size lets the headset send 6 MB JPEG
               snapshots over the websocket). GET /health reports `lan_hint`, the host's primary
               IPv4, so phones/headsets know which address to point at.

Environment: BINDLAB_DB / BINDLAB_CUSTODY_DB override the SQLite paths (see simulate.py --fresh-db).
"""
import socket
import uuid
import os
import asyncio
import json
from urllib.parse import urlencode
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import db
import custody
import evidence
import lab_handoff
import workflow as wf

ROOT = Path(__file__).parent.parent

app = FastAPI(title="BindLab XR")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------------------------------------------------------------- WebSocket hub

class Hub:
    """Client kinds: phone | xr | web; anything else (e.g. the WebXR page's `xr-viewer`) is
    treated like `web` so it receives state/protocol/custody broadcasts."""

    DEVICE_KINDS = {"phone", "xr"}

    def __init__(self) -> None:
        self.clients: dict[WebSocket, str] = {}

    @classmethod
    def matches(cls, kind: str, to: set[str] | None) -> bool:
        if to is None or kind in to:
            return True
        return kind not in cls.DEVICE_KINDS and "web" in to

    async def send(self, msg: dict, to: set[str] | None = None) -> None:
        for ws, kind in list(self.clients.items()):
            if self.matches(kind, to):
                try:
                    await ws.send_json(msg)
                except Exception:
                    self.clients.pop(ws, None)


hub = Hub()


async def set_state(run_id: str, state: str, **kw) -> dict:
    run = db.update_run(run_id, state=state, **kw)
    db.add_event(run_id, f"state:{state}", {"state": state, "step_index": run["step_index"]})
    await hub.send({"type": "state", "run_id": run_id, "state": state,
                    "step_index": run["step_index"]})
    return run


def load_run(run_id: str) -> dict:
    run = db.get_run(run_id)
    if run is None:
        raise HTTPException(404, f"run {run_id} not found")
    # Recover the legacy UI projection after a crash between journal commit and state broadcast.
    if run["state"].startswith("STEP_"):
        graph = custody.export(run_id)
        count = sum(n.get("event_type") == "Review" and n["payload"]["accepted"]
                    for n in graph["nodes"] if "payload" in n)
        if count > run["step_index"]:
            state = "APPROVAL" if count == len(wf.PROTOCOL["steps"]) else wf.step_state(count)
            run = db.update_run(run_id, state=state, step_index=count)
    return run


def guard(run: dict, *allowed: str) -> None:
    try:
        wf.require(run["state"], *allowed)
    except wf.TransitionError as e:
        raise HTTPException(409, str(e))


def with_structure(run: dict) -> dict:
    """Derive data.structure from data.structure_ref for clients that only read `structure`."""
    data = run.get("data") or {}
    if not data.get("structure") and data.get("structure_ref"):
        run = {**run, "data": {**data, "structure": f"/structures/{data['structure_ref']}.pdb"}}
    return run


def lan_ip() -> str | None:
    """Best-effort primary IPv4 (no packets are sent; UDP connect just picks a route)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return None


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, client: str = "web"):
    await ws.accept()
    hub.clients[ws] = client
    try:
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type")
            if kind == "detections":
                try:
                    run = load_run(msg["run_id"])
                    guard(run, "STEP")
                    step = wf.current_step(run["step_index"])
                    if not step or step["id"] != msg["step_id"]:
                        raise ValueError("out-of-order step")
                    receipt = custody.append(msg["run_id"], wf.PROTOCOL, "Observation", {
                        "step_id": msg["step_id"], "detections": custody.measurements(msg["detections"]),
                        "sender_claim": client, "capture_provenance": "UNVERIFIED"})
                    await hub.send({**msg, "from": client, "custody": receipt}, to={"web"})
                    await ws.send_json({"type": "custody_ack", **receipt})
                except (ValueError, KeyError, TypeError, HTTPException) as exc:
                    await ws.send_json({"type": "custody_rejected", "reason": str(exc)})
            elif kind == "haptic":
                await hub.send(msg, to={"phone", "xr"} - {client})
    except WebSocketDisconnect:
        pass
    finally:
        hub.clients.pop(ws, None)


# ---------------------------------------------------------------- Benchmarks

class Bench(BaseModel):
    device: Literal["s25", "xr"]
    model: str
    backend: Literal["qnn", "xnnpack", "portable-cpu"]
    load_ms: float | None = None
    p50_ms: float | None = None
    p95_ms: float | None = None
    peak_rss_mb: float | None = None
    thermal_headroom: float | None = None
    battery_delta: float | None = None
    sustained_s: float | None = None
    status: Literal["ok", "oom", "load_failed"] = "ok"
    error: str | None = None


@app.post("/bench")
async def post_bench(b: Bench):
    db.add_bench(b.model_dump())
    await hub.send({"type": "bench", **b.model_dump()}, to={"web"})
    return {"ok": True}


@app.get("/bench")
async def get_bench():
    return db.list_bench()


# ---------------------------------------------------------------- Binding + lab handoff

class Binding(BaseModel):
    target_seq: str
    binder_seq: str
    model: str
    backend: Literal["qnn", "xnnpack", "portable-cpu"]
    score: float
    target_residues: list[int] = Field(default_factory=list)
    binder_residues: list[int] = Field(default_factory=list)
    latency_ms: float | None = None
    structure: str | None = None  # e.g. "/structures/demo_complex.pdb"
    structure_ref: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,64}$")  # e.g. "1brs"


@app.post("/binding")
async def post_binding(b: Binding):
    run_id = uuid.uuid4().hex[:8]
    db.create_run(run_id, "BINDING", b.model_dump())
    db.add_event(run_id, "binding", b.model_dump())
    run = await set_state(run_id, wf.binding_state(b.score))
    return {"run_id": run_id, "state": run["state"]}


@app.post("/lab/handoff/{run_id}")
async def post_handoff(run_id: str):
    run = load_run(run_id)
    guard(run, "BIND_OK")
    result = lab_handoff.send(run)
    db.add_event(run_id, "lab_handoff", result)
    await set_state(run_id, "SENT_TO_LAB", data_patch={"handoff": result})
    return result


@app.post("/runs/{run_id}/open-xr")
async def open_xr(run_id: str):
    run = with_structure(load_run(run_id))
    if not run["data"].get("structure"):
        raise HTTPException(409, "This run has no structure to display")
    # Explicit opt-in for this USB-connected development rig. Resolve only its
    # registered XR target; never choose the first ADB device or the phone.
    if os.environ.get("BINDLAB_XR_USB") == "1":
        workspace = ROOT.parent.parent
        try:
            serial = json.loads((workspace / "config/devices.local.json").read_text())["xr"]["serial"]
            adb = str(workspace / "tools/android-sdk/platform-tools/adb.exe")

            async def command(*args):
                process = await asyncio.create_subprocess_exec(
                    adb, "-s", serial, *args,
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                try:
                    out, err = await asyncio.wait_for(process.communicate(), timeout=15)
                except asyncio.TimeoutError:
                    process.kill()
                    await process.communicate()
                    raise RuntimeError("Headset command timed out")
                output = (out + err).decode(errors="replace")
                if process.returncode or "Error:" in output:
                    raise RuntimeError(output.strip())
                return output

            if (await command("shell", "getprop", "ro.soc.model")).strip() != "SXR2230P":
                raise RuntimeError("Registered target is not the Galaxy XR")
            for port in (8000, 8080):
                await command("reverse", f"tcp:{port}", f"tcp:{port}")
            url = "http://localhost:8080/viewer/?" + urlencode({"run": run_id, "hub": "http://localhost:8000"})
            output = await command("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                                  "-d", "'" + url + "'", "-p", "com.android.chrome")
            if "Status: ok" not in output:
                raise RuntimeError("Chrome did not confirm the launch")
            return {"run_id": run_id, "state": run["state"], "sent": True, "launched": True}
        except Exception as exc:
            raise HTTPException(503, f"USB headset launch failed: {exc}") from exc
    if "xr" not in hub.clients.values():
        raise HTTPException(503, "Headset receiver is disconnected. Open BindLab on the headset and check its USB connection.")
    await hub.send({"type": "open_viewer", "run_id": run_id}, to={"xr"})
    return {"run_id": run_id, "state": run["state"], "sent": True}


@app.post("/lab/enter/{run_id}")
async def post_enter(run_id: str):
    run = load_run(run_id)
    guard(run, "SENT_TO_LAB")
    await set_state(run_id, "LAB_ENTERED")
    await hub.send({"type": "protocol", "run_id": run_id, "name": wf.PROTOCOL["name"],
                    "steps": wf.PROTOCOL["steps"], "coco_to_lab": wf.PROTOCOL["coco_to_lab"],
                    "disclaimer": wf.PROTOCOL.get("disclaimer")})
    run = await set_state(run_id, wf.step_state(0), step_index=0)
    return {"state": run["state"], "steps": wf.PROTOCOL["steps"]}


class Detection(BaseModel):
    label: str
    conf: float
    box: list[float]


class Step(BaseModel):
    run_id: str
    step_id: str
    status: Literal["passed", "anomaly"]
    detections: list[Detection] = Field(default_factory=list)
    snapshot_b64: str | None = None
    client_event_id: str | None = Field(default=None, max_length=128)


@app.post("/lab/step")
async def post_step(s: Step):
    if s.client_event_id:
        prev = db.get_idempotent(s.run_id, s.client_event_id)
        if prev is not None:
            return {**prev, "duplicate": True}
    run = load_run(s.run_id)
    guard(run, "STEP")
    step = wf.current_step(run["step_index"])
    if step is None or step["id"] != s.step_id:
        raise HTTPException(409, f"current step is {step and step['id']}, got {s.step_id}")
    try:
        receipt = custody.append(s.run_id, wf.PROTOCOL, "StepReport", {
            "step_id": s.step_id, "client_claim": s.status,
            "detections": custody.measurements([d.model_dump() for d in s.detections]),
            **({"client_event_id": s.client_event_id} if s.client_event_id else {})},
            custody.decode_photo(s.snapshot_b64))
    except (ValueError, TypeError) as exc:
        raise HTTPException(422, str(exc))
    await hub.send({"type": "custody", "run_id": s.run_id, **receipt}, to={"web", "xr"})
    result = {"state": run["state"], "decision": "HUMAN_REVIEW_REQUIRED", **receipt}
    if s.client_event_id:
        db.put_idempotent(s.run_id, s.client_event_id, "/lab/step", result)
    return result


class Pose(BaseModel):
    position: list[float] = Field(min_length=3, max_length=3)
    quaternion: list[float] = Field(min_length=4, max_length=4)


class Commit(BaseModel):
    run_id: str
    pose: Pose
    geometry_score: float
    client_event_id: str | None = Field(default=None, max_length=128)


@app.post("/commit")
async def post_commit(c: Commit):
    """Record a docked pose from the XR viewer. Does not change run state."""
    if c.client_event_id:
        prev = db.get_idempotent(c.run_id, c.client_event_id)
        if prev is not None:
            return {**prev, "duplicate": True}
    run = load_run(c.run_id)
    guard(run, "BIND_OK", "BIND_FAIL", "SENT_TO_LAB")
    payload = {"pose": c.pose.model_dump(), "geometry_score": c.geometry_score}
    if c.client_event_id:
        payload["client_event_id"] = c.client_event_id
    ev = db.add_event(c.run_id, "commit", payload)
    await hub.send({"type": "commit", "run_id": c.run_id, "geometry_score": c.geometry_score,
                    "pose": payload["pose"]})
    result = {"ok": True, "run_id": c.run_id, "state": run["state"], "hash": ev["hash"],
              "prev_hash": ev["prev_hash"], "ts": ev["ts"]}
    if c.client_event_id:
        db.put_idempotent(c.run_id, c.client_event_id, "/commit", result)
    return result


class StepReview(BaseModel):
    step_id: str
    reviewer: str = Field(min_length=1, max_length=100)
    note: str = Field(min_length=1, max_length=2000)
    accepted: bool


@app.post("/lab/review/{run_id}")
async def review_step(run_id: str, review: StepReview):
    run = load_run(run_id)
    guard(run, "STEP")
    step = wf.current_step(run["step_index"])
    if not step or step["id"] != review.step_id:
        raise HTTPException(409, "out-of-order review")
    try:
        receipt = custody.append(run_id, wf.PROTOCOL, "Review", review.model_dump())
    except ValueError as exc:
        raise HTTPException(409, str(exc))
    if review.accepted:
        state, idx = wf.advance_step(run["step_index"])
        run = await set_state(run_id, state, step_index=idx)
    return {"state": run["state"], **receipt}


@app.get("/runs/{run_id}/fcg")
async def get_fcg(run_id: str):
    load_run(run_id)
    try:
        return custody.export(run_id)
    except ValueError as exc:
        raise HTTPException(409, str(exc))


# ---------------------------------------------------------------- Approval, evidence, patent

class Approval(BaseModel):
    approver: str = "PI"
    approved: bool = True
    note: str | None = None


@app.post("/approve/{run_id}")
async def post_approve(run_id: str, a: Approval):
    run = load_run(run_id)
    guard(run, "APPROVAL")
    db.add_event(run_id, "approval", a.model_dump())
    if not a.approved:
        return {"state": run["state"]}
    run = await set_state(run_id, "BEAMED", data_patch={"approval": a.model_dump()})
    # "Beam" the approved payload to the headset.
    await hub.send({"type": "beam", "run_id": run_id, "approval": a.model_dump(),
                    "score": run["data"]["score"]}, to={"xr"})
    return {"state": run["state"]}


@app.post("/evidence/{run_id}")
async def post_evidence(run_id: str):
    run = load_run(run_id)
    guard(run, "BEAMED", "EVIDENCE", "PATENT_DRAFT")
    if wf.phase(run["state"]) == "BEAMED":
        run = await set_state(run_id, "EVIDENCE")
    evidence.evidence_pdf(run)
    return {"pdf": f"/evidence/{run_id}.pdf", "chain_ok": db.verify_chain(run_id)}


@app.get("/evidence/{run_id}.pdf")
async def get_evidence_pdf(run_id: str):
    path = evidence.REPORTS / f"{run_id}_evidence.pdf"
    if not path.exists():
        raise HTTPException(404, "evidence not generated yet")
    return FileResponse(path, media_type="application/pdf")


@app.post("/patent/{run_id}")
async def post_patent(run_id: str):
    run = load_run(run_id)
    guard(run, "EVIDENCE", "PATENT_DRAFT")
    if run["state"] == "EVIDENCE":
        run = await set_state(run_id, "PATENT_DRAFT")
    evidence.patent_pdf(run)
    return {"pdf": f"/patent/{run_id}.pdf", **evidence.patent_text(run)}


@app.get("/patent/{run_id}.pdf")
async def get_patent_pdf(run_id: str):
    path = evidence.REPORTS / f"{run_id}_patent_draft.pdf"
    if not path.exists():
        raise HTTPException(404, "patent draft not generated yet")
    return FileResponse(path, media_type="application/pdf")


# ---------------------------------------------------------------- Read APIs + static

@app.get("/runs")
async def get_runs():
    return [with_structure(r) for r in db.list_runs()]


@app.get("/runs/{run_id}")
async def get_run(run_id: str):
    run = with_structure(load_run(run_id))
    return {**run, "events": db.list_events(run_id), "chain_ok": db.verify_chain(run_id),
            "protocol": wf.PROTOCOL}


@app.get("/protocol")
async def get_protocol():
    return wf.PROTOCOL


@app.get("/health")
async def health():
    return {"ok": True, "clients": list(hub.clients.values()), "lan_hint": lan_ip()}


# Built web app (incl. /structures/*.pdb and /xr.html); `npm run build` in web/ first.
WEB_DIST = ROOT / "web" / "dist"
if WEB_DIST.exists():
    app.mount("/", StaticFiles(directory=WEB_DIST, html=True), name="web")
