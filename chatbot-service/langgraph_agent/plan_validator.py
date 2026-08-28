"""Điểm enforce chính sách DUY NHẤT của agent.

Nguyên tắc: **LLM đề xuất, code quyết định.** Prompt planner chỉ là gợi ý và có thể bị
sai hoặc bị prompt-injection từ ngữ cảnh hội thoại; mọi thứ ảnh hưởng tới quyền truy cập
hoặc tới độ rộng truy vấn FHIR đều phải đi qua đây.

Module này thuần tuý (không I/O, không LLM) nên test được đầy đủ mà không cần mạng.
"""

from __future__ import annotations

from typing import Any

from agents.intent.constants import (
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
)
from agents.intent.observation_utils import infer_observation_type
from agents.intent.patient_utils import normalize_patient_id, resolve_explicit_patient_id
from app.config import get_settings
from fhir.tool_registry import (
    ALL_PATIENT_TOOLS,
    MAX_LIMIT,
    PATIENT_SCOPED_TOOLS,
    PUBLIC_TOOLS,
    SUPPORTED_RESOURCE_TYPES,
    get_tool,
)
from langgraph_agent.errors import PlanError, PolicyError
from langgraph_agent.planner import Plan, PlanStep


STEP_REF_PREFIX = "$"


def validate_plan(
    plan: Plan,
    *,
    message: str,
    user_role: str,
    allowed_patient_ids: list[str],
    provided_patient_id: str | None,
) -> Plan:
    """Trả plan đã được làm sạch, hoặc raise PolicyError/PlanError."""
    settings = get_settings()
    max_steps = max(1, settings.agent_max_plan_steps)
    explicit_patient_id = resolve_explicit_patient_id(message)

    validated: list[PlanStep] = []
    known_step_ids: set[str] = set()

    for step in plan.steps[:max_steps]:
        tool = get_tool(step.tool)
        if tool is None:
            raise PlanError(f"Kế hoạch dùng tool không được hỗ trợ: {step.tool}")

        _enforce_role(
            tool_name=step.tool,
            user_role=user_role,
            allowed_patient_ids=allowed_patient_ids,
        )

        args = _keep_known_args(step.args, tool.params)
        args = _resolve_step_refs(args, known_step_ids, step_id=step.id)
        args = _apply_defaults(
            tool_name=step.tool,
            args=args,
            message=message,
            explicit_patient_id=explicit_patient_id,
            provided_patient_id=provided_patient_id,
            user_role=user_role,
            allowed_patient_ids=allowed_patient_ids,
            default_limit=tool.default_limit,
        )
        if step.tool == TOOL_GET_RESOURCE:
            args = _validate_resource_args(args)

        _enforce_patient_scope(
            tool_name=step.tool,
            args=args,
            user_role=user_role,
            allowed_patient_ids=allowed_patient_ids,
        )

        known_step_ids.add(step.id)
        validated.append(PlanStep(id=step.id, tool=step.tool, args=args))

    if not validated:
        raise PlanError("Không lập được kế hoạch FHIR hợp lệ cho câu hỏi này.")

    return Plan(
        steps=tuple(validated),
        answer_mode=plan.answer_mode,
        reason=plan.reason,
        source=plan.source,
    )


def _enforce_role(*, tool_name: str, user_role: str, allowed_patient_ids: list[str]) -> None:
    if user_role != "USER":
        return
    if tool_name in PUBLIC_TOOLS:
        return

    if not allowed_patient_ids:
        raise PolicyError("Tài khoản USER chưa được liên kết với hồ sơ FHIR nào.")
    if tool_name in ALL_PATIENT_TOOLS:
        raise PolicyError("Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.")
    if tool_name == TOOL_SEARCH_PATIENTS:
        raise PolicyError("Tài khoản USER không được tìm kiếm danh sách bệnh nhân.")
    if tool_name == TOOL_GET_RESOURCE and not get_settings().agent_allow_user_resource_lookup:
        raise PolicyError("Tài khoản USER không được tra cứu resource FHIR trực tiếp theo mã resource.")


