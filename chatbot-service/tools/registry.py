"""Danh mục tool mà planner được phép chọn — nguồn sự thật duy nhất về tool.

- Planner đọc danh mục qua ``tool_catalog_for_prompt()`` (thêm tool không cần sửa prompt).
- Validator dùng ``params`` để bỏ tham số lạ, ``needs_patient`` / ``all_patients`` để
  áp chính sách role, ``default_limit`` để clamp.
- Executor gọi ``FhirTool.run``.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from fhir.client import FhirClient
from tools.concepts import explain_concept
from tools.conditions import get_all_patient_conditions, get_conditions
from tools.encounters import get_all_patient_encounters, get_encounters
from tools.medications import get_all_patient_medication_requests, get_medication_requests
from tools.observations import get_all_patient_observations, get_observations
from tools.patients import get_patient, search_patients
from tools.resources import SUPPORTED_RESOURCE_TYPES, fhir_status, get_resource_by_id
from tools.trends import get_observation_trend


TOOL_FHIR_STATUS = "fhir_status"
TOOL_SEARCH_PATIENTS = "search_patients"
TOOL_GET_PATIENT = "get_patient_by_id"
TOOL_GET_RESOURCE = "get_resource_by_id"
TOOL_GET_OBSERVATIONS = "get_observations"
TOOL_GET_OBSERVATION_TREND = "get_observation_trend"
TOOL_GET_ENCOUNTERS = "get_encounters"
TOOL_GET_CONDITIONS = "get_conditions"
TOOL_GET_MEDICATIONS = "get_medication_requests"
TOOL_GET_ALL_OBSERVATIONS = "get_all_patient_observations"
TOOL_GET_ALL_ENCOUNTERS = "get_all_patient_encounters"
TOOL_GET_ALL_CONDITIONS = "get_all_patient_conditions"
TOOL_GET_ALL_MEDICATIONS = "get_all_patient_medication_requests"
TOOL_EXPLAIN_CONCEPT = "explain_concept"

DEFAULT_LIMIT = 5
MAX_LIMIT = 20
SEARCH_DEFAULT_LIMIT = 3
TREND_DEFAULT_LIMIT = 20
COHORT_DEFAULT_LIMIT = 20

# Tham số dùng chung — mô tả ở đây đi thẳng vào prompt planner.
DATE_PARAMS = ("date_from", "date_to")
DATE_HINT = "date_from/date_to: YYYY-MM-DD, chỉ khi câu hỏi nêu mốc thời gian"
ENCOUNTER_HINT = (
    'encounter_id: chỉ lấy dữ liệu của một lần khám; dùng "$<id step get_encounters>.encounter_id" '
    "để lấy lần khám mới nhất của step đó"
)

ToolHandler = Callable[..., Awaitable[dict[str, Any]]]


@dataclass(frozen=True)
class FhirTool:
    name: str
    description: str
    # Tham số planner được phép sinh; tham số ngoài danh sách này bị validator loại bỏ.
    params: tuple[str, ...]
    handler: ToolHandler
    # Cần một patient_id đã xác định trước khi chạy được.
    needs_patient: bool = False
    # Đọc dữ liệu của nhiều bệnh nhân (chính sách role chặn USER).
    all_patients: bool = False
    # Tool gốc tương ứng: get_all_* -> tool theo một bệnh nhân (Spring audit theo tên gốc).
    base_tool: str | None = None
    default_limit: int = DEFAULT_LIMIT

    async def run(self, client: FhirClient, args: dict[str, Any]) -> dict[str, Any]:
        return await self.handler(client, **{k: v for k, v in args.items() if k in self.params})

    def prompt_line(self) -> str:
        params = ", ".join(self.params) if self.params else "không tham số"
        return f"- {self.name}({params}): {self.description}"


_TOOLS: tuple[FhirTool, ...] = (
    FhirTool(
        name=TOOL_FHIR_STATUS,
        description="Kiểm tra HAPI FHIR Server còn hoạt động không.",
        params=(),
        handler=fhir_status,
    ),
    FhirTool(
        name=TOOL_SEARCH_PATIENTS,
        description="Tìm bệnh nhân theo tên, số điện thoại, ngày sinh hoặc mã định danh.",
        params=("name", "phone", "birth_date", "identifier", "limit"),
        handler=search_patients,
        default_limit=SEARCH_DEFAULT_LIMIT,
    ),
    FhirTool(
        name=TOOL_GET_PATIENT,
        description="Lấy hồ sơ một bệnh nhân theo patient_id đã biết.",
        params=("patient_id",),
        handler=get_patient,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_RESOURCE,
        description=(
            "Lấy đúng một resource FHIR đã được nhắc trước đó theo resource_type + resource_id "
            f"({', '.join(SUPPORTED_RESOURCE_TYPES)})."
        ),
        params=("resource_type", "resource_id"),
        handler=get_resource_by_id,
    ),
    FhirTool(
        name=TOOL_GET_OBSERVATIONS,
        description=(
            "Lấy chỉ số/xét nghiệm/sinh hiệu của một bệnh nhân, mới nhất trước. "
            f"observation_type: loại chỉ số (vd blood_pressure, glucose, hba1c). {DATE_HINT}. {ENCOUNTER_HINT}."
        ),
        params=("patient_id", "observation_type", *DATE_PARAMS, "encounter_id", "limit"),
        handler=get_observations,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_OBSERVATION_TREND,
        description=(
            "Xu hướng MỘT loại chỉ số của một bệnh nhân theo thời gian (tăng/giảm, thấp nhất, cao nhất, "
            f"chênh lệch) — dùng khi hỏi xu hướng, diễn biến, thay đổi, tăng hay giảm. Bắt buộc observation_type. {DATE_HINT}."
        ),
        params=("patient_id", "observation_type", *DATE_PARAMS, "limit"),
        handler=get_observation_trend,
        needs_patient=True,
        default_limit=TREND_DEFAULT_LIMIT,
    ),
    FhirTool(
        name=TOOL_GET_ENCOUNTERS,
        description=(
            "Lấy các lần khám của một bệnh nhân, mới nhất trước (limit 1 = lần khám gần nhất). "
            f"{DATE_HINT}."
        ),
        params=("patient_id", *DATE_PARAMS, "limit"),
        handler=get_encounters,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_CONDITIONS,
        description=f"Lấy chẩn đoán/tình trạng đã ghi nhận của một bệnh nhân. {DATE_HINT}. {ENCOUNTER_HINT}.",
        params=("patient_id", *DATE_PARAMS, "encounter_id", "limit"),
        handler=get_conditions,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_MEDICATIONS,
        description=f"Lấy thuốc/đơn thuốc đã ghi nhận của một bệnh nhân. {DATE_HINT}. {ENCOUNTER_HINT}.",
        params=("patient_id", *DATE_PARAMS, "encounter_id", "limit"),
        handler=get_medication_requests,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_ALL_OBSERVATIONS,
        description=(
            "Chỉ số/xét nghiệm của nhiều bệnh nhân. Có observation_type thì xét kết quả GẦN NHẤT của mọi "
            "bệnh nhân có dữ liệu; lọc ngưỡng bằng value_op (gt|ge|lt|le) + value, vd \"bệnh nhân nào "
            "có HbA1c > 7\" → observation_type=hba1c, value_op=gt, value=7. Huyết áp: component "
            "systolic|diastolic (mặc định systolic)."
        ),
        params=("observation_type", "value_op", "value", "component", "limit"),
        handler=get_all_patient_observations,
        all_patients=True,
        base_tool=TOOL_GET_OBSERVATIONS,
        default_limit=COHORT_DEFAULT_LIMIT,
    ),
    FhirTool(
        name=TOOL_GET_ALL_ENCOUNTERS,
        description="Lấy lần khám của nhiều bệnh nhân.",
        params=("limit",),
        handler=get_all_patient_encounters,
        all_patients=True,
        base_tool=TOOL_GET_ENCOUNTERS,
    ),
    FhirTool(
        name=TOOL_GET_ALL_CONDITIONS,
        description="Lấy chẩn đoán của nhiều bệnh nhân.",
        params=("limit",),
        handler=get_all_patient_conditions,
        all_patients=True,
        base_tool=TOOL_GET_CONDITIONS,
    ),
    FhirTool(
        name=TOOL_GET_ALL_MEDICATIONS,
        description="Lấy thuốc của nhiều bệnh nhân.",
        params=("limit",),
        handler=get_all_patient_medication_requests,
        all_patients=True,
        base_tool=TOOL_GET_MEDICATIONS,
    ),
    FhirTool(
        name=TOOL_EXPLAIN_CONCEPT,
        description=(
            "Giải thích một khái niệm y khoa chung, không gắn bệnh nhân "
            "(vd 'HbA1c là gì', 'Metformin dùng để làm gì'). Không gọi FHIR."
        ),
        params=("term", "code"),
        handler=explain_concept,
    ),
)

TOOL_REGISTRY: dict[str, FhirTool] = {tool.name: tool for tool in _TOOLS}

TOOL_NAMES: tuple[str, ...] = tuple(TOOL_REGISTRY)
ALL_PATIENT_TOOLS: frozenset[str] = frozenset(
    name for name, tool in TOOL_REGISTRY.items() if tool.all_patients
)
PATIENT_SCOPED_TOOLS: frozenset[str] = frozenset(
    name for name, tool in TOOL_REGISTRY.items() if tool.needs_patient
)
# Tool nhận khoảng ngày / lần khám (validator điền từ câu hỏi khi planner bỏ sót).
DATE_FILTER_TOOLS: frozenset[str] = frozenset(
    name for name, tool in TOOL_REGISTRY.items() if "date_from" in tool.params
)
ENCOUNTER_FILTER_TOOLS: frozenset[str] = frozenset(
    name for name, tool in TOOL_REGISTRY.items() if "encounter_id" in tool.params
)
# Tool không chạm dữ liệu bệnh nhân nào -> mọi role đều dùng được.
PUBLIC_TOOLS: frozenset[str] = frozenset({TOOL_FHIR_STATUS, TOOL_EXPLAIN_CONCEPT})


def get_tool(name: str) -> FhirTool | None:
    return TOOL_REGISTRY.get(name)


def base_tool_name(name: str) -> str:
    tool = get_tool(name)
    return (tool.base_tool or name) if tool else name


def tool_catalog_for_prompt() -> str:
    return "\n".join(tool.prompt_line() for tool in _TOOLS)
