from typing import Any

from agents.intent_extractor import contains_any


def _contains_any(text: str, keywords: list[str]) -> bool:
    return contains_any(text, keywords)

def _value_or_unknown(value: Any) -> Any:
    return value if value else "không rõ"

def _gender_vi(gender: Any) -> str:
    if gender == "male":
        return "nam"
    if gender == "female":
        return "nữ"
    if gender == "other":
        return "khác"
    return "không rõ"

def _display_vi(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    translations = {
        "blood pressure": "Huyết áp",
        "blood_pressure": "Huyết áp",
        "huyet ap": "Huyết áp",
        "blood pressure panel with all children optional": "Huyết áp",
        "systolic blood pressure": "Huyết áp tâm thu",
        "diastolic blood pressure": "Huyết áp tâm trương",
        "blood glucose": "Đường huyết",
        "glucose [mass/volume] in blood": "Đường huyết",
        "heart rate": "Nhịp tim",
        "heart_rate": "Nhịp tim",
        "nhip tim": "Nhịp tim",
        "hemoglobin a1c/hemoglobin.total in blood": "HbA1c",
        "hba1c": "HbA1c",
        "cholesterol [mass/volume] in serum or plasma": "Cholesterol toàn phần",
        "total cholesterol": "Cholesterol toàn phần",
        "prediabetes": "Tiền đái tháo đường",
        "hypertension": "Tăng huyết áp",
        "essential (primary) hypertension": "Tăng huyết áp nguyên phát",
        "acute upper respiratory infection": "Nhiễm trùng đường hô hấp trên cấp",
        "acute upper respiratory infection, unspecified": "Nhiễm trùng đường hô hấp trên cấp",
        "type 2 diabetes mellitus": "Đái tháo đường type 2",
        "type 2 diabetes mellitus without complications": "Đái tháo đường type 2 không biến chứng",
        "hyperlipidemia": "Rối loạn lipid máu",
        "hyperlipidemia, unspecified": "Rối loạn lipid máu",
        "amlodipine 5 mg tablet": "Amlodipine 5 mg",
        "amlodipine 5 mg oral tablet": "Amlodipine 5 mg",
        "metformin 500 mg tablet": "Metformin 500 mg",
        "metformin 500 mg oral tablet": "Metformin 500 mg",
        "paracetamol 500 mg tablet": "Paracetamol 500 mg",
        "acetaminophen 500 mg oral tablet": "Paracetamol 500 mg",
        "atorvastatin 20 mg tablet": "Atorvastatin 20 mg",
        "atorvastatin 20 mg oral tablet": "Atorvastatin 20 mg",
        "losartan 50 mg tablet": "Losartan 50 mg",
        "losartan 50 mg oral tablet": "Losartan 50 mg",
    }
    return translations.get(value.lower(), value)
