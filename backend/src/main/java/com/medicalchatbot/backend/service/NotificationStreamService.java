package com.medicalchatbot.backend.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.medicalchatbot.backend.dto.response.NotificationItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Giữ các kết nối SSE (Server-Sent Events) đang mở theo user và đẩy thông báo
 * mới tới trình duyệt ngay khi được tạo, thay cho việc frontend poll định kỳ.
 *
 * Một user có thể mở nhiều tab, nên mỗi userId ánh xạ tới nhiều emitter.
 */
@Slf4j
@Service
public class NotificationStreamService {

    /** SSE emitter timeout (30 phút). EventSource của trình duyệt tự kết nối lại. */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, emitter);
        });
        emitter.onError(throwable -> remove(userId, emitter));

        try {
            // Sự kiện mở kết nối; cũng giúp một số proxy flush ngay stream.
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException ex) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void publish(UUID userId, NotificationItem item) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(item));
            } catch (Exception ex) {
                log.debug("Failed to push notification over SSE, dropping emitter for user {}", userId);
                remove(userId, emitter);
            }
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId, emitters);
        }
    }
}
