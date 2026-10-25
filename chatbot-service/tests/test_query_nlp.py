"""Nhận diện mốc thời gian, ngưỡng giá trị, cấp cứu và loại chỉ số (thuần, không I/O)."""

import unittest
from datetime import date

from cache.semantic_cache import embedding_text
from nlp.emergency import EMERGENCY_MEDICAL, EMERGENCY_SELF_HARM, detect_emergency
from nlp.observation_types import (
    canonical_observation_type,
    infer_observation_type,
    loinc_code_param,
    observation_matches_type,
)
from nlp.query_signature import plan_signature, query_signature
from nlp.time_range import extract_time_range, normalize_iso_date
from nlp.value_filter import compare, extract_value_filter, normalize_value_op


TODAY = date(2026, 10, 23)  # thứ Sáu


def time_range(message: str) -> tuple[str | None, str | None] | None:
    result = extract_time_range(message, today=TODAY)
    return (result.date_from, result.date_to) if result else None


class TimeRangeTests(unittest.TestCase):
    def test_relative_windows(self):
        self.assertEqual(time_range("huyết áp 3 tháng qua"), ("2026-07-23", "2026-10-23"))
        self.assertEqual(time_range("duong huyet 6 thang gan day"), ("2026-04-23", "2026-10-23"))
        self.assertEqual(time_range("trong vòng 2 tuần"), ("2026-10-09", "2026-10-23"))
        self.assertEqual(time_range("HbA1c sáu tháng qua thế nào"), ("2026-04-23", "2026-10-23"))
        self.assertEqual(time_range("một năm qua"), ("2025-10-23", "2026-10-23"))
        self.assertEqual(time_range("trong tháng qua"), ("2026-09-23", "2026-10-23"))

    def test_named_periods(self):
        self.assertEqual(time_range("tháng trước"), ("2026-09-01", "2026-09-30"))
        self.assertEqual(time_range("tháng này"), ("2026-10-01", "2026-10-23"))
        self.assertEqual(time_range("năm ngoái"), ("2025-01-01", "2025-12-31"))
        self.assertEqual(time_range("năm nay"), ("2026-01-01", "2026-10-23"))
        self.assertEqual(time_range("hôm qua"), ("2026-10-22", "2026-10-22"))
        self.assertEqual(time_range("tuần trước"), ("2026-10-12", "2026-10-18"))

    def test_specific_month_year_and_quarter(self):
        self.assertEqual(time_range("huyết áp tháng 3"), ("2026-03-01", "2026-03-31"))
        # Tháng chưa tới trong năm nay ⇒ năm trước.
        self.assertEqual(time_range("tháng 12"), ("2025-12-01", "2025-12-31"))
        self.assertEqual(time_range("tháng 2/2024"), ("2024-02-01", "2024-02-29"))
        self.assertEqual(time_range("thang 3 nam 2025"), ("2025-03-01", "2025-03-31"))
        self.assertEqual(time_range("năm 2025"), ("2025-01-01", "2025-12-31"))
        self.assertEqual(time_range("quý 2 năm 2026"), ("2026-04-01", "2026-06-30"))
        self.assertEqual(time_range("từ tháng 1 đến tháng 3 năm 2026"), ("2026-01-01", "2026-03-31"))

    def test_explicit_dates(self):
        self.assertEqual(time_range("từ 01/02/2026 đến 15/03/2026"), ("2026-02-01", "2026-03-15"))
        self.assertEqual(time_range("tu 2026-02-01 den 2026-03-15"), ("2026-02-01", "2026-03-15"))
        self.assertEqual(time_range("trước ngày 01/03/2026"), (None, "2026-02-28"))
        self.assertEqual(time_range("từ ngày 01/09/2026"), ("2026-09-01", "2026-10-23"))
        self.assertEqual(time_range("ngày 05/03/2026"), ("2026-03-05", "2026-03-05"))

    def test_no_time_range(self):
        self.assertIsNone(time_range("Chỉ số huyết áp gần nhất của BN2026-00001"))
        self.assertIsNone(time_range("Lần khám gần nhất của bệnh nhân này là khi nào?"))
        self.assertIsNone(time_range("Kết quả xét nghiệm của tôi thế nào?"))
        # Ngày sinh là tiêu chí tìm bệnh nhân, không phải mốc thời gian.
        self.assertIsNone(time_range("bệnh nhân sinh ngày 01/01/2003 dùng thuốc gì"))

    def test_normalize_iso_date(self):
        self.assertEqual(normalize_iso_date("2026-3-5"), "2026-03-05")
        self.assertEqual(normalize_iso_date("2026-03-05T10:00:00Z"), "2026-03-05")
        self.assertIsNone(normalize_iso_date("2026-02-30"))
        self.assertIsNone(normalize_iso_date("tháng 3"))
        self.assertIsNone(normalize_iso_date(None))


