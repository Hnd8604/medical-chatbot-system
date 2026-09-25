package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Typed wrapper for a JSON value whose schema is owned by an upstream service.
 *
 * <p>{@link JsonValue} keeps the upstream JSON shape unchanged on the wire while
 * avoiding Jackson tree types in controller method signatures.</p>
 */
public record JsonPayload<T>(@JsonValue T value) {
}
