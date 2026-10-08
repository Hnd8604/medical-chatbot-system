"""Test cho agent/execution/executor.py: chốt bệnh nhân, gộp nhiều step, guard quyền runtime."""

import asyncio
import unittest

from agent.errors import PolicyError
from agent.execution.executor import execute_plan
from agent.planning.plan import Plan, PlanStep
from fhir.client import FhirClientError
from tests.fakes import FakeFhirClient, patient_resource


def make_plan(*steps: PlanStep) -> Plan:
    return Plan(steps=tuple(steps))


class PlanExecutorTests(unittest.IsolatedAsyncioTestCase):
    async def _execute(self, plan, *, client=None, role="DOCTOR", allowed=None, patient_id=None):
        return await execute_plan(
            plan,
            client=client or FakeFhirClient(),
            user_role=role,
            allowed_patient_ids=allowed or [],
            provided_patient_id=patient_id,
        )

    async def test_multi_step_merges_evidence(self):
        result = await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001", "limit": 5}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001", "limit": 5}),
            )
        )
        self.assertEqual(result.response_status, "answered")
        self.assertEqual(len(result.payload["evidence"]), 2)
        self.assertEqual(result.tool_name, "multi_tool_plan")
        self.assertEqual(result.resolved_patient_id, "BN2026-00001")

    async def test_single_step_keeps_template_answer(self):
        result = await self._execute(
            make_plan(PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001", "limit": 5}))
        )
        self.assertIn("BN2026-00001", result.payload["answer"])
        self.assertEqual(result.tool_name, "get_conditions")

    async def test_all_patient_tool_reports_base_tool_name(self):
        result = await self._execute(make_plan(PlanStep(id="m", tool="get_all_patient_medication_requests", args={"limit": 5})))
        self.assertEqual(result.tool_name, "get_medication_requests")
        self.assertTrue(result.payload["all_patients"])

    async def test_ambiguous_search_stops_for_selection(self):
        client = FakeFhirClient(patients=[patient_resource("BN2026-00001"), patient_resource("BN2026-00002")])
        result = await self._execute(
            make_plan(
                PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen", "limit": 3}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
            ),
            client=client,
        )
        self.assertEqual(result.response_status, "patient_selection_needed")
        self.assertTrue(result.payload["needs_patient_selection"])
        # Dừng trước khi chạm dữ liệu bệnh nhân nào.
        self.assertNotIn("MedicationRequest:BN2026-00001", client.calls)

    async def test_resolved_patient_flows_into_next_step(self):
        client = FakeFhirClient()
        result = await self._execute(
            make_plan(
                PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen", "limit": 3}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
            ),
            client=client,
        )
        self.assertEqual(result.response_status, "answered")
        self.assertIn("MedicationRequest:BN2026-00001", client.calls)

    async def test_missing_patient_asks_for_clarification(self):
        result = await self._execute(make_plan(PlanStep(id="m", tool="get_medication_requests", args={})))
        self.assertEqual(result.response_status, "clarification_needed")
        self.assertEqual(result.payload["evidence"], [])

    async def test_user_cannot_reach_patient_resolved_at_runtime(self):
        # Validator không thấy giá trị đến từ $resolve_patient; guard trong executor là
        # chốt chặn thứ hai.
        with self.assertRaises(PolicyError):
            await self._execute(
                make_plan(
                    PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen"}),
                    PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
                ),
                role="USER",
                allowed=["BN2026-00099"],
            )

    async def test_fhir_error_becomes_tool_error_payload(self):
        class BrokenClient(FakeFhirClient):
            async def search_patient_resources(self, *args, **kwargs):
                raise FhirClientError("FHIR Server đang bận.", "boom")

        result = await self._execute(
            make_plan(PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"})),
            client=BrokenClient(),
        )
        self.assertEqual(result.response_status, "tool_error")
        self.assertIn("FHIR Server đang bận.", result.payload["answer"])

    async def test_one_failed_step_returns_partial(self):
        class HalfBrokenClient(FakeFhirClient):
            async def search_patient_resources(self, resource_type, *args, **kwargs):
                if resource_type == "MedicationRequest":
                    raise FhirClientError("FHIR Server đang bận.", "boom")
                return await super().search_patient_resources(resource_type, *args, **kwargs)

        result = await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001"}),
            ),
            client=HalfBrokenClient(),
        )
        self.assertEqual(result.response_status, "partial")
        self.assertEqual(result.failed_steps, [{"id": "m", "tool": "get_medication_requests", "error_code": "fhir_error"}])
        self.assertEqual([item["resource_type"] for item in result.payload["evidence"]], ["Condition"])
        self.assertEqual(result.tool_name, "multi_tool_plan")
        self.assertEqual(len(result.executed_steps), 2)

    async def test_all_steps_failed_is_tool_error(self):
        class BrokenClient(FakeFhirClient):
            async def search_patient_resources(self, *args, **kwargs):
                raise FhirClientError("FHIR Server đang bận.", "boom")

        result = await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001"}),
            ),
            client=BrokenClient(),
        )
        self.assertEqual(result.response_status, "tool_error")
        self.assertEqual([step["id"] for step in result.failed_steps], ["c", "m"])
        self.assertEqual(result.payload["evidence"], [])

    async def test_independent_steps_run_concurrently(self):
        order: list[str] = []

        class SlowClient(FakeFhirClient):
            async def search_patient_resources(self, resource_type, patient_id, count=10, sort=None):
                order.append(f"start:{resource_type}")
                await asyncio.sleep(0.02)
                order.append(f"end:{resource_type}")
                return await super().search_patient_resources(resource_type, patient_id, count, sort)

        await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001"}),
            ),
            client=SlowClient(),
        )
        # Chạy song song: cả hai step bắt đầu trước khi step đầu kết thúc.
        self.assertTrue(order[0].startswith("start:"))
        self.assertTrue(order[1].startswith("start:"))


if __name__ == "__main__":
    unittest.main()
