package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.auth.ChangePasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.ForgotPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.LoginRequest;
import com.medicalchatbot.backend.dto.request.auth.RefreshTokenRequest;
import com.medicalchatbot.backend.dto.request.auth.RegisterRequest;
import com.medicalchatbot.backend.dto.request.auth.ResetPasswordRequest;
import com.medicalchatbot.backend.dto.request.auth.VerifyResetCodeRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.MessageResponse;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.dto.response.auth.LoginResponse;
import com.medicalchatbot.backend.dto.response.auth.ResetTicketResponse;
import com.medicalchatbot.backend.dto.response.auth.TokenResponse;
import com.medicalchatbot.backend.service.AuthService;
import com.medicalchatbot.backend.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Authentication", description = "Login, registration, token refresh, logout and password management")
public class AuthController {

    AuthService authService;
    PasswordResetService passwordResetService;

    @PostMapping("/login")
    @Operation(summary = "Log in",
            description = "Authenticates with a username or email and returns an access token, a refresh token and the account.")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.<LoginResponse>builder()
                .result(authService.login(request))
                .message(ResponseMessage.LOGIN_SUCCESS)
                .build();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register", description = "Creates an active USER account on the default quota policy.")
    public ApiResponse<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.<AuthUserResponse>builder()
                .result(authService.register(request))
                .message(ResponseMessage.REGISTER_SUCCESS)
                .build();
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh tokens",
            description = "Trades a refresh token for a new token pair. The presented refresh token can no longer be used.")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.<TokenResponse>builder()
                .result(authService.refresh(request))
                .message(ResponseMessage.REFRESH_SUCCESS)
                .build();
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out", description = "Revokes every access and refresh token of the signed-in user.")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.<Void>builder().message(ResponseMessage.LOGOUT_SUCCESS).build();
    }

    @PutMapping("/password/change")
    @Operation(summary = "Change my password",
            description = "Requires the current password. Signs the user out everywhere; the client must log in again.")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.<Void>builder().message(ResponseMessage.CHANGE_PASSWORD_SUCCESS).build();
    }

    @PostMapping("/password/reset/init")
    @Operation(summary = "Request a password reset code",
            description = "Emails a 6-digit code to an active account. The answer never reveals whether the email is registered.")
    public ApiResponse<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ApiResponse.<MessageResponse>builder()
                .result(passwordResetService.sendResetCode(request))
                .message(ResponseMessage.SEND_RESET_CODE_SUCCESS)
                .build();
    }

    @PostMapping("/password/reset/verify-otp")
    @Operation(summary = "Verify a password reset code", description = "Trades a correct code for a one-time reset ticket.")
    public ApiResponse<ResetTicketResponse> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        return ApiResponse.<ResetTicketResponse>builder()
                .result(passwordResetService.verifyResetCode(request))
                .message(ResponseMessage.VERIFY_RESET_CODE_SUCCESS)
                .build();
    }

    @PostMapping("/password/reset/confirm")
    @Operation(summary = "Reset the password",
            description = "Sets a new password with the reset ticket and signs the user out of every session.")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ApiResponse.<Void>builder().message(ResponseMessage.RESET_PASSWORD_SUCCESS).build();
    }
}
