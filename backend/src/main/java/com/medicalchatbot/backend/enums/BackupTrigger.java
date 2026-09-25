package com.medicalchatbot.backend.enums;

/** Nguồn kích hoạt một lần sao lưu. */
public enum BackupTrigger {
    /** Chạy theo lịch (Windows Task Scheduler, 02:00). */
    AUTO,
    /** Admin bấm nút "Backup ngay" trên UI. */
    MANUAL
}
