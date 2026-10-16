package com.medicalchatbot.backend.entity.converter;

import com.medicalchatbot.backend.enums.UsageStatus;
import jakarta.persistence.Converter;

@Converter
public class UsageStatusConverter extends PersistedEnumConverter<UsageStatus> {

    public UsageStatusConverter() {
        super(UsageStatus.class);
    }
}
