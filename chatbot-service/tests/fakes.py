"""Test double dùng chung: FHIR client trả dữ liệu thô tối thiểu cho normalizer thật.

Mặc định giữ đúng hành vi cũ (1 Condition, 1 MedicationRequest, không Observation/Encounter).
Truyền ``observations`` / ``encounters`` / ``medications`` để có dữ liệu riêng; khi đó client
giả áp đúng các tham số lọc FHIR mà tool gửi (``code``, ``date``, ``encounter``...).
"""

from __future__ import annotations

from typing import Any


LOINC = "http://loinc.org"


def patient_resource(patient_id: str, name: str = "Van A") -> dict[str, Any]:
    return {
        "resourceType": "Patient",
        "id": patient_id,
        "name": [{"family": "Nguyen", "given": [name]}],
        "gender": "male",
        "birthDate": "2003-01-01",
        "telecom": [{"system": "phone", "value": "0900000001"}],
    }


def observation_resource(
    obs_id: str,
    patient_id: str,
    *,
    code: str,
    display: str,
    value: float | None = None,
    unit: str = "",
    date: str = "2026-01-01",
    components: list[tuple[str, str, float, str]] | None = None,
    encounter_id: str | None = None,
) -> dict[str, Any]:
    resource: dict[str, Any] = {
        "resourceType": "Observation",
        "id": obs_id,
        "status": "final",
        "code": {"coding": [{"system": LOINC, "code": code, "display": display}], "text": display},
        "subject": {"reference": f"Patient/{patient_id}"},
        "effectiveDateTime": f"{date}T08:00:00+07:00",
    }
    if value is not None:
        resource["valueQuantity"] = {"value": value, "unit": unit}
    if components:
        resource["component"] = [
            {
                "code": {"coding": [{"system": LOINC, "code": c_code, "display": c_display}]},
                "valueQuantity": {"value": c_value, "unit": c_unit},
            }
            for c_code, c_display, c_value, c_unit in components
        ]
    if encounter_id:
        resource["encounter"] = {"reference": f"Encounter/{encounter_id}"}
    return resource


def bp_observation(obs_id: str, patient_id: str, systolic: float, diastolic: float, date: str) -> dict[str, Any]:
    return observation_resource(
        obs_id,
        patient_id,
        code="85354-9",
        display="Blood pressure panel",
        date=date,
        components=[
            ("8480-6", "Systolic blood pressure", systolic, "mmHg"),
            ("8462-4", "Diastolic blood pressure", diastolic, "mmHg"),
        ],
    )


def encounter_resource(enc_id: str, patient_id: str, start: str) -> dict[str, Any]:
    return {
        "resourceType": "Encounter",
        "id": enc_id,
        "status": "finished",
        "subject": {"reference": f"Patient/{patient_id}"},
        "period": {"start": f"{start}T08:00:00+07:00"},
    }


def medication_resource(med_id: str, patient_id: str, text: str, encounter_id: str | None = None) -> dict[str, Any]:
    resource: dict[str, Any] = {
        "resourceType": "MedicationRequest",
        "id": med_id,
        "medicationCodeableConcept": {"text": text},
        "subject": {"reference": f"Patient/{patient_id}"},
    }
    if encounter_id:
        resource["encounter"] = {"reference": f"Encounter/{encounter_id}"}
    return resource


