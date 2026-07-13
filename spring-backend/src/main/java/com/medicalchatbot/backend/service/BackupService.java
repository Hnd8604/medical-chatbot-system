package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final String RESTORE_RESULT_PREFIX = "RESTORE_RESULT_JSON=";

    private final BackupHistoryRepository backupHistoryRepository;
    private final RestoreHistoryRepository restoreHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${backup.enabled:true}")
    private boolean enabled;

    /** Đường dẫn tới backup.sh, tương đối theo thư mục chạy Spring (spring-backend/). */
    @Value("${backup.script-path:../infra/scripts/backup.sh}")
    private String scriptPath;

    /** Đường dẫn tới restore-auto.sh (bản khôi phục không tương tác). */
    @Value("${backup.restore-script-path:../infra/scripts/restore-auto.sh}")
    private String restoreScriptPath;

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
        return startBackup(BackupTrigger.MANUAL, username, "manual");
    }

    /**
     * Sao lưu tự động theo lịch. Backend tự xử lý (không cần Windows Task Scheduler).
     * Mặc định 02:00 hằng ngày; đổi qua {@code backup.cron} / {@code backup.timezone}.
     */
    @Scheduled(cron = "${backup.cron:0 0 2 * * *}", zone = "${backup.timezone:Asia/Ho_Chi_Minh}")
    public void scheduledBackup() {
        if (!enabled) {
            log.debug("Bỏ qua backup theo lịch: backup.enabled=false.");
            return;
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            log.warn("Bỏ qua backup theo lịch: đang có một tiến trình sao lưu chạy.");
            return;
        }
        log.info("Bắt đầu sao lưu tự động theo lịch...");
        startBackup(BackupTrigger.AUTO, null, "auto");
    }

    /** Tạo bản ghi RUNNING và chạy script ở luồng nền. */
    private BackupHistory startBackup(BackupTrigger trigger, String username, String triggerArg) {
        BackupHistory row = backupHistoryRepository.save(BackupHistory.builder()
                .triggerType(trigger)
                .status(BackupStatus.RUNNING)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());

        UUID id = row.getId();
        CompletableFuture.runAsync(() -> runBackup(id, triggerArg));
        return row;
    }

    @Transactional(readOnly = true)
    public Page<BackupHistory> getHistory(Pageable pageable) {
        return backupHistoryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // ===================== KHÔI PHỤC (RESTORE) =====================

    /**
     * Khôi phục CSDL từ một bản backup đã có. Tạo bản ghi RUNNING rồi chạy
     * restore-auto.sh ở luồng nền. CẨN THẬN: ghi đè dữ liệu hiện tại.
     */
    @Transactional
    public RestoreHistory triggerRestore(UUID backupId, String username) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chức năng sao lưu/khôi phục đang bị tắt (backup.enabled=false).");
        }
        if (backupHistoryRepository.existsByStatus(BackupStatus.RUNNING)
                || restoreHistoryRepository.existsByStatus(BackupStatus.RUNNING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Đang có một tiến trình sao lưu/khôi phục chạy, vui lòng đợi hoàn tất.");
        }

        BackupHistory backup = backupHistoryRepository.findById(backupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy bản sao lưu với ID: " + backupId));
        if (backup.getStatus() != BackupStatus.SUCCESS && backup.getStatus() != BackupStatus.PARTIAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bản sao lưu này không có dữ liệu hợp lệ để khôi phục.");
        }

        String appFile = "";
        String hapiFile = "";
        JsonNode items = backup.getItemsJson();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (!item.path("ok").asBoolean(false)) {
                    continue;
                }
                String db = item.path("db").asText("");
                String file = item.path("file").asText("");
                if ("app".equals(db)) {
                    appFile = file;
                } else if ("hapi".equals(db)) {
                    hapiFile = file;
                }
            }
        }
        if (appFile.isEmpty() && hapiFile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bản sao lưu không có file nào để khôi phục.");
        }

        RestoreHistory row = restoreHistoryRepository.save(RestoreHistory.builder()
                .backupId(backupId)
                .status(BackupStatus.RUNNING)
                .triggeredBy(username)
                .startedAt(OffsetDateTime.now())
                .build());

        UUID id = row.getId();
        OffsetDateTime startedAt = row.getStartedAt();
        String appArg = appFile;
        String hapiArg = hapiFile;
        CompletableFuture.runAsync(() -> runRestore(id, backupId, username, startedAt, appArg, hapiArg));
        return row;
    }

    @Transactional(readOnly = true)
    public Page<RestoreHistory> getRestoreHistory(Pageable pageable) {
        return restoreHistoryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** Chạy restore-auto.sh và cập nhật bản ghi. Chạy ở luồng nền. */
    void runRestore(UUID id, UUID backupId, String username, OffsetDateTime startedAt,
                    String appFile, String hapiFile) {
        try {
            Path script = Paths.get(restoreScriptPath).toAbsolutePath().normalize();
            String scriptArg = script.toString().replace('\\', '/');

            ProcessBuilder pb = new ProcessBuilder(bashPath, scriptArg, appFile, hapiFile);
            // Gộp stderr vào stdout: nếu bash lỗi (vd trỏ nhầm WSL), lỗi thật vẫn đọc được
            // để đưa vào error_message thay vì "Output:" trống.
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                markRestoreFailed(id, backupId, username, startedAt,
                        "Khôi phục quá thời gian (" + timeoutMinutes + " phút) và đã bị hủy.");
                return;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            parseRestoreResult(id, backupId, username, startedAt, stdout);
        } catch (Exception e) {
            log.error("Lỗi khi chạy khôi phục {}: {}", id, e.getMessage(), e);
            markRestoreFailed(id, backupId, username, startedAt,
                    "Không chạy được script khôi phục: " + e.getMessage());
        }
    }

    /**
     * Lấy bản ghi restore theo id; nếu không còn (bị chính quá trình restore app DB
     * xóa khi khôi phục snapshot cũ chưa loại 2 bảng theo dõi), dựng lại từ context
     * ban đầu với cùng id để trạng thái vẫn được ghi thay vì kẹt "ĐANG CHẠY".
     */
    private RestoreHistory loadOrRebuildRestore(UUID id, UUID backupId, String username,
                                                OffsetDateTime startedAt) {
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

    private void parseRestoreResult(UUID id, UUID backupId, String username,
                                    OffsetDateTime startedAt, String stdout) {
        String jsonLine = null;
        for (String line : stdout.split("\\R")) {
            int idx = line.indexOf(RESTORE_RESULT_PREFIX);
            if (idx >= 0) {
                jsonLine = line.substring(idx + RESTORE_RESULT_PREFIX.length()).trim();
                break;
            }
        }

        if (jsonLine == null || jsonLine.isBlank()) {
            String snippet = stdout.length() > 500 ? stdout.substring(0, 500) : stdout;
            markRestoreFailed(id, backupId, username, startedAt,
                    "Không đọc được kết quả khôi phục từ script. Output: " + snippet.trim());
            return;
        }

        try {
            JsonNode result = objectMapper.readTree(jsonLine);
            BackupStatus status = mapStatus(result.path("status").asText("failed"));
            JsonNode items = result.path("items");
            if (!items.isArray()) {
                items = JsonNodeFactory.instance.arrayNode();
            }

            RestoreHistory row = loadOrRebuildRestore(id, backupId, username, startedAt);
            row.setStatus(status);
            row.setItemsJson(items);
            row.setFinishedAt(parseTime(result.path("finished_at").asText(null)));
            row.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeRestoreFailure(status, items));
            restoreHistoryRepository.save(row);
            log.info("Khôi phục {} hoàn tất với trạng thái {}.", id, status);
        } catch (Exception e) {
            log.error("Không parse được JSON kết quả khôi phục {}: {}", id, e.getMessage(), e);
            markRestoreFailed(id, backupId, username, startedAt,
                    "Kết quả khôi phục không hợp lệ: " + e.getMessage());
        }
    }

    private void markRestoreFailed(UUID id, UUID backupId, String username,
                                   OffsetDateTime startedAt, String message) {
        RestoreHistory row = loadOrRebuildRestore(id, backupId, username, startedAt);
        row.setStatus(BackupStatus.FAILED);
        row.setFinishedAt(OffsetDateTime.now());
        row.setErrorMessage(message);
        restoreHistoryRepository.save(row);
    }

    private String describeRestoreFailure(BackupStatus status, JsonNode items) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : items) {
            if (!item.path("ok").asBoolean(false)) {
                String err = item.path("error").asText("");
                sb.append(item.path("db").asText("?")).append(": ")
                        .append(err.isBlank() ? "khôi phục lỗi" : err).append("; ");
            }
        }
        if (sb.length() == 0) {
            return status == BackupStatus.PARTIAL ? "Khôi phục chỉ thành công một phần." : "Khôi phục thất bại.";
        }
        return sb.toString().trim();
    }

    /** Chạy backup.sh và cập nhật bản ghi. Chạy ở luồng nền (không có tx/security context). */
    void runBackup(UUID id, String trigger) {
        try {
            Path script = Paths.get(scriptPath).toAbsolutePath().normalize();
            // Git Bash chấp nhận dấu '/'; tránh rắc rối với '\' trên Windows.
            String scriptArg = script.toString().replace('\\', '/');

            ProcessBuilder pb = new ProcessBuilder(bashPath, scriptArg, trigger);
            // Gộp stderr vào stdout: nếu bash lỗi (vd trỏ nhầm WSL), lỗi thật vẫn đọc được
            // để đưa vào error_message thay vì "Output:" trống.
            pb.redirectErrorStream(true);
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
                String uploadError = item.path("upload_error").asText("");
                sb.append(item.path("db").asText("?")).append(": ")
                        .append(uploadError.isBlank() ? "upload Google Drive lỗi" : uploadError)
                        .append("; ");
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
