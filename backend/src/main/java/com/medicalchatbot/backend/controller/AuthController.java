package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.request.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.LinkPatientRequest;
import com.medicalchatbot.backend.dto.request.LoginRequest;
import com.medicalchatbot.backend.dto.request.RefreshTokenRequest;
import com.medicalchatbot.backend.dto.request.RegisterRequest;
import com.medicalchatbot.backend.dto.request.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.UpdateProfileRequest;
import com.medicalchatbot.backend.dto.request.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.LoginResponse;
import com.medicalchatbot.backend.dto.response.MessageResponse;
import com.medicalchatbot.backend.dto.response.ResetTicketResponse;
import com.medicalchatbot.backend.dto.response.TokenResponse;
import com.medicalchatbot.backend.service.AuthService;
import com.medicalchatbot.backend.service.PasswordResetService;
import com.medicalchatbot.backend.service.PatientLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final PatientLinkService patientLinkService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        authService.logout();
    }

    @GetMapping("/me")
    public AuthUserResponse me() {
        return authService.currentUser();
    }

    @PutMapping("/me")
    public AuthUserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(request);
    }

    /** Signs the user out everywhere; the client must log in again. */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
    }

    @PostMapping("/link-patient")
    public AuthUserResponse linkPatient(@Valid @RequestBody LinkPatientRequest request) {
        return patientLinkService.linkCurrentUser(request);
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return passwordResetService.sendResetCode(request);
    }

    @PostMapping("/verify-reset-code")
    public ResetTicketResponse verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        return passwordResetService.verifyResetCode(request);
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
    }
}
