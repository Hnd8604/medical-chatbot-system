package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.ChatContextMessage;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.request.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.request.ConversationContext;
import com.medicalchatbot.backend.dto.response.ChatMessageItem;
import com.medicalchatbot.backend.dto.response.ChatMessagesResponse;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionSummary;
import com.medicalchatbot.backend.entity.ChatMessage;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.ChatMessageRole;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatApplicationService {

    private static final int RECENT_CONTEXT_MESSAGE_LIMIT = 6;
    private static final int SESSION_SEARCH_QUERY_MAX_LENGTH = 100;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UsageLogRepository usageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final ChatbotServiceClient chatbotServiceClient;
    private final QuotaService quotaService;
    private final CostEstimationService costEstimationService;
    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;
    private final UserPatientScopeService userPatientScopeService;

    @Transactional
    public ChatResponse chat(ChatRequest request) {
        User user = currentUserService.requireCurrentUser();
        UUID userId = user.getId();
        quotaService.assertQuotaAvailable(userId);
        double quotaUsedRatio = quotaService.currentUsedRatio(userId);
        ChatSession session = null;
        ChatSessionMemory sessionMemory = null;
        if (request.sessionId() != null) {
            session = requireSessionForUser(request.sessionId(), userId);
            sessionMemory = session.memory();
        }
        UserPatientScopeService.PatientScope patientScope = userPatientScopeService.resolve(
                user,
                request.patientId(),
                sessionMemory
        );
        if (session == null) {
            session = chatSessionRepository.create(user, titleFromMessage(request.message()));
            sessionMemory = session.memory();
        }
        UUID sessionId = session.getId();
        ChatSessionMemory scopedSessionMemory = userPatientScopeService.scopedMemory(sessionMemory, patientScope);
        String effectivePatientId = patientScope.effectivePatientId();
        List<ChatContextMessage> recentMessages = chatSessionRepository.findRecentMessagesForContext(
                sessionId,
                userId,
                RECENT_CONTEXT_MESSAGE_LIMIT
        );
        ConversationContext conversationContext = conversationContext(scopedSessionMemory, recentMessages);

        chatMessageRepository.save(session, ChatMessageRole.USER, request.message(), userMessageMetadata(
                request,
                effectivePatientId
        ));

        long startedAtNanos = System.nanoTime();
        JsonNode chatbotResponse = chatbotServiceClient.chat(new ChatbotChatRequest(
                userId.toString(),
                user.getRole().name(),
                sessionId.toString(),
                request.message(),
                effectivePatientId,
                patientScope.allowedPatientIds(),
                patientScope.patientScope(),
                conversationContext,
                quotaUsedRatio
        ));
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        String answer = chatbotResponse.path("answer").asText("");
        ChatMessage assistantMsg = chatMessageRepository.saveAndReturn(
                session,
                ChatMessageRole.ASSISTANT,
                answer,
                assistantMessageMetadata(chatbotResponse)
        );
        ChatSessionMemory nextMemory = nextSessionMemory(scopedSessionMemory, effectivePatientId, chatbotResponse);
        chatSessionRepository.updateMemory(session, nextMemory);
        saveUsage(user, session, chatbotResponse, latencyMs);
        saveAuditLog(user, session, request, effectivePatientId, chatbotResponse, latencyMs);

        return new ChatResponse(
                sessionId,
                assistantMsg.getId(),
                answer,
                chatbotResponse.path("intent").asText(null),
                chatbotResponse.path("tool_name").asText(null),
                chatbotResponse.path("intent_source").asText(null),
                chatbotResponse.path("answer_source").asText(null),
                chatbotResponse.path("answer_reason").asText(null),
                chatbotResponse.path("patient_id").asText(null),
                chatbotResponse.path("observation_type").asText(null),
                chatbotResponse.has("all_patients") ? chatbotResponse.path("all_patients").asBoolean(false) : null,
                chatbotResponse.path("patient_search"),
                chatbotResponse.has("needs_patient_selection")
                        ? chatbotResponse.path("needs_patient_selection").asBoolean(false)
                        : null,
                chatbotResponse.path("patient_candidates"),
                chatbotResponse.path("pending_question").asText(null),
                chatbotResponse.path("evidence"),
                chatbotResponse.path("answer_usage"),
                chatbotResponse.path("usage"),
                chatbotResponse.path("saved_usage")
        );
    }

    public ChatSessionListResponse recentSessions(int limit) {
        return sessions(null, limit);
    }

    public ChatSessionListResponse sessions(String query, int limit) {
        UUID userId = currentUserService.requireCurrentUserId();
        String normalizedQuery = normalizeSessionSearchQuery(query);
        List<ChatSessionSummary> sessions = normalizedQuery == null
                ? chatSessionRepository.findRecentSessionsForUser(userId, limit)
                : chatSessionRepository.searchSessionsForUser(userId, normalizedQuery, limit);
        return new ChatSessionListResponse(sessions);
    }

    public ChatMessagesResponse sessionMessages(UUID sessionId) {
        UUID userId = currentUserService.requireCurrentUserId();
        requireSessionForUser(sessionId, userId);
        List<ChatMessageItem> messages = chatSessionRepository.findMessagesForSession(sessionId, userId);
        return new ChatMessagesResponse(sessionId, messages);
    }

    @Transactional
    public ChatSessionRenameResponse renameSession(UUID sessionId, String title) {
        UUID userId = currentUserService.requireCurrentUserId();
        ChatSession session = chatSessionRepository.findByIdAndUser_Id(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên trò chuyện."));
        String normalizedTitle = normalizeSessionTitle(title);
        session.setTitle(normalizedTitle);
        chatSessionRepository.save(session);
        return new ChatSessionRenameResponse(sessionId, normalizedTitle);
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        UUID userId = currentUserService.requireCurrentUserId();
        // Xóa phiên: chat_messages/message_feedback cascade theo FK; usage_logs/audit_logs
        // giữ lại và được set session_id = null (theo ràng buộc migration V1).
        long deleted = chatSessionRepository.deleteByIdAndUser_Id(sessionId, userId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên trò chuyện.");
        }
    }

    private String normalizeSessionTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề hội thoại không được để trống.");
        }
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    private ChatSession requireSessionForUser(UUID sessionId, UUID userId) {
        if (!chatSessionRepository.existsForUser(sessionId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên trò chuyện.");
        }
        return chatSessionRepository.getReferenceById(sessionId);
    }

    private String normalizeSessionSearchQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > SESSION_SEARCH_QUERY_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ khóa tìm kiếm quá dài.");
        }
        return trimmed;
    }

    private void saveUsage(User user, ChatSession session, JsonNode chatbotResponse, long latencyMs) {
        JsonNode usage = chatbotResponse.path("usage");
        String llmProvider = textOrNull(chatbotResponse, "llm_provider");
        String llmModel = textOrNull(chatbotResponse, "llm_model");
        String answerSource = textOrNull(chatbotResponse, "answer_source");

        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        BigDecimal estimatedCostUsd = costEstimationService.estimateUsd(
                llmProvider,
                llmModel,
                inputTokens,
                outputTokens,
                decimalOrZero(usage.path("estimated_cost_usd"))
        );

        JsonNode savedUsage = chatbotResponse.path("saved_usage");
        int savedTokens = 0;
        BigDecimal savedCostUsd = BigDecimal.ZERO;

        if (!savedUsage.isMissingNode() && !savedUsage.isNull()) {
            int savedIn = savedUsage.path("saved_input_tokens").asInt(0);
            int savedOut = savedUsage.path("saved_output_tokens").asInt(0);
            savedTokens = savedIn + savedOut;

            savedCostUsd = costEstimationService.estimateUsd(
                    llmProvider,
                    llmModel,
                    savedIn,
                    savedOut,
                    BigDecimal.ZERO
            );
        }

        usageLogRepository.save(
                user,
                session,
                llmProvider,
                llmModel,
                "chat",
                "success",
                latencyMs,
                inputTokens,
                outputTokens,
                estimatedCostUsd,
                null,
                answerSource,
                savedTokens,
                savedCostUsd
        );
    }

    private void saveAuditLog(
            User user,
            ChatSession session,
            ChatRequest request,
            String effectivePatientId,
            JsonNode chatbotResponse,
            long latencyMs
    ) {
        String toolName = textOrNull(chatbotResponse, "tool_name");
        String responsePatientId = textOrNull(chatbotResponse, "patient_id");

        String action = "CHAT_COMPLETED";
        String resourceType = "chat_session";
        String resourceId = session.getId().toString();

        if (toolName != null) {
            switch (toolName) {
                case "get_medication_requests" -> {
                    action = "VIEW_MEDICATIONS";
                    resourceType = "MedicationRequest";
                    resourceId = responsePatientId;
                }
                case "get_observations" -> {
                    action = "VIEW_OBSERVATIONS";
                    resourceType = "Observation";
                    resourceId = responsePatientId;
                }
                case "get_conditions" -> {
                    action = "VIEW_CONDITIONS";
                    resourceType = "Condition";
                    resourceId = responsePatientId;
                }
                case "get_encounters" -> {
                    action = "VIEW_ENCOUNTERS";
                    resourceType = "Encounter";
                    resourceId = responsePatientId;
                }
                case "get_patient_by_id" -> {
                    action = "VIEW_PATIENT_DETAIL";
                    resourceType = "Patient";
                    resourceId = responsePatientId;
                }
                case "search_patients" -> {
                    action = "SEARCH_PATIENTS";
                    resourceType = "Patient";
                    resourceId = "search_query";
                }
                case "cache_hit" -> {
                    action = "VIEW_FROM_CACHE";
                    resourceType = "Cache";
                    resourceId = responsePatientId;
                }
                case "unsupported_question" -> action = "ASK_UNSUPPORTED";
                default -> {
                }
            }
        }

        if (resourceId == null) {
            resourceId = effectivePatientId != null ? effectivePatientId : session.getId().toString();
        }

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "chat");
        metadata.put("latency_ms", latencyMs);
        String intent = textOrNull(chatbotResponse, "intent");
        metadata.put("intent", intent);
        metadata.put("question_intent", intent);
        metadata.put("tool_name", toolName);
        metadata.put("intent_source", textOrNull(chatbotResponse, "intent_source"));
        metadata.put("answer_source", textOrNull(chatbotResponse, "answer_source"));
        metadata.put("patient_id", responsePatientId);
        metadata.put("request_patient_id", request.patientId());
        metadata.put("effective_patient_id", effectivePatientId);
        metadata.put("llm_provider", textOrNull(chatbotResponse, "llm_provider"));
        metadata.put("llm_model", textOrNull(chatbotResponse, "llm_model"));
        metadata.set("usage", chatbotResponse.path("usage"));
        metadata.set("memory_update", chatbotResponse.path("memory_update"));
        if (chatbotResponse.has("needs_patient_selection")) {
            metadata.put("needs_patient_selection", chatbotResponse.path("needs_patient_selection").asBoolean(false));
        }

        auditLogRepository.save(
                user,
                session,
                action,
                resourceType,
                resourceId,
                metadata
        );
    }

    private ConversationContext conversationContext(
            ChatSessionMemory sessionMemory,
            List<ChatContextMessage> recentMessages
    ) {
        return new ConversationContext(
                sessionMemory.memorySummary(),
                sessionMemory.activePatientId(),
                sessionMemory.lastIntent(),
                sessionMemory.lastToolName(),
                sessionMemory.lastResourceType(),
                sessionMemory.lastResourceId(),
                recentMessages
        );
    }

    private ObjectNode userMessageMetadata(ChatRequest request, String effectivePatientId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("request_patient_id", request.patientId());
        metadata.put("effective_patient_id", effectivePatientId);
        return metadata;
    }

    private ObjectNode assistantMessageMetadata(JsonNode chatbotResponse) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("intent", textOrNull(chatbotResponse, "intent"));
        metadata.put("tool_name", textOrNull(chatbotResponse, "tool_name"));
        metadata.put("patient_id", textOrNull(chatbotResponse, "patient_id"));
        metadata.put("answer_source", textOrNull(chatbotResponse, "answer_source"));
        metadata.set("evidence_refs", evidenceRefs(chatbotResponse));
        metadata.set("memory_update", chatbotResponse.path("memory_update"));
        return metadata;
    }

    private ArrayNode evidenceRefs(JsonNode chatbotResponse) {
        ArrayNode refs = objectMapper.createArrayNode();
        JsonNode memoryRefs = chatbotResponse.path("memory_update").path("evidence_refs");
        if (memoryRefs.isArray()) {
            memoryRefs.forEach(refs::add);
            return refs;
        }

        JsonNode evidence = chatbotResponse.path("evidence");
        if (!evidence.isArray()) {
            return refs;
        }
        for (JsonNode item : evidence) {
            ObjectNode ref = objectMapper.createObjectNode();
            ref.put("resource_type", textOrNull(item, "resource_type"));
            ref.put("resource_id", textOrNull(item, "id"));
            ref.put("summary", textOrNull(item, "summary"));
            refs.add(ref);
        }
        return refs;
    }

    private ChatSessionMemory nextSessionMemory(
            ChatSessionMemory current,
            String effectivePatientId,
            JsonNode chatbotResponse
    ) {
        JsonNode memoryUpdate = chatbotResponse.path("memory_update");
        boolean allPatients = chatbotResponse.path("all_patients").asBoolean(false);
        boolean needsPatientSelection = chatbotResponse.path("needs_patient_selection").asBoolean(false);
        boolean concretePatientResponse = !allPatients && !needsPatientSelection;

        String activePatientId = current.activePatientId();
        if (concretePatientResponse) {
            activePatientId = firstNonBlank(
                    textOrNull(memoryUpdate, "active_patient_id"),
                    textOrNull(chatbotResponse, "patient_id"),
                    effectivePatientId,
                    activePatientId
            );
        }

        String lastResourceType = current.lastResourceType();
        String lastResourceId = current.lastResourceId();
        if (concretePatientResponse) {
            lastResourceType = firstNonBlank(
                    textOrNull(memoryUpdate, "last_resource_type"),
                    firstEvidenceRefField(chatbotResponse, "resource_type"),
                    lastResourceType
            );
            lastResourceId = firstNonBlank(
                    textOrNull(memoryUpdate, "last_resource_id"),
                    firstEvidenceRefField(chatbotResponse, "resource_id"),
                    lastResourceId
            );
        }

        return new ChatSessionMemory(
                activePatientId,
                firstNonBlank(textOrNull(memoryUpdate, "summary"), current.memorySummary()),
                firstNonBlank(textOrNull(memoryUpdate, "last_intent"), textOrNull(chatbotResponse, "intent"), current.lastIntent()),
                firstNonBlank(textOrNull(memoryUpdate, "last_tool_name"), textOrNull(chatbotResponse, "tool_name"), current.lastToolName()),
                lastResourceType,
                lastResourceId
        );
    }

    private String firstEvidenceRefField(JsonNode chatbotResponse, String fieldName) {
        JsonNode memoryRefs = chatbotResponse.path("memory_update").path("evidence_refs");
        if (memoryRefs.isArray() && memoryRefs.size() > 0) {
            String value = textOrNull(memoryRefs.get(0), fieldName);
            if (value != null) {
                return value;
            }
        }

        JsonNode evidence = chatbotResponse.path("evidence");
        if (!evidence.isArray() || evidence.size() == 0) {
            return null;
        }
        String evidenceField = "resource_id".equals(fieldName) ? "id" : fieldName;
        return textOrNull(evidence.get(0), evidenceField);
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    private BigDecimal decimalOrZero(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText("0"));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String titleFromMessage(String message) {
        String normalized = message == null ? "Cuộc trò chuyện mới" : message.strip();
        if (normalized.isEmpty()) {
            return "Cuộc trò chuyện mới";
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }
}
