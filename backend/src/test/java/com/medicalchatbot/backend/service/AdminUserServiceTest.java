package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.PatientRelationship;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    private AdminUserService service;
    private User admin;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AdminUserService(
                userRepository,
                userPatientLinkRepository,
                currentUserService,
                new AuditService(auditLogRepository, new AuditLogMapper(), objectMapper),
                new UserMapper()
        );
        admin = TestUsers.user(UserRole.ADMIN);
    }

    @Test
    void adminCannotLockSelf() {
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        AppException exception = assertThrows(AppException.class,
                () -> service.updateStatus(admin.getId(), UserStatus.LOCKED));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals(UserStatus.ACTIVE, admin.getStatus());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void adminCannotDemoteSelf() {
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        AppException exception = assertThrows(AppException.class,
                () -> service.updateRole(admin.getId(), UserRole.DOCTOR));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @Test
    void adminCanUpdateAnotherUsersRoleAndStatusWithAudit() {
        User target = TestUsers.user(UserRole.DOCTOR);
        when(currentUserService.requireCurrentUser()).thenReturn(admin);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        AdminUserResponse roleResponse = service.updateRole(target.getId(), UserRole.USER);
        AdminUserResponse statusResponse = service.updateStatus(target.getId(), UserStatus.LOCKED);

        assertEquals(UserRole.USER, roleResponse.role());
        assertEquals(UserStatus.LOCKED, statusResponse.status());
        ArgumentCaptor<AuditLog> audits = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(audits.capture());
        AuditLog roleAudit = audits.getAllValues().getFirst();
        assertEquals("ADMIN_UPDATE_USER_ROLE", roleAudit.getAction());
        assertEquals(admin, roleAudit.getUser());
        assertEquals(target.getId().toString(), roleAudit.getResourceId());
        assertEquals("DOCTOR", roleAudit.getMetadataJson().path("old_value").asText());
        assertEquals("USER", roleAudit.getMetadataJson().path("new_value").asText());
    }

    @Test
    void listIncludesPatientLinksAndPageMetadata() {
        User user = TestUsers.user(UserRole.USER);
        UserPatientLink link = UserPatientLink.builder()
                .user(user)
                .fhirPatientId("BN2026-00001")
                .relationship(PatientRelationship.SELF)
                .primaryLink(true)
                .build();
        when(userRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(userPatientLinkRepository.findByUserIds(List.of(user.getId()))).thenReturn(List.of(link));

        PageResponse<AdminUserResponse> response = service.list(0, 20, "  ");

        assertEquals(1, response.totalElements());
        assertEquals(1, response.items().size());
        AdminUserResponse.PatientLink patientLink = response.items().getFirst().patientLinks().getFirst();
        assertEquals("BN2026-00001", patientLink.fhirPatientId());
        assertEquals(PatientRelationship.SELF, patientLink.relationship());
        assertEquals(true, patientLink.isPrimary());
    }

    @Test
    void searchTermSwitchesToSearchQuery() {
        when(userRepository.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list(0, 20, " doctor ");

        verify(userRepository).search(eq("doctor"), any(Pageable.class));
    }
}
