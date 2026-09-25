package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hồ sơ FHIR của chính người dùng (SELF), gộp trong một lần gọi.
 * Chỉ trả về hồ sơ đã liên kết với tài khoản hiện tại — không cho truy cập bệnh nhân khác.
 */
public record SelfPatientProfileResponse(
        String patientId,
        JsonNode patient,
        JsonNode encounters,
        JsonNode observations,
        JsonNode conditions,
        JsonNode medications
) {
}
