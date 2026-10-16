package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.security.JwtAuthenticationFilter;
import com.medicalchatbot.backend.service.PatientService;
import com.medicalchatbot.backend.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void healthIsWrappedInTheSuccessEnvelope() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("ok"));
    }

    @Test
    void chatbotStatusPassesTheUpstreamPayloadThrough() throws Exception {
        when(patientService.fhirStatus())
                .thenReturn(new ObjectMapper().readTree("{\"fhir_server\":\"up\",\"resource_count\":12}"));

        mockMvc.perform(get("/api/chatbot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.fhir_server").value("up"))
                .andExpect(jsonPath("$.result.resource_count").value(12));
    }
}
