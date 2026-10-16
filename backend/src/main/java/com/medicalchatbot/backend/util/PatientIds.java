package com.medicalchatbot.backend.util;

import java.util.regex.Pattern;

/** FHIR patient ids arrive both as {@code BN2026-00001} and {@code Patient/BN2026-00001}. */
public final class PatientIds {

    private static final Pattern RESOURCE_PREFIX = Pattern.compile("(?i)^Patient/");

    private PatientIds() {
    }

    /** Bare patient id without the {@code Patient/} prefix, or {@code null} when blank. */
    public static String normalize(String value) {
        String trimmed = TextUtils.trimToNull(value);
        return trimmed == null ? null : TextUtils.trimToNull(RESOURCE_PREFIX.matcher(trimmed).replaceFirst(""));
    }

    public static boolean same(String left, String right) {
        String normalizedLeft = normalize(left);
        return normalizedLeft != null && normalizedLeft.equalsIgnoreCase(normalize(right));
    }
}
