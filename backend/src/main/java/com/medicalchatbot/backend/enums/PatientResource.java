package com.medicalchatbot.backend.enums;

/** Clinical resources chatbot-service exposes per patient ({@code /patients/{id}/<path>}). */
public enum PatientResource {
    OBSERVATIONS("observations"),
    CONDITIONS("conditions"),
    ENCOUNTERS("encounters"),
    MEDICATIONS("medications");

    private final String path;

    PatientResource(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