def _enforce_patient_scope(
    *,
    tool_name: str,
    args: dict[str, Any],
    user_role: str,
    allowed_patient_ids: list[str],
) -> None:
    if user_role != "USER" or tool_name not in PATIENT_SCOPED_TOOLS:
        return
    patient_id = normalize_patient_id(args.get("patient_id"))
    # Tham chiếu "$step.patient_id" chưa resolve được ở đây; executor kiểm tra lại
    # sau khi có giá trị thật (xem plan_executor._assert_patient_allowed).
    if isinstance(args.get("patient_id"), str) and args["patient_id"].startswith(STEP_REF_PREFIX):
        return
    if not patient_id or patient_id not in allowed_patient_ids:
        raise PolicyError("Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.")


def _keep_known_args(args: dict[str, Any], params: tuple[str, ...]) -> dict[str, Any]:
    """Bỏ mọi tham số LLM tự nghĩ ra ngoài chữ ký của tool."""
    if not isinstance(args, dict):
        return {}
    return {
        key: value
        for key, value in args.items()
        if key in params and value not in (None, "", [], {})
    }


def _resolve_step_refs(args: dict[str, Any], known_step_ids: set[str], *, step_id: str) -> dict[str, Any]:
    """Giữ lại tham chiếu "$step.field" hợp lệ, bỏ tham chiếu trỏ vào step không tồn tại."""
    cleaned: dict[str, Any] = {}
    for key, value in args.items():
        if not (isinstance(value, str) and value.startswith(STEP_REF_PREFIX)):
            cleaned[key] = value
            continue
        target = value[1:].split(".", 1)[0]
        if target in known_step_ids and target != step_id:
            cleaned[key] = value
        # Tham chiếu hỏng thì bỏ hẳn tham số; _apply_defaults sẽ điền bằng patient_id
        # đang có, còn nếu vẫn thiếu thì executor hỏi lại người dùng.
    return cleaned


def _apply_defaults(
    *,
    tool_name: str,
    args: dict[str, Any],
    message: str,
    explicit_patient_id: str | None,
    provided_patient_id: str | None,
    user_role: str,
    allowed_patient_ids: list[str],
    default_limit: int,
) -> dict[str, Any]:
    args = dict(args)

    if "limit" in (get_tool(tool_name).params if get_tool(tool_name) else ()):
        args["limit"] = _clamp_limit(args.get("limit"), default_limit)

    if tool_name == TOOL_GET_OBSERVATIONS and not args.get("observation_type"):
        observation_type = infer_observation_type(message)
        if observation_type:
            args["observation_type"] = observation_type

    if tool_name in PATIENT_SCOPED_TOOLS:
        raw = args.get("patient_id")
        if isinstance(raw, str) and raw.startswith(STEP_REF_PREFIX):
            return args
        patient_id = (
            normalize_patient_id(raw)
            or explicit_patient_id
            or normalize_patient_id(provided_patient_id)
        )
        if not patient_id and user_role == "USER" and allowed_patient_ids:
            patient_id = allowed_patient_ids[0]
        if patient_id:
            args["patient_id"] = patient_id
        else:
            args.pop("patient_id", None)

    return args


def _validate_resource_args(args: dict[str, Any]) -> dict[str, Any]:
    canonical = {name.lower(): name for name in SUPPORTED_RESOURCE_TYPES}
    resource_type = canonical.get(str(args.get("resource_type") or "").strip().lower())
    resource_id = str(args.get("resource_id") or "").strip()
    if not resource_type or not resource_id:
        raise PlanError(
            "Tra cứu resource cần resource_type hợp lệ "
            f"({', '.join(SUPPORTED_RESOURCE_TYPES)}) và resource_id."
        )
    return {**args, "resource_type": resource_type, "resource_id": resource_id}


def _clamp_limit(value: Any, default: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(1, min(MAX_LIMIT, number))
