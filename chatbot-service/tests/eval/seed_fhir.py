"""FHIR server giả trong bộ nhớ, nạp từ file seed — cho ``run_eval --offline``.

Hỗ trợ đúng những gì tool gửi lên HAPI: tìm theo bệnh nhân, ``code`` (LOINC), khoảng
ngày (``ge``/``le``), ``encounter``, ``_sort=-date``, ``_include=Observation:subject`` và
tìm bệnh nhân theo tên / SĐT / ngày sinh. Đủ để chạy cả bộ eval mà không cần HAPI.
"""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from fhir.client import FhirNotFoundError
from nlp.text import normalize_text


SEED_PATH = Path(__file__).resolve().parents[3] / "infra" / "hapi-fhir" / "seed" / "synthetic-fhir-transaction-bundle.json"
_DATE_PARAMS = {"Observation": "date", "Encounter": "date", "Condition": "recorded-date", "MedicationRequest": "authoredon"}


class SeedFhirClient:
    def __init__(self, seed_path: Path = SEED_PATH) -> None:
        bundle = json.loads(seed_path.read_text(encoding="utf-8"))
        self.resources: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for entry in bundle.get("entry", []):
            resource = entry["resource"]
            self.resources[resource["resourceType"]].append(resource)

    async def get_metadata(self) -> dict[str, Any]:
        return {"resourceType": "CapabilityStatement", "fhirVersion": "4.0.1", "software": {"name": "seed (offline)"}}

    async def get_patient(self, patient_id: str) -> dict[str, Any]:
        return await self.get_resource("Patient", patient_id)

    async def get_resource(self, resource_type: str, resource_id: str) -> dict[str, Any]:
        for resource in self.resources.get(resource_type, []):
            if resource.get("id") == resource_id:
                return resource
        raise FhirNotFoundError("Không tìm thấy tài nguyên FHIR được yêu cầu.")

    async def search_patients(self, *, count: int = 20, **criteria: Any) -> dict[str, Any]:
        patients = [p for p in self.resources["Patient"] if _patient_matches(p, **criteria)]
        return {"entry": [{"resource": p} for p in patients[:count]], "total": len(patients)}

    async def search_patients_flexible(self, *, count: int = 20, **criteria: Any) -> dict[str, Any]:
        return await self.search_patients(count=count, **criteria)

    async def search_patient_resources(
        self,
        resource_type: str,
        patient_id: str,
        *,
        count: int = 10,
        sort: str | None = None,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        items = [
            r
            for r in self.resources.get(resource_type, [])
            if _subject(r) == patient_id and _matches(r, params or {}, _DATE_PARAMS.get(resource_type, "date"))
        ]
        return {"entry": [{"resource": r} for r in _sorted(items, sort)[:count]]}

    async def search_resources(self, resource_type: str, params: dict[str, Any], *, max_results: int = 1000) -> dict[str, Any]:
        items = [
            r
            for r in self.resources.get(resource_type, [])
            if _matches(r, params, _DATE_PARAMS.get(resource_type, "date"))
        ]
        items = _sorted(items, params.get("_sort"))
        entries = [{"resource": r, "search": {"mode": "match"}} for r in items[:max_results]]
        if params.get("_include") == f"{resource_type}:subject":
            wanted = {_subject(r) for r in items[:max_results]}
            entries += [
                {"resource": p, "search": {"mode": "include"}} for p in self.resources["Patient"] if p["id"] in wanted
            ]
        return {"entry": entries, "total": len(items), "truncated": len(items) > max_results}


def _patient_matches(patient: dict[str, Any], *, name=None, phone=None, birth_date=None, identifier=None) -> bool:
    if name:
        parts = normalize_text(" ".join([n.get("family", "") + " " + " ".join(n.get("given", [])) for n in patient.get("name", [])])).split()
        # Như HAPI: mỗi từ trong tên tìm khớp tiền tố của một phần tên.
        if not all(any(part.startswith(token) for part in parts) for token in normalize_text(name).split()):
            return False
    if phone and phone not in [t.get("value") for t in patient.get("telecom", [])]:
        return False
    if birth_date and patient.get("birthDate") != birth_date:
        return False
    if identifier and identifier not in [i.get("value") for i in patient.get("identifier", [])]:
        return False
    return True


def _subject(resource: dict[str, Any]) -> str:
    return str((resource.get("subject") or {}).get("reference", "")).removeprefix("Patient/")


def _when(resource: dict[str, Any]) -> str:
    return str(
        resource.get("effectiveDateTime")
        or (resource.get("period") or {}).get("start")
        or resource.get("authoredOn")
        or resource.get("recordedDate")
        or ""
    )[:10]


def _sorted(items: list[dict[str, Any]], sort: str | None) -> list[dict[str, Any]]:
    if sort == "-date":
        return sorted(items, key=_when, reverse=True)
    if sort == "date":
        return sorted(items, key=_when)
    return items


def _matches(resource: dict[str, Any], params: dict[str, Any], date_param: str) -> bool:
    code = params.get("code")
    if code:
        wanted = {part.split("|")[-1] for part in str(code).split(",")}
        codes = {c.get("code") for c in (resource.get("code") or {}).get("coding", [])}
        if not wanted & codes:
            return False
    encounter = params.get("encounter")
    if encounter and (resource.get("encounter") or {}).get("reference") != encounter:
        return False
    bounds = params.get(date_param)
    when = _when(resource)
    for bound in [bounds] if isinstance(bounds, str) else (bounds or []):
        if bound.startswith("ge") and when < bound[2:]:
            return False
        if bound.startswith("le") and when > bound[2:]:
            return False
    return True
