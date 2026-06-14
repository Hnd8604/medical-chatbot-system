"""
Unit tests for agents/intent/* modules (M4 - Medical Intent Classification).
Tests are isolated per module and do not require running services.
"""
import unittest

from agents.intent.vocabulary import (
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    CONDITION_KEYWORDS,
)
from agents.intent.text_utils import (
    normalize_text,
    contains_any,
    extract_phone,
    normalize_phone,
    extract_birth_date,
    extract_identifier,
    extract_patient_name,
    clean_name_candidate,
    normalize_patient_id,
)
from agents.intent.patient_utils import (
    resolve_explicit_patient_id,
    resolve_patient_id_for_request,
    extract_patient_search_criteria,
    has_patient_search_criteria,
    is_patient_list_request,
)
from agents.intent.observation_utils import infer_observation_type
from agents.intent.guardrails import (
    enforce_contact_detail_routing,
    enforce_patient_list_routing,
    apply_all_patient_scope,
    apply_patient_id_hint,
    add_observation_type_hint,
)
from agents.intent.models import IntentPlan
from agents.intent.constants import (
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    TOOL_UNSUPPORTED,
    DEFAULT_PATIENT_ID,
)
from agents.intent.rule_extractor import RuleBasedIntentExtractor


# ---------------------------------------------------------------------------
# Vocabulary
# ---------------------------------------------------------------------------

class VocabularyTests(unittest.TestCase):
    def test_medication_keywords_include_thuoc(self):
        self.assertIn("thuoc", MEDICATION_KEYWORDS)

    def test_observation_keywords_include_spo2(self):
        self.assertIn("spo2", OBSERVATION_KEYWORDS)

    def test_observation_keywords_include_nhip_tho(self):
        self.assertIn("nhip tho", OBSERVATION_KEYWORDS)

    def test_observation_keywords_include_creatinine(self):
        self.assertIn("creatinine", OBSERVATION_KEYWORDS)

    def test_observation_keywords_include_alt(self):
        self.assertIn("alt", OBSERVATION_KEYWORDS)

    def test_condition_keywords_include_di_ung(self):
        self.assertIn("di ung", CONDITION_KEYWORDS)

    def test_condition_keywords_include_tien_su_benh(self):
        self.assertIn("tien su benh", CONDITION_KEYWORDS)

    def test_encounter_keywords_include_nhap_vien(self):
        self.assertIn("nhap vien", ENCOUNTER_KEYWORDS)

    def test_encounter_keywords_include_cap_cuu(self):
        self.assertIn("cap cuu", ENCOUNTER_KEYWORDS)

    def test_encounter_keywords_include_tai_kham(self):
        self.assertIn("tai kham", ENCOUNTER_KEYWORDS)


# ---------------------------------------------------------------------------
# TextUtils
# ---------------------------------------------------------------------------

