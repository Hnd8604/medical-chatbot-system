"""Tool có bộ lọc: mã LOINC trên server, khoảng ngày, lần khám, xu hướng, nhóm bệnh nhân."""

import unittest

from agent.answer.evidence_budget import apply_evidence_budget
from tests.fakes import (
    FakeFhirClient,
    bp_observation,
    encounter_resource,
    medication_resource,
    observation_resource,
    patient_resource,
)
from tools.encounters import get_all_patient_encounters, get_encounters
from tools.medications import get_medication_requests
from tools.observations import get_all_patient_observations, get_observations
from tools.trends import get_observation_trend


def hba1c(obs_id, patient_id, value, date):
    return observation_resource(
        obs_id, patient_id, code="4548-4", display="Hemoglobin A1c/Hemoglobin.total in Blood", value=value, unit="%", date=date
    )


def heart_rate(obs_id, patient_id, value, date):
    return observation_resource(obs_id, patient_id, code="8867-4", display="Heart rate", value=value, unit="/min", date=date)


class ObservationFilterTests(unittest.IsolatedAsyncioTestCase):
    async def test_type_filter_runs_on_server_and_finds_older_lab(self):
        # 30 lần đo nhịp tim mới hơn HbA1c: cách cũ (tải 20 bản ghi rồi lọc) báo nhầm "không có".
        observations = [heart_rate(f"HR-{i}", "BN1", 70 + i % 5, f"2026-06-{i % 28 + 1:02d}") for i in range(30)]
        observations.append(hba1c("A1C-1", "BN1", 7.4, "2026-01-10"))
        client = FakeFhirClient(observations=observations)

        payload = await get_observations(client, patient_id="BN1", observation_type="hba1c")

        self.assertEqual([item["id"] for item in payload["evidence"]], ["A1C-1"])
        resource_type, params = client.requests[0]
        self.assertEqual(resource_type, "Observation")
        self.assertIn("4548-4", params["code"])

    async def test_name_fallback_when_data_has_no_loinc_code(self):
        uncoded = {
            "resourceType": "Observation",
            "id": "OBS-X",
            "code": {"text": "Glucose"},
            "subject": {"reference": "Patient/BN1"},
            "effectiveDateTime": "2026-02-01",
            "valueQuantity": {"value": 6.1, "unit": "mmol/L"},
        }
        client = FakeFhirClient(observations=[uncoded])
        payload = await get_observations(client, patient_id="BN1", observation_type="glucose")
        self.assertEqual([item["id"] for item in payload["evidence"]], ["OBS-X"])
        self.assertEqual(len(client.requests), 2)  # lọc theo mã (rỗng) rồi lùi về lọc theo tên

    async def test_date_range_is_sent_to_fhir(self):
        client = FakeFhirClient(
            observations=[hba1c("A", "BN1", 8.0, "2026-01-05"), hba1c("B", "BN1", 7.5, "2026-03-05")]
        )
        payload = await get_observations(
            client, patient_id="BN1", observation_type="hba1c", date_from="2026-03-01", date_to="2026-03-31"
        )
        self.assertEqual([item["id"] for item in payload["evidence"]], ["B"])
        self.assertEqual(client.requests[0][1]["date"], ["ge2026-03-01", "le2026-03-31"])
        self.assertIn("từ 2026-03-01 đến 2026-03-31", payload["answer"])
        self.assertEqual(payload["applied_filters"], {"date_from": "2026-03-01", "date_to": "2026-03-31"})

    async def test_medications_filtered_by_encounter(self):
        client = FakeFhirClient(
            medications=[
                medication_resource("MED-OLD", "BN1", "Metformin 500 mg", encounter_id="ENC-1"),
                medication_resource("MED-NEW", "BN1", "Metformin 1000 mg", encounter_id="ENC-2"),
            ]
        )
        payload = await get_medication_requests(client, patient_id="BN1", encounter_id="Encounter/ENC-2")
        self.assertEqual([item["id"] for item in payload["evidence"]], ["MED-NEW"])
        self.assertEqual(client.requests[0][1]["encounter"], "Encounter/ENC-2")
        self.assertIn("trong lần khám Encounter/ENC-2", payload["answer"])

    async def test_encounters_newest_first(self):
        client = FakeFhirClient(
            encounters=[encounter_resource("ENC-1", "BN1", "2025-11-01"), encounter_resource("ENC-2", "BN1", "2026-02-01")]
        )
        payload = await get_encounters(client, patient_id="BN1", limit=1)
        self.assertEqual([item["id"] for item in payload["evidence"]], ["ENC-2"])


class TrendToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_trend_statistics_are_computed_by_code(self):
        client = FakeFhirClient(
            observations=[
                hba1c("A", "BN1", 8.9, "2025-11-01"),
                hba1c("B", "BN1", 8.1, "2026-01-01"),
                hba1c("C", "BN1", 7.2, "2026-03-01"),
            ]
        )
        payload = await get_observation_trend(client, patient_id="BN1", observation_type="hba1c")

        trend = payload["evidence"][-1]
        self.assertEqual(trend["resource_type"], "ObservationTrend")
        self.assertTrue(trend["pinned"])
        series = trend["data"]["series"][0]
        self.assertEqual(series["count"], 3)
        self.assertEqual(series["first"], {"date": "2025-11-01", "value": 8.9})
        self.assertEqual(series["last"], {"date": "2026-03-01", "value": 7.2})
        self.assertEqual(series["change"], -1.7)
        self.assertEqual(series["change_percent"], -19.1)
        self.assertEqual(series["direction"], "giảm")
        self.assertIn("8.9 → 8.1 → 7.2", payload["answer"])
        self.assertIn("giảm từ 8.9 xuống 7.2", payload["answer"])
        # evidence[0] vẫn là resource FHIR thật, mới nhất.
        self.assertEqual(payload["evidence"][0]["id"], "C")

    async def test_blood_pressure_trend_has_two_series(self):
        client = FakeFhirClient(
            observations=[
                bp_observation("BP-1", "BN1", 150, 95, "2026-01-01"),
                bp_observation("BP-2", "BN1", 138, 88, "2026-02-01"),
            ]
        )
        payload = await get_observation_trend(client, patient_id="BN1", observation_type="blood_pressure")
        names = [series["name"] for series in payload["evidence"][-1]["data"]["series"]]
        self.assertEqual(names, ["Huyết áp tâm thu", "Huyết áp tâm trương"])

    async def test_single_point_is_not_a_trend(self):
        client = FakeFhirClient(observations=[hba1c("A", "BN1", 7.0, "2026-01-01")])
        payload = await get_observation_trend(client, patient_id="BN1", observation_type="hba1c")
        self.assertIn("chưa đủ dữ liệu", payload["answer"])

    async def test_pinned_summary_survives_evidence_budget(self):
        client = FakeFhirClient(observations=[hba1c(f"O{i}", "BN1", 7 + i / 10, f"2026-01-{i + 1:02d}") for i in range(10)])
        payload = await get_observation_trend(client, patient_id="BN1", observation_type="hba1c")
        trimmed, stats = apply_evidence_budget(payload["evidence"], max_chars=1500)
        self.assertGreater(stats["dropped_items"], 0)
        self.assertEqual(trimmed[-1]["resource_type"], "ObservationTrend")


class CohortToolTests(unittest.IsolatedAsyncioTestCase):
    def _client(self):
        patients = [patient_resource(f"BN{i}", name=f"Benh Nhan {i}") for i in range(1, 6)]
        observations = [
            hba1c("A1", "BN1", 8.2, "2026-03-01"),
            hba1c("A1-old", "BN1", 6.0, "2025-01-01"),
            hba1c("A2", "BN2", 6.4, "2026-02-01"),
            hba1c("A3-old", "BN3", 9.0, "2025-06-01"),
            hba1c("A3", "BN3", 6.9, "2026-04-01"),  # mới nhất < 7 ⇒ không tính dù cũ từng 9.0
            hba1c("A4", "BN4", 7.0, "2026-01-01"),
            hba1c("A5", "BN5", 9.4, "2026-05-01"),
        ]
        return FakeFhirClient(patients=patients, observations=observations)

    async def test_threshold_uses_latest_value_of_every_patient(self):
        client = self._client()
        payload = await get_all_patient_observations(client, observation_type="hba1c", value_op="gt", value=7)

        self.assertEqual(client.calls, ["search_resources:Observation"])  # một truy vấn, không lặp từng người
        listed = [item["id"] for item in payload["evidence"] if item["resource_type"] == "Observation"]
        self.assertEqual(listed, ["A5", "A1"])  # cao xuống thấp; 7.0 không thoả "> 7"
        cohort = payload["evidence"][-1]["data"]
        self.assertEqual(cohort["patients_checked"], 5)
        self.assertEqual(cohort["patients_matched"], 2)
        self.assertIn("đã kiểm tra kết quả HbA1c gần nhất của 5 bệnh nhân", payload["answer"])
        self.assertIn("2 bệnh nhân có HbA1c > 7 %", payload["answer"])

    async def test_inclusive_threshold_and_no_match(self):
        payload = await get_all_patient_observations(self._client(), observation_type="hba1c", value_op="ge", value=7)
        self.assertEqual(payload["evidence"][-1]["data"]["patients_matched"], 3)
        payload = await get_all_patient_observations(self._client(), observation_type="hba1c", value_op="gt", value=12)
        self.assertIn("không có bệnh nhân nào", payload["answer"])

    async def test_blood_pressure_threshold_uses_systolic_by_default(self):
        client = FakeFhirClient(
            patients=[patient_resource("BN1"), patient_resource("BN2")],
            observations=[bp_observation("P1", "BN1", 150, 85, "2026-01-01"), bp_observation("P2", "BN2", 130, 95, "2026-01-01")],
        )
        systolic = await get_all_patient_observations(client, observation_type="blood_pressure", value_op="ge", value=140)
        diastolic = await get_all_patient_observations(
            client, observation_type="blood_pressure", value_op="ge", value=90, component="diastolic"
        )
        self.assertEqual([i["id"] for i in systolic["evidence"] if i["id"]], ["P1"])
        self.assertEqual([i["id"] for i in diastolic["evidence"] if i["id"]], ["P2"])

    async def test_per_patient_tools_report_coverage(self):
        client = FakeFhirClient(
            patients=[patient_resource(f"BN{i}") for i in range(1, 4)],
            encounters=[],
            patients_total=100,
        )
        payload = await get_all_patient_encounters(client)
        self.assertIn("mới duyệt 3/100 bệnh nhân", payload["answer"])


if __name__ == "__main__":
    unittest.main()
