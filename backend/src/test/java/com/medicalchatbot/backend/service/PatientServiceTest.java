package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.request.PatientSearchRequest;
import com.medicalchatbot.backend.dto.response.SelfPatientProfileResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private ChatbotServiceClient chatbotServiceClient;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private PatientScopeService patientScopeService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PatientService service;

    @BeforeEach
    void setUp() {
        service = new PatientService(chatbotServiceClient, currentUserService, patientScopeService);
    }

    @Test
    void patientPassesTheUpstreamPayloadThrough() throws Exception {
        JsonNode upstream = objectMapper.readTree("""
                {"id": "BN2026-00001", "name": "Van A Nguyen", "telecom": [{"system": "phone", "value": "0901234567"}]}
                """);
        when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(upstream);

        assertEquals(upstream, service.getPatient("BN2026-00001"));
    }

    @Test
    void searchSendsOnlyNonBlankCriteria() {
        PatientSearchRequest search = new PatientSearchRequest("Nguyen Van A", " ", "2003-01-01", null, 25);

        service.searchPatients(search);

        verify(chatbotServiceClient).searchPatients(search);
        assertEquals(Map.of("name", "Nguyen Van A", "birth_date", "2003-01-01"), search.criteria());
    }

    @Test
    void currentUserProfileUsesThePrimaryLinkedPatient() throws Exception {
        User user = TestUsers.user(UserRole.USER);
        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(patientScopeService.linkedPatientIds(user)).thenReturn(List.of("BN2026-00007", "BN2026-00009"));
        when(chatbotServiceClient.getPatient("BN2026-00007")).thenReturn(objectMapper.readTree("{\"id\":\"BN2026-00007\"}"));

        SelfPatientProfileResponse profile = service.currentUserProfile();

        assertEquals("BN2026-00007", profile.patientId());
        verify(chatbotServiceClient).getPatientResources("BN2026-00007", PatientResource.ENCOUNTERS, 5);
        verify(chatbotServiceClient).getPatientResources("BN2026-00007", PatientResource.MEDICATIONS, 20);
        verify(chatbotServiceClient, never()).getPatient("BN2026-00009");
    }

    @Test
    void currentUserProfileWithoutLinkIsNotFound() {
        User user = TestUsers.user(UserRole.USER);
        when(currentUserService.requireCurrentUser()).thenReturn(user);
        when(patientScopeService.linkedPatientIds(user)).thenReturn(List.of());

        AppException exception = assertThrows(AppException.class, () -> service.currentUserProfile());

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        verify(chatbotServiceClient, never()).getPatientResources(any(), eq(PatientResource.ENCOUNTERS), anyInt());
    }
}
