#!/usr/bin/env python
"""Check HAPI FHIR connectivity using only public FHIR REST endpoints."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


FHIR_BASE_URL = os.getenv("FHIR_BASE_URL", "http://localhost:8080/fhir").rstrip("/")
DEMO_PATIENT_ID = os.getenv("DEMO_PATIENT_ID", "BN2026-00001")


def get_json(path: str, query: dict[str, str] | None = None) -> dict:
    url = f"{FHIR_BASE_URL}/{path.lstrip('/')}"
    if query:
        url = f"{url}?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(url, headers={"Accept": "application/fhir+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def bundle_count(bundle: dict) -> int:
    if bundle.get("resourceType") != "Bundle":
        raise ValueError(f"Expected Bundle, got {bundle.get('resourceType')!r}")
    return int(bundle.get("total", len(bundle.get("entry", []))))


def main() -> int:
    checks: list[tuple[str, str]] = []

    try:
        metadata = get_json("metadata")
        if metadata.get("resourceType") != "CapabilityStatement":
            raise ValueError("metadata did not return a CapabilityStatement")
        checks.append(("metadata", metadata.get("fhirVersion", "unknown")))

        patient = get_json(f"Patient/{DEMO_PATIENT_ID}")
        if patient.get("resourceType") != "Patient":
            raise ValueError(f"Patient/{DEMO_PATIENT_ID} did not return a Patient resource")
        patient_name = patient.get("name", [{}])[0]
        checks.append(("patient", f"{patient_name.get('family', '')} {' '.join(patient_name.get('given', []))}".strip()))

        patient_ref = f"Patient/{DEMO_PATIENT_ID}"
        observations = get_json(
            "Observation",
            {"patient": patient_ref, "_sort": "-date", "_count": "5"},
        )
        observations_count = bundle_count(observations)
        if observations_count < 1:
            raise ValueError("expected at least one Observation for the demo patient")
        checks.append(("observations", str(observations_count)))

        conditions = get_json("Condition", {"patient": patient_ref})
        conditions_count = bundle_count(conditions)
        if conditions_count < 1:
            raise ValueError("expected at least one Condition for the demo patient")
        checks.append(("conditions", str(conditions_count)))

        medications = get_json("MedicationRequest", {"patient": patient_ref})
        medications_count = bundle_count(medications)
        if medications_count < 1:
            raise ValueError("expected at least one MedicationRequest for the demo patient")
        checks.append(("medication_requests", str(medications_count)))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        print(f"Connection check failed for {FHIR_BASE_URL}: {exc}", file=sys.stderr)
        return 1

    print(f"HAPI FHIR connection OK: {FHIR_BASE_URL}")
    for name, value in checks:
        print(f"- {name}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
