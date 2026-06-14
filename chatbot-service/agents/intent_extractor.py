import json
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Protocol

from app.config import get_settings


DEFAULT_PATIENT_ID = get_settings().demo_patient_id

TOOL_GET_PATIENT = "get_patient_by_id"
TOOL_SEARCH_PATIENTS = "search_patients"
TOOL_GET_OBSERVATIONS = "get_observations"
TOOL_GET_ENCOUNTERS = "get_encounters"
TOOL_GET_CONDITIONS = "get_conditions"
TOOL_GET_MEDICATIONS = "get_medication_requests"
TOOL_UNSUPPORTED = "unsupported_question"

TOOL_TO_INTENT = {
    TOOL_GET_PATIENT: "patient",
    TOOL_SEARCH_PATIENTS: "patients",
    TOOL_GET_OBSERVATIONS: "observations",
    TOOL_GET_ENCOUNTERS: "encounters",
    TOOL_GET_CONDITIONS: "conditions",
    TOOL_GET_MEDICATIONS: "medications",
    TOOL_UNSUPPORTED: "unknown",
}

MEDICATION_KEYWORDS = ["medication", "medicine", "drug", "thuoc"]
OBSERVATION_KEYWORDS = [
    "observation",
    "glucose",
    "blood pressure",
    "heart rate",
    "cholesterol",
    "hba1c",
    "lab",
    "chi so",
    "xet nghiem",
    "ket qua",
    "huyet ap",
    "duong huyet",
    "nhip tim",
]
ENCOUNTER_KEYWORDS = [
    "encounter",
    "visit",
    "appointment",
    "check-up",
    "checkup",
    "kham",
    "lan kham",
    "lich su kham",
    "dot kham",
    "kham gan nhat",
    "phong kham",
    "phong nao",
]
PATIENT_CONTACT_KEYWORDS = ["phone", "telephone", "mobile", "contact", "so dien thoai", "dien thoai"]
PATIENT_LIST_KEYWORDS = [
    "all patients",
    "list patients",
    "patients list",
    "tat ca benh nhan",
    "danh sach benh nhan",
    "liet ke benh nhan",
    "cac benh nhan",
    "toan bo benh nhan",
]
CONDITION_KEYWORDS = [
    "condition",
    "diagnosis",
    "diagnose",
    "chan doan",
    "benh ly",
    "benh gi",
    "mac benh",
    "tinh trang benh",
]
PATIENT_INFO_KEYWORDS = ["patient", "information", "info", "thong tin", "benh nhan"]


@dataclass(frozen=True)
class IntentPlan:
    tool_name: str
    patient_id: str = DEFAULT_PATIENT_ID
    search_name: str | None = None
    search_phone: str | None = None
    search_birth_date: str | None = None
    search_identifier: str | None = None
    observation_type: str | None = None
    limit: int = 5
    all_patients: bool = False
    reason: str | None = None
    source: str = "rules"
    usage: dict[str, int | float] = field(default_factory=lambda: {
        "input_tokens": 0,
        "output_tokens": 0,
        "estimated_cost_usd": 0,
    })

    @property
    def intent(self) -> str:
        return TOOL_TO_INTENT.get(self.tool_name, "unknown")


class IntentExtractor(Protocol):
    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        ...


class RuleBasedIntentExtractor:
    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        patient_id = resolve_patient_id_for_request(message, provided_patient_id)
        text = normalize_text(message)
        search_criteria = extract_patient_search_criteria(message)
        has_search_criteria = bool(search_criteria)

        all_patient_scope = is_patient_list_request(message) and not resolve_explicit_patient_id(message)
        if contains_any(text, MEDICATION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_MEDICATIONS,
                patient_id=patient_id,
                **search_criteria,
                limit=20,
                all_patients=all_patient_scope,
            )
        if contains_any(text, OBSERVATION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_OBSERVATIONS,
                patient_id=patient_id,
                **search_criteria,
                observation_type=infer_observation_type(message),
                limit=5,
                all_patients=all_patient_scope,
            )
        if contains_any(text, ENCOUNTER_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_ENCOUNTERS,
                patient_id=patient_id,
                **search_criteria,
                limit=5,
                all_patients=all_patient_scope,
            )
        if contains_any(text, PATIENT_CONTACT_KEYWORDS):
            tool_name = TOOL_SEARCH_PATIENTS if has_search_criteria else TOOL_GET_PATIENT
            return IntentPlan(tool_name=tool_name, patient_id=patient_id, **search_criteria)
        if contains_any(text, CONDITION_KEYWORDS):
            return IntentPlan(
                tool_name=TOOL_GET_CONDITIONS,
                patient_id=patient_id,
                **search_criteria,
                limit=20,
                all_patients=all_patient_scope,
            )
        if all_patient_scope or has_search_criteria:
            return IntentPlan(tool_name=TOOL_SEARCH_PATIENTS, patient_id=patient_id, **search_criteria, limit=20)
        if contains_any(text, PATIENT_INFO_KEYWORDS):
            return IntentPlan(tool_name=TOOL_GET_PATIENT, patient_id=patient_id)

        return IntentPlan(
            tool_name=TOOL_UNSUPPORTED,
            patient_id=patient_id,
            reason="Chưa phát hiện được intent truy xuất FHIR được hỗ trợ.",
        )


