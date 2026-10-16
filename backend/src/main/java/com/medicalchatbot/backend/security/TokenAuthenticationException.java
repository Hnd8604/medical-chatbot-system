package com.medicalchatbot.backend.security;

import com.medicalchatbot.backend.exception.ErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * A well-formed access token that must still be refused (revoked, unknown or inactive account).
 * {@link JwtAuthenticationEntryPoint} answers with its {@link ErrorCode}.
 */
@Getter
public class TokenAuthenticationException extends AuthenticationException {

    private final transient ErrorCode errorCode;

    public TokenAuthenticationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
