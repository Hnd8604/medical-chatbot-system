"""Test cho nlp/: từ khoá, chuẩn hoá văn bản, tham chiếu bệnh nhân, loại chỉ số."""

import unittest

from nlp.observation_types import infer_observation_type, observation_matches_type
from nlp.patient_refs import (
    extract_patient_search_criteria,
    is_patient_list_request,
    is_self_patient_reference,
    resolve_explicit_patient_id,
    resolve_patient_id_for_request,
)
from nlp.text import (
    clean_name_candidate,
    contains_any,
    extract_birth_date,
    extract_identifier,
    extract_patient_name,
    extract_phone,
    normalize_patient_id,
    normalize_phone,
    normalize_text,
)
from nlp.vocabulary import (
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
)


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

    def test_normalize_text_replaces_vietnamese_d_stroke(self):
        self.assertEqual(normalize_text("điện thoại"), "dien thoai")

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
        self.assertEqual(normalize_patient_id("Patient/BN2026-00001"), "BN2026-00001")

    def test_normalize_patient_id_returns_none_for_empty(self):
        self.assertIsNone(normalize_patient_id(""))


# ---------------------------------------------------------------------------
# PatientUtils
# ---------------------------------------------------------------------------

class PatientUtilsTests(unittest.TestCase):
    def test_resolve_explicit_patient_id_from_fhir_reference(self):
        result = resolve_explicit_patient_id("lay thong tin Patient/BN2026-00002")
        self.assertEqual(result, "BN2026-00002")

    def test_resolve_explicit_patient_id_from_numbered_patient(self):
        result = resolve_explicit_patient_id("benh nhan so 3 dang dung thuoc gi")
        self.assertEqual(result, "BN2026-00003")

    def test_resolve_explicit_patient_id_returns_none_when_absent(self):
        self.assertIsNone(resolve_explicit_patient_id("benh nhan nguyen van a"))

    def test_resolve_patient_id_for_request_prefers_message_id(self):
        result = resolve_patient_id_for_request("Patient/BN2026-00005", provided_patient_id="BN2026-00001")
        self.assertEqual(result, "BN2026-00005")

    def test_resolve_patient_id_for_request_falls_back_to_provided(self):
        result = resolve_patient_id_for_request("thuoc gi", provided_patient_id="BN2026-00002")
        self.assertEqual(result, "BN2026-00002")

    def test_resolve_patient_id_for_request_returns_none_without_context(self):
        result = resolve_patient_id_for_request("thuoc gi")
        self.assertIsNone(result)

    def test_extract_patient_search_criteria_extracts_phone(self):
        criteria = extract_patient_search_criteria("tim benh nhan so dt 0912345678")
        self.assertEqual(criteria.get("phone"), "0912345678")

    def test_is_patient_list_request_true_for_danh_sach(self):
        self.assertTrue(is_patient_list_request("danh sach benh nhan"))

    def test_is_patient_list_request_false_for_single_patient(self):
        self.assertFalse(is_patient_list_request("thong tin benh nhan 1"))

    def test_self_patient_reference_detects_my_profile(self):
        self.assertTrue(is_self_patient_reference("Thong tin ca nhan cua toi la gi?"))

    def test_self_patient_reference_detects_accented_phone(self):
        self.assertTrue(is_self_patient_reference("Số điện thoại của tôi là gì?"))

    def test_self_patient_reference_does_not_create_search_criteria(self):
        self.assertEqual(extract_patient_search_criteria("Thong tin cua toi"), {})

    def test_contextual_patient_reference_does_not_create_search_criteria(self):
        # "bệnh nhân này/đó" là tham chiếu ngữ cảnh, không phải tên -> không tạo search.
        self.assertEqual(extract_patient_search_criteria("Tóm tắt hồ sơ của bệnh nhân này"), {})
        self.assertEqual(extract_patient_search_criteria("Bệnh nhân này đang dùng những thuốc nào?"), {})
        self.assertEqual(
            extract_patient_search_criteria("Kết quả xét nghiệm mới nhất của bệnh nhân này"), {}
        )

    def test_clean_name_candidate_rejects_demonstrative_pronoun(self):
        self.assertIsNone(clean_name_candidate("này"))
        self.assertIsNone(clean_name_candidate("kia"))


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


class ObservationMatchTests(unittest.TestCase):
    def test_matches_vietnamese_blood_pressure_type(self):
        observation = {"code": "Blood pressure panel", "components": [{"code": "Systolic blood pressure"}]}
        self.assertTrue(observation_matches_type(observation, "huyết áp"))

    def test_matches_heart_rate_type(self):
        self.assertTrue(observation_matches_type({"code": "Heart rate"}, "nhịp tim"))
        self.assertFalse(observation_matches_type({"code": "Glucose"}, "nhịp tim"))


if __name__ == "__main__":
    unittest.main()
