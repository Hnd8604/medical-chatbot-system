package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final String RESULT_PREFIX = "BACKUP_RESULT_JSON=";

    private final BackupHistoryRepository backupHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${backup.enabled:true}")
    private boolean enabled;

    /** Đường dẫn tới backup.sh, tương đối theo thư mục chạy Spring (spring-backend/). */
    @Value("${backup.script-path:../infra/scripts/backup.sh}")
    private String scriptPath;

    /** Trình thông dịch bash (Git Bash trên Windows). Ghi đè bằng đường dẫn tuyệt đối nếu cần. */
    @Value("${backup.bash-path:bash}")
    private String bashPath;

    @Value("${backup.timeout-minutes:10}")
    private long timeoutMinutes;

    /**
     * Tạo bản ghi RUNNING rồi chạy script backup ở luồng nền. Trả về ngay bản ghi
     * đang chạy để frontend poll trạng thái qua {@link #getHistory}.
     */
    @Transactional
    public BackupHistory triggerManualBackup(String username) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chức năng sao lưu đang bị tắt (backup.enabled=false).");
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Đang có một tiến trình sao lưu chạy, vui lòng đợi hoàn tất.");
        }

        BackupHistory row = backupHistoryRepository.save(BackupHistory.builder()
                .triggerType(BackupTrigger.MANUAL)
                .status(BackupStatus.RUNNING)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());

        UUID id = row.getId();
        CompletableFuture.runAsync(() -> runBackup(id, "manual"));
        return row;
    }

    @Transactional(readOnly = true)
    public Page<BackupHistory> getHistory(Pageable pageable) {
        return backupHistoryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** Chạy backup.sh và cập nhật bản ghi. Chạy ở luồng nền (không có tx/security context). */
    void runBackup(UUID id, String trigger) {
        try {
            Path script = Paths.get(scriptPath).toAbsolutePath().normalize();
            // Git Bash chấp nhận dấu '/'; tránh rắc rối với '\' trên Windows.
            String scriptArg = script.toString().replace('\\', '/');

            ProcessBuilder pb = new ProcessBuilder(bashPath, scriptArg, trigger);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                markFailed(id, "Sao lưu quá thời gian (" + timeoutMinutes + " phút) và đã bị hủy.");
                return;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("backup.sh trả exit code {} cho backup {}", exitCode, id);
            }
            parseAndSave(id, stdout);
        } catch (Exception e) {
            log.error("Lỗi khi chạy backup {}: {}", id, e.getMessage(), e);
            markFailed(id, "Không chạy được script sao lưu: " + e.getMessage());
        }
    }

    private void parseAndSave(UUID id, String stdout) {
        String jsonLine = null;
        for (String line : stdout.split("\\R")) {
            int idx = line.indexOf(RESULT_PREFIX);
            if (idx >= 0) {
                jsonLine = line.substring(idx + RESULT_PREFIX.length()).trim();
                break;
            }
        }

        if (jsonLine == null || jsonLine.isBlank()) {
            String snippet = stdout.length() > 500 ? stdout.substring(0, 500) : stdout;
            markFailed(id, "Không đọc được kết quả sao lưu từ script. Output: " + snippet.trim());
            return;
        }

        try {
            JsonNode result = objectMapper.readTree(jsonLine);
            BackupStatus status = mapStatus(result.path("status").asText("failed"));
            JsonNode items = result.path("items");
            if (!items.isArray()) {
                items = JsonNodeFactory.instance.arrayNode();
            }

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
            row.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeFailure(status, items));
            backupHistoryRepository.save(row);
            log.info("Backup {} hoàn tất với trạng thái {} ({} bytes).", id, status, totalSize);
        } catch (Exception e) {
            log.error("Không parse được JSON kết quả backup {}: {}", id, e.getMessage(), e);
            markFailed(id, "Kết quả sao lưu không hợp lệ: " + e.getMessage());
        }
    }

    private void markFailed(UUID id, String message) {
        BackupHistory row = backupHistoryRepository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        row.setStatus(BackupStatus.FAILED);
        row.setFinishedAt(OffsetDateTime.now());
        row.setErrorMessage(message);
        backupHistoryRepository.save(row);
    }

    private BackupStatus mapStatus(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase()) {
            case "success" -> BackupStatus.SUCCESS;
            case "partial" -> BackupStatus.PARTIAL;
            default -> BackupStatus.FAILED;
        };
    }

    private String describeFailure(BackupStatus status, JsonNode items) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : items) {
            boolean ok = item.path("ok").asBoolean(false);
            boolean uploaded = item.path("uploaded").asBoolean(false);
            if (!ok) {
                sb.append(item.path("db").asText("?")).append(": dump lỗi; ");
            } else if (!uploaded) {
                sb.append(item.path("db").asText("?")).append(": upload Google Drive lỗi; ");
            }
        }
        if (sb.length() == 0) {
            return status == BackupStatus.PARTIAL ? "Sao lưu chỉ thành công một phần." : "Sao lưu thất bại.";
        }
        return sb.toString().trim();
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return OffsetDateTime.now();
        }
    }
}
