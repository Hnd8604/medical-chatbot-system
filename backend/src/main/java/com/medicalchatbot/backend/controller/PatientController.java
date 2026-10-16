package com.medicalchatbot.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.dto.request.PatientSearchRequest;
import com.medicalchatbot.backend.service.PatientService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FHIR patient data for staff (DOCTOR/ADMIN). Payloads are passed through from chatbot-service. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    @GetMapping
    public JsonNode search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(name = "birth_date", required = false) String birthDate,
            @RequestParam(required = false) String identifier,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return patientService.searchPatients(new PatientSearchRequest(name, phone, birthDate, identifier, limit));
    }

    @GetMapping("/{patientId}")
    public JsonNode patient(@PathVariable String patientId) {
        return patientService.getPatient(patientId);
    }

    @GetMapping("/{patientId}/observations")
    public JsonNode observations(@PathVariable String patientId, @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        return patientService.getResources(patientId, PatientResource.OBSERVATIONS, limit);
    }

    @GetMapping("/{patientId}/conditions")
    public JsonNode conditions(@PathVariable String patientId, @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return patientService.getResources(patientId, PatientResource.CONDITIONS, limit);
    }

    @GetMapping("/{patientId}/encounters")
    public JsonNode encounters(@PathVariable String patientId, @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        return patientService.getResources(patientId, PatientResource.ENCOUNTERS, limit);
    }

    @GetMapping("/{patientId}/medications")
    public JsonNode medications(@PathVariable String patientId, @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return patientService.getResources(patientId, PatientResource.MEDICATIONS, limit);
    }
}
