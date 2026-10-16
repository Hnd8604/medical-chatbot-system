package com.medicalchatbot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Reads the token from {@code Authorization: Bearer ...}. Browsers cannot set headers on an
 * {@code EventSource}, so only the notification stream also accepts {@code ?token=}; keeping it to
 * that endpoint limits tokens showing up in access logs.
 */
public class NotificationStreamBearerTokenResolver implements BearerTokenResolver {

    private static final String STREAM_PATH = "/notifications/stream";
    private static final String TOKEN_PARAMETER = "token";

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String token = headerResolver.resolve(request);
        if (token != null) {
            return token;
        }
        if ("GET".equals(request.getMethod()) && request.getRequestURI().endsWith(STREAM_PATH)) {
            String parameter = request.getParameter(TOKEN_PARAMETER);
            return parameter == null || parameter.isBlank() ? null : parameter.strip();
        }
        return null;
    }
}
