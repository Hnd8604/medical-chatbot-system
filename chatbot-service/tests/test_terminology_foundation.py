import unittest

from terminology.cache import TerminologyCache, build_cache_key
from terminology.code_extractor import extract_codes_from_payload
from terminology.loinc_parser import parse_loinc_lookup_response
from terminology.medlineplus_parser import parse_medlineplus_response
from terminology.rxnorm_parser import (
    parse_rxnorm_properties_response,
    parse_rxnorm_rxcui_response,
)
from terminology.schemas import (
    ExternalKnowledgeItem,
    KnowledgeType,
    TerminologyCode,
    TerminologySource,
)


def _obs_evidence(system: str, code: str) -> dict:
    return {
        "resource_type": "Observation",
        "id": "o1",
        "data": {
            "resource_type": "Observation",
            "code_detail": {"text": "HbA1c", "coding": [{"system": system, "code": code, "display": "HbA1c"}]},
        },
    }


class CacheTests(unittest.TestCase):
    def test_set_get_and_expiry(self) -> None:
        now = {"t": 1000.0}
        cache = TerminologyCache(default_ttl_seconds=10, time_provider=lambda: now["t"])
        cache.set("k", [1, 2])
        self.assertEqual(cache.get("k"), [1, 2])
        now["t"] += 11  # vượt TTL
        self.assertIsNone(cache.get("k"))

    def test_cache_key_excludes_patient(self) -> None:
        # Key chỉ gồm source|system|code|text — không có patient/resource id.
        key = build_cache_key(source="LOINC", system="http://loinc.org", code="4548-4", text="HbA1c")
        self.assertIn("loinc", key)
        self.assertIn("4548-4", key)
        self.assertNotIn("patient", key.lower())


class CodeExtractorTests(unittest.TestCase):
    def test_extracts_loinc_observation(self) -> None:
        codes = extract_codes_from_payload({"evidence": [_obs_evidence("http://loinc.org", "4548-4")]})
        self.assertEqual(len(codes), 1)
        self.assertEqual(codes[0].resource_type, "Observation")
        self.assertEqual(codes[0].system, "http://loinc.org")
        self.assertEqual(codes[0].code, "4548-4")

    def test_extracts_condition_and_medication(self) -> None:
        payload = {
            "evidence": [
                {
                    "resource_type": "Condition",
                    "id": "c1",
                    "data": {
                        "resource_type": "Condition",
                        "code_detail": {"text": "DM2", "coding": [{"system": "http://snomed.info/sct", "code": "44054006"}]},
                    },
                },
                {
                    "resource_type": "MedicationRequest",
                    "id": "m1",
                    "data": {
                        "resource_type": "MedicationRequest",
                        "medication_detail": {"text": "metformin", "coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "860975"}]},
                    },
                },
            ]
        }
        codes = extract_codes_from_payload(payload)
        systems = {c.resource_type: c.system for c in codes}
        self.assertEqual(systems["Condition"], "http://snomed.info/sct")
        self.assertEqual(systems["MedicationRequest"], "http://www.nlm.nih.gov/research/umls/rxnorm")

    def test_medication_text_only_yields_text_code(self) -> None:
        payload = {
            "evidence": [
                {
                    "resource_type": "MedicationRequest",
                    "id": "m2",
                    "data": {
                        "resource_type": "MedicationRequest",
                        "medication_detail": {"text": "paracetamol", "coding": []},
                    },
                }
            ]
        }
        codes = extract_codes_from_payload(payload)
        self.assertEqual(len(codes), 1)
        self.assertIsNone(codes[0].code)
        self.assertEqual(codes[0].text, "paracetamol")

    def test_no_evidence_returns_empty(self) -> None:
        self.assertEqual(extract_codes_from_payload({"evidence": []}), [])
        self.assertEqual(extract_codes_from_payload(None), [])


class ParserTests(unittest.TestCase):
    def test_loinc_parser(self) -> None:
        raw = {
            "parameter": [
                {"name": "display", "valueString": "Hemoglobin A1c"},
                {"name": "property", "part": [{"name": "code", "valueCode": "COMPONENT"}, {"name": "value", "valueString": "Hemoglobin A1c/Hemoglobin.total"}]},
            ]
        }
        code = TerminologyCode(resource_type="Observation", resource_id="o1", source_field="code_detail", system="http://loinc.org", code="4548-4")
        items = parse_loinc_lookup_response(raw, code)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].source, TerminologySource.LOINC)
        self.assertEqual(items[0].display, "Hemoglobin A1c")
        self.assertEqual(items[0].fields.get("component"), "Hemoglobin A1c/Hemoglobin.total")

    def test_rxnorm_parsers(self) -> None:
        code = TerminologyCode(resource_type="MedicationRequest", resource_id="m1", source_field="medication_detail", code="860975")
        items = parse_rxnorm_properties_response({"properties": {"rxcui": "860975", "name": "metformin", "tty": "IN"}}, code)
        self.assertEqual(items[0].source, TerminologySource.RXNORM)
        self.assertEqual(items[0].display, "metformin")
        self.assertEqual(parse_rxnorm_rxcui_response({"idGroup": {"rxnormId": ["860975"]}}), "860975")
        self.assertIsNone(parse_rxnorm_rxcui_response({"idGroup": {}}))

    def test_medlineplus_parser(self) -> None:
        raw = {"feed": {"entry": [{"title": "Diabetes", "summary": "<p>Info about <b>diabetes</b>.</p>", "link": {"href": "https://medlineplus.gov/diabetes.html"}}]}}
        code = TerminologyCode(resource_type="Condition", resource_id="c1", source_field="code_detail", system="http://snomed.info/sct", code="44054006")
        items = parse_medlineplus_response(raw, code, KnowledgeType.CONDITION)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].source, TerminologySource.MEDLINEPLUS)
        self.assertEqual(items[0].url, "https://medlineplus.gov/diabetes.html")
        self.assertNotIn("<b>", items[0].summary or "")  # HTML đã được strip

    def test_external_knowledge_as_dict(self) -> None:
        item = ExternalKnowledgeItem(source=TerminologySource.LOINC, type=KnowledgeType.LAB_TEST, code="4548-4", display="HbA1c")
        d = item.as_dict()
        self.assertEqual(d["source"], "LOINC")
        self.assertEqual(d["type"], "lab_test")


if __name__ == "__main__":
    unittest.main()
