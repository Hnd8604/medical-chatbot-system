package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.request.AuthLinkPatientRequest;
import com.medicalchatbot.backend.dto.request.AuthRefreshRequest;
import com.medicalchatbot.backend.dto.request.AuthRegisterRequest;
import com.medicalchatbot.backend.dto.request.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.request.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.AuthLinkPatientResponse;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.dto.response.AuthRefreshResponse;
import com.medicalchatbot.backend.dto.response.AuthRegisterResponse;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.ChangePasswordResponse;
import com.medicalchatbot.backend.dto.response.ForgotPasswordResponse;
import com.medicalchatbot.backend.dto.response.ResetPasswordResponse;
import com.medicalchatbot.backend.dto.response.VerifyResetCodeResponse;
import com.medicalchatbot.backend.service.AuthService;
import com.medicalchatbot.backend.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    @PostMapping("/login")
    public AuthLoginResponse login(@Valid @RequestBody AuthLoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthRegisterResponse> register(@Valid @RequestBody AuthRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/refresh")
    public AuthRefreshResponse refresh(@Valid @RequestBody AuthRefreshRequest request) {
        return authService.refresh(request);
    }

    @GetMapping("/me")
    public AuthUserResponse me() {
        return authService.currentUser();
    }

    @PutMapping("/me")
    public AuthUserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(request);
    }

    @PostMapping("/link-patient")
    public AuthLinkPatientResponse linkPatient(@Valid @RequestBody AuthLinkPatientRequest request) {
        return authService.linkPatient(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ChangePasswordResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request);
    }

    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return passwordResetService.forgotPassword(request);
    }

    @PostMapping("/verify-reset-code")
    public VerifyResetCodeResponse verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        return passwordResetService.verifyResetCode(request);
    }

    @PostMapping("/reset-password")
    public ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.resetPassword(request);
    }
}
