package com.medicalchatbot.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.patient.PatientSearchRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FHIR payloads are passed through from chatbot-service unchanged. */
@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Patients", description = "FHIR patient records for medical staff (DOCTOR, ADMIN)")
public class PatientController {

    PatientService patientService;

    @GetMapping
    @Operation(summary = "Search patients", description = "Blank criteria are ignored.")
    public ApiResponse<JsonNode> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String birthDate,
            @RequestParam(required = false) String identifier,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return ApiResponse.<JsonNode>builder()
                .result(patientService.searchPatients(new PatientSearchRequest(name, phone, birthDate, identifier, limit)))
                .message(ResponseMessage.SEARCH_PATIENTS_SUCCESS)
                .build();
    }

    @GetMapping("/{patientId}")
    @Operation(summary = "Get a patient")
    public ApiResponse<JsonNode> patient(@PathVariable String patientId) {
        return ApiResponse.<JsonNode>builder()
                .result(patientService.getPatient(patientId))
                .message(ResponseMessage.GET_PATIENT_SUCCESS)
                .build();
    }

    @GetMapping("/{patientId}/observations")
    @Operation(summary = "Get a patient's observations", description = "Most recent first.")
    public ApiResponse<JsonNode> observations(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return resources(patientId, PatientResource.OBSERVATIONS, limit, ResponseMessage.GET_OBSERVATIONS_SUCCESS);
    }

    @GetMapping("/{patientId}/conditions")
    @Operation(summary = "Get a patient's conditions")
    public ApiResponse<JsonNode> conditions(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return resources(patientId, PatientResource.CONDITIONS, limit, ResponseMessage.GET_CONDITIONS_SUCCESS);
    }

    @GetMapping("/{patientId}/encounters")
    @Operation(summary = "Get a patient's encounters", description = "Most recent first.")
    public ApiResponse<JsonNode> encounters(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        return resources(patientId, PatientResource.ENCOUNTERS, limit, ResponseMessage.GET_ENCOUNTERS_SUCCESS);
    }

    @GetMapping("/{patientId}/medications")
    @Operation(summary = "Get a patient's medications")
    public ApiResponse<JsonNode> medications(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return resources(patientId, PatientResource.MEDICATIONS, limit, ResponseMessage.GET_MEDICATIONS_SUCCESS);
    }

    private ApiResponse<JsonNode> resources(String patientId, PatientResource resource, int limit, String message) {
        return ApiResponse.<JsonNode>builder()
                .result(patientService.getResources(patientId, resource, limit))
                .message(message)
                .build();
    }
}
