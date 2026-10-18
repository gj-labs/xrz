"""Run state machine: BINDING -> BIND_OK -> SENT_TO_LAB -> LAB_ENTERED -> STEP_n -> APPROVAL
-> BEAMED -> EVIDENCE -> PATENT_DRAFT. BIND_FAIL is terminal."""
import json
from pathlib import Path

BINDING_THRESHOLD = 0.5

PROTOCOL = json.loads((Path(__file__).parent / "protocol.json").read_text(encoding="utf-8"))

ORDER = ["BINDING", "BIND_OK", "SENT_TO_LAB", "LAB_ENTERED", "STEP", "APPROVAL",
         "BEAMED", "EVIDENCE", "PATENT_DRAFT"]


class TransitionError(ValueError):
    pass


def step_state(index: int) -> str:
    return f"STEP_{index + 1}"


def phase(state: str) -> str:
    return "STEP" if state.startswith("STEP_") else state


def require(state: str, *allowed: str) -> None:
    if phase(state) not in allowed:
        raise TransitionError(f"invalid in state {state}; expected one of {allowed}")


def binding_state(score: float) -> str:
    return "BIND_OK" if score >= BINDING_THRESHOLD else "BIND_FAIL"


def advance_step(step_index: int) -> tuple[str, int]:
    """Called when the current step passes; returns (new_state, new_step_index)."""
    nxt = step_index + 1
    if nxt >= len(PROTOCOL["steps"]):
        return "APPROVAL", nxt
    return step_state(nxt), nxt


def current_step(step_index: int) -> dict | None:
    steps = PROTOCOL["steps"]
    return steps[step_index] if step_index < len(steps) else None
