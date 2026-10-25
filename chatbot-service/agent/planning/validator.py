"""Điểm enforce chính sách của agent.

Nguyên tắc: **LLM đề xuất, code quyết định.** Prompt planner chỉ là gợi ý và có thể bị
sai hoặc bị prompt-injection từ ngữ cảnh hội thoại; mọi thứ ảnh hưởng tới quyền truy cập
hoặc tới độ rộng truy vấn FHIR đều phải đi qua đây.

Module này thuần tuý (không I/O, không LLM) nên test được đầy đủ mà không cần mạng.
"""

from __future__ import annotations

from datetime import date
from typing import Any

from agent.errors import PlanError, PolicyError
from agent.planning.plan import STEP_REF_PREFIX, Plan, PlanStep
from app.config import get_settings
from nlp.observation_types import canonical_observation_type, infer_bp_component, infer_observation_type
from nlp.patient_refs import resolve_explicit_patient_id
from nlp.text import normalize_patient_id
from nlp.time_range import TimeRange, extract_time_range, normalize_iso_date, today_local
from nlp.value_filter import extract_value_filter, normalize_threshold, normalize_value_op
from tools.filters import normalize_encounter_id
from tools.registry import (
    ALL_PATIENT_TOOLS,
    DATE_FILTER_TOOLS,
    MAX_LIMIT,
    PATIENT_SCOPED_TOOLS,
    PUBLIC_TOOLS,
    TOOL_GET_ALL_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_OBSERVATION_TREND,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_RESOURCE,
    TOOL_SEARCH_PATIENTS,
    get_tool,
)
from tools.resources import SUPPORTED_RESOURCE_TYPES


# Tool nhận observation_type (validator suy từ câu hỏi khi planner bỏ sót).
_OBSERVATION_TYPE_TOOLS = frozenset({TOOL_GET_OBSERVATIONS, TOOL_GET_OBSERVATION_TREND, TOOL_GET_ALL_OBSERVATIONS})


def validate_plan(
    plan: Plan,
    *,
    message: str,
    user_role: str,
    allowed_patient_ids: list[str],
    provided_patient_id: str | None,
    today: date | None = None,
) -> Plan:
    """Trả plan đã được làm sạch, hoặc raise PolicyError/PlanError."""
    settings = get_settings()
    max_steps = max(1, settings.max_plan_steps)
    explicit_patient_id = resolve_explicit_patient_id(message)
    # Khoảng ngày suy bằng luật (tất định) thắng phép tính ngày của LLM; LLM không đặt
    # ngày cho step nào thì luật điền cho mọi step nhận ngày.
    rule_range = extract_time_range(message, today=today or today_local(settings.app_timezone))
    planner_dated = any(
        normalize_iso_date(step.args.get("date_from")) or normalize_iso_date(step.args.get("date_to"))
        for step in plan.steps
        if isinstance(step.args, dict)
    )

    validated: list[PlanStep] = []
    known_steps: dict[str, str] = {}

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
        args = _resolve_step_refs(args, known_steps, step_id=step.id)
        args = _apply_filters(
            tool_name=step.tool,
            args=args,
            message=message,
            rule_range=rule_range,
            planner_dated=planner_dated,
        )
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

        known_steps[step.id] = step.tool
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
    if tool_name == TOOL_GET_RESOURCE and not get_settings().allow_user_resource_lookup:
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
    # sau khi có giá trị thật (xem executor._assert_patient_allowed).
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