class TextUtilsTests(unittest.TestCase):
    def test_normalize_text_removes_diacritics(self):
        self.assertEqual(normalize_text("Bệnh nhân"), "benh nhan")

    def test_normalize_text_lowercases(self):
        self.assertEqual(normalize_text("GLUCOSE"), "glucose")

    def test_normalize_text_replaces_d_stroke(self):
        result = normalize_text("đường")
        self.assertNotIn("đ", result)
        self.assertIn("d", result)

    def test_contains_any_returns_true_on_match(self):
        self.assertTrue(contains_any("xet nghiem duong huyet", ["duong huyet"]))

    def test_contains_any_returns_false_when_no_match(self):
        self.assertFalse(contains_any("thong tin benh nhan", ["thuoc", "glucose"]))

    def test_extract_phone_matches_vn_mobile(self):
        self.assertEqual(extract_phone("so dt 0912345678"), "0912345678")

    def test_extract_phone_matches_with_plus84(self):
        result = extract_phone("+84912345678")
        self.assertEqual(result, "0912345678")

    def test_extract_phone_returns_none_when_absent(self):
        self.assertIsNone(extract_phone("benh nhan Nguyen Van A"))

    def test_normalize_phone_strips_country_code(self):
        self.assertEqual(normalize_phone("+84912345678"), "0912345678")

    def test_normalize_phone_returns_none_for_none(self):
        self.assertIsNone(normalize_phone(None))

    def test_extract_birth_date_parses_iso(self):
        self.assertEqual(extract_birth_date("sinh ngay 1990-05-15"), "1990-05-15")

    def test_extract_birth_date_parses_dd_mm_yyyy(self):
        self.assertEqual(extract_birth_date("15/05/1990"), "1990-05-15")

    def test_extract_birth_date_returns_none_for_invalid(self):
        self.assertIsNone(extract_birth_date("khong co ngay sinh"))

    def test_extract_identifier_finds_cccd(self):
        result = extract_identifier("cccd: 001234567890")
        self.assertEqual(result, "001234567890")

    def test_extract_patient_name_finds_after_benh_nhan(self):
        result = extract_patient_name("thong tin benh nhan Nguyen Van A")
        self.assertIsNotNone(result)
        self.assertIn("Nguyen", result)

    def test_clean_name_candidate_removes_stop_phrase(self):
        result = clean_name_candidate("Nguyen Van A dang kham")
        self.assertIsNotNone(result)
        self.assertNotIn("kham", result)

    def test_normalize_patient_id_strips_fhir_prefix(self):
        self.assertEqual(normalize_patient_id("Patient/demo-patient-001"), "demo-patient-001")

    def test_normalize_patient_id_returns_none_for_empty(self):
        self.assertIsNone(normalize_patient_id(""))


# ---------------------------------------------------------------------------
# PatientUtils
# ---------------------------------------------------------------------------

class PatientUtilsTests(unittest.TestCase):
    def test_resolve_explicit_patient_id_from_fhir_reference(self):
        result = resolve_explicit_patient_id("lay thong tin Patient/demo-patient-002")
        self.assertEqual(result, "demo-patient-002")

    def test_resolve_explicit_patient_id_from_numbered_patient(self):
        result = resolve_explicit_patient_id("benh nhan so 3 dang dung thuoc gi")
        self.assertEqual(result, "demo-patient-003")

    def test_resolve_explicit_patient_id_returns_none_when_absent(self):
        self.assertIsNone(resolve_explicit_patient_id("benh nhan nguyen van a"))

    def test_resolve_patient_id_for_request_prefers_message_id(self):
        result = resolve_patient_id_for_request("Patient/demo-patient-005", provided_patient_id="demo-patient-001")
        self.assertEqual(result, "demo-patient-005")

    def test_resolve_patient_id_for_request_falls_back_to_provided(self):
        result = resolve_patient_id_for_request("thuoc gi", provided_patient_id="demo-patient-002")
        self.assertEqual(result, "demo-patient-002")

    def test_resolve_patient_id_for_request_falls_back_to_default(self):
        result = resolve_patient_id_for_request("thuoc gi")
        self.assertEqual(result, DEFAULT_PATIENT_ID)

    def test_extract_patient_search_criteria_extracts_phone(self):
        criteria = extract_patient_search_criteria("tim benh nhan so dt 0912345678")
        self.assertEqual(criteria.get("search_phone"), "0912345678")

    def test_has_patient_search_criteria_true_with_name(self):
        plan = IntentPlan(tool_name=TOOL_SEARCH_PATIENTS, search_name="Nguyen Van A")
        self.assertTrue(has_patient_search_criteria(plan))

    def test_has_patient_search_criteria_false_when_empty(self):
        plan = IntentPlan(tool_name=TOOL_GET_PATIENT)
        self.assertFalse(has_patient_search_criteria(plan))

    def test_is_patient_list_request_true_for_danh_sach(self):
        self.assertTrue(is_patient_list_request("danh sach benh nhan"))

    def test_is_patient_list_request_false_for_single_patient(self):
        self.assertFalse(is_patient_list_request("thong tin benh nhan 1"))


# ---------------------------------------------------------------------------
# ObservationUtils
# ---------------------------------------------------------------------------