class OpenAIIntentExtractor:
    def __init__(self, api_key: str, model: str, timeout_seconds: float,base_url: str | None = None) -> None:
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)
        self.model = model
        self.fallback = RuleBasedIntentExtractor()

    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        patient_id_hint = normalize_patient_id(provided_patient_id)
        system_prompt = (
            "You extract the user's intent for a medical chatbot. "
            "The product is for Vietnamese users, so Vietnamese medical wording is expected. "
            "Select exactly one tool. Use FHIR tools for structured patient data. "
            "Do not answer the medical question. Do not invent patient data. "
            f"If the user did not provide a patient id, use {DEFAULT_PATIENT_ID} for local demo. "
            "Vietnamese 'benh nhan' means patient, not condition. "
            "Questions about all patients, patient list, 'tat ca benh nhan', or 'danh sach benh nhan' must use search_patients. "
            "Questions that identify a patient by name, phone, birth date, or identifier must use search_patients unless a clear FHIR patient id is provided. "
            "Questions about phone, contact, 'so dien thoai', or 'dien thoai' must use get_patient_by_id only when a patient id is provided; otherwise use search_patients with name or phone criteria. "
            "Questions about encounters, visits, appointments, 'lan kham', 'lich su kham', or 'kham gan nhat' must use get_encounters."
        )
        user_prompt = {
            "message": message,
            "normalized_message": normalize_text(message),
            "provided_patient_id": patient_id_hint,
            "allowed_patient_id_default": DEFAULT_PATIENT_ID,
        }

        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(user_prompt)},
                ],
                tools=FHIR_TOOL_DEFINITIONS,
                tool_choice="required",
                temperature=0,
            )
        except Exception:
            return await self.fallback.extract(message, provided_patient_id)

        tool_calls = response.choices[0].message.tool_calls or []
        if not tool_calls:
            return await self.fallback.extract(message, provided_patient_id)

        tool_call = tool_calls[0]
        arguments = parse_tool_arguments(tool_call.function.arguments)
        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        plan = plan_from_tool_call(
            tool_name=tool_call.function.name,
            arguments=arguments,
            provided_patient_id=provided_patient_id,
            usage=usage,
            source="llm",
        )
        plan = enforce_patient_list_routing(message, plan)
        plan = apply_all_patient_scope(message, plan)
        plan = apply_patient_search_criteria_hint(message, plan)
        plan = apply_patient_id_hint(message, provided_patient_id, plan)
        plan = enforce_contact_detail_routing(message, plan)
        return add_observation_type_hint(message, plan)


def get_intent_extractor() -> IntentExtractor:
    settings = get_settings()
    if settings.use_openai_llm and settings.openai_api_key:
        return OpenAIIntentExtractor(
            api_key=settings.openai_api_key,
            model=settings.llm_model,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.openai_base_url,
        )
    return RuleBasedIntentExtractor()


