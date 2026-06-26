package com.medicalchatbot.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.request.FeedbackRequest;
import com.medicalchatbot.backend.dto.response.ChatMessagesResponse;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionListResponse;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.FeedbackResponse;
import com.medicalchatbot.backend.dto.response.ModelPricingListResponse;
import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.service.ChatApplicationService;
import com.medicalchatbot.backend.service.ChatbotServiceClient;
import com.medicalchatbot.backend.service.CostManagementService;
import com.medicalchatbot.backend.service.FeedbackService;
import com.medicalchatbot.backend.service.QuotaService;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ChatbotController {

    private final ChatbotServiceClient chatbotServiceClient;
    private final ChatApplicationService chatApplicationService;
    private final QuotaService quotaService;
    private final CostManagementService costManagementService;
    private final FeedbackService feedbackService;

    @GetMapping("/chatbot/status")
    JsonNode chatbotStatus() {
        return chatbotServiceClient.getStatus();
    }

    @GetMapping("/patients")
    JsonNode searchPatients(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(name = "birth_date", required = false) String birthDate,
            @RequestParam(required = false) String identifier,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotServiceClient.searchPatients(name, phone, birthDate, identifier, limit);
    }

    @GetMapping("/patients/{patientId}")
    JsonNode patient(@PathVariable String patientId) {
        return chatbotServiceClient.getPatient(patientId);
    }

    @GetMapping("/patients/{patientId}/observations")
    JsonNode observations(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return chatbotServiceClient.getPatientObservations(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/conditions")
    JsonNode conditions(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotServiceClient.getPatientConditions(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/encounters")
    JsonNode encounters(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return chatbotServiceClient.getPatientEncounters(patientId, limit);
    }

    @GetMapping("/patients/{patientId}/medications")
    JsonNode medications(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return chatbotServiceClient.getPatientMedications(patientId, limit);
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
    FeedbackResponse submitFeedback(
            @PathVariable UUID messageId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return feedbackService.submitFeedback(messageId, request);
    }
}