class ObservationUtilsTests(unittest.TestCase):
    def _check(self, message: str, expected: str):
        self.assertEqual(infer_observation_type(message), expected)

    def test_blood_pressure(self):
        self._check("huyet ap hien tai", "blood_pressure")

    def test_glucose(self):
        self._check("duong huyet bao nhieu", "glucose")

    def test_heart_rate(self):
        self._check("nhip tim", "heart_rate")

    def test_cholesterol(self):
        self._check("cholesterol", "cholesterol")

    def test_hba1c(self):
        self._check("ket qua hba1c", "hba1c")

    def test_spo2(self):
        self._check("do bao hoa oxy spo2", "spo2")

    def test_respiratory_rate(self):
        self._check("nhip tho cua benh nhan", "respiratory_rate")

    def test_weight(self):
        self._check("can nang benh nhan la bao nhieu", "weight")

    def test_height(self):
        self._check("chieu cao", "height")

    def test_creatinine(self):
        self._check("creatinine huyet thanh", "creatinine")

    def test_ast_sgot(self):
        self._check("ket qua sgot", "ast")

    def test_alt_sgpt(self):
        self._check("chi so sgpt", "alt")

    def test_triglyceride(self):
        self._check("triglyceride mau", "triglyceride")

    def test_ldl(self):
        self._check("ldl cholesterol", "ldl")

    def test_hdl(self):
        self._check("hdl", "hdl")

    def test_returns_none_for_unknown(self):
        self.assertIsNone(infer_observation_type("benh nhan co benh gi"))


# ---------------------------------------------------------------------------
# Guardrails
# ---------------------------------------------------------------------------

