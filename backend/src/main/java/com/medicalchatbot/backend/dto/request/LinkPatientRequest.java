package com.medicalchatbot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Patient id plus two facts from the FHIR record that prove the caller owns it. */
public record LinkPatientRequest(
        @NotBlank @Size(max = 100) String patientId,
        @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String birthDate,
        @NotBlank @Size(max = 30) String phone
) {
}
