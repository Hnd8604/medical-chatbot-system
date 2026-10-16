package com.medicalchatbot.backend.service;

import java.util.concurrent.TimeUnit;

import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.request.chat.ChatRequest;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.service.ChatTurnService.PreparedTurn;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs one chat turn: {@link ChatTurnService#prepare} checks quota and scope and stores the
 * question, chatbot-service answers it, then {@link ChatTurnService#complete} stores the answer.
 *
 * <p>Deliberately not transactional: a turn may wait for chatbot-service up to its read timeout,
 * and holding a pooled connection that long would starve every other request. A failed turn is
 * discarded instead of rolled back, and the LLM calls it already paid for are still recorded.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatService {

    ChatTurnService chatTurnService;
    ChatbotServiceClient chatbotServiceClient;
    ChatInteractionRecorder interactionRecorder;

    public ChatResponse chat(ChatRequest request) {
        PreparedTurn turn = chatTurnService.prepare(request);

        long startedAt = System.nanoTime();
        ChatbotChatResponse reply = askChatbot(turn, startedAt);
        long latencyMs = elapsedMillis(startedAt);
        try {
            return chatTurnService.complete(turn, reply, latencyMs);
        } catch (RuntimeException exception) {
            // The LLM has already run (and cost money) but its answer could not be stored.
            discard(turn);
            recordSpendingOfFailedTurn(() -> interactionRecorder.recordUnsavedTurn(
                    turn.user(), turn.existingSession(), reply, latencyMs));
            throw exception;
        }
    }

    /**
     * A turn can fail half-way after it already paid for LLM calls (e.g. routing done, then FHIR
     * failed or the plan was blocked by policy); chatbot-service lists those calls in its error body.
     */
    private ChatbotChatResponse askChatbot(PreparedTurn turn, long startedAt) {
        try {
            return chatbotServiceClient.chat(turn.chatbotRequest());
        } catch (RestClientResponseException exception) {
            long latencyMs = elapsedMillis(startedAt);
            discard(turn);
            recordSpendingOfFailedTurn(() -> interactionRecorder.recordFailedTurn(
                    turn.user(),
                    turn.existingSession(),
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsByteArray(),
                    latencyMs
            ));
            throw exception;
        } catch (RuntimeException exception) {
            discard(turn);
            throw exception;
        }
    }

    /** Cleanup of a failed turn must never replace the error the user should see. */
    private void discard(PreparedTurn turn) {
        try {
            chatTurnService.discard(turn);
        } catch (RuntimeException discardError) {
            log.warn("Could not discard the question of a failed chat turn in session {} ({})",
                    turn.sessionId(), discardError.getClass().getSimpleName());
        }
    }

    /** Bookkeeping of a failed turn must never replace the error the user should see. */
    private void recordSpendingOfFailedTurn(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException recordingError) {
            log.warn("Could not record the LLM usage of a failed chat turn ({})",
                    recordingError.getClass().getSimpleName());
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
