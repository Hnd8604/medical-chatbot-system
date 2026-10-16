package com.medicalchatbot.backend.entity.converter;

import com.medicalchatbot.backend.enums.CostSource;
import jakarta.persistence.Converter;

@Converter
public class CostSourceConverter extends PersistedEnumConverter<CostSource> {

    public CostSourceConverter() {
        super(CostSource.class);
    }
}
