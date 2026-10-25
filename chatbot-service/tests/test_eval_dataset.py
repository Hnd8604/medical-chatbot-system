"""Bộ eval là lưới chống regression khi sửa prompt, nên bản thân nó phải hợp lệ.

Test này KHÔNG gọi mạng: chỉ kiểm nhãn có nhất quán với registry tool và route hiện tại.
"""

import json
import unittest

from agent.planning.router import SUPPORTED_META_KINDS, SUPPORTED_ROUTES
from agent.state import ROUTE_EMERGENCY
from api.schemas import ChatRequest
from tests.eval.run_cache_pairs import PAIRS_PATH, load_pairs
from tests.eval.run_eval import build_request, expand_cases, load_cases, score_content
from tools.registry import TOOL_REGISTRY


VALID_ROUTES = SUPPORTED_ROUTES | {ROUTE_EMERGENCY}


class EvalDatasetTests(unittest.TestCase):
    def setUp(self):
        self.cases = load_cases()
        self.turns = expand_cases(self.cases)

    def test_ids_unique_and_dataset_large_enough(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(self.turns), 120)

    def test_every_case_is_tagged(self):
        for case in self.cases:
            self.assertTrue(case.get("tags"), case["id"])

    def test_expected_tools_exist_in_registry(self):
        for turn in self.turns:
            for tool in turn.get("expected_tools", []):
                self.assertIn(tool, TOOL_REGISTRY, f"case {turn.get('message')} gán tool lạ: {tool}")

    def test_routes_and_roles_are_valid(self):
        for case in self.cases:
            self.assertIn(case.get("role", "DOCTOR"), {"USER", "DOCTOR", "ADMIN"}, case["id"])
        for turn in self.turns:
            if "expected_route" in turn:
                self.assertIn(turn["expected_route"], VALID_ROUTES, turn.get("message"))
            if "expected_meta_kind" in turn:
                self.assertIn(turn["expected_meta_kind"], SUPPORTED_META_KINDS, turn.get("message"))

    def test_content_labels_are_lists_of_strings(self):
        for turn in self.turns:
            for key in ("must_include", "must_not_include"):
                values = turn.get(key, [])
                self.assertIsInstance(values, list, turn.get("message"))
                self.assertTrue(all(isinstance(value, str) and value for value in values), turn.get("message"))

    def test_user_cases_declare_allowed_patients(self):
        # Nhãn cho role USER mà quên allowed_patient_ids sẽ luôn fail vì lý do sai.
        for case in self.cases:
            if case.get("role") == "USER" and case.get("expected_route") in {"fhir", None}:
                self.assertTrue(case.get("allowed_patient_ids"), case["id"])

    def test_every_case_builds_a_valid_request(self):
        for case in self.cases:
            for turn in case.get("turns") or [case]:
                merged = {**case, **turn, "turns": None}
                ChatRequest(**build_request(merged, "eval-user"))

    def test_content_scoring_normalizes_vietnamese_decimals_and_accents(self):
        ok, _ = score_content({"must_include": ["7.8", "tang huyet ap"]}, "HbA1c 7,8 %; Tăng huyết áp vô căn")
        self.assertTrue(ok)
        ok, detail = score_content({"must_not_include": ["BN2026-00099"]}, "Dữ liệu của BN2026-00099")
        self.assertFalse(ok)
        self.assertIn("BN2026-00099", detail)


class CachePairDatasetTests(unittest.TestCase):
    def test_pairs_are_well_formed(self):
        pairs = load_pairs(PAIRS_PATH)
        self.assertGreaterEqual(len(pairs), 40)
        ids = [pair["id"] for pair in pairs]
        self.assertEqual(len(ids), len(set(ids)))
        for pair in pairs:
            self.assertIsInstance(pair["same_answer"], bool, pair["id"])
            self.assertTrue(pair["a"] and pair["b"] and pair["kind"], pair["id"])
        # Cần cả hai loại để đo được hit đúng lẫn hit nhầm.
        self.assertTrue(any(pair["same_answer"] for pair in pairs))
        self.assertTrue(any(not pair["same_answer"] for pair in pairs))

    def test_pairs_file_is_jsonl(self):
        for line in PAIRS_PATH.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("//"):
                json.loads(line)


if __name__ == "__main__":
    unittest.main()