class GuardrailTests(unittest.TestCase):
    def _base_plan(self, tool_name: str = TOOL_GET_MEDICATIONS, **kwargs) -> IntentPlan:
        return IntentPlan(tool_name=tool_name, confidence_score=0.85, **kwargs)

    def test_enforce_contact_detail_no_op_when_already_patient_tool(self):
        plan = self._base_plan(tool_name=TOOL_GET_PATIENT)
        result = enforce_contact_detail_routing("so dien thoai", plan)
        self.assertEqual(result.tool_name, TOOL_GET_PATIENT)
        self.assertEqual(result.confidence_score, 0.85)

    def test_enforce_contact_detail_routes_to_patient_when_keyword_present(self):
        plan = self._base_plan(tool_name=TOOL_GET_MEDICATIONS)
        result = enforce_contact_detail_routing("so dien thoai cua benh nhan", plan)
        self.assertEqual(result.tool_name, TOOL_GET_PATIENT)
        self.assertLess(result.confidence_score, 0.85)

    def test_enforce_contact_detail_no_op_when_has_search_criteria(self):
        plan = self._base_plan(tool_name=TOOL_GET_MEDICATIONS, search_name="Nguyen Van A")
        result = enforce_contact_detail_routing("so dien thoai", plan)
        self.assertEqual(result.tool_name, TOOL_GET_MEDICATIONS)

    def test_enforce_patient_list_routes_to_search(self):
        plan = self._base_plan(tool_name=TOOL_GET_PATIENT)
        result = enforce_patient_list_routing("danh sach benh nhan", plan)
        self.assertEqual(result.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertLess(result.confidence_score, 0.85)

    def test_enforce_patient_list_no_op_when_already_search(self):
        plan = self._base_plan(tool_name=TOOL_SEARCH_PATIENTS)
        result = enforce_patient_list_routing("danh sach benh nhan", plan)
        self.assertEqual(result.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertEqual(result.confidence_score, 0.85)

    def test_apply_all_patient_scope_sets_flag(self):
        plan = self._base_plan(tool_name=TOOL_GET_MEDICATIONS)
        result = apply_all_patient_scope("tat ca benh nhan dang dung thuoc gi", plan)
        self.assertTrue(result.all_patients)

    def test_apply_patient_id_hint_overrides_default(self):
        plan = self._base_plan(tool_name=TOOL_GET_PATIENT, patient_id=DEFAULT_PATIENT_ID)
        result = apply_patient_id_hint("", "demo-patient-003", plan)
        self.assertEqual(result.patient_id, "demo-patient-003")

    def test_apply_patient_id_hint_no_op_when_already_correct(self):
        plan = self._base_plan(tool_name=TOOL_GET_PATIENT, patient_id="demo-patient-003")
        result = apply_patient_id_hint("", "demo-patient-003", plan)
        self.assertIs(result, plan)

    def test_add_observation_type_hint_sets_blood_pressure(self):
        plan = self._base_plan(tool_name=TOOL_GET_OBSERVATIONS)
        result = add_observation_type_hint("huyet ap hien tai", plan)
        self.assertEqual(result.observation_type, "blood_pressure")

    def test_add_observation_type_hint_no_op_when_already_set(self):
        plan = self._base_plan(tool_name=TOOL_GET_OBSERVATIONS, observation_type="glucose")
        result = add_observation_type_hint("huyet ap", plan)
        self.assertEqual(result.observation_type, "glucose")

    def test_guardrail_discounts_confidence_when_tool_changes(self):
        original_score = 0.92
        plan = IntentPlan(tool_name=TOOL_GET_MEDICATIONS, confidence_score=original_score)
        result = enforce_patient_list_routing("danh sach benh nhan", plan)
        self.assertLess(result.confidence_score, original_score)


# ---------------------------------------------------------------------------
# RuleBasedExtractor
# ---------------------------------------------------------------------------

class RuleBasedExtractorTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.extractor = RuleBasedIntentExtractor()

    async def test_medication_intent(self):
        plan = await self.extractor.extract("benh nhan dang dung thuoc gi")
        self.assertEqual(plan.tool_name, TOOL_GET_MEDICATIONS)
        self.assertGreater(plan.confidence_score, 0.0)

    async def test_observation_intent_with_spo2(self):
        plan = await self.extractor.extract("spo2 cua benh nhan 1 la bao nhieu")
        self.assertEqual(plan.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(plan.observation_type, "spo2")

    async def test_observation_intent_with_creatinine(self):
        plan = await self.extractor.extract("ket qua creatinine moi nhat")
        self.assertEqual(plan.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(plan.observation_type, "creatinine")

    async def test_condition_intent_with_di_ung(self):
        plan = await self.extractor.extract("benh nhan co di ung gi khong")
        self.assertEqual(plan.tool_name, TOOL_GET_CONDITIONS)

    async def test_encounter_intent_with_nhap_vien(self):
        plan = await self.extractor.extract("benh nhan nhap vien lan nao")
        self.assertEqual(plan.tool_name, TOOL_GET_ENCOUNTERS)

    async def test_encounter_intent_with_cap_cuu(self):
        plan = await self.extractor.extract("benh nhan co lich su cap cuu khong")
        self.assertEqual(plan.tool_name, TOOL_GET_ENCOUNTERS)

    async def test_unsupported_returns_low_confidence(self):
        plan = await self.extractor.extract("thoi tiet hom nay the nao")
        self.assertEqual(plan.tool_name, TOOL_UNSUPPORTED)
        self.assertEqual(plan.confidence_score, 0.30)

    async def test_all_intents_have_positive_confidence(self):
        messages = [
            "thuoc cua benh nhan 1",
            "huyet ap benh nhan 2",
            "lich su kham",
            "chan doan la gi",
            "thong tin benh nhan",
        ]
        for msg in messages:
            plan = await self.extractor.extract(msg)
            self.assertGreater(plan.confidence_score, 0.0, f"Failed for: {msg}")

    async def test_medication_confidence_greater_than_unsupported(self):
        med_plan = await self.extractor.extract("dang dung thuoc gi")
        unsup_plan = await self.extractor.extract("xin chao")
        self.assertGreater(med_plan.confidence_score, unsup_plan.confidence_score)

    async def test_confidence_with_search_criteria_is_higher(self):
        with_criteria = await self.extractor.extract("thuoc cua benh nhan Nguyen Van A")
        without_criteria = await self.extractor.extract("benh nhan dung thuoc gi")
        self.assertGreaterEqual(with_criteria.confidence_score, without_criteria.confidence_score)


if __name__ == "__main__":
    unittest.main()
