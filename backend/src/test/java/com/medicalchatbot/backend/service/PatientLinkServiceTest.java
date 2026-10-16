package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.request.patient.LinkPatientRequest;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.PatientRelationship;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PatientLinkServiceTest {

    private static final String PATIENT_JSON = """
            {"id": "BN2026-00001", "birth_date": "2003-01-01",
             "telecom": [{"system": "phone", "value": "+84 900 000 001"}]}
            """;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPatientLinkRepository userPatientLinkRepository;

    @Mock
    private ChatbotServiceClient chatbotServiceClient;

    @Mock
    private AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PatientLinkService service;
    private User user;

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(auditLogRepository, Mappers.getMapper(AuditLogMapper.class), objectMapper);
        service = new PatientLinkService(userRepository, userPatientLinkRepository, chatbotServiceClient,
                Mappers.getMapper(UserMapper.class), new AuthAuditService(auditService));
        user = TestUsers.user(UserRole.USER);
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void createsSelfPrimaryLinkWhenVerificationMatches() throws Exception {
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findSelfLinksByUserId(user.getId())).thenReturn(List.of());
        when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(objectMapper.readTree(PATIENT_JSON));
        when(userPatientLinkRepository.existsSelfLinkForOtherUser("BN2026-00001", user.getId())).thenReturn(false);

        var response = service.linkCurrentUser(new LinkPatientRequest("Patient/BN2026-00001", "2003-01-01", "0900000001"));

        assertFalse(response.onboardingRequired());
        ArgumentCaptor<UserPatientLink> link = ArgumentCaptor.forClass(UserPatientLink.class);
        verify(userPatientLinkRepository).saveAndFlush(link.capture());
        assertEquals("BN2026-00001", link.getValue().getFhirPatientId());
        assertEquals(PatientRelationship.SELF, link.getValue().getRelationship());
        assertTrue(link.getValue().isPrimaryLink());
        verify(auditLogRepository).save(any());
    }

    @Test
    void rejectsMismatchedVerification() throws Exception {
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findSelfLinksByUserId(user.getId())).thenReturn(List.of());
        when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(objectMapper.readTree(PATIENT_JSON));

        AppException exception = assertThrows(AppException.class, () -> service.linkCurrentUser(
                new LinkPatientRequest("BN2026-00001", "2004-01-01", "0900000001")));

        assertEquals(ErrorCode.PATIENT_VERIFICATION_FAILED, exception.getErrorCode());
        verify(userPatientLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownPatientLooksLikeAMismatch() {
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findSelfLinksByUserId(user.getId())).thenReturn(List.of());
        when(chatbotServiceClient.getPatient("BN2026-09999"))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        AppException exception = assertThrows(AppException.class, () -> service.linkCurrentUser(
                new LinkPatientRequest("BN2026-09999", "2003-01-01", "0900000001")));

        assertEquals(ErrorCode.PATIENT_VERIFICATION_FAILED, exception.getErrorCode());
    }

    @Test
    void unreachableFhirServiceIsAnUpstreamError() {
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findSelfLinksByUserId(user.getId())).thenReturn(List.of());
        when(chatbotServiceClient.getPatient("BN2026-00001")).thenThrow(new ResourceAccessException("down"));

        AppException exception = assertThrows(AppException.class, () -> service.linkCurrentUser(
                new LinkPatientRequest("BN2026-00001", "2003-01-01", "0900000001")));

        assertEquals(ErrorCode.PATIENT_VERIFICATION_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void relinkingTheSamePatientIsIdempotent() {
        TestUsers.signIn(user, userRepository);
        when(userPatientLinkRepository.findSelfLinksByUserId(user.getId())).thenReturn(List.of(
                UserPatientLink.builder().user(user).fhirPatientId("BN2026-00001").primaryLink(true).build()));

        service.linkCurrentUser(new LinkPatientRequest("bn2026-00001", "2003-01-01", "0900000001"));

        verify(chatbotServiceClient, never()).getPatient(any());
        verify(userPatientLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void staffAccountsDoNotLinkPatients() {
        TestUsers.signIn(TestUsers.user(UserRole.DOCTOR), userRepository);

        AppException exception = assertThrows(AppException.class, () -> service.linkCurrentUser(
                new LinkPatientRequest("BN2026-00001", "2003-01-01", "0900000001")));

        assertEquals(ErrorCode.PATIENT_LINK_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void onboardingIsOnlyRequiredForUnlinkedUsers() {
        when(userPatientLinkRepository.existsByUserId(user.getId())).thenReturn(false);

        assertTrue(service.isOnboardingRequired(user));
        assertFalse(service.isOnboardingRequired(TestUsers.user(UserRole.DOCTOR)));
    }
}
