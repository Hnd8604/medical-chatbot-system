package com.medicalchatbot.backend.util;

import java.util.UUID;

import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.security.AuthenticatedUser;

/** The user of the current request, as authenticated from its access token. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** @throws AppException {@link ErrorCode#UNAUTHENTICATED} when the request is anonymous */
    public static AuthenticatedUser currentUser() {
        return AuthenticatedUser.current().orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
    }

    public static UUID currentUserId() {
        return currentUser().id();
    }

    public static String currentUsername() {
        return currentUser().username();
    }
}
