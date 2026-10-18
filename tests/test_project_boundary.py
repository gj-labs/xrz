"""XRZ-only governance invariants; no Antigence Clean Room admission by implication."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class TestProjectBoundary(unittest.TestCase):
    def setUp(self):
        self.boundary = json.loads((ROOT / "governance/PROJECT_BOUNDARY_XRZ.json").read_text())

    def test_exact_project_and_remote(self):
        p = self.boundary["project_binding"]
        self.assertEqual(p["project_id"], "XRZ")
        self.assertEqual(p["upstream_repo"], "gj-labs/xrz")
        self.assertEqual(p["development_fork"], "biobitworks/xrz")
        self.assertNotEqual(p["project_id"], self.boundary["excluded_independent_project"]["project_id"])

    def test_antigence_is_excluded_and_private(self):
        ant = self.boundary["excluded_independent_project"]
        self.assertEqual(ant["project_id"], "ANTIGENCE_CLEANROOM")
        self.assertEqual(ant["repository"], "biobitworks/antigence")
        self.assertEqual(ant["visibility"], "private")
        self.assertEqual(ant["source_provenance"], "NO_ANTIGENCE_SOURCE_IMPORTED_IN_XRZ")
        self.assertIn("No private Antigence inputs in public XRZ and no XRZ experiments inserted into the private Antigence canonical Clean Room ledger.", (ROOT / "governance/PROJECT_BOUNDARY_XRZ.md").read_text())

    def test_withdrawn_pr_is_never_admitted(self):
        p = self.boundary["disposition_of_mistaken_xrz_pr"]
        self.assertEqual(p["merge_state"], "CLOSED_NOT_MERGED")
        self.assertEqual(p["status"], "WITHDRAWN_SCOPE_MISMATCH")
        self.assertEqual(p["eight_case_mock_protocol"], "UNADMITTED_TO_BOTH_CANONICAL_PROJECTS")

    def test_no_implicit_cross_project_root_claim(self):
        self.assertEqual(self.boundary["verified_merkle_mmr_root"], "NOT_COMPUTED")
        self.assertEqual(self.boundary["signature_state"], "NOT_SIGNED")

    def test_no_mistaken_antigence_implementation_in_active_branch(self):
        for forbidden in ("docs/ANTIGENCE_CLEANROOM_PROTOCOL.md",
                          "prompts/ANTIGENCE_CLEANROOM_COPILOT.md",
                          "protocols/clean_room_experiment_v1.json"):
            self.assertFalse((ROOT / forbidden).exists(), forbidden)

    def test_no_other_project_has_been_made_dependency(self):
        self.assertEqual(self.boundary["excluded_independent_project"]["existing_lineage"], "DO_NOT_RECONSTRUCT_OR_MERGE")
        self.assertTrue(any("No automatic issue" in statement for statement in self.boundary["admission_constraints"]))

if __name__ == "__main__":
    unittest.main()
