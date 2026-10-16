package com.medicalchatbot.backend.enums;

public enum AlertSeverity {
    /** Thông tin chung (vd hệ thống vừa backup xong). */
    INFO,
    /** Cảnh báo (vd quota của user sắp hết). */
    WARNING,
    /** Nghiêm trọng (vd LLM sập, FHIR server mất kết nối). */
    CRITICAL
}
