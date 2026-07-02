#!/usr/bin/env python
"""Sinh dữ liệu FHIR mô phỏng thực tế cho HAPI (100 bản ghi mỗi loại).

Tạo một transaction Bundle gồm 100 Patient, 100 Encounter, 100 Observation,
100 Condition và 100 MedicationRequest, liên kết chéo và nhất quán lâm sàng.

- ID bệnh nhân dạng mã hồ sơ thực tế: BN2026-00001 .. BN2026-00100
- Không dùng chữ "demo" ở bất cứ đâu.
- Chỉ tham chiếu bác sĩ bằng display (HAPI bật enforce_referential_integrity).

Chạy:
    python infra/hapi-fhir/scripts/generate_fhir_seed.py
Ghi ra: infra/hapi-fhir/seed/synthetic-fhir-transaction-bundle.json
"""

from __future__ import annotations

import json
import random
from pathlib import Path

SEED_DIR = Path(__file__).resolve().parents[1] / "seed"
OUTPUT_FILE = SEED_DIR / "synthetic-fhir-transaction-bundle.json"

PATIENT_COUNT = 100
PATIENT_ID_SYSTEM = "http://medical-chatbot.local/patient-id"
MRN_SYSTEM = "http://hospital.example.vn/mrn"
TZ = "+07:00"

rng = random.Random(20260702)

# --- Danh mục tên tiếng Việt -------------------------------------------------

FAMILY_NAMES = [
    "Nguyen", "Tran", "Le", "Pham", "Hoang", "Huynh", "Phan", "Vu", "Vo", "Dang",
    "Bui", "Do", "Ho", "Ngo", "Duong", "Ly", "Dinh", "Trinh", "Doan", "Luong",
]
MALE_MIDDLE = ["Van", "Minh", "Quang", "Huu", "Duc", "Thanh", "Cong", "Ba", "Xuan", "Hoang"]
MALE_GIVEN = ["Hung", "Nam", "Tuan", "Khoa", "An", "Binh", "Cuong", "Dung", "Hai",
              "Long", "Phong", "Son", "Trung", "Vinh", "Thang", "Kien"]
FEMALE_MIDDLE = ["Thi", "Ngoc", "Thu", "Kim", "Mai", "Hong", "Bich", "Thanh"]
FEMALE_GIVEN = ["Hoa", "Lan", "Huong", "Mai", "Nga", "Trang", "Yen", "Thao", "Linh",
                "Ha", "Anh", "Chi", "Loan", "Phuong", "Nhung", "Tuyet"]

DOCTORS = [
    "BS. Nguyen Van Hung", "BS. Tran Thi Lan", "BS. Le Quang Minh", "BS. Pham Thu Ha",
    "BS. Hoang Duc Anh", "BS. Vo Thi Nga", "BS. Dang Minh Tuan", "BS. Bui Thi Mai",
]

CITIES = [
    ("Ha Noi", ["Cau Giay", "Dong Da", "Hoan Kiem", "Ha Dong", "Thanh Xuan"]),
    ("Ho Chi Minh", ["Quan 1", "Quan 3", "Binh Thanh", "Thu Duc", "Go Vap"]),
    ("Da Nang", ["Hai Chau", "Thanh Khe", "Son Tra"]),
    ("Hai Phong", ["Le Chan", "Ngo Quyen"]),
    ("Can Tho", ["Ninh Kieu", "Cai Rang"]),
]
STREETS = ["Nguyen Trai", "Le Loi", "Tran Hung Dao", "Hai Ba Trung", "Ly Thuong Kiet",
           "Nguyen Hue", "Le Duan", "Phan Chu Trinh", "Dinh Tien Hoang", "Ba Trieu"]

MARITAL = [
    ("M", "Married"), ("S", "Never Married"), ("W", "Widowed"), ("D", "Divorced"),
]

# --- Hồ sơ lâm sàng (bệnh + chỉ số + thuốc nhất quán) ------------------------

INTERP_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation"


def interp(code: str, display: str) -> dict:
    return {"coding": [{"system": INTERP_SYSTEM, "code": code, "display": display}], "text": display}


