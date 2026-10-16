package com.medicalchatbot.backend.enums;

public enum ChatMessageRole implements PersistedEnum {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String value;

    ChatMessageRole(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
