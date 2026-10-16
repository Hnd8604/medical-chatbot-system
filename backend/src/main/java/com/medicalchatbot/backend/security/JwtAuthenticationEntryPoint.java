package com.medicalchatbot.backend.security;

import java.io.IOException;

import com.medicalchatbot.backend.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Answers every authentication failure with the standard error body instead of an empty 401. */
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    SecurityErrorWriter securityErrorWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        securityErrorWriter.write(response, errorCode(exception));
    }

    static ErrorCode errorCode(AuthenticationException exception) {
        if (exception instanceof TokenAuthenticationException tokenException) {
            return tokenException.getErrorCode();
        }
        if (exception instanceof OAuth2AuthenticationException) {
            return isExpired(exception) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID;
        }
        // No token at all on a protected endpoint.
        return ErrorCode.UNAUTHENTICATED;
    }

    /** The timestamp validator reports expiry only through its error description ("Jwt expired at ..."). */
    private static boolean isExpired(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation) {
                return validation.getErrors().stream()
                        .anyMatch(error -> error.getDescription() != null && error.getDescription().contains("expired"));
            }
        }
        return false;
    }
}
