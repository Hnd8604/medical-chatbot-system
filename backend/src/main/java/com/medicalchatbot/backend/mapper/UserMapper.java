package com.medicalchatbot.backend.mapper;

import java.util.List;

import com.medicalchatbot.backend.dto.response.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public AuthUserResponse toAuthUserResponse(User user, boolean onboardingRequired) {
        return new AuthUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                onboardingRequired
        );
    }

    public AdminUserResponse toAdminUserResponse(User user, List<UserPatientLink> links) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                links.stream().map(UserMapper::toPatientLink).toList()
        );
    }

    private static AdminUserResponse.PatientLink toPatientLink(UserPatientLink link) {
        return new AdminUserResponse.PatientLink(link.getFhirPatientId(), link.getRelationship(), link.isPrimaryLink());
    }
}