def plan_from_tool_call(
    *,
    tool_name: str,
    arguments: dict[str, Any],
    provided_patient_id: str | None,
    usage: dict[str, int | float],
    source: str,
) -> IntentPlan:
    patient_id = normalize_patient_id(provided_patient_id) or normalize_patient_id(arguments.get("patient_id"))
    if not patient_id:
        patient_id = DEFAULT_PATIENT_ID

    limit = arguments.get("limit", 5)
    if not isinstance(limit, int):
        limit = 5

    if tool_name == TOOL_GET_MEDICATIONS:
        limit = clamp(limit, 1, 50)
    elif tool_name == TOOL_GET_CONDITIONS:
        limit = clamp(limit, 1, 50)
    elif tool_name == TOOL_GET_OBSERVATIONS:
        limit = clamp(limit, 1, 20)
    elif tool_name == TOOL_GET_ENCOUNTERS:
        limit = clamp(limit, 1, 20)
    else:
        limit = clamp(limit, 1, 50)

    if tool_name not in TOOL_TO_INTENT:
        tool_name = TOOL_UNSUPPORTED

    return IntentPlan(
        tool_name=tool_name,
        patient_id=patient_id,
        search_name=string_or_none(arguments.get("name")),
        search_phone=normalize_phone(string_or_none(arguments.get("phone"))),
        search_birth_date=normalize_birth_date(string_or_none(arguments.get("birth_date"))),
        search_identifier=string_or_none(arguments.get("identifier")),
        observation_type=string_or_none(arguments.get("observation_type")),
        limit=limit,
        all_patients=False,
        reason=string_or_none(arguments.get("reason")),
        source=source,
        usage=usage,
    )


