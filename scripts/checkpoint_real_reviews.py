"""Compute source-bound L1 MMR over actual GitHub PR inline review comment snapshot.

The result proves a deterministic commitment to reviewed *snapshot bytes* only.
It does not prove original reviewer signing, model activity, external attestation,
independent scientific truth, or production MMR append-only consistency.
"""
from pathlib import Path
import hashlib
import json
from custody.agent_turn_mmr import construct_turn_checkpoint, reference_verify

def run() -> dict:
    source = Path(__file__).resolve().parents[1] / "evidence" / "github_review_comments_snapshot_v1.json"
    raw_bytes = source.read_bytes()
    snapshot = json.loads(raw_bytes.decode("utf-8"))
    if snapshot.get("schema") != "xrz.github_review_comment_snapshot.v1":
        raise ValueError("snapshot schema mismatch")
    if snapshot.get("repository") != "biobitworks/xrz" or snapshot.get("pr") != 1:
        raise ValueError("snapshot binding mismatch")
    items = snapshot["comments"]
    if len(items) != 8:
        raise ValueError("expected exactly eight separately identified original review comments")
    if items != sorted(items, key=lambda c: (c["created_at"], c["id"])):
        raise ValueError("comment order is not frozen chronologically")
    ids = [c["id"] for c in items]
    if len(set(ids)) != len(ids):
        raise ValueError("duplicate comment identity")
    events = []
    atoms = []
    for i, comment in enumerate(items):
        actor_login = comment["author_login"]
        if actor_login not in {"Copilot", "chatgpt-codex-connector[bot]"}:
            raise ValueError("unexpected reviewer identity")
        if comment["url"] != "https://github.com/biobitworks/xrz/pull/1#discussion_r" + str(comment["id"]):
            raise ValueError("unbound comment URL")
        body_bytes = comment["body"].encode("utf-8")
        body_sha = hashlib.sha256(body_bytes).hexdigest()
        atoms.append({"github_comment_id": comment["id"], "review_actor_login": actor_login,
                      "occurrence_id": "github-pr-comment:" + str(comment["id"]),
                      "body_sha256": body_sha, "body_bytes": len(body_bytes),
                      "source_url": comment["url"]})
        events.append({
            "schema": "xrz.agent_turn.v2",
            "event_kind": "REVIEW_RESULT",
            "event_id": "github-pr-comment:" + str(comment["id"]),
            "actor": "COPILOT" if actor_login == "Copilot" else "OTHER",
            "model_id": actor_login,
            "turn_id": "github-pr-1-reviewed-predecessor-001",
            "repo": "biobitworks/xrz",
            "task_ref": "https://github.com/biobitworks/xrz/pull/1",
            "sequence": i,
            "status": "OBSERVED",
            "input_occurrences": (["repo:biobitworks/xrz@e20e03f5d4a3044b856757b2acd25a509ad09d6f"]
                                  if actor_login == "chatgpt-codex-connector[bot]"
                                  else ["github-pr-1-review-input-commit:UNKNOWN"]),
            "output_occurrences": ["github-pr-comment:" + str(comment["id"])],
            "evidence_refs": ["sha256:" + body_sha, comment["url"]],
            "tool_execution_receipt_ref": comment["url"],
            "observation_context_ref": "evidence/github_review_comments_snapshot_v1.json"
        })
    receipt = construct_turn_checkpoint(events, parent_checkpoint=None)
    if not reference_verify(events, receipt):
        raise AssertionError("independent construction paths mismatch")
    expected_receipt_path = source.parent / "github_agent_review_l1_mmr_receipt_v1.json"
    expected = json.loads(expected_receipt_path.read_text(encoding="utf-8"))
    if hashlib.sha256(raw_bytes).hexdigest() != expected["source_snapshot_sha256"]:
        raise AssertionError("source bytes differ from frozen review receipt")
    if receipt["root"] != expected["mmr"]["root"] or receipt["peaks"] != expected["mmr"]["peaks"]:
        raise AssertionError("L1 replay does not reproduce source-bound frozen peak/root")
    if receipt["ordered_leaf_hashes"] != expected["mmr"]["ordered_leaf_hashes"]:
        raise AssertionError("L1 ordered leaf replay diverged")
    tampered = json.loads(json.dumps(events))
    tampered[0]["evidence_refs"][0] = "sha256:" + "0" * 64
    if reference_verify(tampered, receipt):
        raise AssertionError("negative control accepted altered body digest")
    return {
        "source_file": "evidence/github_review_comments_snapshot_v1.json",
        "source_sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "comment_count": len(items),
        "comment_atoms": atoms,
        "mmr": receipt,
        "verification_scope": "PASS_SINGLE_HOST_DUAL_ALGORITHM",
        "negative_test": "ALTERED_REVIEW_BODY_HASH_REJECTED",
        "signing_status": "NOT_SIGNED",
        "copilot_coding_agent_execution": "NOT_TESTED",
        "source_authenticity": "GITHUB_CONNECTOR_OBSERVATION_NOT_CRYPTOGRAPHICALLY_SIGNED",
        "production_mmr_status": "NOT_COMMITTED",
        "next_action": "Second-host independent reconstruction and signed/custodied release admission"
    }

if __name__ == "__main__":
    print(json.dumps(run(), sort_keys=True, indent=2))
