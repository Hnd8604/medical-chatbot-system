"""Router dựa trên luật cho low-cost mode — không tốn LLM call.

Thay router LLM khi quota gần cạn, nhưng vẫn giữ hai việc mà nhánh FHIR không làm thay
được:

1. **Lọc câu hỏi không an toàn** (xin kê đơn, đổi liều, chẩn đoán từ triệu chứng...) →
   ``unsupported`` kèm ``safety_flag``. Luật cố ý hẹp: chỉ bắt câu có khung "xin lời
   khuyên" rõ ràng, để câu hỏi dữ liệu ("bệnh nhân đang dùng thuốc gì") vẫn đi FHIR.
2. **Nhận ra câu không cần dữ liệu**: chào hỏi → ``general_chat``; hỏi về chính cuộc trò
   chuyện → ``conversation_meta``. Không thì rule planner không hiểu và lượt bị hỏi lại.

Còn lại → ``fhir`` (rule planner + validator xử lý tiếp).
"""

from __future__ import annotations

import re

from agent.planning.router import RouteDecision
from agent.state import ROUTE_CONVERSATION_META, ROUTE_FHIR, ROUTE_GENERAL_CHAT, ROUTE_UNSUPPORTED
from nlp.patient_refs import resolve_explicit_patient_id
from nlp.text import contains_any, normalize_text
from nlp.vocabulary import (
    CONDITION_KEYWORDS,
    ENCOUNTER_KEYWORDS,
    MEDICATION_KEYWORDS,
    OBSERVATION_KEYWORDS,
    PATIENT_INFO_KEYWORDS,
)


SOURCE_LOW_COST = "low_cost"

# Các mẫu chạy trên văn bản đã normalize_text (không dấu, chữ thường).
_SYMPTOM = (
    r"(dau (dau|bung|nguc|hong|lung|da day|rang|khop)|bi sot|sot cao|chong mat|buon non|"
    r"kho tho|tieu chay|phat ban|ho khan|ho nhieu|bi ho|met moi|mat ngu|headache|fever|cough)"
)

_TREATMENT_PATTERNS = [
    r"\b(co )?nen (uong|dung|ngung|bo|thoi|tiep tuc) (thuoc|uong thuoc|dung thuoc)\b",
    r"\bnen (uong|dung) (thuoc )?gi\b",
    r"\b(nen|co the|duoc phep) (tu )?(doi|tang|giam|chinh) lieu\b",
    r"\b(doi|tang|giam|chinh) lieu (duoc khong|the nao|nhu the nao|ra sao)\b",
    r"\bke (don|thuoc) (cho|giup) (toi|minh|em|tui)\b",
    r"\bke (cho|giup) (toi|minh|em|tui) (don|thuoc)\b",
    r"\b(hay|vui long|giup toi) ke (don|thuoc)\b",
    r"\b(uong|dung) (thuoc )?gi (de|thi|cho) (chua|khoi|het|do|giam)\b",
    r"\b(cach|lam sao de|lam the nao de) (chua|dieu tri|tri)\b",
    r"\b(nen|phai) dieu tri (the nao|nhu the nao|ra sao)\b",
    rf"\b{_SYMPTOM}\b.*\b(uong|dung) (thuoc )?gi\b",
    r"\b(prescribe|should i (take|stop|increase|decrease|change))\b",
]

_DIAGNOSIS_PATTERNS = [
    r"\btu chan doan\b",
    r"\bchan doan (benh )?moi\b(?! nhat)",
    r"\b(hay|giup|vui long|xin) (toi |minh )?chan doan\b",
    r"\bchan doan (giup|cho) (toi|minh|em|tui)\b",
    r"\bco phai (toi|minh|em|tui) (bi|mac)\b",
    r"\btrieu chung (nay|do|tren) la (benh |bi )?gi\b",
    rf"\b{_SYMPTOM}\b.*\b(bi benh gi|benh gi|bi gi|bi lam sao|la sao|co sao khong|co nguy hiem khong)\b",
    r"\b(diagnose me|what (disease|illness) do i have)\b",
]

_ADVICE_PATTERNS = [
    r"\b(toi|minh|em|tui) (nen|phai) lam gi\b",
    r"\bco can (di )?(cap cuu|nhap vien) khong\b",
]

