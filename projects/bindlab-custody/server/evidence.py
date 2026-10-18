"""Evidence packet and patent-draft PDFs built from a run's hash-chained events."""
import base64
import datetime as dt
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import Image, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

import db

SNAPSHOTS = Path(__file__).parent / "snapshots"
REPORTS = Path(__file__).parent / "reports"
STYLES = getSampleStyleSheet()


def save_snapshot(run_id: str, step_id: str, snapshot_b64: str | None) -> str | None:
    if not snapshot_b64:
        return None
    d = SNAPSHOTS / run_id
    d.mkdir(parents=True, exist_ok=True)
    path = d / f"{step_id}.jpg"
    path.write_bytes(base64.b64decode(snapshot_b64.split(",")[-1]))
    return str(path)


def _ts(t: float) -> str:
    return dt.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S")


def _seq(s: str, width: int = 80) -> str:
    return "<br/>".join(s[i:i + width] for i in range(0, len(s), width))


def evidence_pdf(run: dict) -> Path:
    REPORTS.mkdir(exist_ok=True)
    path = REPORTS / f"{run['id']}_evidence.pdf"
    d = run["data"]
    events = db.list_events(run["id"])
    chain_ok = db.verify_chain(run["id"])

    story = [
        Paragraph(f"BindLab XR evidence packet: run {run['id']}", STYLES["Title"]),
        Paragraph(f"Generated {_ts(dt.datetime.now().timestamp())} · state {run['state']} · "
                  f"hash chain {'VERIFIED' if chain_ok else 'BROKEN'}", STYLES["Normal"]),
        Spacer(1, 12),
        Paragraph("Binding result", STYLES["Heading2"]),
        Paragraph(f"Score <b>{d['score']:.3f}</b> using ESM2-{d['model']} on {d['backend']} "
                  f"({d.get('latency_ms', 0):.0f} ms on-device)", STYLES["Normal"]),
        Paragraph(f"<b>Target</b><br/><font face='Courier' size=7>{_seq(d['target_seq'])}</font>",
                  STYLES["Normal"]),
        Paragraph(f"<b>Binder</b><br/><font face='Courier' size=7>{_seq(d['binder_seq'])}</font>",
                  STYLES["Normal"]),
        Paragraph(f"Predicted interface: target {d.get('target_residues', [])}, "
                  f"binder {d.get('binder_residues', [])}", STYLES["Normal"]),
        Spacer(1, 12),
        Paragraph("Event log (sha256 chain)", STYLES["Heading2"]),
    ]
    rows = [["Time", "Event", "Hash"]]
    for e in events:
        rows.append([_ts(e["ts"]), e["kind"], e["hash"][:16] + "…"])
    table = Table(rows, colWidths=[1.6 * inch, 2.4 * inch, 2.4 * inch])
    table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica", 8),
        ("BACKGROUND", (0, 0), (-1, 0), colors.lightgrey),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.grey),
    ]))
    story.append(table)

    snaps = sorted((SNAPSHOTS / run["id"]).glob("*.jpg")) if (SNAPSHOTS / run["id"]).exists() else []
    if snaps:
        story += [Spacer(1, 12), Paragraph("Lab step snapshots (Galaxy XR)", STYLES["Heading2"])]
        for s in snaps:
            story += [Paragraph(s.stem, STYLES["Normal"]), Image(str(s), width=3 * inch, height=2.25 * inch)]

    SimpleDocTemplate(str(path), pagesize=letter).build(story)
    return path


def patent_text(run: dict) -> dict:
    d = run["data"]
    return {
        "title": "System and method for on-device protein binding screening coupled to "
                 "vision-validated laboratory execution",
        "abstract": (
            f"A mobile device runs a protein language model (ESM2-{d['model']}) locally on "
            f"{d['backend'].upper()} hardware to score binding between a target and a candidate "
            f"binder (score {d['score']:.3f}). A successful candidate is handed to a laboratory, "
            "where an extended-reality headset running on-device object detection checks each "
            "protocol step against required and forbidden objects, gives visual, spatial-audio "
            "and haptic feedback, and records hash-chained evidence of execution."
        ),
        "claims": [
            "1. A method comprising: computing, on a handheld device without network access, "
            "embeddings of a target protein and a candidate binder with a transformer protein "
            "language model; scoring binding from said embeddings; and when the score exceeds a "
            "threshold, sending a work order to a laboratory.",
            "2. The method of claim 1, further comprising validating each laboratory protocol "
            "step by detecting required objects in a head-mounted camera stream with an "
            "on-device object detector, and blocking the next step until they are detected.",
            "3. The method of claim 2, wherein a forbidden object detected during a step raises "
            "an anomaly with spatialised audio directed toward the object and haptic feedback.",
            "4. The method of claim 1, wherein every workflow event is appended to a hash chain "
            "forming tamper-evident evidence of the experiment.",
        ],
        "notice": "AUTO-GENERATED DRAFT. Not a patent filing and not legal advice.",
    }


def patent_pdf(run: dict) -> Path:
    REPORTS.mkdir(exist_ok=True)
    path = REPORTS / f"{run['id']}_patent_draft.pdf"
    p = patent_text(run)
    story = [
        Paragraph(p["notice"], STYLES["Italic"]),
        Paragraph(p["title"], STYLES["Title"]),
        Paragraph("Abstract", STYLES["Heading2"]),
        Paragraph(p["abstract"], STYLES["Normal"]),
        Paragraph("Claims", STYLES["Heading2"]),
        *[Paragraph(c, STYLES["Normal"]) for c in p["claims"]],
        Spacer(1, 12),
        Paragraph(f"Supporting evidence: run {run['id']} (see evidence packet).", STYLES["Normal"]),
    ]
    SimpleDocTemplate(str(path), pagesize=letter).build(story)
    return path
