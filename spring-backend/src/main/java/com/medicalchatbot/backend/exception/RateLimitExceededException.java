package com.medicalchatbot.backend.exception;

public class RateLimitExceededException extends RuntimeException {

    private final String identifier;

    public RateLimitExceededException(String message, String identifier) {
        super(message);
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}