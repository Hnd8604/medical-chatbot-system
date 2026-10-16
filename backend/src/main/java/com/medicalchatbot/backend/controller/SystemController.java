package com.medicalchatbot.backend.controller;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness endpoints. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SystemController {

    private final PatientService patientService;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Whether chatbot-service can reach the FHIR server. */
    @GetMapping("/chatbot/status")
    public JsonNode chatbotStatus() {
        return patientService.fhirStatus();
    }
}
