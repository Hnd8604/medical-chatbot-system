package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.AdminUserItemResponse;
import com.medicalchatbot.backend.dto.response.AdminUserListResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPatientLinkRepository userPatientLinkRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    void adminCannotLockSelf() {
        User admin = user(UUID.fromString("00000000-0000-0000-0000-000000000401"), "admin_demo", UserRole.ADMIN, UserStatus.ACTIVE);
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> newService().updateStatus(admin.getId(), UserStatus.LOCKED)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(admin);
    }

    @Test
    void adminCannotDemoteSelf() {
        User admin = user(UUID.fromString("00000000-0000-0000-0000-000000000402"), "admin_demo", UserRole.ADMIN, UserStatus.ACTIVE);
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> newService().updateRole(admin.getId(), UserRole.DOCTOR)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(admin);
    }

    @Test
    void adminCanUpdateAnotherUsersRoleAndStatus() {
        User admin = user(UUID.fromString("00000000-0000-0000-0000-000000000403"), "admin_demo", UserRole.ADMIN, UserStatus.ACTIVE);
        User target = user(UUID.fromString("00000000-0000-0000-0000-000000000404"), "doctor_demo", UserRole.DOCTOR, UserStatus.ACTIVE);
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        AdminUserItemResponse roleResponse = newService().updateRole(target.getId(), UserRole.USER);
        AdminUserItemResponse statusResponse = newService().updateStatus(target.getId(), UserStatus.LOCKED);

        assertEquals(UserRole.USER, roleResponse.role());
        assertEquals(UserStatus.LOCKED, statusResponse.status());
        verify(userRepository, times(2)).save(target);
    }

    @Test
    void listUsersIncludesPatientLinks() {
        User user = user(UUID.fromString("00000000-0000-0000-0000-000000000405"), "user_demo", UserRole.USER, UserStatus.ACTIVE);
        UserPatientLink link = UserPatientLink.builder()
                .user(user)
                .fhirPatientId("BN2026-00001")
                .relationship("SELF")
                .primaryLink(true)
                .build();
        when(userRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));
        when(userPatientLinkRepository.findLinksForUsers(List.of(user.getId())))
                .thenReturn(List.of(link));

        AdminUserListResponse response = newService().listUsers(0, 20);

        assertEquals(1, response.users().size());
        AdminUserItemResponse item = response.users().get(0);
        assertEquals(1, item.patientLinks().size());
        assertEquals("BN2026-00001", item.patientLinks().get(0).fhirPatientId());
        assertEquals("SELF", item.patientLinks().get(0).relationship());
    }

    private AdminUserService newService() {
        return new AdminUserService(
                userRepository,
                userPatientLinkRepository,
                currentUserService,
                auditLogRepository,
                new ObjectMapper()
        );
    }

    private User user(UUID id, String username, UserRole role, UserStatus status) {
        User user = new User(id);
        ReflectionTestUtils.setField(user, "username", username);
        ReflectionTestUtils.setField(user, "email", username + "@medical-chatbot.local");
        ReflectionTestUtils.setField(user, "displayName", username);
        ReflectionTestUtils.setField(user, "role", role);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "passwordHash", "hash");
        return user;
    }
}
