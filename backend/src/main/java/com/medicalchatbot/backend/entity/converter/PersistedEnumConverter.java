package com.medicalchatbot.backend.entity.converter;

import com.medicalchatbot.backend.enums.PersistedEnum;
import jakarta.persistence.AttributeConverter;

/** Stores a {@link PersistedEnum} by its {@link PersistedEnum#value()} instead of its name. */
public abstract class PersistedEnumConverter<E extends Enum<E> & PersistedEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected PersistedEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PersistedEnum.fromValue(type, dbData);
    }
}
