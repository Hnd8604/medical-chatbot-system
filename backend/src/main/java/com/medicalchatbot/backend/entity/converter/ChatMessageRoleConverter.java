package com.medicalchatbot.backend.entity.converter;

import com.medicalchatbot.backend.enums.ChatMessageRole;
import jakarta.persistence.Converter;

@Converter
public class ChatMessageRoleConverter extends PersistedEnumConverter<ChatMessageRole> {

    public ChatMessageRoleConverter() {
        super(ChatMessageRole.class);
    }
}
