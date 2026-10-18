"""Sends a successful binding to the lab.

The real destination is still to be decided (email / webhook / LIMS / printed work order).
Set LAB_WEBHOOK_URL to POST the payload. Every handoff is also written to outbox/ so
there is always a printable work order.
"""
import json
import os
from pathlib import Path

import httpx

OUTBOX = Path(__file__).parent / "outbox"


def build_payload(run: dict) -> dict:
    d = run["data"]
    return {
        "run_id": run["id"],
        "target_seq": d["target_seq"],
        "binder_seq": d["binder_seq"],
        "score": d["score"],
        "model": d["model"],
        "backend": d["backend"],
        "target_residues": d.get("target_residues", []),
        "binder_residues": d.get("binder_residues", []),
        "structure": d.get("structure"),
    }


def send(run: dict) -> dict:
    payload = build_payload(run)
    OUTBOX.mkdir(exist_ok=True)
    path = OUTBOX / f"{run['id']}.json"
    path.write_text(json.dumps(payload, indent=2))
    result = {"destination": "outbox", "path": str(path)}

    url = os.environ.get("LAB_WEBHOOK_URL")
    if url:
        try:
            r = httpx.post(url, json=payload, timeout=5)
            result.update(destination="webhook", status_code=r.status_code)
        except httpx.HTTPError as e:
            result.update(webhook_error=str(e))
    return result
