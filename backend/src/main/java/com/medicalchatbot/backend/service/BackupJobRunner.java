package com.medicalchatbot.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.config.properties.BackupProperties;
import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import com.medicalchatbot.backend.service.BackupService.RestoreJob;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs {@code backup.sh} / {@code restore-auto.sh} and stores their outcome. The scripts print one
 * line {@code BACKUP_RESULT_JSON=...} / {@code RESTORE_RESULT_JSON=...} that describes the result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BackupJobRunner {

    private static final String BACKUP_RESULT_PREFIX = "BACKUP_RESULT_JSON=";
    private static final String RESTORE_RESULT_PREFIX = "RESTORE_RESULT_JSON=";
    private static final int OUTPUT_SNIPPET_LENGTH = 500;
    /** How long a killed script may take to release its output file. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(5);

    BackupHistoryRepository backupHistoryRepository;
    RestoreHistoryRepository restoreHistoryRepository;
    ObjectMapper objectMapper;
    BackupProperties properties;

    public void runBackup(UUID backupId, BackupTrigger trigger) {
        try {
            ScriptResult result = runScript(properties.scriptPath(), trigger.scriptArgument());
            if (result.timedOut()) {
                markBackupFailed(backupId, "Sao lưu quá thời gian (" + timeoutMinutes() + " phút) và đã bị hủy.");
                return;
            }
            if (result.exitCode() != 0) {
                log.warn("backup.sh exited with code {} for backup {}", result.exitCode(), backupId);
            }
            applyBackupResult(backupId, result.output());
        } catch (IOException | RuntimeException exception) {
            log.error("Backup {} could not run: {}", backupId, exception.getMessage(), exception);
            markBackupFailed(backupId, "Không chạy được script sao lưu: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markBackupFailed(backupId, "Sao lưu bị gián đoạn.");
        }
    }

    public void runRestore(RestoreJob job) {
        try {
            ScriptResult result = runScript(properties.restoreScriptPath(), job.appDumpFile(), job.hapiDumpFile());
            if (result.timedOut()) {
                markRestoreFailed(job, "Khôi phục quá thời gian (" + timeoutMinutes() + " phút) và đã bị hủy.");
                return;
            }
            applyRestoreResult(job, result.output());
        } catch (IOException | RuntimeException exception) {
            log.error("Restore {} could not run: {}", job.restoreId(), exception.getMessage(), exception);
            markRestoreFailed(job, "Không chạy được script khôi phục: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markRestoreFailed(job, "Khôi phục bị gián đoạn.");
        }
    }

    void applyBackupResult(UUID backupId, String output) {
        Optional<JsonNode> result = parseResult(output, BACKUP_RESULT_PREFIX);
        if (result.isEmpty()) {
            markBackupFailed(backupId, "Không đọc được kết quả sao lưu từ script. Output: " + snippet(output));
            return;
        }
        BackupHistory backup = backupHistoryRepository.findById(backupId).orElse(null);
        if (backup == null) {
            log.warn("backup_history {} no longer exists; result dropped", backupId);
            return;
        }

        BackupStatus status = statusOf(result.get());
        JsonNode items = itemsOf(result.get());
        long totalSize = 0;
        for (JsonNode item : items) {
            totalSize += item.path("size_bytes").asLong(0);
        }
        JsonNode uploadTarget = result.get().path("upload_target");

        backup.setStatus(status);
        backup.setItemsJson(items);
        backup.setTotalSizeBytes(totalSize);
        backup.setUploadTarget(uploadTarget.isTextual() ? uploadTarget.asText() : null);
        backup.setFinishedAt(finishedAt(result.get()));
        backup.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeBackupFailure(status, items));
        backupHistoryRepository.save(backup);
        log.info("Backup {} finished with status {} ({} bytes)", backupId, status, totalSize);
    }

    void applyRestoreResult(RestoreJob job, String output) {
        Optional<JsonNode> result = parseResult(output, RESTORE_RESULT_PREFIX);
        if (result.isEmpty()) {
            markRestoreFailed(job, "Không đọc được kết quả khôi phục từ script. Output: " + snippet(output));
            return;
        }

        BackupStatus status = statusOf(result.get());
        JsonNode items = itemsOf(result.get());
        RestoreHistory restore = loadOrRecreate(job);
        restore.setStatus(status);
        restore.setItemsJson(items);
        restore.setFinishedAt(finishedAt(result.get()));
        restore.setErrorMessage(status == BackupStatus.SUCCESS ? null : describeRestoreFailure(status, items));
        restoreHistoryRepository.save(restore);
        log.info("Restore {} finished with status {}", job.restoreId(), status);
    }

    private ScriptResult runScript(String scriptPath, String... arguments) throws IOException, InterruptedException {
        // Forward slashes so Git Bash on Windows accepts the path as well.
        String script = Path.of(scriptPath).toAbsolutePath().normalize().toString().replace('\\', '/');
        List<String> command = new ArrayList<>(List.of(properties.bashPath(), script));
        command.addAll(List.of(arguments));
        return execute(command, properties.timeout());
    }

    /**
     * Runs the command with its output written to a temporary file. Reading a pipe only once the
     * process has exited would deadlock: when the OS pipe buffer is full the script blocks on its
     * next write and never exits, until the timeout kills it.
     */
    static ScriptResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Path outputFile = Files.createTempFile("backup-job-", ".log");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // pg_dump & co. are children of the script and would otherwise keep running.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                return ScriptResult.TIMED_OUT;
            }
            // Decoded leniently: tool output is not guaranteed to be valid UTF-8.
            String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
            return new ScriptResult(false, process.exitValue(), output);
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not delete the temporary script output {}", file);
        }
    }

    private Optional<JsonNode> parseResult(String output, String prefix) {
        for (String line : output.split("\\R")) {
            int index = line.indexOf(prefix);
            if (index >= 0) {
                String json = line.substring(index + prefix.length()).trim();
                try {
                    return json.isBlank() ? Optional.empty() : Optional.of(objectMapper.readTree(json));
                } catch (IOException exception) {
                    log.error("Invalid result JSON from backup script: {}", exception.getMessage());
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Restoring the app DB also restores its {@code restore_history} table, which may no longer
     * contain the row of this restore; a new row is then recorded with the job's data. (Ids are
     * generated: saving an entity with a preset id merges it instead of inserting that id.)
     */
    private RestoreHistory loadOrRecreate(RestoreJob job) {
        return restoreHistoryRepository.findById(job.restoreId()).orElseGet(() -> {
            log.warn("restore_history {} was overwritten by the restore; recording it again", job.restoreId());
            return RestoreHistory.builder()
                    .backupId(job.backupId())
                    .triggeredBy(job.triggeredBy())
                    .startedAt(job.startedAt() != null ? job.startedAt() : OffsetDateTime.now())
                    .build();
        });
    }

    private void markBackupFailed(UUID backupId, String message) {
        backupHistoryRepository.findById(backupId).ifPresent(backup -> {
            backup.markFailed(message);
            backupHistoryRepository.save(backup);
        });
    }

    private void markRestoreFailed(RestoreJob job, String message) {
        RestoreHistory restore = loadOrRecreate(job);
        restore.markFailed(message);
        restoreHistoryRepository.save(restore);
    }

    private static BackupStatus statusOf(JsonNode result) {
        return switch (result.path("status").asText("failed").toLowerCase(Locale.ROOT)) {
            case "success" -> BackupStatus.SUCCESS;
            case "partial" -> BackupStatus.PARTIAL;
            default -> BackupStatus.FAILED;
        };
    }

    private static JsonNode itemsOf(JsonNode result) {
        JsonNode items = result.path("items");
        return items.isArray() ? items : JsonNodeFactory.instance.arrayNode();
    }

    private static OffsetDateTime finishedAt(JsonNode result) {
        String value = result.path("finished_at").asText("");
        try {
            return value.isBlank() ? OffsetDateTime.now() : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return OffsetDateTime.now();
        }
    }

    private static String describeBackupFailure(BackupStatus status, JsonNode items) {
        StringBuilder description = new StringBuilder();
        for (JsonNode item : items) {
            String db = item.path("db").asText("?");
            if (!item.path("ok").asBoolean(false)) {
                description.append(db).append(": dump lỗi; ");
            } else if (!item.path("uploaded").asBoolean(false)) {
                String uploadError = item.path("upload_error").asText("");
                description.append(db).append(": ")
                        .append(uploadError.isBlank() ? "upload Google Drive lỗi" : uploadError)
                        .append("; ");
            }
        }
        if (description.isEmpty()) {
            return status == BackupStatus.PARTIAL ? "Sao lưu chỉ thành công một phần." : "Sao lưu thất bại.";
        }
        return description.toString().trim();
    }

    private static String describeRestoreFailure(BackupStatus status, JsonNode items) {
        StringBuilder description = new StringBuilder();
        for (JsonNode item : items) {
            if (!item.path("ok").asBoolean(false)) {
                String error = item.path("error").asText("");
                description.append(item.path("db").asText("?")).append(": ")
                        .append(error.isBlank() ? "khôi phục lỗi" : error)
                        .append("; ");
            }
        }
        if (description.isEmpty()) {
            return status == BackupStatus.PARTIAL ? "Khôi phục chỉ thành công một phần." : "Khôi phục thất bại.";
        }
        return description.toString().trim();
    }

    private long timeoutMinutes() {
        return properties.timeout().toMinutes();
    }

    private static String snippet(String output) {
        return (output.length() > OUTPUT_SNIPPET_LENGTH ? output.substring(0, OUTPUT_SNIPPET_LENGTH) : output).trim();
    }

    record ScriptResult(boolean timedOut, int exitCode, String output) {

        static final ScriptResult TIMED_OUT = new ScriptResult(true, -1, "");
    }
}
