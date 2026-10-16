package com.medicalchatbot.backend.config.properties;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Backup/restore jobs run the scripts in {@code infra/scripts} through bash. The schedule itself
 * is read from {@code backup.cron} / {@code backup.timezone} by {@code @Scheduled}.
 */
@Validated
@ConfigurationProperties(prefix = "backup")
public record BackupProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank String scriptPath,
        @NotBlank String restoreScriptPath,
        @DefaultValue("bash") @NotBlank String bashPath,
        @DefaultValue("10m") @NotNull Duration timeout
) {
}
