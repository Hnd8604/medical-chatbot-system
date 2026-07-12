package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.SelfPatientProfileResponse;
import com.medicalchatbot.backend.service.SelfPatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint SELF-scoped: người dùng chỉ đọc được hồ sơ FHIR đã liên kết với chính mình.
 * Không nhận patient id từ client — luôn suy ra từ tài khoản đang đăng nhập.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me")
public class SelfPatientController {

    private final SelfPatientService selfPatientService;

    @GetMapping("/patient/profile")
    SelfPatientProfileResponse patientProfile() {
        return selfPatientService.currentUserProfile();
    }
}