class ValueFilterTests(unittest.TestCase):
    def test_symbols(self):
        self.assertEqual(extract_value_filter("Bệnh nhân nào có HbA1c > 7?"), ("gt", 7.0))
        self.assertEqual(extract_value_filter("HbA1c >= 6,5"), ("ge", 6.5))
        self.assertEqual(extract_value_filter("SpO2 < 95"), ("lt", 95.0))
        self.assertEqual(extract_value_filter("cholesterol ≤ 200"), ("le", 200.0))

    def test_words(self):
        self.assertEqual(extract_value_filter("bệnh nhân nào có HbA1c trên 7%"), ("gt", 7.0))
        self.assertEqual(extract_value_filter("huyet ap tam thu lon hon 140"), ("gt", 140.0))
        self.assertEqual(extract_value_filter("SpO2 dưới 95"), ("lt", 95.0))
        self.assertEqual(extract_value_filter("cholesterol từ 240 trở lên"), ("ge", 240.0))
        self.assertEqual(extract_value_filter("TSH không quá 4"), ("le", 4.0))

    def test_counts_and_ages_are_not_thresholds(self):
        self.assertIsNone(extract_value_filter("bệnh nhân trên 60 tuổi"))
        self.assertIsNone(extract_value_filter("khám nhiều hơn 3 lần"))
        self.assertIsNone(extract_value_filter("Chỉ số HbA1c của BN2026-00001"))

    def test_normalize_and_compare(self):
        self.assertEqual(normalize_value_op(">"), "gt")
        self.assertEqual(normalize_value_op("lớn hơn"), "gt")
        self.assertEqual(normalize_value_op("<="), "le")
        self.assertIsNone(normalize_value_op("khoảng"))
        self.assertTrue(compare(7.1, "gt", 7))
        self.assertFalse(compare(7.0, "gt", 7))
        self.assertTrue(compare(7.0, "ge", 7))


class EmergencyTests(unittest.TestCase):
    def test_detects_medical_emergencies(self):
        for message in (
            "Tôi đang đau ngực, khó thở",
            "toi dang dau nguc kho tho",
            "bố tôi vừa ngất xỉu",
            "con tôi đang co giật",
            "mẹ tôi méo miệng, nói ngọng đột ngột",
            "tôi uống nhầm thuốc của mẹ",
        ):
            self.assertEqual(detect_emergency(message), EMERGENCY_MEDICAL, message)

    def test_detects_self_harm(self):
        for message in ("Tôi muốn tự tử", "toi muon tu tu", "tôi không muốn sống nữa"):
            self.assertEqual(detect_emergency(message), EMERGENCY_SELF_HARM, message)

    def test_record_and_knowledge_questions_are_not_emergencies(self):
        for message in (
            "bệnh nhân BN2026-00001 có ghi nhận khó thở không",
            "Đau ngực là triệu chứng của bệnh gì?",
            "Bệnh nhân nào khám vì khó thở?",
            "bệnh nhân có tiền sử đột quỵ không",
            "Tôi đau đầu và sốt, hãy chẩn đoán tôi bị bệnh gì",
            "Có nên ngưng thuốc này không?",
        ):
            self.assertIsNone(detect_emergency(message), message)

    def test_unaccented_homographs_do_not_trigger(self):
        # "từ từ"/"tự tử", "xíu"/"xỉu", "ngắt"/"ngất" trùng nhau khi bỏ dấu.
        for message in ("uống thuốc từ từ thôi", "đợi tôi một xíu", "tôi bị ngắt kết nối", "bố tôi bị đột quỵ năm ngoái"):
            self.assertIsNone(detect_emergency(message), message)


