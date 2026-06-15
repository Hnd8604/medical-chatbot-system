package com.medicalchatbot.backend.enums;

public enum AlertSeverity {
    INFO,       // Thông tin chung (vd: Hệ thống vừa backup xong)
    WARNING,    // Cảnh báo (vd: Quota của user sắp hết)
    CRITICAL    // Nghiêm trọng (vd: LLM sập, FHIR server mất kết nối)
}