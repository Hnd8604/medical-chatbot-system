package com.medicalchatbot.backend.enums;

public enum ChatMessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String databaseValue;

    ChatMessageRole(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
