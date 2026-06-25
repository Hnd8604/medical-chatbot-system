package com.medicalchatbot.backend.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserPatientScopeService {

    private final UserPatientLinkRepository userPatientLinkRepository;

    public PatientScope resolve(User user, String requestedPatientId, ChatSessionMemory sessionMemory) {
        String requestPatientId = normalizePatientId(requestedPatientId);
        String memoryPatientId = sessionMemory == null ? null : normalizePatientId(sessionMemory.activePatientId());

        if (user.getRole() != UserRole.USER) {
            return new PatientScope(
                    firstNonBlank(requestPatientId, memoryPatientId),
                    List.of(),
                    "STAFF",
                    false
            );
        }

        List<String> allowedPatientIds = userPatientLinkRepository.findPatientIdsForUser(user.getId()).stream()
                .map(UserPatientScopeService::normalizePatientId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (allowedPatientIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tai khoan USER chua duoc lien ket voi ho so FHIR nao."
            );
        }

        Set<String> allowedSet = new LinkedHashSet<>(allowedPatientIds);
        if (requestPatientId != null && !allowedSet.contains(requestPatientId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tai khoan USER chi duoc truy cap ho so FHIR da lien ket voi chinh minh."
            );
        }

        String effectivePatientId = firstNonBlank(
                requestPatientId,
                allowedSet.contains(memoryPatientId) ? memoryPatientId : null,
                allowedPatientIds.get(0)
        );

        return new PatientScope(effectivePatientId, allowedPatientIds, "SELF", true);
    }

    public ChatSessionMemory scopedMemory(ChatSessionMemory memory, PatientScope scope) {
        if (memory == null || !scope.selfScoped()) {
            return memory;
        }
        String activePatientId = normalizePatientId(memory.activePatientId());
        if (activePatientId != null && scope.allowedPatientIds().contains(activePatientId)) {
            return memory;
        }
        return new ChatSessionMemory(
                scope.effectivePatientId(),
                null,
                null,
                null,
                null,
                null
        );
    }

    public static String normalizePatientId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceFirst("(?i)^Patient/", "");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record PatientScope(
            String effectivePatientId,
            List<String> allowedPatientIds,
            String patientScope,
            boolean selfScoped
    ) {
    }
}