def parse_tool_arguments(raw_arguments: str | None) -> dict[str, Any]:
    if not raw_arguments:
        return {}
    try:
        parsed = json.loads(raw_arguments)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def enforce_contact_detail_routing(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name == TOOL_GET_PATIENT:
        return plan
    if has_patient_search_criteria(plan):
        return plan
    if not contains_any(normalize_text(message), PATIENT_CONTACT_KEYWORDS):
        return plan
    return IntentPlan(
        tool_name=TOOL_GET_PATIENT,
        patient_id=plan.patient_id,
        search_name=plan.search_name,
        search_phone=plan.search_phone,
        search_birth_date=plan.search_birth_date,
        search_identifier=plan.search_identifier,
        observation_type=plan.observation_type,
        limit=plan.limit,
        all_patients=plan.all_patients,
        reason="Contact detail requests are routed to the Patient resource.",
        source=f"{plan.source}_guardrail",
        usage=plan.usage,
    )


def enforce_patient_list_routing(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name == TOOL_SEARCH_PATIENTS:
        return plan
    if not is_patient_list_request(message):
        return plan
    return IntentPlan(
        tool_name=TOOL_SEARCH_PATIENTS,
        patient_id=plan.patient_id,
        search_name=plan.search_name,
        search_phone=plan.search_phone,
        search_birth_date=plan.search_birth_date,
        search_identifier=plan.search_identifier,
        observation_type=plan.observation_type,
        limit=plan.limit,
        all_patients=plan.all_patients,
        reason="Patient list requests are routed to Patient search.",
        source=f"{plan.source}_guardrail",
        usage=plan.usage,
    )


def apply_all_patient_scope(message: str, plan: IntentPlan) -> IntentPlan:
    if not is_patient_list_request(message) or resolve_explicit_patient_id(message):
        return plan

    text = normalize_text(message)
    tool_name = plan.tool_name
    if contains_any(text, MEDICATION_KEYWORDS):
        tool_name = TOOL_GET_MEDICATIONS
    elif contains_any(text, OBSERVATION_KEYWORDS):
        tool_name = TOOL_GET_OBSERVATIONS
    elif contains_any(text, ENCOUNTER_KEYWORDS):
        tool_name = TOOL_GET_ENCOUNTERS
    elif contains_any(text, CONDITION_KEYWORDS):
        tool_name = TOOL_GET_CONDITIONS

    if tool_name == TOOL_SEARCH_PATIENTS:
        return plan

    return IntentPlan(
        tool_name=tool_name,
        patient_id=plan.patient_id,
        search_name=plan.search_name,
        search_phone=plan.search_phone,
        search_birth_date=plan.search_birth_date,
        search_identifier=plan.search_identifier,
        observation_type=plan.observation_type,
        limit=plan.limit,
        all_patients=True,
        reason=plan.reason,
        source=plan.source,
        usage=plan.usage,
    )


def apply_patient_id_hint(message: str, provided_patient_id: str | None, plan: IntentPlan) -> IntentPlan:
    explicit_patient_id = resolve_explicit_patient_id(message) or normalize_patient_id(provided_patient_id)
    if not explicit_patient_id or explicit_patient_id == plan.patient_id:
        return plan
    return IntentPlan(
        tool_name=plan.tool_name,
        patient_id=explicit_patient_id,
        search_name=plan.search_name,
        search_phone=plan.search_phone,
        search_birth_date=plan.search_birth_date,
        search_identifier=plan.search_identifier,
        observation_type=plan.observation_type,
        limit=plan.limit,
        all_patients=plan.all_patients,
        reason=plan.reason,
        source=plan.source,
        usage=plan.usage,
    )


def apply_patient_search_criteria_hint(message: str, plan: IntentPlan) -> IntentPlan:
    if has_patient_search_criteria(plan) or resolve_explicit_patient_id(message):
        return plan

    criteria = extract_patient_search_criteria(message)
    if not criteria:
        return plan

    tool_name = TOOL_SEARCH_PATIENTS if plan.tool_name == TOOL_GET_PATIENT else plan.tool_name
    return IntentPlan(
        tool_name=tool_name,
        patient_id=plan.patient_id,
        search_name=criteria.get("search_name"),
        search_phone=criteria.get("search_phone"),
        search_birth_date=criteria.get("search_birth_date"),
        search_identifier=criteria.get("search_identifier"),
        observation_type=plan.observation_type,
        limit=plan.limit,
        all_patients=plan.all_patients,
        reason=plan.reason,
        source=f"{plan.source}_guardrail",
        usage=plan.usage,
    )


def add_observation_type_hint(message: str, plan: IntentPlan) -> IntentPlan:
    if plan.tool_name != TOOL_GET_OBSERVATIONS or plan.observation_type:
        return plan
    observation_type = infer_observation_type(message)
    if not observation_type:
        return plan
    return IntentPlan(
        tool_name=plan.tool_name,
        patient_id=plan.patient_id,
        search_name=plan.search_name,
        search_phone=plan.search_phone,
        search_birth_date=plan.search_birth_date,
        search_identifier=plan.search_identifier,
        observation_type=observation_type,
        limit=plan.limit,
        all_patients=plan.all_patients,
        reason=plan.reason,
        source=plan.source,
        usage=plan.usage,
    )


def infer_observation_type(message: str) -> str | None:
    text = normalize_text(message)
    if contains_any(text, ["blood pressure", "blood_pressure", "huyet ap"]):
        return "blood_pressure"
    if contains_any(text, ["glucose", "duong huyet"]):
        return "glucose"
    if contains_any(text, ["heart rate", "nhip tim"]):
        return "heart_rate"
    if contains_any(text, ["cholesterol"]):
        return "cholesterol"
    if contains_any(text, ["hba1c", "a1c"]):
        return "hba1c"
    return None


def is_patient_list_request(message: str) -> bool:
    return contains_any(normalize_text(message), PATIENT_LIST_KEYWORDS)


def has_patient_search_criteria(plan: IntentPlan) -> bool:
    return any([
        plan.search_name,
        plan.search_phone,
        plan.search_birth_date,
        plan.search_identifier,
    ])


def extract_patient_search_criteria(message: str) -> dict[str, str]:
    if resolve_explicit_patient_id(message) or is_patient_list_request(message):
        return {}

    criteria: dict[str, str] = {}
    phone = extract_phone(message)
    if phone:
        criteria["search_phone"] = phone

    birth_date = extract_birth_date(message)
    if birth_date:
        criteria["search_birth_date"] = birth_date

    identifier = extract_identifier(message)
    if identifier:
        criteria["search_identifier"] = identifier

    name = extract_patient_name(message)
    if name:
        criteria["search_name"] = name

    return criteria


def extract_phone(message: str) -> str | None:
    match = re.search(r"(?<!\d)(?:\+?84|0)[\d\s.-]{8,14}\d(?!\d)", message)
    if not match:
        return None
    return normalize_phone(match.group(0))


def normalize_phone(value: str | None) -> str | None:
    if not value:
        return None
    digits = re.sub(r"\D", "", value)
    if digits.startswith("84") and len(digits) >= 10:
        digits = "0" + digits[2:]
    return digits or None


def extract_birth_date(message: str) -> str | None:
    iso_match = re.search(r"\b(19|20)\d{2}-\d{2}-\d{2}\b", message)
    if iso_match:
        return iso_match.group(0)

    date_match = re.search(r"\b([0-3]?\d)[/-]([0-1]?\d)[/-]((?:19|20)\d{2})\b", message)
    if not date_match:
        return None
    day = int(date_match.group(1))
    month = int(date_match.group(2))
    year = int(date_match.group(3))
    if not (1 <= day <= 31 and 1 <= month <= 12):
        return None
    return f"{year:04d}-{month:02d}-{day:02d}"


def normalize_birth_date(value: str | None) -> str | None:
    if not value:
        return None
    parsed = extract_birth_date(value)
    return parsed or value.strip()


def extract_identifier(message: str) -> str | None:
    match = re.search(
        r"\b(?:identifier|ma dinh danh|mã định danh|cccd|cmnd|bhyt)\s*[:#-]?\s*([A-Za-z0-9.-]{4,})",
        message,
        flags=re.IGNORECASE,
    )
    return match.group(1) if match else None


def extract_patient_name(message: str) -> str | None:
    if contains_any(normalize_text(message), ["tat ca", "danh sach", "liet ke", "toan bo"]):
        return None

    patterns = [
        r"(?:bệnh nhân|benh nhan|patient)\s+([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:tên|ten|name)\s+(?:là|la)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:của|cua)\s+(?:bệnh nhân|benh nhan)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:tìm|tim|search|find)\s+(?:bệnh nhân|benh nhan|patient)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
    ]
    for pattern in patterns:
        match = re.search(pattern, message, flags=re.IGNORECASE)
        if not match:
            continue
        cleaned = clean_name_candidate(match.group(1))
        if cleaned:
            return cleaned
    return None


def clean_name_candidate(candidate: str) -> str | None:
    value = re.sub(r"\s+", " ", candidate).strip(" .,'-")
    if not value:
        return None

    stop_phrases = [
        " sinh ngay",
        " ngày sinh",
        " ngay sinh",
        " so dien thoai",
        " số điện thoại",
        " sdt",
        " dang",
        " đang",
        " co ",
        " có ",
        " kham",
        " khám",
        " chan doan",
        " chẩn đoán",
        " huyet ap",
        " huyết áp",
        " thuoc",
        " thuốc",
        " thong tin",
        " thông tin",
    ]
    lowered = normalize_text(f" {value} ")
    cut_at = len(value)
    for phrase in stop_phrases:
        index = lowered.find(normalize_text(phrase))
        if index >= 0:
            cut_at = min(cut_at, max(0, index - 1))
    value = value[:cut_at].strip(" .,'-")

    lowered_value = normalize_text(value)
    if lowered_value.startswith(("sdt", "so dien thoai", "ngay sinh", "sinh ngay", "phone")):
        return None
    if lowered_value in {"ai", "nao", "hien co", "tat ca", "danh sach", "benh nhan"}:
        return None
    if re.fullmatch(r"\d+", value):
        return None
    if len(value) < 2:
        return None
    return value


def resolve_patient_id_for_request(message: str, provided_patient_id: str | None = None) -> str:
    return (
        resolve_explicit_patient_id(message)
        or normalize_patient_id(provided_patient_id)
        or DEFAULT_PATIENT_ID
    )


def resolve_patient_id(message: str) -> str:
    return resolve_explicit_patient_id(message) or DEFAULT_PATIENT_ID


def resolve_explicit_patient_id(message: str) -> str | None:
    patient_ref = re.search(r"Patient/([A-Za-z0-9.-]+)", message, flags=re.IGNORECASE)
    if patient_ref:
        return patient_ref.group(1)

    demo_id = re.search(r"\bdemo-patient-[A-Za-z0-9.-]+\b", message, flags=re.IGNORECASE)
    if demo_id:
        return demo_id.group(0)

    numbered_patient = re.search(
        r"\b(?:patient|benh\s+nhan)\s*[-#:]?\s*0*([1-9][0-9]*)\b",
        normalize_text(message),
        flags=re.IGNORECASE,
    )
    if numbered_patient:
        return f"demo-patient-{int(numbered_patient.group(1)):03d}"

    return None


def normalize_patient_id(patient_id: Any) -> str | None:
    if not isinstance(patient_id, str):
        return None
    normalized = patient_id.removeprefix("Patient/").strip()
    return normalized or None


def normalize_text(text: str) -> str:
    normalized = unicodedata.normalize("NFD", text)
    without_accents = "".join(char for char in normalized if unicodedata.category(char) != "Mn")
    return without_accents.lower().replace("đ", "d").replace("Đ", "d")


def contains_any(text: str, keywords: list[str]) -> bool:
    return any(keyword in text for keyword in keywords)


def string_or_none(value: Any) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(value, maximum))


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
            "description": "Retrieve patient medication requests.",
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
