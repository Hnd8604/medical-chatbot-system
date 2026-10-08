"""Test double dùng chung: FHIR client trả dữ liệu thô tối thiểu cho normalizer thật."""

from __future__ import annotations

from typing import Any


def patient_resource(patient_id: str, name: str = "Van A") -> dict[str, Any]:
    return {
        "resourceType": "Patient",
        "id": patient_id,
        "name": [{"family": "Nguyen", "given": [name]}],
        "gender": "male",
        "birthDate": "2003-01-01",
        "telecom": [{"system": "phone", "value": "0900000001"}],
    }


class FakeFhirClient:
    def __init__(self, *, patients: list[dict[str, Any]] | None = None) -> None:
        self.patients = patients if patients is not None else [patient_resource("BN2026-00001")]
        self.calls: list[str] = []

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
        return {"entry": [{"resource": patient} for patient in self.patients]}

    async def search_patient_resources(self, resource_type: str, patient_id: str, count: int = 10, sort: str | None = None):
        self.calls.append(f"{resource_type}:{patient_id}")
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
