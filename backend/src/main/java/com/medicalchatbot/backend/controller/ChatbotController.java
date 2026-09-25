package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.request.RenameSessionRequest;
import com.medicalchatbot.backend.dto.response.ChatMessagesResponse;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionRenameResponse;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.dto.response.JsonPayload;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.service.ChatApplicationService;
import com.medicalchatbot.backend.service.ChatbotQueryService;
import com.medicalchatbot.backend.service.CostManagementService;
import com.medicalchatbot.backend.service.FeedbackService;
import com.medicalchatbot.backend.service.QuotaService;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ChatbotController {

    private final ChatbotQueryService chatbotQueryService;
    private final ChatApplicationService chatApplicationService;
    private final QuotaService quotaService;
    private final CostManagementService costManagementService;
    private final FeedbackService feedbackService;

    @GetMapping("/chatbot/status")
    JsonPayload<Map<String, Object>> chatbotStatus() {
        return chatbotQueryService.status();
    }

    @GetMapping("/patients")
    JsonPayload<Map<String, Object>> searchPatients(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(name = "birth_date", required = false) String birthDate,
            @RequestParam(required = false) String identifier,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotQueryService.searchPatients(name, phone, birthDate, identifier, limit);
    }

    @GetMapping("/patients/{patientId}")
    JsonPayload<Map<String, Object>> patient(@PathVariable String patientId) {
        return chatbotQueryService.patient(patientId);
    }

    @GetMapping("/patients/{patientId}/observations")
    JsonPayload<Map<String, Object>> observations(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return chatbotQueryService.observations(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/conditions")
    JsonPayload<Map<String, Object>> conditions(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotQueryService.conditions(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/encounters")
    JsonPayload<Map<String, Object>> encounters(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return chatbotQueryService.encounters(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/medications")
    JsonPayload<Map<String, Object>> medications(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotQueryService.medications(patientId, limit);
    }

    @PostMapping("/chat")
    ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatApplicationService.chat(request);
    }

    @GetMapping("/chat/sessions")
    ChatSessionListResponse chatSessions(
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return chatApplicationService.sessions(query, limit);
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    ChatMessagesResponse chatSessionMessages(@PathVariable UUID sessionId) {
        return chatApplicationService.sessionMessages(sessionId);
    }

    @PatchMapping("/chat/sessions/{sessionId}")
    ChatSessionRenameResponse renameChatSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameSessionRequest request
    ) {
        return chatApplicationService.renameSession(sessionId, request.title());
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteChatSession(@PathVariable UUID sessionId) {
        chatApplicationService.deleteSession(sessionId);
    }

    @GetMapping("/quota/status")
    QuotaStatusResponse quotaStatus() {
        return quotaService.currentUserStatus();
    }

    @GetMapping("/usage/cost-summary")
    CostSummaryResponse costSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return costManagementService.currentUserCostSummary(from, to);
    }

    @GetMapping("/model-pricing")
    ModelPricingListResponse modelPricing() {
        return costManagementService.activePricing();
    }

    @PostMapping("/chat/messages/{messageId}/feedback")
    FeedbackResponse createFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return feedbackService.createFeedback(messageId, request);
    }

    @PutMapping("/chat/messages/{messageId}/feedback")
    FeedbackResponse updateFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return feedbackService.updateFeedback(messageId, request);
    }

    @DeleteMapping("/chat/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFeedback(@PathVariable UUID messageId) {
        feedbackService.deleteFeedback(messageId);
    }
}
