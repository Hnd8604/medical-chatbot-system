package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.Objects;

import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.util.PatientIds;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Decides which patient records a chat turn may touch. Staff (DOCTOR/ADMIN) may ask about any
 * patient; a USER is confined to the records linked to their own account.
 */
@Service
@RequiredArgsConstructor
public class PatientScopeService {

    private final UserPatientLinkRepository userPatientLinkRepository;

    /**
     * @param requestedPatientId patient explicitly selected for this turn, may be null
     * @param memory             memory of the session, whose active patient is the default focus
     * @throws AppException when a USER has no linked record or asks for someone else's
     */
    public PatientScope resolve(User user, String requestedPatientId, ChatMemory memory) {
        String requested = PatientIds.normalize(requestedPatientId);
        String remembered = PatientIds.normalize(memory.activePatientId());
        if (user.getRole() != UserRole.USER) {
            return PatientScope.staff(TextUtils.firstNonBlank(requested, remembered));
        }

        List<String> allowedPatientIds = linkedPatientIds(user);
        if (allowedPatientIds.isEmpty()) {
            throw AppException.forbidden("Tài khoản USER chưa được liên kết với hồ sơ FHIR nào.");
        }
        if (requested != null && !allowedPatientIds.contains(requested)) {
            throw AppException.forbidden("Tài khoản USER chỉ được truy cập hồ sơ FHIR đã liên kết với chính mình.");
        }
        String effectivePatientId = TextUtils.firstNonBlank(
                requested,
                allowedPatientIds.contains(remembered) ? remembered : null,
                allowedPatientIds.get(0)
        );
        return PatientScope.self(effectivePatientId, allowedPatientIds);
    }

    /** Linked patient ids of the user, primary first; used both for chat scope and the profile panel. */
    public List<String> linkedPatientIds(User user) {
        return userPatientLinkRepository.findPatientIdsByUserId(user.getId()).stream()
                .map(PatientIds::normalize)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public record PatientScope(String effectivePatientId, List<String> allowedPatientIds, String name, boolean selfScoped) {

        static PatientScope staff(String effectivePatientId) {
            return new PatientScope(effectivePatientId, List.of(), "STAFF", false);
        }

        static PatientScope self(String effectivePatientId, List<String> allowedPatientIds) {
            return new PatientScope(effectivePatientId, allowedPatientIds, "SELF", true);
        }

        /**
         * Memory a USER may carry into this turn: if the remembered patient is not one of theirs
         * (e.g. the link changed), the remembered context is dropped.
         */
        public ChatMemory restrict(ChatMemory memory) {
            if (!selfScoped) {
                return memory;
            }
            String remembered = PatientIds.normalize(memory.activePatientId());
            return remembered != null && allowedPatientIds.contains(remembered)
                    ? memory
                    : ChatMemory.forPatient(effectivePatientId);
        }
    }
}
