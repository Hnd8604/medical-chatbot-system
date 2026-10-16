package com.medicalchatbot.backend.enums;

import java.util.Locale;

/** Nguồn kích hoạt một lần sao lưu. */
public enum BackupTrigger {
    /** Chạy theo lịch {@code backup.cron} của backend (mặc định 02:00 hằng ngày). */
    AUTO,
    /** Admin bấm nút "Backup ngay" trên UI. */
    MANUAL;

    /** Giá trị truyền cho {@code backup.sh}. */
    public String scriptArgument() {
        return name().toLowerCase(Locale.ROOT);
    }
}
