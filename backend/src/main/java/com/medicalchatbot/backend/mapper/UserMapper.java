package com.medicalchatbot.backend.mapper;

import java.util.List;

import com.medicalchatbot.backend.dto.response.admin.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface UserMapper {

    AuthUserResponse toAuthUserResponse(User user, boolean onboardingRequired);

    @Mapping(target = "patientLinks", source = "links")
    AdminUserResponse toAdminUserResponse(User user, List<UserPatientLink> links);

    @Mapping(target = "isPrimary", source = "primaryLink")
    AdminUserResponse.PatientLink toPatientLink(UserPatientLink link);
}
