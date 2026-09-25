package com.medicalchatbot.backend.enums;

/** Trạng thái của một lần sao lưu. */
public enum BackupStatus {
    /** Đang chạy (script chưa trả kết quả). */
    RUNNING,
    /** Tất cả DB dump + upload thành công. */
    SUCCESS,
    /** Một phần thành công (vd dump ok nhưng upload lỗi, hoặc chỉ 1 trong 2 DB ok). */
    PARTIAL,
    /** Thất bại hoàn toàn. */
    FAILED
}
