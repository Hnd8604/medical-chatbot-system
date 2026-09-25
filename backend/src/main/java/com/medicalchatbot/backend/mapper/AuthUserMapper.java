package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps the user domain entity to the authenticated-user API representation. */
@Component
@RequiredArgsConstructor
public class AuthUserMapper {

    private final UserPatientLinkRepository userPatientLinkRepository;

    public AuthUserResponse toResponse(User user) {
        boolean onboardingRequired = user.getRole() == UserRole.USER
                && userPatientLinkRepository.findPatientIdsForUser(user.getId()).isEmpty();
        return AuthUserResponse.from(user, onboardingRequired);
    }
}
