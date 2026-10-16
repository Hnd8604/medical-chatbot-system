package com.medicalchatbot.backend.enums;

/**
 * Enum whose database (and upstream contract) value differs from its constant name, for example
 * {@code ChatMessageRole.ASSISTANT} stored as {@code assistant}.
 */
public interface PersistedEnum {

    String value();

    static <E extends Enum<E> & PersistedEnum> E fromValue(Class<E> type, String value) {
        for (E constant : type.getEnumConstants()) {
            if (constant.value().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + value);
    }
}
