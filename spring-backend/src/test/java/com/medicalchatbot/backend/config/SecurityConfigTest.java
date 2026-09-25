package com.medicalchatbot.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.JwtTokenService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigTest.SecurityRoutes.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        SecurityErrorWriter.class,
        SecurityConfigTest.SecurityRoutes.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/health",
            "/api/chatbot/status",
            "/actuator/health/readiness",
            "/swagger-ui/index.html",
            "/api-docs/swagger-config"
    })
    void publicGetEndpointsAllowAnonymousAccess(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @Test
    void publicAuthEndpointIsLimitedToConfiguredHttpMethod() throws Exception {
        mockMvc.perform(post("/api/auth/login"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/me",
            "/api/me/patient/profile",
            "/api/chat",
            "/api/chat/sessions",
            "/api/quota/status",
            "/api/usage/cost-summary",
            "/api/notifications",
            "/api/model-pricing"
    })
    @WithMockUser(roles = "USER")
    void authenticatedEndpointsAllowAuthenticatedUser(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/me",
            "/api/chat",
            "/api/patients",
            "/api/admin/users"
    })
    void protectedEndpointsRejectAnonymousAccess(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void patientEndpointsRejectOrdinaryUser() throws Exception {
        mockMvc.perform(get("/api/patients/patient-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void patientEndpointsAllowDoctor() throws Exception {
        mockMvc.perform(get("/api/patients/patient-1"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/users",
            "/api/audit-logs",
            "/api/metrics/cache"
    })
    @WithMockUser(roles = "ADMIN")
    void adminEndpointsAllowAdmin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void unlistedEndpointIsDeniedEvenWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/unlisted"))
                .andExpect(status().isForbidden());
    }

    @RestController
    public static class SecurityRoutes {

        @RequestMapping({
                "/api/health",
                "/api/chatbot/status",
                "/actuator/health/readiness",
                "/swagger-ui/index.html",
                "/api-docs/swagger-config",
                "/api/auth/me",
                "/api/me/patient/profile",
                "/api/chat",
                "/api/chat/sessions",
                "/api/quota/status",
                "/api/usage/cost-summary",
                "/api/notifications",
                "/api/model-pricing",
                "/api/patients",
                "/api/patients/patient-1",
                "/api/admin/users",
                "/api/audit-logs",
                "/api/metrics/cache",
                "/api/unlisted"
        })
        Map<String, String> route() {
            return Map.of("status", "ok");
        }

        @GetMapping("/api/auth/login")
        Map<String, String> loginWithGet() {
            return Map.of("status", "ok");
        }

        @PostMapping("/api/auth/login")
        Map<String, String> login() {
            return Map.of("status", "ok");
        }
    }
}
