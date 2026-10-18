"""SQLite storage: runs, bench results, and a hash-chained event log."""
import hashlib
import json
import os
import sqlite3
import time
from pathlib import Path

# BINDLAB_DB overrides the location (used by simulate.py --fresh-db and tests).
DB_PATH = Path(os.environ.get("BINDLAB_DB") or Path(__file__).parent / "db.sqlite")

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
  id TEXT PRIMARY KEY,
  created REAL,
  state TEXT,
  step_index INTEGER DEFAULT 0,
  data TEXT
);
CREATE TABLE IF NOT EXISTS bench (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created REAL,
  device TEXT,
  model TEXT,
  backend TEXT,
  data TEXT
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT,
  ts REAL,
  kind TEXT,
  payload TEXT,
  prev_hash TEXT,
  hash TEXT
);
CREATE TABLE IF NOT EXISTS idempotency (
  run_id TEXT,
  client_event_id TEXT,
  endpoint TEXT,
  created REAL,
  result TEXT,
  PRIMARY KEY (run_id, client_event_id)
);
"""


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    return conn


_conn = connect()


def create_run(run_id: str, state: str, data: dict) -> None:
    _conn.execute(
        "INSERT INTO runs (id, created, state, data) VALUES (?, ?, ?, ?)",
        (run_id, time.time(), state, json.dumps(data)),
    )
    _conn.commit()


def get_run(run_id: str) -> dict | None:
    row = _conn.execute("SELECT * FROM runs WHERE id = ?", (run_id,)).fetchone()
    if not row:
        return None
    return {**dict(row), "data": json.loads(row["data"])}


def list_runs() -> list[dict]:
    rows = _conn.execute("SELECT * FROM runs ORDER BY created DESC").fetchall()
    return [{**dict(r), "data": json.loads(r["data"])} for r in rows]


def update_run(run_id: str, *, state: str | None = None, step_index: int | None = None,
               data_patch: dict | None = None) -> dict:
    run = get_run(run_id)
    if run is None:
        raise KeyError(run_id)
    if state is not None:
        run["state"] = state
    if step_index is not None:
        run["step_index"] = step_index
    if data_patch:
        run["data"].update(data_patch)
    _conn.execute(
        "UPDATE runs SET state = ?, step_index = ?, data = ? WHERE id = ?",
        (run["state"], run["step_index"], json.dumps(run["data"]), run_id),
    )
    _conn.commit()
    return run


def add_bench(row: dict) -> None:
    _conn.execute(
        "INSERT INTO bench (created, device, model, backend, data) VALUES (?, ?, ?, ?, ?)",
        (time.time(), row["device"], row["model"], row["backend"], json.dumps(row)),
    )
    _conn.commit()


def list_bench() -> list[dict]:
    rows = _conn.execute("SELECT * FROM bench ORDER BY created").fetchall()
    return [{"id": r["id"], "created": r["created"], **json.loads(r["data"])} for r in rows]


def _event_hash(prev_hash: str, run_id: str, ts: float, kind: str, payload: str) -> str:
    return hashlib.sha256(f"{prev_hash}|{run_id}|{ts:.6f}|{kind}|{payload}".encode()).hexdigest()


def add_event(run_id: str, kind: str, payload: dict) -> dict:
    """Append an event to the run's hash chain (each hash covers the previous one)."""
    last = _conn.execute(
        "SELECT hash FROM events WHERE run_id = ? ORDER BY id DESC LIMIT 1", (run_id,)
    ).fetchone()
    prev_hash = last["hash"] if last else "GENESIS"
    ts = time.time()
    body = json.dumps(payload, sort_keys=True)
    h = _event_hash(prev_hash, run_id, ts, kind, body)
    _conn.execute(
        "INSERT INTO events (run_id, ts, kind, payload, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?)",
        (run_id, ts, kind, body, prev_hash, h),
    )
    _conn.commit()
    return {"run_id": run_id, "ts": ts, "kind": kind, "payload": payload,
            "prev_hash": prev_hash, "hash": h}


def list_events(run_id: str) -> list[dict]:
    rows = _conn.execute(
        "SELECT * FROM events WHERE run_id = ? ORDER BY id", (run_id,)
    ).fetchall()
    return [{**dict(r), "payload": json.loads(r["payload"])} for r in rows]


def get_idempotent(run_id: str, client_event_id: str) -> dict | None:
    """Previously returned result for (run_id, client_event_id), or None."""
    row = _conn.execute(
        "SELECT result FROM idempotency WHERE run_id = ? AND client_event_id = ?",
        (run_id, client_event_id),
    ).fetchone()
    return json.loads(row["result"]) if row else None


def put_idempotent(run_id: str, client_event_id: str, endpoint: str, result: dict) -> None:
    _conn.execute(
        "INSERT OR IGNORE INTO idempotency (run_id, client_event_id, endpoint, created, result) "
        "VALUES (?, ?, ?, ?, ?)",
        (run_id, client_event_id, endpoint, time.time(), json.dumps(result)),
    )
    _conn.commit()


def verify_chain(run_id: str) -> bool:
    prev = "GENESIS"
    for e in list_events(run_id):
        body = json.dumps(e["payload"], sort_keys=True)
        if e["prev_hash"] != prev or e["hash"] != _event_hash(prev, run_id, e["ts"], e["kind"], body):
            return False
        prev = e["hash"]
    return True
