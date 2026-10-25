"""Phát hiện câu mô tả tình huống cấp cứu — thuần luật, chạy trước cả semantic cache.

Mục tiêu là bắt câu người dùng đang gặp nguy hiểm ("tôi đang đau ngực, khó thở",
"bố tôi vừa ngất", "tôi muốn tự tử") để trả ngay hướng dẫn gọi 115, không tốn LLM call
và không thể bị cache hay lỗi LLM che mất.

Luật cố ý tránh câu tra cứu hồ sơ: câu có dấu hiệu hỏi dữ liệu / định nghĩa ("tiền sử",
"chẩn đoán", "BN2026-...", "là gì") không bị coi là cấp cứu, nên "bệnh nhân BN2026-00001
có ghi nhận khó thở không" vẫn đi nhánh FHIR như cũ.
"""

from __future__ import annotations

import re
import unicodedata

from nlp.text import normalize_text


EMERGENCY_MEDICAL = "medical"
EMERGENCY_SELF_HARM = "self_harm"

# Mẫu chạy trên văn bản đã normalize_text (không dấu, chữ thường).
_SYMPTOM_PATTERNS = (
    r"dau (that |quan |nhoi )?nguc",
    r"tuc nguc",
    r"kho tho",
    r"khong (the )?tho (duoc|noi)",
    r"ngung tho",
    r"tho doc",
    r"nghet tho",
    # "ngất" và "ngắt" (ngắt kết nối), "xỉu" và "xíu" (đợi xíu) trùng nhau khi bỏ dấu.
    r"ngat (xiu|di|lim)\b",
    r"\b(bi|vua|da|dang|hay) ngat\b(?! ket)",
    r"\b(bi|vua) xiu\b",
    r"xiu (di|nga)\b",
    r"bat tinh",
    r"hon me",
    r"co giat",
    r"dot quy",
    r"meo mieng",
    r"(liet|yeu) (nua nguoi|mot ben)",
    r"noi ngong",
    r"(chay|mat) (rat )?nhieu mau",
    r"(chay|mat) mau khong (cam|ngung|dung)",
    r"khong cam (duoc )?mau",
    r"(non|oi|ho|khac|di ngoai|di cau) ra mau",
    r"(uong|dung|nuot) (qua lieu|nham thuoc|ca vi thuoc|nhieu vien thuoc)",
    r"qua lieu",
    r"ngo doc",
    r"soc phan ve",
    r"sung (moi|hong|luoi)",
    r"dau dau (du doi|kinh khung|nhu bua bo|dot ngot)",
    r"sot cao (co giat|li bi|khong ha)",
    r"bong nang",
    r"tai nan",
    r"chest pain",
    r"can'?t breathe",
    r"short(ness)? of breath",
    r"unconscious",
    r"seizure",
    r"stroke",
)
# "tự tử" và "từ từ" trùng nhau khi bỏ dấu: dạng có dấu kiểm riêng (_SELF_HARM_ACCENTED),
# dạng không dấu chỉ nhận khi có từ chỉ ý định đi kèm.
_SELF_HARM_ACCENTED = ("tự tử", "tự sát")
_SELF_HARM_PATTERNS = (
    r"\b(muon|dinh|nghi den|nghi toi|se|sap|co y dinh|y dinh|tinh) tu tu\b",
    r"tu sat",
    r"muon chet",
    r"khong muon song",
    r"ket lieu (ban than|cuoc doi|doi)",
    r"tu (lam )?hai (ban than|minh)",
    r"tu lam dau (ban than|minh)",
    r"cat tay",
    r"kill myself",
    r"suicid",
    r"end my life",
)
# Người đang gặp chuyện, hoặc dấu hiệu "đang xảy ra".
_PERSONAL = (
    r"\b(toi|tui|minh|em|anh|chi|con|bo|me|ba|ong|vo|chong|chau|be|nguoi nha|nguoi than|ban toi|i|my)\b"
)
_URGENT = (
    r"\b(dang|vua|bong|tu nhien|dot ngot|hien tai|bay gio|luc nay|cuu|cap cuu|giup voi|khan cap|nguy kich|now|help)\b"
)
# Sự việc đã qua ("năm ngoái bị đột quỵ") — không cấp cứu nếu không kèm dấu hiệu "đang".
_PAST = r"\b(nam ngoai|nam truoc|thang truoc|tuan truoc|truoc day|ngay xua|hoi truoc|nhung nam truoc)\b"
# Câu tra cứu hồ sơ hoặc hỏi kiến thức — không phải người đang gặp nguy hiểm.
_RECORD_OR_KNOWLEDGE = (
    r"tien su|ho so|benh an|ghi nhan|chan doan|da tung|lan kham|ly do kham|dot kham|xet nghiem|"
    r"\bchi so\b|ket qua|don thuoc|\bbn\d|patient/|la gi|nghia la|dinh nghia|"
    r"trieu chung cua|dau hieu cua|nguyen nhan|bao nhieu benh nhan|benh nhan nao|tat ca benh nhan"
)


def detect_emergency(message: str) -> str | None:
    """Trả ``self_harm`` / ``medical`` nếu câu mô tả tình huống cấp cứu, ngược lại ``None``."""
    text = normalize_text(message or "")
    if not text.strip() or re.search(_RECORD_OR_KNOWLEDGE, text):
        return None
    lowered = unicodedata.normalize("NFC", message).lower()
    if any(phrase in lowered for phrase in _SELF_HARM_ACCENTED) or any(
        re.search(pattern, text) for pattern in _SELF_HARM_PATTERNS
    ):
        return EMERGENCY_SELF_HARM
    if not any(re.search(pattern, text) for pattern in _SYMPTOM_PATTERNS):
        return None
    urgent = re.search(_URGENT, text)
    if re.search(_PAST, text) and not urgent:
        return None
    if urgent or re.search(_PERSONAL, text):
        return EMERGENCY_MEDICAL
    return None