_META_PATTERNS = [
    ("summary", r"\btom tat (lai )?(cuoc tro chuyen|cuoc hoi thoai|doan chat|doan hoi thoai|nhung gi|noi dung)\b"),
    ("summary", r"\b(nay gio|tu dau) (chung ta|minh|toi) (da )?(noi|trao doi|hoi) (nhung )?gi\b"),
    ("last_answer", r"\b(ban|bot) vua (noi|tra loi|bao|nhac) (gi|the nao|cai gi)\b"),
    ("last_answer", r"\bvua roi (ban|bot) (da )?(noi|tra loi|bao|nhac) (gi|the nao|cai gi)\b"),
    ("last_answer", r"\bcau tra loi (truoc|vua roi|gan nhat|luc nay)\b"),
    ("last_answer", r"\b(toi|minh) (vua|da|luc nay) hoi (gi|cai gi)\b"),
    ("last_answer", r"\bcau hoi (truoc|vua roi|luc nay)\b"),
    ("current_context", r"\b(dang|hien) (noi|hoi|trao doi|xem) (ve )?(ai|benh nhan nao)\b"),
    ("current_context", r"\bbenh nhan (hien tai|dang chon|dang xem|dang noi toi) la ai\b"),
]

_GREETING_WORDS = {"xin", "chao", "hello", "hi", "hey", "alo", "cam", "on", "thanks", "thank", "ok", "oke", "okay"}
_GREETING_FILLERS = {"ban", "bot", "nhe", "nha", "a", "ah", "nhieu", "you", "there", "minh", "em", "anh", "chi"}
_CAPABILITY_PATTERN = (
    r"\bban (la ai|lam (duoc )?(nhung )?gi|giup (duoc )?(gi|toi gi)|ho tro (duoc )?gi"
    r"|co the (lam|giup|ho tro) (duoc )?(nhung )?gi)\b"
)

_DATA_KEYWORDS = (
    MEDICATION_KEYWORDS + OBSERVATION_KEYWORDS + ENCOUNTER_KEYWORDS + CONDITION_KEYWORDS + PATIENT_INFO_KEYWORDS
)


def rule_route(message: str) -> RouteDecision:
    text = normalize_text(message or "")

    safety_flag = _safety_flag(text)
    if safety_flag:
        return RouteDecision(
            route=ROUTE_UNSUPPORTED,
            safety_flag=safety_flag,
            reason="Low-cost mode: luật an toàn chặn câu xin tư vấn y khoa.",
            source=SOURCE_LOW_COST,
        )

    for meta_kind, pattern in _META_PATTERNS:
        if re.search(pattern, text):
            return RouteDecision(
                route=ROUTE_CONVERSATION_META,
                meta_kind=meta_kind,
                reason="Low-cost mode: câu hỏi về cuộc trò chuyện.",
                source=SOURCE_LOW_COST,
            )

    if _is_small_talk(message, text):
        return RouteDecision(
            route=ROUTE_GENERAL_CHAT,
            reason="Low-cost mode: chào hỏi / hỏi về chức năng.",
            source=SOURCE_LOW_COST,
        )

    return RouteDecision(route=ROUTE_FHIR, reason="Low-cost mode: bỏ router LLM.", source=SOURCE_LOW_COST)


def _safety_flag(text: str) -> str | None:
    for flag, patterns in (
        ("treatment_request", _TREATMENT_PATTERNS),
        ("diagnosis_request", _DIAGNOSIS_PATTERNS),
        ("medical_advice", _ADVICE_PATTERNS),
    ):
        if any(re.search(pattern, text) for pattern in patterns):
            return flag
    return None


def _is_small_talk(message: str, text: str) -> bool:
    # Câu có nhắc tới dữ liệu/bệnh nhân thì luôn đi FHIR, kể cả khi mở đầu bằng "chào".
    if resolve_explicit_patient_id(message) or contains_any(text, _DATA_KEYWORDS):
        return False
    if re.search(_CAPABILITY_PATTERN, text):
        return True
    words = re.findall(r"[a-z0-9]+", text)
    if not words or len(words) > 6 or words[0] not in _GREETING_WORDS:
        return False
    return all(word in _GREETING_WORDS or word in _GREETING_FILLERS for word in words)
