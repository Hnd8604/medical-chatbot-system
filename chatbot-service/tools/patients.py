"""Tool về bệnh nhân: tìm kiếm theo tiêu chí và đọc hồ sơ theo patient_id.

Mỗi tool trả payload ``{answer, intent, patient_id, evidence, ...}``: ``answer`` là câu
template tiếng Việt, ``evidence`` là dữ liệu đã normalize mà answer LLM được phép dùng.
Lỗi FHIR được để nguyên cho executor quy thành ``tool_error``.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from fhir.client import FhirClient
from fhir.normalizer import normalize_patient, normalize_patient_bundle
from tools.formatting import (
    evidence_item,
    format_patient_summary,
    format_search_criteria,
    gender_vi,
    value_or_unknown,
)


T = TypeVar("T")


async def search_patients(
    client: FhirClient,
    *,
    name: str | None = None,
    phone: str | None = None,
    birth_date: str | None = None,
    identifier: str | None = None,
    limit: int = 3,
) -> dict[str, Any]:
    criteria = {"name": name, "phone": phone, "birth_date": birth_date, "identifier": identifier}
    has_criteria = any(criteria.values())
    patients = normalize_patient_bundle(
        await client.search_patients_flexible(count=limit, **criteria)
    )

    if not patients:
        answer = "Không tìm thấy bệnh nhân nào trong FHIR Server."
    else:
        summary = "; ".join(format_patient_summary(patient) for patient in patients)
        criteria_text = format_search_criteria(criteria)
        criteria_suffix = f" phù hợp với {criteria_text}" if criteria_text else ""
        answer = (
            f"Theo dữ liệu FHIR hiện có, hệ thống tìm thấy {len(patients)} bệnh nhân"
            f"{criteria_suffix}: {summary}."
        )
        if has_criteria and len(patients) > 1:
            answer = (
                "Tìm thấy nhiều bệnh nhân phù hợp. "
                "Vui lòng chọn đúng bệnh nhân hoặc cung cấp thêm ngày sinh, số điện thoại, mã định danh: "
                f"{summary}."
            )

    # Tìm theo tiêu chí và khớp đúng 1 bệnh nhân → coi như đã chốt được bệnh nhân: trả
    # patient_id để frontend đổ panel hồ sơ và session memory ghi active_patient_id.
    resolved_patient_id = None
    if has_criteria and len(patients) == 1:
        candidate = patients[0].get("id")
        resolved_patient_id = candidate if isinstance(candidate, str) and candidate else None

    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "list_patients",
        "patient_id": resolved_patient_id,
        "evidence": [
            evidence_item("Patient", patient.get("id"), format_patient_summary(patient), patient)
            for patient in patients
        ],
    }
    if has_criteria:
        payload["patient_search"] = criteria
    if has_criteria and len(patients) > 1:
        payload["needs_patient_selection"] = True
        payload["patient_candidates"] = [_patient_candidate(patient) for patient in patients]
    return payload


async def get_patient(client: FhirClient, *, patient_id: str) -> dict[str, Any]:
    patient = normalize_patient(await client.get_patient(patient_id))
    phone = value_or_unknown(patient.get("phone"))
    name = value_or_unknown(patient.get("name"))
    gender = gender_vi(patient.get("gender"))
    birth_date = value_or_unknown(patient.get("birth_date"))
    answer = (
        f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có họ tên {name}, "
        f"giới tính {gender}, ngày sinh {birth_date}, số điện thoại {phone}."
    )
    summary = (
        f"Họ tên: {name}; "
        f"giới tính: {gender}; "
        f"ngày sinh: {birth_date}; "
        f"số điện thoại: {phone}"
    )
    return {
        "answer": answer,
        "intent": "patient",
        "patient_id": patient_id,
        "evidence": [evidence_item("Patient", patient.get("id"), summary, patient)],
    }


# Tool "tất cả bệnh nhân" theo từng người (không lọc theo loại chỉ số) đọc trang đầu này.
ALL_PATIENTS_PAGE_SIZE = 20
PER_PATIENT_CONCURRENCY = 8


async def fetch_patients(client: FhirClient, limit: int = ALL_PATIENTS_PAGE_SIZE) -> list[dict[str, Any]]:
    """Danh sách bệnh nhân cho các tool đọc dữ liệu của nhiều bệnh nhân."""
    patients, _ = await fetch_patient_page(client, limit)
    return patients


async def fetch_patient_page(
    client: FhirClient, limit: int = ALL_PATIENTS_PAGE_SIZE
) -> tuple[list[dict[str, Any]], int | None]:
    """Trang đầu danh sách bệnh nhân kèm tổng số (nếu FHIR server báo ``total``)."""
    bundle = await client.search_patients(count=limit)
    total = bundle.get("total")
    return normalize_patient_bundle(bundle), total if isinstance(total, int) else None


async def map_patients(
    patients: list[dict[str, Any]],
    fetch: Callable[[dict[str, Any]], Awaitable[T]],
    *,
    concurrency: int = PER_PATIENT_CONCURRENCY,
) -> list[T]:
    """Gọi ``fetch`` cho từng bệnh nhân, song song có giới hạn; giữ đúng thứ tự."""
    semaphore = asyncio.Semaphore(concurrency)

    async def run(patient: dict[str, Any]) -> T:
        async with semaphore:
            return await fetch(patient)

    return list(await asyncio.gather(*(run(patient) for patient in patients)))


def coverage_note(listed: int, total: int | None) -> str:
    """Câu báo rõ khi mới duyệt một phần danh sách bệnh nhân (thay vì im lặng trả thiếu)."""
    if total and total > listed:
        return (
            f" Lưu ý: mới duyệt {listed}/{total} bệnh nhân đầu tiên; hãy hỏi theo một loại chỉ số "
            "hoặc một bệnh nhân cụ thể để xem đầy đủ."
        )
    return ""


def with_patient_context(resource: dict[str, Any], patient: dict[str, Any]) -> dict[str, Any]:
    """Gắn danh tính bệnh nhân vào resource khi evidence trộn nhiều bệnh nhân."""
    return {
        **resource,
        "patient": {
            "id": patient.get("id"),
            "name": patient.get("name"),
            "gender": patient.get("gender"),
            "birth_date": patient.get("birth_date"),
            "phone": patient.get("phone"),
        },
    }


def _patient_candidate(patient: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": patient.get("id"),
        "name": patient.get("name"),
        "gender": patient.get("gender"),
        "birth_date": patient.get("birth_date"),
        "phone": patient.get("phone"),
        "identifier": patient.get("identifier"),
    }
