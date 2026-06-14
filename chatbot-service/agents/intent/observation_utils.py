from __future__ import annotations

from agents.intent.text_utils import normalize_text, contains_any


def infer_observation_type(message: str) -> str | None:
    text = normalize_text(message)
    if contains_any(text, ["blood pressure", "blood_pressure", "huyet ap"]):
        return "blood_pressure"
    if contains_any(text, ["glucose", "duong huyet"]):
        return "glucose"
    if contains_any(text, ["heart rate", "nhip tim"]):
        return "heart_rate"
    if contains_any(text, ["ldl"]):
        return "ldl"
    if contains_any(text, ["hdl"]):
        return "hdl"
    if contains_any(text, ["cholesterol"]):
        return "cholesterol"
    if contains_any(text, ["hba1c", "a1c"]):
        return "hba1c"
    if contains_any(text, ["spo2", "do bao hoa oxy"]):
        return "spo2"
    if contains_any(text, ["nhip tho", "tan so tho", "respiratory"]):
        return "respiratory_rate"
    if contains_any(text, ["can nang", "weight"]):
        return "weight"
    if contains_any(text, ["chieu cao", "height"]):
        return "height"
    if contains_any(text, ["bmi"]):
        return "bmi"
    if contains_any(text, ["creatinine"]):
        return "creatinine"
    if contains_any(text, ["ure", "urea", "bun"]):
        return "urea"
    if contains_any(text, ["ferritin"]):
        return "ferritin"
    if contains_any(text, ["bilirubin"]):
        return "bilirubin"
    if contains_any(text, ["ast", "sgot"]):
        return "ast"
    if contains_any(text, ["alt", "sgpt"]):
        return "alt"
    if contains_any(text, ["triglyceride"]):
        return "triglyceride"
    return None