class ObservationTypeTests(unittest.TestCase):
    def test_new_types(self):
        self.assertEqual(infer_observation_type("nhiệt độ cơ thể"), "body_temperature")
        self.assertEqual(infer_observation_type("chỉ số TSH"), "tsh")
        self.assertEqual(infer_observation_type("hemoglobin của tôi"), "hemoglobin")
        self.assertEqual(infer_observation_type("Hemoglobin A1c"), "hba1c")

    def test_short_codes_need_word_boundaries(self):
        # "ast"/"alt" không được bắt nhầm trong "last", "salt".
        self.assertIsNone(infer_observation_type("show the last salt intake"))
        self.assertEqual(infer_observation_type("chỉ số AST"), "ast")

    def test_canonical_type_and_loinc_param(self):
        self.assertEqual(canonical_observation_type("huyết áp"), "blood_pressure")
        self.assertEqual(canonical_observation_type("HbA1c"), "hba1c")
        self.assertEqual(canonical_observation_type("body temperature"), "body_temperature")
        self.assertEqual(loinc_code_param("hba1c"), "http://loinc.org|4548-4,http://loinc.org|17856-6")
        self.assertIsNone(loinc_code_param("xet nghiem la"))

    def test_hba1c_does_not_match_plain_hemoglobin(self):
        hemoglobin = {"code": "Hemoglobin", "code_detail": {"coding": [{"code": "718-7"}]}}
        hba1c = {"code": "HbA1c", "code_detail": {"coding": [{"code": "4548-4"}]}}
        self.assertFalse(observation_matches_type(hemoglobin, "hba1c"))
        self.assertTrue(observation_matches_type(hba1c, "hba1c"))
        self.assertTrue(observation_matches_type(hemoglobin, "hemoglobin"))
        self.assertFalse(observation_matches_type(hba1c, "hemoglobin"))


class CacheSignatureTests(unittest.TestCase):
    def _sig(self, message):
        return query_signature(message, today=TODAY)

    def test_signature_separates_what_embeddings_cannot(self):
        # Các cặp này giống nhau 0.97–0.99 về embedding (đo bằng tests/eval/run_cache_pairs.py).
        for a, b in (
            ("Bệnh nhân nào có HbA1c > 7?", "Bệnh nhân nào có HbA1c > 8?"),
            ("Bệnh nhân nào có SpO2 dưới 95?", "Bệnh nhân nào có SpO2 trên 95?"),
            ("HbA1c năm 2025 của bệnh nhân này", "HbA1c năm 2026 của bệnh nhân này"),
            ("Huyết áp tâm thu của bệnh nhân này", "Huyết áp tâm trương của bệnh nhân này"),
            ("Lần khám gần nhất của bệnh nhân này", "Lần khám đầu tiên của bệnh nhân này"),
            ("3 lần khám gần nhất của bệnh nhân này", "Lần khám gần nhất của bệnh nhân này"),
            ("HbA1c của bệnh nhân này", "HbA1c của bệnh nhân này có ý nghĩa gì?"),
        ):
            self.assertNotEqual(self._sig(a), self._sig(b), (a, b))

    def test_signature_keeps_paraphrases_together(self):
        self.assertEqual(self._sig("Bệnh nhân này đang dùng thuốc gì?"), self._sig("benh nhan nay dang dung thuoc gi"))
        self.assertEqual(self._sig("Bệnh nhân nào có HbA1c > 7?"), self._sig("Những bệnh nhân nào có HbA1c lớn hơn 7?"))

    def test_plan_signature_ignores_refillable_values(self):
        self.assertEqual(plan_signature("Bệnh nhân nào có HbA1c > 7?"), plan_signature("Bệnh nhân nào có HbA1c > 8?"))
        self.assertEqual(plan_signature("HbA1c năm 2025"), plan_signature("HbA1c năm 2026"))
        self.assertNotEqual(plan_signature("Huyết áp của bệnh nhân này"), plan_signature("Đường huyết của bệnh nhân này"))

    def test_embedding_text_drops_accents_and_punctuation(self):
        self.assertEqual(embedding_text("Bệnh nhân này đang dùng thuốc gì?"), "benh nhan nay dang dung thuoc gi")
        self.assertEqual(embedding_text("  HbA1c,  của tôi! "), "hba1c cua toi")


if __name__ == "__main__":
    unittest.main()
