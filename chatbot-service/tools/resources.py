"""Tool mức server/resource: trạng thái HAPI FHIR và đọc một resource theo mã."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient, FhirClientError, FhirNotFoundError
from fhir.normalizer import (
    normalize_capability_statement,
    normalize_condition,
    normalize_encounter,
    normalize_medication_request,
    normalize_observation,
    normalize_patient,
)
from tools.formatting import evidence_item, format_resource_summary, value_or_unknown


# Resource type mà get_resource_by_id được phép đọc; chặn resource ngoài phạm vi sản
# phẩm (vd AuditEvent, Practitioner nội bộ).
SUPPORTED_RESOURCE_NORMALIZERS = {
    "Patient": normalize_patient,
    "Encounter": normalize_encounter,
    "Observation": normalize_observation,
    "Condition": normalize_condition,
    "MedicationRequest": normalize_medication_request,
}
SUPPORTED_RESOURCE_TYPES: tuple[str, ...] = tuple(SUPPORTED_RESOURCE_NORMALIZERS)


async def fhir_status(client: FhirClient) -> dict[str, Any]:
    try:
        status_info = normalize_capability_statement(await client.get_metadata())
    except FhirClientError as exc:
        # Server sập chính là câu trả lời của tool này, không phải lỗi tool.
        return {
            "answer": f"HAPI FHIR Server hiện không khả dụng: {exc.user_message}",
            "intent": "fhir_status",
            "patient_id": None,
            "evidence": [],
        }
    software = value_or_unknown(status_info.get("software"))
    fhir_version = value_or_unknown(status_info.get("fhir_version"))
    return {
        "answer": (
            "HAPI FHIR Server đang hoạt động bình thường. "
            f"Phần mềm: {software}; phiên bản FHIR: {fhir_version}."
        ),
        "intent": "fhir_status",
        "patient_id": None,
        "evidence": [evidence_item("CapabilityStatement", None, "Trạng thái HAPI FHIR Server", status_info)],
    }


async def get_resource_by_id(
    client: FhirClient,
    *,
    resource_type: str,
    resource_id: str,
) -> dict[str, Any]:
    canonical_types = {name.lower(): name for name in SUPPORTED_RESOURCE_TYPES}
    canonical_type = canonical_types.get((resource_type or "").strip().lower())
    resource_id = (resource_id or "").strip()
    if not canonical_type or not resource_id:
        return {
            "answer": (
                "Vui lòng cung cấp loại resource và mã resource cần tra cứu. "
                f"Các loại được hỗ trợ: {', '.join(SUPPORTED_RESOURCE_TYPES)}."
            ),
            "intent": "resource",
            "patient_id": None,
            "evidence": [],
        }

    try:
        resource = SUPPORTED_RESOURCE_NORMALIZERS[canonical_type](
            await client.get_resource(canonical_type, resource_id)
        )
    except FhirNotFoundError:
        return {
            "answer": f"Không tìm thấy {canonical_type}/{resource_id} trong FHIR Server.",
            "intent": "resource",
            "patient_id": None,
            "evidence": [],
        }

    summary = format_resource_summary(canonical_type, resource)
    return {
        "answer": f"Theo dữ liệu FHIR hiện có, {canonical_type}/{resource_id}: {summary}.",
        "intent": "resource",
        "patient_id": None,
        "evidence": [evidence_item(canonical_type, resource.get("id") or resource_id, summary, resource)],
    }
