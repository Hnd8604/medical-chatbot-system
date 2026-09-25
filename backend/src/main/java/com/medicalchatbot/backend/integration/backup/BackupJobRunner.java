package com.medicalchatbot.backend.integration.backup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Infrastructure component that executes backup/restore scripts and persists
 * their results. Request validation and job creation remain in the application service layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupJobRunner {

    private static final String BACKUP_RESULT_PREFIX = "BACKUP_RESULT_JSON=";
    private static final String RESTORE_RESULT_PREFIX = "RESTORE_RESULT_JSON=";

    private final BackupHistoryRepository backupHistoryRepository;
    private final RestoreHistoryRepository restoreHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${backup.script-path:../infra/scripts/backup.sh}")
    private String scriptPath;

    @Value("${backup.restore-script-path:../infra/scripts/restore-auto.sh}")
    private String restoreScriptPath;

    @Value("${backup.bash-path:bash}")
    private String bashPath;

    @Value("${backup.timeout-minutes:10}")
    private long timeoutMinutes;

    public void runRestore(
            UUID id,
            UUID backupId,
            String username,
            OffsetDateTime startedAt,
            String appFile,
            String hapiFile
    ) {
        try {
            Path script = Paths.get(restoreScriptPath).toAbsolutePath().normalize();
            String scriptArg = script.toString().replace('\\', '/');

            ProcessBuilder processBuilder = new ProcessBuilder(bashPath, scriptArg, appFile, hapiFile);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                markRestoreFailed(id, backupId, username, startedAt,
                        "Khôi phục quá thời gian (" + timeoutMinutes + " phút) và đã bị hủy.");
                return;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            parseRestoreResult(id, backupId, username, startedAt, stdout);
        } catch (Exception exception) {
            log.error("Lỗi khi chạy khôi phục {}: {}", id, exception.getMessage(), exception);
            markRestoreFailed(id, backupId, username, startedAt,
                    "Không chạy được script khôi phục: " + exception.getMessage());
        }
    }

    public void runBackup(UUID id, String trigger) {
        try {
            Path script = Paths.get(scriptPath).toAbsolutePath().normalize();
            String scriptArg = script.toString().replace('\\', '/');

            ProcessBuilder processBuilder = new ProcessBuilder(bashPath, scriptArg, trigger);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                markBackupFailed(id, "Sao lưu quá thời gian (" + timeoutMinutes + " phút) và đã bị hủy.");
                return;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("backup.sh trả exit code {} cho backup {}", exitCode, id);
            }
            parseBackupResult(id, stdout);
        } catch (Exception exception) {
            log.error("Lỗi khi chạy backup {}: {}", id, exception.getMessage(), exception);
            markBackupFailed(id, "Không chạy được script sao lưu: " + exception.getMessage());
        }
    }

    void parseRestoreResult(
            UUID id,
            UUID backupId,
            String username,
            OffsetDateTime startedAt,
            String stdout
    ) {
        String json = extractResult(stdout, RESTORE_RESULT_PREFIX);
        if (json == null) {
            markRestoreFailed(id, backupId, username, startedAt,
                    "Không đọc được kết quả khôi phục từ script. Output: " + outputSnippet(stdout));
            return;
        }

        try {
            JsonNode result = objectMapper.readTree(json);
            BackupStatus status = mapStatus(result.path("status").asText("failed"));
            JsonNode items = arrayOrEmpty(result.path("items"));

            RestoreHistory row = loadOrRebuildRestore(id, backupId, username, startedAt);
            row.setStatus(status);
            row.setItemsJson(items);
            row.setFinishedAt(parseTime(result.path("finished_at").asText(null)));
            row.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeRestoreFailure(status, items));
            restoreHistoryRepository.save(row);
            log.info("Khôi phục {} hoàn tất với trạng thái {}.", id, status);
        } catch (Exception exception) {
            log.error("Không parse được JSON kết quả khôi phục {}: {}", id, exception.getMessage(), exception);
            markRestoreFailed(id, backupId, username, startedAt,
                    "Kết quả khôi phục không hợp lệ: " + exception.getMessage());
        }
    }

    void parseBackupResult(UUID id, String stdout) {
        String json = extractResult(stdout, BACKUP_RESULT_PREFIX);
        if (json == null) {
            markBackupFailed(id, "Không đọc được kết quả sao lưu từ script. Output: " + outputSnippet(stdout));
            return;
        }

        try {
            JsonNode result = objectMapper.readTree(json);
            BackupStatus status = mapStatus(result.path("status").asText("failed"));
            JsonNode items = arrayOrEmpty(result.path("items"));

            long totalSize = 0;
            for (JsonNode item : items) {
                totalSize += item.path("size_bytes").asLong(0);
            }

            JsonNode uploadTargetNode = result.path("upload_target");
            String uploadTarget = uploadTargetNode.isNull() || uploadTargetNode.isMissingNode()
                    ? null : uploadTargetNode.asText(null);

            BackupHistory row = backupHistoryRepository.findById(id).orElse(null);
            if (row == null) {
                log.warn("Không tìm thấy backup_history {} để cập nhật kết quả.", id);
                return;
            }
            row.setStatus(status);
            row.setItemsJson(items);
            row.setTotalSizeBytes(totalSize);
            row.setUploadTarget(uploadTarget);
            row.setFinishedAt(parseTime(result.path("finished_at").asText(null)));
            row.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeBackupFailure(status, items));
            backupHistoryRepository.save(row);
            log.info("Backup {} hoàn tất với trạng thái {} ({} bytes).", id, status, totalSize);
        } catch (Exception exception) {
            log.error("Không parse được JSON kết quả backup {}: {}", id, exception.getMessage(), exception);
            markBackupFailed(id, "Kết quả sao lưu không hợp lệ: " + exception.getMessage());
        }
    }

    private RestoreHistory loadOrRebuildRestore(
            UUID id,
            UUID backupId,
            String username,
            OffsetDateTime startedAt
    ) {
        return restoreHistoryRepository.findById(id).orElseGet(() -> {
            log.warn("restore_history {} không còn (đã bị restore app DB ghi đè); tạo lại bản ghi kết quả.", id);
            return RestoreHistory.builder()
                    .id(id)
                    .backupId(backupId)
                    .triggeredBy(username)
                    .startedAt(startedAt != null ? startedAt : OffsetDateTime.now())
                    .build();
        });
    }

    private void markRestoreFailed(
            UUID id,
            UUID backupId,
            String username,
            OffsetDateTime startedAt,
            String message
    ) {
        RestoreHistory row = loadOrRebuildRestore(id, backupId, username, startedAt);
        row.setStatus(BackupStatus.FAILED);
        row.setFinishedAt(OffsetDateTime.now());
        row.setErrorMessage(message);
        restoreHistoryRepository.save(row);
    }

    private void markBackupFailed(UUID id, String message) {
        BackupHistory row = backupHistoryRepository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        row.setStatus(BackupStatus.FAILED);
        row.setFinishedAt(OffsetDateTime.now());
        row.setErrorMessage(message);
        backupHistoryRepository.save(row);
    }

    private String extractResult(String stdout, String prefix) {
        for (String line : stdout.split("\\R")) {
            int index = line.indexOf(prefix);
            if (index >= 0) {
                String result = line.substring(index + prefix.length()).trim();
                return result.isBlank() ? null : result;
            }
        }
        return null;
    }

    private String outputSnippet(String stdout) {
        return (stdout.length() > 500 ? stdout.substring(0, 500) : stdout).trim();
    }

    private JsonNode arrayOrEmpty(JsonNode node) {
        return node.isArray() ? node : JsonNodeFactory.instance.arrayNode();
    }

    private BackupStatus mapStatus(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase()) {
            case "success" -> BackupStatus.SUCCESS;
            case "partial" -> BackupStatus.PARTIAL;
            default -> BackupStatus.FAILED;
        };
    }

    private String describeRestoreFailure(BackupStatus status, JsonNode items) {
        StringBuilder description = new StringBuilder();
        for (JsonNode item : items) {
            if (!item.path("ok").asBoolean(false)) {
                String error = item.path("error").asText("");
                description.append(item.path("db").asText("?")).append(": ")
                        .append(error.isBlank() ? "khôi phục lỗi" : error).append("; ");
            }
        }
        if (description.isEmpty()) {
            return status == BackupStatus.PARTIAL
                    ? "Khôi phục chỉ thành công một phần."
                    : "Khôi phục thất bại.";
        }
        return description.toString().trim();
    }

    private String describeBackupFailure(BackupStatus status, JsonNode items) {
        StringBuilder description = new StringBuilder();
        for (JsonNode item : items) {
            boolean ok = item.path("ok").asBoolean(false);
            boolean uploaded = item.path("uploaded").asBoolean(false);
            if (!ok) {
                description.append(item.path("db").asText("?")).append(": dump lỗi; ");
            } else if (!uploaded) {
                String uploadError = item.path("upload_error").asText("");
                description.append(item.path("db").asText("?")).append(": ")
                        .append(uploadError.isBlank() ? "upload Google Drive lỗi" : uploadError)
                        .append("; ");
            }
        }
        if (description.isEmpty()) {
            return status == BackupStatus.PARTIAL
                    ? "Sao lưu chỉ thành công một phần."
                    : "Sao lưu thất bại.";
        }
        return description.toString().trim();
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception exception) {
            return OffsetDateTime.now();
        }
    }
}
