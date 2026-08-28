"""Registry FHIR tool cho LangGraph agent.

Đây **không phải** một lớp truy xuất FHIR mới. Mỗi tool là một wrapper mỏng quanh
answerer đang có trong ``chat/resource_answerers.py``, nên agent dùng lại nguyên:

- Câu trả lời template tiếng Việt (là fallback của answer LLM, đồng thời là
  fast-path template ở ``agent_template_fast_path``).
- Shape ``evidence`` mà Spring/frontend đang đọc.
- Payload ``needs_patient_selection`` / ``patient_candidates``.

Nhờ vậy hai đường ``/chat`` và ``/chat/langgraph`` không bao giờ phân kỳ về cách
gọi FHIR hay cách format dữ liệu — chỉ khác tầng điều phối.

Mỗi handler trả về payload dict ``{answer, intent, patient_id, evidence, usage, ...}``.
Lỗi FHIR được để nguyên cho ``plan_executor`` bắt và quy thành ``tool_error``.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from agents.intent.constants import (
    TOOL_EXPLAIN_CONCEPT,
    TOOL_FHIR_STATUS,
    TOOL_GET_ALL_CONDITIONS,
    TOOL_GET_ALL_ENCOUNTERS,
    TOOL_GET_ALL_MEDICATIONS,
    TOOL_GET_ALL_OBSERVATIONS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
)
from agents.intent.models import IntentPlan
from chat.resource_answerers import (
    _answer_all_patient_conditions,
    _answer_all_patient_encounters,
    _answer_all_patient_medications,
    _answer_all_patient_observations,
    _answer_conditions,
    _answer_encounters,
    _answer_explain_concept,
    _answer_fhir_status,
    _answer_medications,
    _answer_observations,
    _answer_patient,
    _answer_patients,
    _answer_resource_by_id,
)
from fhir.client import FhirClient


ToolHandler = Callable[..., Awaitable[dict[str, Any]]]

DEFAULT_LIMIT = 5
MAX_LIMIT = 20
SEARCH_DEFAULT_LIMIT = 3
SUPPORTED_RESOURCE_TYPES = (
    "Patient",
    "Encounter",
    "Observation",
    "Condition",
    "MedicationRequest",
)


@dataclass(frozen=True)
class FhirTool:
    """Mô tả một tool mà planner được phép chọn."""

    name: str
    description: str
    # Tham số planner được phép sinh; tham số ngoài danh sách này bị validator loại bỏ.
    params: tuple[str, ...]
    handler: ToolHandler
    # Cần một patient_id đã xác định trước khi chạy được.
    needs_patient: bool = False
    # Đọc dữ liệu của nhiều bệnh nhân (chính sách role chặn USER).
    all_patients: bool = False
    # Tool gốc tương ứng, dùng khi dựng IntentPlan cho response (get_all_* -> base).
    base_tool: str | None = None
    # Giới hạn mặc định khi planner không nêu.
    default_limit: int = DEFAULT_LIMIT

    async def run(self, client: FhirClient, args: dict[str, Any]) -> dict[str, Any]:
        return await self.handler(client, **{k: v for k, v in args.items() if k in self.params})

    def prompt_line(self) -> str:
        params = ", ".join(self.params) if self.params else "không tham số"
        return f"- {self.name}({params}): {self.description}"


def _plan(**kwargs: Any) -> IntentPlan:
    """Dựng IntentPlan tạm để gọi lại answerer cũ (chúng nhận IntentPlan)."""
    return IntentPlan(source="langgraph_planner", **kwargs)


async def _tool_fhir_status(client: FhirClient) -> dict[str, Any]:
    return await _answer_fhir_status(client)


async def _tool_search_patients(
    client: FhirClient,
    *,
    name: str | None = None,
    phone: str | None = None,
    birth_date: str | None = None,
    identifier: str | None = None,
    limit: int = SEARCH_DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_patients(
        client,
        _plan(
            tool_name=TOOL_SEARCH_PATIENTS,
            search_name=name,
            search_phone=phone,
            search_birth_date=birth_date,
            search_identifier=identifier,
            limit=limit,
        ),
    )


async def _tool_get_patient_by_id(client: FhirClient, *, patient_id: str) -> dict[str, Any]:
    return await _answer_patient(client, patient_id)


async def _tool_get_resource_by_id(
    client: FhirClient,
    *,
    resource_type: str,
    resource_id: str,
) -> dict[str, Any]:
    return await _answer_resource_by_id(
        client,
        _plan(tool_name=TOOL_GET_RESOURCE, resource_type=resource_type, resource_id=resource_id),
    )


async def _tool_get_observations(
    client: FhirClient,
    *,
    patient_id: str,
    observation_type: str | None = None,
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_observations(client, patient_id, limit, observation_type)


async def _tool_get_encounters(
    client: FhirClient,
    *,
    patient_id: str,
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_encounters(client, patient_id, limit)


async def _tool_get_conditions(
    client: FhirClient,
    *,
    patient_id: str,
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_conditions(client, patient_id, limit)


async def _tool_get_medications(
    client: FhirClient,
    *,
    patient_id: str,
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_medications(client, patient_id, limit)


async def _tool_all_observations(
    client: FhirClient,
    *,
    observation_type: str | None = None,
    limit: int = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await _answer_all_patient_observations(client, limit, observation_type)


async def _tool_all_encounters(client: FhirClient, *, limit: int = DEFAULT_LIMIT) -> dict[str, Any]:
    return await _answer_all_patient_encounters(client, limit)


async def _tool_all_conditions(client: FhirClient, *, limit: int = DEFAULT_LIMIT) -> dict[str, Any]:
    return await _answer_all_patient_conditions(client, limit)


async def _tool_all_medications(client: FhirClient, *, limit: int = DEFAULT_LIMIT) -> dict[str, Any]:
    return await _answer_all_patient_medications(client, limit)


async def _tool_explain_concept(
    client: FhirClient,
    *,
    term: str | None = None,
    code: str | None = None,
) -> dict[str, Any]:
    # Không chạm FHIR: chỉ tra terminology (LOINC/RxNorm/MedlinePlus).
    return await _answer_explain_concept(
        _plan(tool_name=TOOL_EXPLAIN_CONCEPT, term=term, code=code, explain=True)
    )


_TOOLS: tuple[FhirTool, ...] = (
    FhirTool(
        name=TOOL_FHIR_STATUS,
        description="Kiểm tra HAPI FHIR Server còn hoạt động không.",
        params=(),
        handler=_tool_fhir_status,
    ),
    FhirTool(
        name=TOOL_SEARCH_PATIENTS,
        description="Tìm bệnh nhân theo tên, số điện thoại, ngày sinh hoặc mã định danh.",
        params=("name", "phone", "birth_date", "identifier", "limit"),
        handler=_tool_search_patients,
        default_limit=SEARCH_DEFAULT_LIMIT,
    ),
    FhirTool(
        name=TOOL_GET_PATIENT,
        description="Lấy hồ sơ một bệnh nhân theo patient_id đã biết.",
        params=("patient_id",),
        handler=_tool_get_patient_by_id,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_RESOURCE,
        description=(
            "Lấy đúng một resource FHIR đã được nhắc trước đó theo resource_type + resource_id "
            f"({', '.join(SUPPORTED_RESOURCE_TYPES)})."
        ),
        params=("resource_type", "resource_id"),
        handler=_tool_get_resource_by_id,
    ),
    FhirTool(
        name=TOOL_GET_OBSERVATIONS,
        description="Lấy chỉ số/xét nghiệm/sinh hiệu của một bệnh nhân.",
        params=("patient_id", "observation_type", "limit"),
        handler=_tool_get_observations,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_ENCOUNTERS,
        description="Lấy các lần khám của một bệnh nhân.",
        params=("patient_id", "limit"),
        handler=_tool_get_encounters,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_CONDITIONS,
        description="Lấy chẩn đoán/tình trạng đã ghi nhận của một bệnh nhân.",
        params=("patient_id", "limit"),
        handler=_tool_get_conditions,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_MEDICATIONS,
        description="Lấy thuốc/đơn thuốc đã ghi nhận của một bệnh nhân.",
        params=("patient_id", "limit"),
        handler=_tool_get_medications,
        needs_patient=True,
    ),
    FhirTool(
        name=TOOL_GET_ALL_OBSERVATIONS,
        description="Lấy chỉ số/xét nghiệm của nhiều bệnh nhân.",
        params=("observation_type", "limit"),
        handler=_tool_all_observations,
        all_patients=True,
        base_tool=TOOL_GET_OBSERVATIONS,
    ),
    FhirTool(
        name=TOOL_GET_ALL_ENCOUNTERS,
        description="Lấy lần khám của nhiều bệnh nhân.",
        params=("limit",),
        handler=_tool_all_encounters,
        all_patients=True,
        base_tool=TOOL_GET_ENCOUNTERS,
    ),
    FhirTool(
        name=TOOL_GET_ALL_CONDITIONS,
        description="Lấy chẩn đoán của nhiều bệnh nhân.",
        params=("limit",),
        handler=_tool_all_conditions,
        all_patients=True,
        base_tool=TOOL_GET_CONDITIONS,
    ),
    FhirTool(
        name=TOOL_GET_ALL_MEDICATIONS,
        description="Lấy thuốc của nhiều bệnh nhân.",
        params=("limit",),
        handler=_tool_all_medications,
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
        handler=_tool_explain_concept,
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
# Tool không chạm dữ liệu bệnh nhân nào -> mọi role đều dùng được.
PUBLIC_TOOLS: frozenset[str] = frozenset({TOOL_FHIR_STATUS, TOOL_EXPLAIN_CONCEPT})


def get_tool(name: str) -> FhirTool | None:
    return TOOL_REGISTRY.get(name)


def tool_catalog_for_prompt() -> str:
    """Danh mục tool đưa vào prompt planner (nguồn sự thật duy nhất về tool)."""
    return "\n".join(tool.prompt_line() for tool in _TOOLS)
