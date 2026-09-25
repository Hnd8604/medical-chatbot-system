package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminAlertControllerTest {

    @Mock
    private AlertService alertService;

    @Mock
    private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminAlertController(alertService, currentUserService)
        ).build();
    }

    @Test
    void resolveUsesAuthenticatedUsernameAndIgnoresSpoofedQueryParameter() throws Exception {
        UUID alertId = UUID.randomUUID();
        when(currentUserService.getCurrentUsername()).thenReturn("real-admin");

        mockMvc.perform(patch("/api/admin/alerts/{id}/resolve", alertId)
                        .queryParam("resolvedBy", "spoofed-admin"))
                .andExpect(status().isOk());

        verify(alertService).resolveAlert(alertId, "real-admin");
    }
}
