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
    # Vital signs mở rộng
    "spo2",
    "do bao hoa oxy",
    "nhip tho",
    "tan so tho",
    "can nang",
    "chieu cao",
    "bmi",
    # Lab panels
    "creatinine",
    "ure",
    "ferritin",
    "bilirubin",
    "ast",
    "alt",
    "sgot",
    "sgpt",
    "triglyceride",
    "ldl",
    "hdl",
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
    # Mở rộng
    "nhap vien",
    "xuat vien",
    "cap cuu",
    "tai kham",
    "kham lai",
    "noi tru",
    "ngoai tru",
    "ra vien",
]

PATIENT_CONTACT_KEYWORDS = [
    "phone",
    "telephone",
    "mobile",
    "contact",
    "so dien thoai",
    "dien thoai",
]

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
    # Mở rộng
    "di ung",
    "tien su benh",
    "benh man tinh",
    "man tinh",
    "co tien su",
]

PATIENT_INFO_KEYWORDS = ["patient", "information", "info", "thong tin", "benh nhan"]

# Câu hỏi mang ý "giải thích/ý nghĩa/công dụng" -> bật terminology enrichment (explain=True).
EXPLAIN_KEYWORDS = [
    "la gi",
    "nghia la gi",
    "y nghia",
    "giai thich",
    "dung de lam gi",
    "cong dung",
    "tac dung",
    "noi len dieu gi",
    "meaning",
    "explain",
    "what is",
    "what does",
]
