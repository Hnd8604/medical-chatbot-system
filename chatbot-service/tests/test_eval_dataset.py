"""Bộ eval là lưới chống regression khi sửa prompt, nên bản thân nó phải hợp lệ.

Test này KHÔNG gọi mạng: chỉ kiểm nhãn có nhất quán với registry tool và route hiện tại.
"""

import unittest

from agent.planning.router import SUPPORTED_META_KINDS, SUPPORTED_ROUTES
from api.schemas import ChatRequest
from tests.eval.run_eval import build_request, load_cases
from tools.registry import TOOL_REGISTRY


class EvalDatasetTests(unittest.TestCase):
    def setUp(self):
        self.cases = load_cases()

    def test_ids_unique_and_non_empty(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(ids), 40)

    def test_expected_tools_exist_in_registry(self):
        for case in self.cases:
            for tool in case.get("expected_tools", []):
                self.assertIn(tool, TOOL_REGISTRY, f"case {case['id']} gán tool lạ: {tool}")

    def test_routes_and_roles_are_valid(self):
        for case in self.cases:
            self.assertIn(case["expected_route"], SUPPORTED_ROUTES, case["id"])
            self.assertIn(case.get("role", "DOCTOR"), {"USER", "DOCTOR", "ADMIN"}, case["id"])
            if "expected_meta_kind" in case:
                self.assertIn(case["expected_meta_kind"], SUPPORTED_META_KINDS, case["id"])

    def test_user_cases_declare_allowed_patients(self):
        # Nhãn cho role USER mà quên allowed_patient_ids sẽ luôn fail vì lý do sai.
        for case in self.cases:
            if case.get("role") == "USER" and case["expected_route"] == "fhir":
                self.assertTrue(case.get("allowed_patient_ids"), case["id"])

    def test_every_case_builds_a_valid_request(self):
        for case in self.cases:
            ChatRequest(**build_request(case, "eval-user"))


if __name__ == "__main__":
    unittest.main()