def bp_value(_rng: random.Random) -> dict:
    """Blood pressure dùng component (systolic/diastolic)."""
    sys = _rng.randint(112, 168)
    dia = _rng.randint(68, 104)
    high = sys >= 140 or dia >= 90
    return {
        "components": [
            ("8480-6", "Systolic blood pressure", sys, "mmHg", "mm[Hg]"),
            ("8462-4", "Diastolic blood pressure", dia, "mmHg", "mm[Hg]"),
        ],
        "interpretation": interp("H", "High") if high else interp("N", "Normal"),
        "note": f"Huyet ap {sys}/{dia} mmHg do tai phong kham.",
    }


def simple_value(loinc, display, unit, ucum, lo, hi, ref_low, ref_high, decimals=0):
    def build(_rng: random.Random) -> dict:
        if decimals:
            value = round(_rng.uniform(lo, hi), decimals)
        else:
            value = _rng.randint(int(lo), int(hi))
        if ref_high is not None and value > ref_high:
            it = interp("H", "High")
        elif ref_low is not None and value < ref_low:
            it = interp("L", "Low")
        else:
            it = interp("N", "Normal")
        rr = {}
        if ref_low is not None:
            rr["low"] = {"value": ref_low, "unit": unit, "system": "http://unitsofmeasure.org", "code": ucum}
        if ref_high is not None:
            rr["high"] = {"value": ref_high, "unit": unit, "system": "http://unitsofmeasure.org", "code": ucum}
        rr["text"] = "Khoang tham chieu nguoi lon."
        return {
            "value": {"value": value, "unit": unit, "system": "http://unitsofmeasure.org", "code": ucum},
            "loinc": loinc, "display": display,
            "interpretation": it,
            "reference_range": rr,
            "note": f"{display}: {value} {unit}.",
        }
    return build


