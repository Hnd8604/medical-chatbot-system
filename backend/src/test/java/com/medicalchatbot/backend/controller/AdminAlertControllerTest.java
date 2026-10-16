package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.service.AlertService;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAlertController(alertService)).build();
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void resolveUsesAuthenticatedUsernameAndIgnoresSpoofedQueryParameter() throws Exception {
        UUID alertId = UUID.randomUUID();
        TestUsers.signIn(TestUsers.user(UUID.randomUUID(), "real-admin", UserRole.ADMIN));

        mockMvc.perform(put("/admin/alerts/{id}/resolve", alertId).queryParam("resolvedBy", "spoofed-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đánh dấu cảnh báo là đã xử lý."));

        verify(alertService).resolve(alertId, "real-admin");
    }
}
