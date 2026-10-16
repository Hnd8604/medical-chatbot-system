package com.medicalchatbot.backend.dto.request.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Patient id plus two facts from the FHIR record that prove the caller owns it. */
public record LinkPatientRequest(
        @NotBlank(message = "Vui lòng nhập mã bệnh nhân.")
        @Size(max = 100, message = "Mã bệnh nhân không được vượt quá {max} ký tự.")
        String patientId,

        @NotBlank(message = "Vui lòng nhập ngày sinh.")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Ngày sinh phải có định dạng YYYY-MM-DD.")
        String birthDate,

        @NotBlank(message = "Vui lòng nhập số điện thoại.")
        @Size(max = 30, message = "Số điện thoại không được vượt quá {max} ký tự.")
        String phone
) {
}