class FakeFhirClient:
    def __init__(
        self,
        *,
        patients: list[dict[str, Any]] | None = None,
        observations: list[dict[str, Any]] | None = None,
        encounters: list[dict[str, Any]] | None = None,
        medications: list[dict[str, Any]] | None = None,
        patients_total: int | None = None,
    ) -> None:
        self.patients = patients if patients is not None else [patient_resource("BN2026-00001")]
        self.observations = observations or []
        self.encounters = encounters
        self.medications = medications
        self.patients_total = patients_total
        self.calls: list[str] = []
        # (resource_type, params) của mọi lần tìm kiếm — để test kiểm tham số lọc FHIR.
        self.requests: list[tuple[str, dict[str, Any]]] = []

    async def get_metadata(self) -> dict[str, Any]:
        self.calls.append("metadata")
        return {"resourceType": "CapabilityStatement", "fhirVersion": "4.0.1", "software": {"name": "HAPI FHIR"}}

    async def get_patient(self, patient_id: str) -> dict[str, Any]:
        self.calls.append(f"get_patient:{patient_id}")
        return patient_resource(patient_id)

    async def get_resource(self, resource_type: str, resource_id: str) -> dict[str, Any]:
        self.calls.append(f"get_resource:{resource_type}/{resource_id}")
        return {
            "resourceType": resource_type,
            "id": resource_id,
            "code": {"text": "HbA1c"},
            "valueQuantity": {"value": 7.2, "unit": "%"},
            "subject": {"reference": "Patient/BN2026-00001"},
        }

    async def search_patients_flexible(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append("search_patients")
        return {"entry": [{"resource": patient} for patient in self.patients]}

    async def search_patients(self, count: int = 20) -> dict[str, Any]:
        bundle: dict[str, Any] = {"entry": [{"resource": patient} for patient in self.patients[:count]]}
        if self.patients_total is not None:
            bundle["total"] = self.patients_total
        return bundle

    async def search_patient_resources(
        self,
        resource_type: str,
        patient_id: str,
        count: int = 10,
        sort: str | None = None,
        params: dict[str, Any] | None = None,
    ):
        self.calls.append(f"{resource_type}:{patient_id}")
        self.requests.append((resource_type, dict(params or {})))
        params = params or {}
        if resource_type == "Observation":
            items = [o for o in self.observations if _subject(o) == patient_id and _matches(o, params, "date")]
            return _bundle(_sorted(items, sort)[:count])
        if resource_type == "Encounter" and self.encounters is not None:
            items = [e for e in self.encounters if _subject(e) == patient_id and _matches(e, params, "date")]
            return _bundle(_sorted(items, sort)[:count])
        if resource_type == "MedicationRequest" and self.medications is not None:
            items = [m for m in self.medications if _subject(m) == patient_id and _matches(m, params, "authoredon")]
            return _bundle(items[:count])
        if resource_type == "Condition":
            return {
                "entry": [
                    {
                        "resource": {
                            "resourceType": "Condition",
                            "id": "COND-1",
                            "code": {"text": "Đái tháo đường type 2"},
                            "subject": {"reference": f"Patient/{patient_id}"},
                        }
                    }
                ]
            }
        if resource_type == "MedicationRequest":
            return {
                "entry": [
                    {
                        "resource": {
                            "resourceType": "MedicationRequest",
                            "id": "MED-1",
                            "medicationCodeableConcept": {"text": "Metformin"},
                            "subject": {"reference": f"Patient/{patient_id}"},
                        }
                    }
                ]
            }
        return {"entry": []}

    async def search_resources(self, resource_type: str, params: dict[str, Any], *, max_results: int = 1000):
        self.calls.append(f"search_resources:{resource_type}")
        self.requests.append((resource_type, dict(params)))
        if resource_type != "Observation":
            return {"entry": [], "total": 0, "truncated": False}
        items = _sorted([o for o in self.observations if _matches(o, params, "date")], params.get("_sort"))
        entries = [{"resource": item, "search": {"mode": "match"}} for item in items[:max_results]]
        if params.get("_include") == "Observation:subject":
            wanted = {_subject(item) for item in items[:max_results]}
            entries += [
                {"resource": patient, "search": {"mode": "include"}}
                for patient in self.patients
                if patient["id"] in wanted
            ]
        return {"entry": entries, "total": len(items), "truncated": len(items) > max_results}


def _bundle(resources: list[dict[str, Any]]) -> dict[str, Any]:
    return {"entry": [{"resource": resource} for resource in resources]}


def _subject(resource: dict[str, Any]) -> str:
    return str((resource.get("subject") or {}).get("reference", "")).removeprefix("Patient/")


def _when(resource: dict[str, Any]) -> str:
    return str(
        resource.get("effectiveDateTime")
        or (resource.get("period") or {}).get("start")
        or resource.get("authoredOn")
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
    for bound in [bounds] if isinstance(bounds, str) else bounds or []:
        when = _when(resource)
        if bound.startswith("ge") and when < bound[2:]:
            return False
        if bound.startswith("le") and when > bound[2:]:
            return False
    return True
