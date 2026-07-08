from agents.intent.constants import (
    TOOL_FHIR_STATUS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_ALL_OBSERVATIONS,
    TOOL_GET_ALL_ENCOUNTERS,
    TOOL_GET_ALL_CONDITIONS,
    TOOL_GET_ALL_MEDICATIONS,
    TOOL_UNSUPPORTED,
)

FHIR_TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": TOOL_FHIR_STATUS,
            "description": "Check whether the HAPI FHIR server is up and reachable. Use for questions about FHIR server status, availability, or connection health.",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_RESOURCE,
            "description": "Retrieve a single FHIR resource by resource type and resource id, for example Observation/OBS-2026-00002 or Encounter/ENC-2026-00004. Use when the user references a concrete FHIR resource id that is not a Patient id.",
            "parameters": {
                "type": "object",
                "properties": {
                    "resource_type": {
                        "type": "string",
                        "enum": ["Patient", "Encounter", "Observation", "Condition", "MedicationRequest"],
                        "description": "FHIR resource type of the referenced resource.",
                    },
                    "resource_id": {
                        "type": "string",
                        "description": "FHIR resource id without the resource type prefix.",
                    },
                },
                "required": ["resource_type", "resource_id"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_PATIENT,
            "description": "Retrieve basic patient demographics and contact details such as phone number by FHIR patient id.",
            "parameters": {
                "type": "object",
                "properties": {
                    "patient_id": {
                        "type": "string",
                        "description": "FHIR Patient id without the Patient/ prefix. Omit when the patient is not identified yet.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_SEARCH_PATIENTS,
            "description": "Search or list patients by name, phone, birth date, identifier, or when the user asks for all patients.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Patient name from the question, for example Tran Thi B or Nguyen Van A.",
                    },
                    "phone": {
                        "type": "string",
                        "description": "Patient phone number from the question.",
                    },
                    "birth_date": {
                        "type": "string",
                        "description": "Patient birth date in YYYY-MM-DD format when available.",
                    },
                    "identifier": {
                        "type": "string",
                        "description": "Patient business identifier, insurance id, CCCD, or CMND when available.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of patients to return.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_OBSERVATIONS,
            "description": "Retrieve recent observations such as blood pressure, glucose, heart rate, or labs.",
            "parameters": {
                "type": "object",
                "properties": {
                    "patient_id": {
                        "type": "string",
                        "description": "FHIR Patient id without the Patient/ prefix. Omit when the patient is not identified yet.",
                    },
                    "observation_type": {
                        "type": "string",
                        "description": "Optional observation category from the user question.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of observations to retrieve.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_ENCOUNTERS,
            "description": "Retrieve patient encounters or visits, including recent visit time, visit type, reason, participants, and location.",
            "parameters": {
                "type": "object",
                "properties": {
                    "patient_id": {
                        "type": "string",
                        "description": "FHIR Patient id without the Patient/ prefix. Omit when the patient is not identified yet.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of encounters to retrieve.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_CONDITIONS,
            "description": "Retrieve patient conditions or diagnoses.",
            "parameters": {
                "type": "object",
                "properties": {
                    "patient_id": {
                        "type": "string",
                        "description": "FHIR Patient id without the Patient/ prefix. Omit when the patient is not identified yet.",
                    },
                    "limit": {"type": "integer"},
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_MEDICATIONS,
            "description": "Retrieves the patient's medications. CRITICAL INSTRUCTION: In FHIR standard, this corresponds to MedicationRequest resources. You MUST use this tool whenever the user asks about the patient's medications, prescriptions, or drugs.",
            "parameters": {
                "type": "object",
                "properties": {
                    "patient_id": {
                        "type": "string",
                        "description": "FHIR Patient id without the Patient/ prefix. Omit when the patient is not identified yet.",
                    },
                    "limit": {"type": "integer"},
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_ALL_OBSERVATIONS,
            "description": "Retrieve recent observations (blood pressure, glucose, labs, ...) across many patients at once, with a per-patient limit. Use when the question is about observations of all patients or every patient instead of one identified patient.",
            "parameters": {
                "type": "object",
                "properties": {
                    "observation_type": {
                        "type": "string",
                        "description": "Optional observation category from the user question.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of observations to retrieve per patient.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_ALL_ENCOUNTERS,
            "description": "Retrieve recent encounters or visits across many patients at once, with a per-patient limit. Use when the question is about encounters of all patients or every patient instead of one identified patient.",
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of encounters to retrieve per patient.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_ALL_CONDITIONS,
            "description": "Retrieve conditions or diagnoses across many patients at once, with a per-patient limit. Use when the question is about conditions of all patients or every patient instead of one identified patient.",
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of conditions to retrieve per patient.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_GET_ALL_MEDICATIONS,
            "description": "Retrieve MedicationRequest prescriptions across many patients at once, with a per-patient limit. Use when the question is about medications of all patients or every patient instead of one identified patient.",
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of medication requests to retrieve per patient.",
                    },
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": TOOL_UNSUPPORTED,
            "description": "Use when the question is not a supported structured FHIR retrieval request.",
            "parameters": {
                "type": "object",
                "properties": {
                    "reason": {"type": "string"},
                    "patient_id": {"type": "string"},
                },
                "required": ["reason"],
                "additionalProperties": False,
            },
        },
    },
]
