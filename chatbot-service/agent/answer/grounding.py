"""Kiểm tra tất định câu trả lời LLM có bám evidence không (thuần, không I/O).

Không cố hiểu nghĩa câu trả lời — chỉ bắt hai loại bịa nguy hiểm nhất và dễ kiểm:

- **Định danh**: mã bệnh nhân (``BN2026-00001``) và tham chiếu resource (``Patient/x``,
  ``Observation/y``) phải có trong evidence, câu hỏi hoặc là bệnh nhân đã chốt.
- **Con số**: số có phần thập phân hoặc ≥ 10 phải khớp một số trong evidence (cho phép
  LLM làm tròn tới số chữ số thập phân nó viết). Số nguyên nhỏ (đếm "3 thuốc", "2 lần")
  bỏ qua vì quá dễ báo nhầm.

Chuẩn hoá trước khi so: ``7,2`` ↔ ``7.2``, ngày ``dd/mm/yyyy`` ↔ ISO, tuổi tính từ
``birth_date``.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date
from typing import Any


_PATIENT_CODE_RE = re.compile(r"\bBN\d{4}-\d{5}\b", re.IGNORECASE)
_RESOURCE_REF_RE = re.compile(
    r"\b(Patient|Observation|Encounter|Condition|MedicationRequest|Medication|Practitioner|Organization)"
    # id kết thúc bằng chữ/số: dấu chấm cuối câu không thuộc về id.
    r"/([A-Za-z0-9](?:[A-Za-z0-9\-.]{0,62}[A-Za-z0-9])?)"
)
_VN_DATE_RE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b")
_ISO_DATE_RE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})")
_TIME_RE = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\b")
_NUMBER_RE = re.compile(r"(?<![\w.,])\d+(?:[.,]\d+)*")

# Số nguyên nhỏ hơn ngưỡng này không kiểm (đếm số mục, thứ tự...).
MIN_CHECKED_INTEGER = 10


@dataclass(frozen=True)
class GroundingResult:
    passed: bool
    # [{kind, value}] — kind: patient_id | resource_ref | date | number.
    violations: list[dict[str, str]] = field(default_factory=list)


def check_answer(
    answer: str,
    *,
    evidence: list[dict[str, Any]],
    question: str = "",
    patient_id: str | None = None,
    external_knowledge: list[dict[str, Any]] | None = None,
    template_answer: str = "",
) -> GroundingResult:
    """Đối chiếu ``answer`` với nguồn dữ kiện được phép dùng.

    ``template_answer`` là câu template do tool dựng từ chính evidence — tính là nguồn
    hợp lệ (ví dụ số đếm "Tìm thấy 12 bệnh nhân").
    """
    sources = _source_text(evidence, external_knowledge, question, patient_id, template_answer)
    haystack = sources.lower()
    violations: list[dict[str, str]] = []
    text = answer or ""

    for match in _PATIENT_CODE_RE.finditer(text):
        if match.group(0).lower() not in haystack:
            violations.append({"kind": "patient_id", "value": match.group(0)})
    for match in _RESOURCE_REF_RE.finditer(text):
        # "Patient/BN2026-00001" khớp nếu evidence chứa cả tham chiếu hoặc riêng id.
        if match.group(0).lower() not in haystack and match.group(2).lower() not in haystack:
            violations.append({"kind": "resource_ref", "value": match.group(0)})
    text = _RESOURCE_REF_RE.sub(" ", _PATIENT_CODE_RE.sub(" ", text))

    source_dates = _dates_in(sources)
    for match in _VN_DATE_RE.finditer(text):
        day, month, year = (int(part) for part in match.groups())
        if (year, month, day) not in source_dates:
            violations.append({"kind": "date", "value": match.group(0)})
    for match in _ISO_DATE_RE.finditer(text):
        if tuple(int(part) for part in match.groups()) not in source_dates:
            violations.append({"kind": "date", "value": match.group(0)})
    text = _TIME_RE.sub(" ", _ISO_DATE_RE.sub(" ", _VN_DATE_RE.sub(" ", text)))

    source_numbers = _numbers_in(_ISO_DATE_RE.sub(" ", sources)) | _date_parts(source_dates)
    source_numbers |= _ages_from_birth_dates(evidence)
    source_numbers.add(float(len(evidence)))
    for match in _NUMBER_RE.finditer(text):
        token = match.group(0)
        if not _number_is_grounded(token, source_numbers):
            violations.append({"kind": "number", "value": token})

    return GroundingResult(passed=not violations, violations=violations)


def _source_text(
    evidence: list[dict[str, Any]],
    external_knowledge: list[dict[str, Any]] | None,
    question: str,
    patient_id: str | None,
    template_answer: str,
) -> str:
    parts = [
        json.dumps(evidence, ensure_ascii=False, default=str),
        json.dumps(external_knowledge or [], ensure_ascii=False, default=str),
        question or "",
        patient_id or "",
        template_answer or "",
    ]
    return "\n".join(parts)


def _dates_in(text: str) -> set[tuple[int, int, int]]:
    dates = {tuple(int(part) for part in match.groups()) for match in _ISO_DATE_RE.finditer(text)}
    for match in _VN_DATE_RE.finditer(text):
        day, month, year = (int(part) for part in match.groups())
        dates.add((year, month, day))
    return dates  # type: ignore[return-value]


def _date_parts(dates: set[tuple[int, int, int]]) -> set[float]:
    # "năm 2024", "tháng 3" là một phần của ngày có trong evidence.
    return {float(part) for value in dates for part in value}


def _ages_from_birth_dates(evidence: list[dict[str, Any]]) -> set[float]:
    """Tuổi suy ra từ birth_date: LLM được phép nói "45 tuổi" khi evidence có ngày sinh."""
    ages: set[float] = set()
    today = date.today()
    for item in evidence:
        data = item.get("data") if isinstance(item, dict) else None
        raw = data.get("birth_date") if isinstance(data, dict) else None
        match = _ISO_DATE_RE.match(raw) if isinstance(raw, str) else None
        if not match:
            continue
        year, month, day = (int(part) for part in match.groups())
        age = today.year - year - ((today.month, today.day) < (month, day))
        ages.update({float(age), float(age - 1), float(age + 1)})
    return ages


def _numbers_in(text: str) -> set[float]:
    numbers: set[float] = set()
    for match in _NUMBER_RE.finditer(text):
        numbers.update(value for value, _ in _candidates(match.group(0)))
    # Lượt quét thô cho số dính chữ như "120mmHg" hoặc nằm trong mã.
    for raw in re.findall(r"\d+(?:\.\d+)?", text):
        numbers.add(float(raw))
    return numbers


def _candidates(token: str) -> set[tuple[float, int]]:
    """Các cách đọc một số kèm số chữ số thập phân.

    "7,2" → (7.2, 1); "1.200" → {(1.2, 3), (1200, 0)}; "1,234.5" → (1234.5, 1).
    """
    values: set[tuple[float, int]] = set()
    for decimal_sep, group_sep in ((".", ","), (",", ".")):
        if token.count(decimal_sep) > 1:
            continue
        normalized = token.replace(group_sep, "")
        whole, _, fraction = normalized.partition(decimal_sep)
        try:
            values.add((float(f"{whole}.{fraction}" if fraction else whole), len(fraction)))
        except ValueError:
            continue
    return values


def _number_is_grounded(token: str, source_numbers: set[float]) -> bool:
    candidates = _candidates(token)
    if not candidates:
        return True
    if all(value.is_integer() and value < MIN_CHECKED_INTEGER for value, _ in candidates):
        return True
    for value, decimals in candidates:
        # Cho phép LLM làm tròn tới số chữ số thập phân nó viết: 7.25 → "7,3", 120.4 → "120".
        tolerance = 0.5 * 10 ** (-decimals) + 1e-9
        if any(abs(source - value) <= tolerance for source in source_numbers):
            return True
    return False
