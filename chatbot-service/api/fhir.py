from fastapi import APIRouter, Depends, HTTPException, Query, status

from fhir.client import FhirClient, FhirClientError, FhirNotFoundError, get_fhir_client
from fhir.normalizer import (
    normalize_capability_statement,
    normalize_condition_bundle,
    normalize_encounter_bundle,
    normalize_medication_request_bundle,
    normalize_observation_bundle,
    normalize_patient,
    normalize_patient_bundle,
)


router = APIRouter(tags=["fhir"])


def translate_fhir_error(error: FhirClientError) -> HTTPException:
    if isinstance(error, FhirNotFoundError):
        return HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=error.user_message,
        )
    return HTTPException(
        status_code=status.HTTP_502_BAD_GATEWAY,
        detail=error.user_message,
    )


@router.get("/fhir/status")
async def fhir_status(client: FhirClient = Depends(get_fhir_client)) -> dict:
    try:
        metadata = await client.get_metadata()
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "status": "ok",
        "fhir": normalize_capability_statement(metadata),
    }


@router.get("/patients")
async def search_patients(
    name: str | None = Query(default=None, min_length=1),
    phone: str | None = Query(default=None, min_length=1),
    birth_date: str | None = Query(default=None, min_length=4),
    identifier: str | None = Query(default=None, min_length=1),
    limit: int = Query(default=20, ge=1, le=50),
    client: FhirClient = Depends(get_fhir_client),
) -> dict:
    try:
        bundle = await client.search_patients_flexible(
            count=limit,
            name=name,
            phone=phone,
            birth_date=birth_date,
            identifier=identifier,
        )
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "criteria": {
            "name": name,
            "phone": phone,
            "birth_date": birth_date,
            "identifier": identifier,
        },
        "patients": normalize_patient_bundle(bundle),
    }


@router.get("/patients/{patient_id}")
async def get_patient(patient_id: str, client: FhirClient = Depends(get_fhir_client)) -> dict:
    try:
        patient = await client.get_patient(patient_id)
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return normalize_patient(patient)


@router.get("/patients/{patient_id}/observations")
async def get_patient_observations(
    patient_id: str,
    limit: int = Query(default=5, ge=1, le=50),
    client: FhirClient = Depends(get_fhir_client),
) -> dict:
    try:
        bundle = await client.search_patient_resources(
            "Observation",
            patient_id,
            count=limit,
            sort="-date",
        )
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "patient_id": patient_id,
        "observations": normalize_observation_bundle(bundle),
    }


@router.get("/patients/{patient_id}/conditions")
async def get_patient_conditions(
    patient_id: str,
    limit: int = Query(default=20, ge=1, le=50),
    client: FhirClient = Depends(get_fhir_client),
) -> dict:
    try:
        bundle = await client.search_patient_resources("Condition", patient_id, count=limit)
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "patient_id": patient_id,
        "conditions": normalize_condition_bundle(bundle),
    }


@router.get("/patients/{patient_id}/encounters")
async def get_patient_encounters(
    patient_id: str,
    limit: int = Query(default=5, ge=1, le=50),
    client: FhirClient = Depends(get_fhir_client),
) -> dict:
    try:
        bundle = await client.search_patient_resources(
            "Encounter",
            patient_id,
            count=limit,
            sort="-date",
        )
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "patient_id": patient_id,
        "encounters": normalize_encounter_bundle(bundle),
    }


@router.get("/patients/{patient_id}/medications")
async def get_patient_medications(
    patient_id: str,
    limit: int = Query(default=20, ge=1, le=50),
    client: FhirClient = Depends(get_fhir_client),
) -> dict:
    try:
        bundle = await client.search_patient_resources("MedicationRequest", patient_id, count=limit)
    except FhirClientError as exc:
        raise translate_fhir_error(exc) from exc
    return {
        "patient_id": patient_id,
        "medications": normalize_medication_request_bundle(bundle),
    }
