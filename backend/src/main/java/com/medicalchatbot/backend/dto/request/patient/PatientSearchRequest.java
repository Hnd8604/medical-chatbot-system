package com.medicalchatbot.backend.dto.request.patient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Patient search criteria; blank criteria are ignored. */
public record PatientSearchRequest(String name, String phone, String birthDate, String identifier, int limit) {

    /** Non-blank criteria keyed by the query parameter names of chatbot-service. */
    public Map<String, String> criteria() {
        Map<String, String> criteria = new LinkedHashMap<>();
        putIfPresent(criteria, "name", name);
        putIfPresent(criteria, "phone", phone);
        putIfPresent(criteria, "birth_date", birthDate);
        putIfPresent(criteria, "identifier", identifier);
        return criteria;
    }

    private static void putIfPresent(Map<String, String> criteria, String key, String value) {
        if (value != null && !value.isBlank()) {
            criteria.put(key, value);
        }
    }
}
