package com.medicalchatbot.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness of the backend itself is {@code /actuator/health}. */
@RestController
@RequestMapping("/chatbot")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "System", description = "Public status endpoints")
public class SystemController {

    PatientService patientService;

    @GetMapping("/status")
    @Operation(summary = "Status of the AI service", description = "Whether chatbot-service can reach the FHIR server.")
    public ApiResponse<JsonNode> chatbotStatus() {
        return ApiResponse.<JsonNode>builder()
                .result(patientService.fhirStatus())
                .message(ResponseMessage.GET_CHATBOT_STATUS_SUCCESS)
                .build();
    }
}
