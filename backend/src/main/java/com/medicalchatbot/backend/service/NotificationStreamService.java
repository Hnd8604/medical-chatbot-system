package com.medicalchatbot.backend.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.medicalchatbot.backend.dto.response.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the open Server-Sent Events connections per user and pushes new notifications to them, so
 * the browser does not have to poll. A user may have several tabs, hence several emitters.
 */
@Slf4j
@Service
public class NotificationStreamService {

    /** The browser's EventSource reconnects automatically after the timeout. */
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final Map<UUID, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        emittersByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, emitter);
        });
        emitter.onError(error -> remove(userId, emitter));

        try {
            // Tells the client the stream is open (it resyncs then) and makes proxies flush.
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException exception) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void publish(UUID userId, NotificationResponse notification) {
        for (SseEmitter emitter : emittersByUser.getOrDefault(userId, List.of())) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(notification));
            } catch (IOException | IllegalStateException exception) {
                log.debug("Dropping a closed notification stream of user {}", userId);
                remove(userId, emitter);
            }
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        emittersByUser.computeIfPresent(userId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
