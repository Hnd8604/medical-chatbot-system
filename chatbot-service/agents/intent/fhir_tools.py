from agents.intent.constants import (
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_MEDICATIONS,
    TOOL_UNSUPPORTED,
)

FHIR_TOOL_DEFINITIONS = [
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
                        "description": "FHIR Patient id without the Patient/ prefix.",
                    },
                },
                "required": ["patient_id"],
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
                    "patient_id": {"type": "string"},
                    "observation_type": {
                        "type": "string",
                        "description": "Optional observation category from the user question.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of observations to retrieve.",
                    },
                },
                "required": ["patient_id"],
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
                    "patient_id": {"type": "string"},
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of encounters to retrieve.",
                    },
                },
                "required": ["patient_id"],
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
                    "patient_id": {"type": "string"},
                    "limit": {"type": "integer"},
                },
                "required": ["patient_id"],
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
                    "patient_id": {"type": "string"},
                    "limit": {"type": "integer"},
                },
                "required": ["patient_id"],
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