PROFILES = [
    {
        "key": "hypertension",
        "encounter": ("185349003", "Encounter for check up", "Kham dinh ky tang huyet ap"),
        "reason": "Theo doi huyet ap dinh ky",
        "condition": {"icd10": "I10", "display": "Essential (primary) hypertension",
                      "text": "Tang huyet ap vo can", "status": "active", "severity": "moderate",
                      "onset_years": (2, 8)},
        "observation": {"category": "vital-signs", "code": "85354-9",
                        "display": "Blood pressure panel", "text": "Huyet ap", "builder": bp_value},
        "medication": {"rxnorm": "197361", "display": "Amlodipine 5 MG Oral Tablet",
                       "text": "Amlodipin 5 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (5 mg) moi sang.",
                       "status": "active"},
    },
    {
        "key": "diabetes",
        "encounter": ("390906007", "Follow-up encounter", "Tai kham dai thao duong"),
        "reason": "Kiem soat duong huyet",
        "condition": {"icd10": "E11.9", "display": "Type 2 diabetes mellitus without complications",
                      "text": "Dai thao duong type 2", "status": "active", "severity": "moderate",
                      "onset_years": (1, 10)},
        "observation": {"category": "laboratory", "code": "4548-4",
                        "display": "Hemoglobin A1c/Hemoglobin.total in Blood", "text": "HbA1c",
                        "builder": simple_value("4548-4", "HbA1c", "%", "%", 5.5, 10.5, None, 5.7, decimals=1)},
        "medication": {"rxnorm": "861007", "display": "Metformin hydrochloride 500 MG Oral Tablet",
                       "text": "Metformin 500 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (500 mg) x 2 lan/ngay sau an.",
                       "status": "active"},
    },
    {
        "key": "hyperlipidemia",
        "encounter": ("185349003", "Encounter for check up", "Kham roi loan lipid mau"),
        "reason": "Kiem tra mo mau",
        "condition": {"icd10": "E78.5", "display": "Hyperlipidemia, unspecified",
                      "text": "Roi loan lipid mau", "status": "active", "severity": "mild",
                      "onset_years": (1, 6)},
        "observation": {"category": "laboratory", "code": "2093-3",
                        "display": "Cholesterol [Mass/volume] in Serum or Plasma", "text": "Cholesterol toan phan",
                        "builder": simple_value("2093-3", "Cholesterol toan phan", "mg/dL", "mg/dL",
                                                170, 300, None, 200)},
        "medication": {"rxnorm": "617312", "display": "Atorvastatin 20 MG Oral Tablet",
                       "text": "Atorvastatin 20 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (20 mg) vao buoi toi.",
                       "status": "active"},
    },
    {
        "key": "asthma",
        "encounter": ("185345009", "Encounter for symptom", "Kham hen phe quan"),
        "reason": "Kho tho tai phat",
        "condition": {"icd10": "J45.909", "display": "Unspecified asthma, uncomplicated",
                      "text": "Hen phe quan", "status": "active", "severity": "mild",
                      "onset_years": (2, 12)},
        "observation": {"category": "vital-signs", "code": "2708-6",
                        "display": "Oxygen saturation in Arterial blood", "text": "Do bao hoa oxy (SpO2)",
                        "builder": simple_value("2708-6", "SpO2", "%", "%", 91, 99, 95, None)},
        "medication": {"rxnorm": "745752", "display": "Salbutamol 100 microgram/actuation inhaler",
                       "text": "Salbutamol xit dinh lieu", "dose": (2, "puff", "{puff}"),
                       "route": "Duong hit",
                       "instruction": "Xit 1-2 nhat khi kho tho, toi da 4 lan/ngay.",
                       "status": "active"},
    },
    {
        "key": "uri",
        "encounter": ("185345009", "Encounter for symptom", "Kham viem duong ho hap tren"),
        "reason": "Sot, ho, dau hong",
        "condition": {"icd10": "J06.9", "display": "Acute upper respiratory infection, unspecified",
                      "text": "Viem duong ho hap tren cap", "status": "resolved", "severity": "mild",
                      "onset_years": (0, 0)},
        "observation": {"category": "vital-signs", "code": "8310-5",
                        "display": "Body temperature", "text": "Nhiet do co the",
                        "builder": simple_value("8310-5", "Nhiet do", "Cel", "Cel", 36.4, 39.4, 36.1, 37.2, decimals=1)},
        "medication": {"rxnorm": "198440", "display": "Acetaminophen 500 MG Oral Tablet",
                       "text": "Paracetamol 500 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (500 mg) moi 6 gio khi sot.",
                       "status": "completed"},
    },
    {
        "key": "gastritis",
        "encounter": ("185345009", "Encounter for symptom", "Kham dau da day"),
        "reason": "Dau thuong vi, o hoi",
        "condition": {"icd10": "K29.70", "display": "Gastritis, unspecified, without bleeding",
                      "text": "Viem da day", "status": "active", "severity": "mild",
                      "onset_years": (0, 3)},
        "observation": {"category": "vital-signs", "code": "29463-7",
                        "display": "Body weight", "text": "Can nang",
                        "builder": simple_value("29463-7", "Can nang", "kg", "kg", 45, 88, None, None)},
        "medication": {"rxnorm": "200329", "display": "Omeprazole 20 MG Delayed Release Oral Capsule",
                       "text": "Omeprazol 20 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (20 mg) truoc an sang 30 phut.",
                       "status": "active"},
    },
    {
        "key": "hypothyroidism",
        "encounter": ("390906007", "Follow-up encounter", "Tai kham tuyen giap"),
        "reason": "Theo doi chuc nang tuyen giap",
        "condition": {"icd10": "E03.9", "display": "Hypothyroidism, unspecified",
                      "text": "Suy giap", "status": "active", "severity": "mild",
                      "onset_years": (1, 7)},
        "observation": {"category": "laboratory", "code": "3016-3",
                        "display": "Thyrotropin [Units/volume] in Serum or Plasma", "text": "TSH",
                        "builder": simple_value("3016-3", "TSH", "mIU/L", "m[IU]/L", 0.4, 8.5, 0.4, 4.0, decimals=2)},
        "medication": {"rxnorm": "966224", "display": "Levothyroxine sodium 0.05 MG Oral Tablet",
                       "text": "Levothyroxin 50 mcg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (50 mcg) moi sang khi bung doi.",
                       "status": "active"},
    },
    {
        "key": "anemia",
        "encounter": ("185349003", "Encounter for check up", "Kham thieu mau"),
        "reason": "Met moi, da xanh",
        "condition": {"icd10": "D50.9", "display": "Iron deficiency anemia, unspecified",
                      "text": "Thieu mau thieu sat", "status": "active", "severity": "mild",
                      "onset_years": (0, 2)},
        "observation": {"category": "laboratory", "code": "718-7",
                        "display": "Hemoglobin [Mass/volume] in Blood", "text": "Hemoglobin",
                        "builder": simple_value("718-7", "Hemoglobin", "g/dL", "g/dL", 8.0, 15.0, 12.0, None, decimals=1)},
        "medication": {"rxnorm": "861722", "display": "Ferrous sulfate 325 MG Oral Tablet",
                       "text": "Sat sulfat 325 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien/ngay sau an, kem vitamin C.",
                       "status": "active"},
    },
    {
        "key": "osteoarthritis",
        "encounter": ("185345009", "Encounter for symptom", "Kham dau khop goi"),
        "reason": "Dau khop goi khi van dong",
        "condition": {"icd10": "M17.9", "display": "Osteoarthritis of knee, unspecified",
                      "text": "Thoai hoa khop goi", "status": "active", "severity": "moderate",
                      "onset_years": (1, 9)},
        "observation": {"category": "vital-signs", "code": "8867-4",
                        "display": "Heart rate", "text": "Nhip tim",
                        "builder": simple_value("8867-4", "Nhip tim", "/min", "/min", 58, 102, 60, 100)},
        "medication": {"rxnorm": "103766", "display": "Meloxicam 7.5 MG Oral Tablet",
                       "text": "Meloxicam 7,5 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (7,5 mg)/ngay sau an.",
                       "status": "active"},
    },
    {
        "key": "migraine",
        "encounter": ("185345009", "Encounter for symptom", "Kham dau nua dau"),
        "reason": "Dau dau tai phat",
        "condition": {"icd10": "G43.909",
                      "display": "Migraine, unspecified, not intractable, without status migrainosus",
                      "text": "Dau nua dau (Migraine)", "status": "active", "severity": "mild",
                      "onset_years": (1, 6)},
        "observation": {"category": "vital-signs", "code": "8867-4",
                        "display": "Heart rate", "text": "Nhip tim",
                        "builder": simple_value("8867-4", "Nhip tim", "/min", "/min", 60, 98, 60, 100)},
        "medication": {"rxnorm": "197806", "display": "Ibuprofen 400 MG Oral Tablet",
                       "text": "Ibuprofen 400 mg", "dose": (1, "vien", "{tbl}"),
                       "route": "Duong uong",
                       "instruction": "Uong 1 vien (400 mg) khi dau dau, toi da 3 lan/ngay.",
                       "status": "active"},
    },
]

