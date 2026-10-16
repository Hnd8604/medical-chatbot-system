package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.patient.PatientSearchRequest;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.service.PatientService;
import com.medicalchatbot.backend.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PatientController.class)
@AutoConfigureMockMvc(addFilters = false)
class PatientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void patientPayloadIsPassedThroughUnchanged() throws Exception {
        when(patientService.getPatient("BN2026-00001"))
                .thenReturn(objectMapper.readTree("{\"id\": \"BN2026-00001\", \"birthDate\": \"2003-01-01\"}"));

        mockMvc.perform(get("/patients/BN2026-00001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value("BN2026-00001"))
                // FHIR field names are not renamed by the snake_case API convention.
                .andExpect(jsonPath("$.result.birthDate").value("2003-01-01"));
    }

    @Test
    void searchForwardsTheCriteria() throws Exception {
        PatientSearchRequest search = new PatientSearchRequest("Nguyen Van A", null, "2003-01-01", null, 20);
        when(patientService.searchPatients(search))
                .thenReturn(objectMapper.readTree("{\"patients\": [{\"id\": \"BN2026-00001\"}]}"));

        mockMvc.perform(get("/patients?name=Nguyen Van A&birthDate=2003-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.patients[0].id").value("BN2026-00001"));
        verify(patientService).searchPatients(search);
    }

    @Test
    void resourceEndpointsUseTheirDefaultLimits() throws Exception {
        when(patientService.getResources("BN2026-00005", PatientResource.ENCOUNTERS, 5))
                .thenReturn(objectMapper.readTree("{\"encounters\": [{\"id\": \"ENC-1\"}]}"));

        mockMvc.perform(get("/patients/BN2026-00005/encounters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.encounters[0].id").value("ENC-1"));
    }

    @Test
    void limitAboveFiftyIsAValidationError() throws Exception {
        mockMvc.perform(get("/patients/BN2026-00001/observations?limit=100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9009))
                .andExpect(jsonPath("$.message").value("Tham số 'limit' phải nhỏ hơn hoặc bằng 50."));
    }
}