def _resolve_step_refs(args: dict[str, Any], known_steps: dict[str, str], *, step_id: str) -> dict[str, Any]:
    """Giữ lại tham chiếu "$step.field" hợp lệ (chỉ trỏ về step ĐỨNG TRƯỚC ⇒ không có vòng).

    - ``patient_id`` trỏ sai: bỏ tham số; _apply_defaults điền bằng patient_id đang có,
      vẫn thiếu thì executor hỏi lại người dùng.
    - ``encounter_id`` phải trỏ vào một step ``get_encounters``; trỏ sai thì plan hỏng,
      vì bỏ đi sẽ âm thầm trả lời một câu hỏi rộng hơn câu được hỏi.
    """
    cleaned: dict[str, Any] = {}
    for key, value in args.items():
        if not (isinstance(value, str) and value.startswith(STEP_REF_PREFIX)):
            cleaned[key] = value
            continue
        target, _, field = value[1:].partition(".")
        valid = target in known_steps and target != step_id
        if key == "encounter_id":
            if not (valid and field == "encounter_id" and known_steps[target] == TOOL_GET_ENCOUNTERS):
                raise PlanError(
                    "Tham chiếu lần khám phải lấy từ một bước get_encounters đứng trước "
                    '(dạng "$<id step>.encounter_id").'
                )
            cleaned[key] = value
        elif valid:
            cleaned[key] = value
    return cleaned


def _apply_filters(
    *,
    tool_name: str,
    args: dict[str, Any],
    message: str,
    rule_range: TimeRange | None,
    planner_dated: bool,
) -> dict[str, Any]:
    """Chuẩn hoá bộ lọc: loại chỉ số, khoảng ngày, lần khám, ngưỡng giá trị."""
    args = dict(args)

    if tool_name in _OBSERVATION_TYPE_TOOLS:
        observation_type = canonical_observation_type(args.get("observation_type")) or infer_observation_type(message)
        if observation_type:
            args["observation_type"] = observation_type
        elif args.get("observation_type") and tool_name != TOOL_GET_OBSERVATIONS:
            # Loại lạ không có mã LOINC: các tool nhóm/xu hướng không dùng được.
            args.pop("observation_type")
        if tool_name == TOOL_GET_OBSERVATION_TREND and not args.get("observation_type"):
            raise PlanError(
                "Cần nêu rõ loại chỉ số để xem xu hướng (vd huyết áp, đường huyết, HbA1c, cân nặng)."
            )

    if tool_name in DATE_FILTER_TOOLS:
        start = normalize_iso_date(args.pop("date_from", None))
        end = normalize_iso_date(args.pop("date_to", None))
        planner_range = TimeRange(start, end) if (start or end) else None
        if rule_range is not None and (planner_range is not None or not planner_dated):
            chosen = rule_range
        else:
            chosen = planner_range
        if chosen is not None:
            args.update(_ordered(chosen).as_args())

    if "encounter_id" in args and not str(args["encounter_id"]).startswith(STEP_REF_PREFIX):
        encounter_id = normalize_encounter_id(args["encounter_id"])
        if encounter_id:
            args["encounter_id"] = encounter_id
        else:
            args.pop("encounter_id")

    if tool_name == TOOL_GET_ALL_OBSERVATIONS:
        op, value = normalize_value_op(args.pop("value_op", None)), normalize_threshold(args.pop("value", None))
        if op is None or value is None:
            op, value = extract_value_filter(message) or (None, None)
        if op is not None and value is not None:
            if not args.get("observation_type"):
                raise PlanError("Cần nêu rõ loại chỉ số để lọc theo ngưỡng (vd HbA1c > 7).")
            args["value_op"], args["value"] = op, value
        if args.get("observation_type") == "blood_pressure":
            component = str(args.get("component") or "").strip().lower()
            args["component"] = component if component in {"systolic", "diastolic"} else (
                infer_bp_component(message) or "systolic"
            )
        else:
            args.pop("component", None)

    return args


def _ordered(time_range: TimeRange) -> TimeRange:
    if time_range.date_from and time_range.date_to and time_range.date_from > time_range.date_to:
        return TimeRange(time_range.date_to, time_range.date_from)
    return time_range


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

    tool = get_tool(tool_name)
    if tool and "limit" in tool.params:
        args["limit"] = _clamp_limit(args.get("limit"), default_limit)

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
