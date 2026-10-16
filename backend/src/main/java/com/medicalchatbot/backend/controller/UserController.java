package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.auth.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.request.patient.LinkPatientRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.patient.SelfPatientProfileResponse;
import com.medicalchatbot.backend.service.AuthService;
import com.medicalchatbot.backend.service.PatientLinkService;
import com.medicalchatbot.backend.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "My account", description = "The signed-in account, its profile and its linked patient record")
public class UserController {

    AuthService authService;
    PatientLinkService patientLinkService;
    PatientService patientService;

    @GetMapping("/my-info")
    @Operation(summary = "Get my account", description = "Returns the signed-in account.")
    public ApiResponse<AuthUserResponse> getMyInfo() {
        return ApiResponse.<AuthUserResponse>builder()
                .result(authService.currentUser())
                .message(ResponseMessage.GET_MY_INFO_SUCCESS)
                .build();
    }

    @PutMapping("/my-info")
    @Operation(summary = "Update my profile", description = "Changes the display name and email of the signed-in account.")
    public ApiResponse<AuthUserResponse> updateMyInfo(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.<AuthUserResponse>builder()
                .result(authService.updateProfile(request))
                .message(ResponseMessage.UPDATE_PROFILE_SUCCESS)
                .build();
    }

    @PostMapping("/my-patient-link")
    @Operation(summary = "Link my patient record",
            description = "USER accounts prove they own a FHIR patient record (birth date + phone) before they can chat.")
    public ApiResponse<AuthUserResponse> linkMyPatient(@Valid @RequestBody LinkPatientRequest request) {
        return ApiResponse.<AuthUserResponse>builder()
                .result(patientLinkService.linkCurrentUser(request))
                .message(ResponseMessage.LINK_PATIENT_SUCCESS)
                .build();
    }

    @GetMapping("/my-patient-profile")
    @Operation(summary = "Get my patient record",
            description = "Patient, recent encounters and observations, conditions and medications in one call. "
                    + "No patient id is accepted, so a user can never read someone else's record here.")
    public ApiResponse<SelfPatientProfileResponse> getMyPatientProfile() {
        return ApiResponse.<SelfPatientProfileResponse>builder()
                .result(patientService.currentUserProfile())
                .message(ResponseMessage.GET_MY_PATIENT_PROFILE_SUCCESS)
                .build();
    }
}
