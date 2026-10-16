package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.SelfPatientProfileResponse;
import com.medicalchatbot.backend.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The FHIR record linked to the signed-in account. No patient id is accepted from the client, so
 * a user can never read someone else's record here.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me")
public class SelfPatientController {

    private final PatientService patientService;

    @GetMapping("/patient/profile")
    public SelfPatientProfileResponse patientProfile() {
        return patientService.currentUserProfile();
    }
}
