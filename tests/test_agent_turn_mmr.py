import unittest
from custody.agent_turn_mmr import canonical_bytes, construct_turn_checkpoint, reference_verify

def fixture(n=5):
    return [{"schema": "xrz.agent_turn.v2", "event_kind": "OBSERVATION", "event_id": "fixture-" + str(i),
             "actor": "SYNTHETIC_COPILOT", "turn_id": "fixture-turn", "repo": "biobitworks/xrz",
             "task_ref": "local-synthetic-test", "sequence": i, "status": "SYNTHETIC",
             "input_occurrences": [], "output_occurrences": [], "evidence_refs": []}
            for i in range(n)]

class TestAgentTurnMMR(unittest.TestCase):
    def test_repeatable(self):
        e = fixture()
        self.assertEqual(construct_turn_checkpoint(e), construct_turn_checkpoint(e))

    def test_reference_oracle(self):
        for n in range(1, 23):
            e = fixture(n)
            self.assertTrue(reference_verify(e, construct_turn_checkpoint(e)), n)

    def test_mutation(self):
        e = fixture()
        receipt = construct_turn_checkpoint(e)
        e[1]["status"] = "TAMPERED"
        self.assertFalse(reference_verify(e, receipt))

    def test_reordering(self):
        e = fixture()
        receipt = construct_turn_checkpoint(e)
        e[1], e[2] = e[2], e[1]
        self.assertFalse(reference_verify(e, receipt))

    def test_truncation(self):
        e = fixture()
        self.assertFalse(reference_verify(e[:-1], construct_turn_checkpoint(e)))

    def test_appending(self):
        e = fixture()
        self.assertFalse(reference_verify(e + fixture(1), construct_turn_checkpoint(e)))

    def test_duplicate_event_rejected(self):
        e = fixture()
        e[1]["event_id"] = e[0]["event_id"]
        with self.assertRaises(ValueError):
            construct_turn_checkpoint(e)

    def test_unicode_normalization_rejected(self):
        with self.assertRaises(ValueError):
            canonical_bytes({"x": "e\u0301"})

    def test_float_rejected(self):
        with self.assertRaises(TypeError):
            canonical_bytes({"latency": 0.0001})

    def test_frozen_parent(self):
        e = fixture()
        a = construct_turn_checkpoint(e, parent_checkpoint="ab"*32)
        b = construct_turn_checkpoint(e, parent_checkpoint="cd"*32)
        self.assertNotEqual(a["root"], b["root"])
        self.assertTrue(reference_verify(e, a))
        self.assertTrue(reference_verify(e, b))
        self.assertFalse(reference_verify(e, dict(a, parent_checkpoint="cd"*32)))

    def test_empty_rejected(self):
        with self.assertRaises(ValueError):
            construct_turn_checkpoint([])

    def test_signature_not_claimed(self):
        self.assertEqual(construct_turn_checkpoint(fixture())["signature_state"], "NOT_SIGNED")

    def test_root_tamper(self):
        e = fixture()
        r = construct_turn_checkpoint(e)
        r["root"] = "0"*64
        self.assertFalse(reference_verify(e, r))

    def test_known_synthetic_copilot_vector(self):
        e = []
        kinds = ["INSTRUCTION_READ", "CODE_EDIT", "TEST_EXECUTION"]
        labels = ["synthetic_instructions_read", "synthetic_code_proposal", "synthetic_test_failure"]
        for i, (kind, label) in enumerate(zip(kinds, labels)):
            e.append({"schema": "xrz.agent_turn.v2", "event_kind": kind,
                "event_id": "SYNTHETIC-V2-" + str(i),
                "actor": "SYNTHETIC_COPILOT", "turn_id": "synthetic-turn-002",
                "repo": "biobitworks/xrz", "task_ref": "synthetic-local-successor-fixture",
                "sequence": i, "status": "SYNTHETIC",
                "input_occurrences": ["source-occurrence-0"],
                "output_occurrences": ["fixture-v2-occurrence-" + str(i)],
                "evidence_refs": ["synthetic:" + label]})
        parent = "0fc4c6332e1c1831036193a6d66b235e7b2fc5893908dd49c880a32fb234166c"
        r = construct_turn_checkpoint(e, parent_checkpoint=parent)
        self.assertEqual(r["root"], "d459eb5b1c36a67b8637a4897e6201ae5e8dff7ccd47e03434a792b1968e0049")
        self.assertEqual([p["height"] for p in r["peaks"]], [1, 0])
        self.assertTrue(reference_verify(e, r))

    def test_event_kind_required(self):
        e = fixture()
        del e[0]["event_kind"]
        with self.assertRaises(ValueError):
            construct_turn_checkpoint(e)

    def test_event_kind_enum_invalid(self):
        e = fixture()
        e[0]["event_kind"] = "MADE_UP_ACTION"
        with self.assertRaises(ValueError):
            construct_turn_checkpoint(e)



    def test_receipt_bool_count_rejected(self):
        events = fixture(1)
        r = construct_turn_checkpoint(events)
        self.assertFalse(reference_verify(events, dict(r, event_count=True)))

    def test_receipt_bool_peak_height_rejected(self):
        for n in (1, 2):
            events = fixture(n)
            r = construct_turn_checkpoint(events)
            bad = dict(r, peaks=[dict(p) for p in r["peaks"]])
            bad["peaks"][0]["height"] = bool(n - 1)
            self.assertFalse(reference_verify(events, bad))

    def test_unsupported_receipt_status_rejected(self):
        events = fixture()
        r = construct_turn_checkpoint(events)
        for field, value in (("signature_state", "SIGNED_VERIFIED"),
                             ("state", "MMR_COMMITTED"),
                             ("verification", "PASS")):
            self.assertFalse(reference_verify(events, dict(r, **{field:value})), field)

    def test_invalid_agent_schema_on_constructor_and_verifier(self):
        for field, value in (("actor", None), ("status", "INVALID"),
                             ("input_occurrences", "not-a-list"),
                             ("sequence", False),
                             ("event_kind", "INVALID")):
            events = fixture(1)
            receipt = construct_turn_checkpoint(events)
            events[0][field] = value
            with self.assertRaises(ValueError):
                construct_turn_checkpoint(events)
            self.assertFalse(reference_verify(events, receipt), field)
        for mode in ("missing", "extra"):
            events = fixture(1)
            receipt = construct_turn_checkpoint(events)
            if mode == "missing":
                del events[0]["event_id"]
            else:
                events[0]["unknown_field"] = "not allowed"
            with self.assertRaises(ValueError):
                construct_turn_checkpoint(events)
            self.assertFalse(reference_verify(events, receipt), mode)

    def test_forged_empty_receipt_rejected(self):
        events = fixture(1)
        r = construct_turn_checkpoint(events)
        r["event_count"] = 0
        r["ordered_leaf_hashes"] = []
        r["peaks"] = []
        self.assertFalse(reference_verify([], r))

    def test_whitespace_parent_on_both_paths(self):
        events = fixture(1)
        for malformed in (" " * 64, "Z" * 64, "ab" * 31, "ab " * 21 + "x"):
            with self.assertRaises(ValueError):
                construct_turn_checkpoint(events, malformed)
        r = construct_turn_checkpoint(events, "ab" * 32)
        self.assertFalse(reference_verify(events, dict(r, parent_checkpoint=" " * 64)))

    def test_correctly_sequenced_append_invalidates_prior_receipt(self):
        events = fixture(1)
        prior = construct_turn_checkpoint(events)
        extra = fixture(1)[0].copy()
        extra["event_id"] = "successor"
        extra["sequence"] = 1
        self.assertFalse(reference_verify(events + [extra], prior))

    def test_receipt_extra_field_rejected(self):
        events = fixture()
        r = construct_turn_checkpoint(events)
        r["unreviewed_attestation"] = True
        self.assertFalse(reference_verify(events, r))

    def test_receipt_peak_size_and_order_rejected(self):
        events = fixture(3)
        r = construct_turn_checkpoint(events)
        reordered = dict(r, peaks=list(reversed(r["peaks"])))
        self.assertFalse(reference_verify(events, reordered))
        wrong = dict(r, peaks=[dict(p) for p in r["peaks"]])
        wrong["peaks"][0]["height"] = 3
        self.assertFalse(reference_verify(events, wrong))



    def test_sha256_instruction_digest_newline_rejected(self):
        original = fixture(1)
        original[0]["instruction_blob_sha256"] = "a" * 64
        receipt = construct_turn_checkpoint(original)
        self.assertTrue(reference_verify(original, receipt))
        tampered = fixture(1)
        tampered[0]["instruction_blob_sha256"] = "a" * 64 + "\n"
        with self.assertRaises(ValueError):
            construct_turn_checkpoint(tampered)
        self.assertFalse(reference_verify(tampered, receipt))

    def test_fcg_agent_turn_contract_fields_align_with_schema(self):
        import json
        from pathlib import Path
        root = Path(__file__).resolve().parents[1]
        agent = json.loads((root / "contracts/AGENT_TURN.v2.schema.json").read_text())
        graph = json.loads((root / "contracts/FCO_FCG_v2.json").read_text())
        self.assertEqual(set(graph["entities"]["AgentTurn"]), set(agent["properties"]))
        for edge in ("CITES", "BUILDS_ON", "USES_DATA_FROM",
                     "REPRODUCES", "COMPARES_WITH", "CORRECTS", "CONTAINS_RECEIPT"):
            self.assertIn(edge, graph["edge_types"])


if __name__ == "__main__":
    unittest.main()
