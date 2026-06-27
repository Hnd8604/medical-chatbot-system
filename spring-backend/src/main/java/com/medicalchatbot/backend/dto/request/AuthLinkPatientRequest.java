package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AuthLinkPatientRequest(
        @JsonProperty("patient_id")
        @NotBlank String patientId,
        @JsonProperty("birth_date")
        @NotBlank String birthDate,
        @NotBlank String phone
) {
}
