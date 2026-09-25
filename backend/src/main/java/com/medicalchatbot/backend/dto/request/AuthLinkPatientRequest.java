package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthLinkPatientRequest(
        @JsonProperty("patient_id")
        @NotBlank @Size(max = 100) String patientId,
        @JsonProperty("birth_date")
        @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String birthDate,
        @NotBlank @Size(max = 30) String phone
) {
}