SEVERITY_SNOMED = {
    "mild": ("255604002", "Mild"),
    "moderate": ("6736007", "Moderate"),
    "severe": ("24484000", "Severe"),
}


def make_name(gender: str) -> dict:
    family = rng.choice(FAMILY_NAMES)
    if gender == "male":
        given = [rng.choice(MALE_MIDDLE), rng.choice(MALE_GIVEN)]
    else:
        given = [rng.choice(FEMALE_MIDDLE), rng.choice(FEMALE_GIVEN)]
    return {"use": "official", "family": family, "given": given}


def full_name(name: dict) -> str:
    return " ".join([name["family"], *name["given"]])


def make_encounter_datetime(index: int) -> tuple[str, str]:
    """Trải các lượt khám trong 6 thang dau 2026."""
    month = (index % 6) + 1
    day = (index % 27) + 1
    hour = 8 + (index % 8)
    start = f"2026-{month:02d}-{day:02d}T{hour:02d}:00:00{TZ}"
    end = f"2026-{month:02d}-{day:02d}T{hour:02d}:35:00{TZ}"
    return start, end


def build() -> dict:
    entries: list[dict] = []

    for i in range(1, PATIENT_COUNT + 1):
        pid = f"BN2026-{i:05d}"
        profile = PROFILES[(i - 1) % len(PROFILES)]
        gender = "male" if rng.random() < 0.5 else "female"
        name = make_name(gender)
        # Tao 1 cap trung ten de test tinh nang chon benh nhan (ambiguous).
        if i == 52:
            name = {"use": "official", "family": "Tran", "given": ["Thi", "Mai"]}
        if i == 2:
            name = {"use": "official", "family": "Tran", "given": ["Thi", "Mai"]}
            gender = "female"
        display_name = full_name(name)

        birth_year = rng.randint(1950, 2005)
        birth = f"{birth_year}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"
        phone = f"09{i:08d}"
        city, districts = rng.choice(CITIES)
        district = rng.choice(districts)
        marital_code, marital_display = rng.choice(MARITAL)
        doctor = DOCTORS[(i - 1) % len(DOCTORS)]
        enc_start, enc_end = make_encounter_datetime(i - 1)
        enc_date = enc_start[:10]

        enc_id = f"ENC-2026-{i:05d}"
        obs_id = f"OBS-2026-{i:05d}"
        con_id = f"CON-2026-{i:05d}"
        med_id = f"MED-2026-{i:05d}"

        # -- Patient --
        patient = {
            "resourceType": "Patient",
            "id": pid,
            "active": True,
            "identifier": [
                {"system": PATIENT_ID_SYSTEM, "value": pid},
                {"system": MRN_SYSTEM, "value": f"MRN-2026-{i:05d}"},
            ],
            "name": [name],
            "telecom": [
                {"system": "phone", "value": phone, "use": "mobile"},
                {"system": "email", "value": f"benhnhan{i:03d}@example.vn", "use": "home"},
            ],
            "gender": gender,
            "birthDate": birth,
            "address": [{
                "use": "home", "type": "physical",
                "line": [f"{rng.randint(1, 250)} {rng.choice(STREETS)}"],
                "district": district, "city": city, "country": "VN",
            }],
            "maritalStatus": {
                "coding": [{"system": "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus",
                            "code": marital_code, "display": marital_display}],
                "text": marital_display,
            },
            "contact": [{
                "relationship": [{
                    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0131",
                                "code": "N", "display": "Next-of-Kin"}],
                    "text": "Nguoi than",
                }],
                "name": {"family": name["family"], "given": [rng.choice(MALE_GIVEN + FEMALE_GIVEN)]},
                "telecom": [{"system": "phone", "value": f"08{i:08d}", "use": "mobile"}],
            }],
        }
        entries.append(_entry(patient, "Patient", pid))

        # -- Encounter --
        enc_code, enc_display, enc_text = profile["encounter"]
        encounter = {
            "resourceType": "Encounter",
            "id": enc_id,
            "status": "finished",
            "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                      "code": "AMB", "display": "ambulatory"},
            "type": [{"coding": [{"system": "http://snomed.info/sct", "code": enc_code,
                                  "display": enc_display}], "text": enc_text}],
            "serviceType": {"coding": [{"system": "http://snomed.info/sct", "code": "394802001",
                                        "display": "General medicine"}], "text": "Kham noi tong quat"},
            "subject": {"reference": f"Patient/{pid}", "display": display_name},
            "participant": [{
                "type": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v3-ParticipationType",
                                      "code": "ATND", "display": "attender"}]}],
                "individual": {"display": doctor},
            }],
            "period": {"start": enc_start, "end": enc_end},
            "reasonCode": [{"text": profile["reason"]}],
            "location": [{"location": {"display": f"Phong kham ngoai tru {((i - 1) % 6) + 1}"},
                          "status": "completed"}],
        }
        entries.append(_entry(encounter, "Encounter", enc_id))

        # -- Observation --
        obs_spec = profile["observation"]
        obs_data = obs_spec["builder"](rng)
        observation = {
            "resourceType": "Observation",
            "id": obs_id,
            "status": "final",
            "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category",
                                      "code": obs_spec["category"],
                                      "display": "Vital Signs" if obs_spec["category"] == "vital-signs" else "Laboratory"}]}],
            "code": {"coding": [{"system": "http://loinc.org", "code": obs_spec["code"],
                                 "display": obs_spec["display"]}], "text": obs_spec["text"]},
            "subject": {"reference": f"Patient/{pid}", "display": display_name},
            "encounter": {"reference": f"Encounter/{enc_id}"},
            "effectiveDateTime": f"{enc_date}T{enc_start[11:16]}:00{TZ}",
            "issued": enc_end,
            "performer": [{"display": doctor}],
        }
        if "components" in obs_data:
            observation["component"] = [
                {"code": {"coding": [{"system": "http://loinc.org", "code": c, "display": d}]},
                 "valueQuantity": {"value": v, "unit": u, "system": "http://unitsofmeasure.org", "code": uc}}
                for (c, d, v, u, uc) in obs_data["components"]
            ]
        else:
            observation["valueQuantity"] = obs_data["value"]
            observation["referenceRange"] = [obs_data["reference_range"]]
        observation["interpretation"] = [obs_data["interpretation"]]
        observation["note"] = [{"text": obs_data["note"]}]
        entries.append(_entry(observation, "Observation", obs_id))

        # -- Condition --
        cond = profile["condition"]
        sev_code, sev_display = SEVERITY_SNOMED[cond["severity"]]
        resolved = cond["status"] == "resolved"
        onset_lo, onset_hi = cond["onset_years"]
        onset_year = 2026 - rng.randint(onset_lo, onset_hi) if onset_hi else 2026
        onset = f"{onset_year}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"
        condition = {
            "resourceType": "Condition",
            "id": con_id,
            "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                                           "code": cond["status"],
                                           "display": "Resolved" if resolved else "Active"}]},
            "verificationStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                                               "code": "confirmed", "display": "Confirmed"}]},
            "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-category",
                                      "code": "encounter-diagnosis", "display": "Encounter Diagnosis"}]}],
            "severity": {"coding": [{"system": "http://snomed.info/sct", "code": sev_code,
                                     "display": sev_display}], "text": sev_display},
            "code": {"coding": [{"system": "http://hl7.org/fhir/sid/icd-10", "code": cond["icd10"],
                                 "display": cond["display"]}], "text": cond["text"]},
            "subject": {"reference": f"Patient/{pid}", "display": display_name},
            "encounter": {"reference": f"Encounter/{enc_id}"},
            "onsetDateTime": onset,
            "recordedDate": enc_date,
            "asserter": {"display": doctor},
            "note": [{"text": f"Chan doan: {cond['text']} ({cond['icd10']})."}],
        }
        if resolved:
            condition["abatementDateTime"] = enc_date
        entries.append(_entry(condition, "Condition", con_id))

        # -- MedicationRequest --
        med = profile["medication"]
        dose_val, dose_unit, dose_ucum = med["dose"]
        medication = {
            "resourceType": "MedicationRequest",
            "id": med_id,
            "status": med["status"],
            "intent": "order",
            "priority": "routine",
            "medicationCodeableConcept": {
                "coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm",
                            "code": med["rxnorm"], "display": med["display"]}],
                "text": med["text"],
            },
            "subject": {"reference": f"Patient/{pid}", "display": display_name},
            "encounter": {"reference": f"Encounter/{enc_id}"},
            "authoredOn": enc_date,
            "requester": {"display": doctor},
            "reasonCode": [{"text": cond["text"]}],
            "reasonReference": [{"reference": f"Condition/{con_id}", "display": cond["text"]}],
            "dosageInstruction": [{
                "sequence": 1,
                "text": med["instruction"],
                "patientInstruction": "Dung thuoc theo huong dan cua bac si.",
                "route": {"text": med["route"]},
                "doseAndRate": [{"doseQuantity": {"value": dose_val, "unit": dose_unit,
                                                  "system": "http://unitsofmeasure.org", "code": dose_ucum}}],
            }],
            "dispenseRequest": {
                "numberOfRepeatsAllowed": 2,
                "quantity": {"value": 30, "unit": dose_unit},
                "expectedSupplyDuration": {"value": 30, "unit": "days",
                                           "system": "http://unitsofmeasure.org", "code": "d"},
            },
            "note": [{"text": f"Ke don {med['text']} cho {cond['text']}."}],
        }
        entries.append(_entry(medication, "MedicationRequest", med_id))

    return {"resourceType": "Bundle", "type": "transaction", "entry": entries}


def _entry(resource: dict, resource_type: str, resource_id: str) -> dict:
    return {"resource": resource, "request": {"method": "PUT", "url": f"{resource_type}/{resource_id}"}}


def main() -> int:
    bundle = build()
    OUTPUT_FILE.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    counts: dict[str, int] = {}
    for entry in bundle["entry"]:
        rt = entry["resource"]["resourceType"]
        counts[rt] = counts.get(rt, 0) + 1
    print(f"Wrote {len(bundle['entry'])} resources to {OUTPUT_FILE}")
    print("Counts:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
