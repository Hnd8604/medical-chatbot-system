package com.medicalchatbot.backend.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.AuthRateLimitInterceptor;
import com.medicalchatbot.backend.security.JwtAuthenticationEntryPoint;
import com.medicalchatbot.backend.security.JwtTokenProvider;
import com.medicalchatbot.backend.security.RateLimitInterceptor;
import com.medicalchatbot.backend.security.SecurityErrorWriter;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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
        JwtConfig.class,
        JwtTokenProvider.class,
        JwtAuthenticationEntryPoint.class,
        SecurityErrorWriter.class,
        SecurityConfigTest.SecurityRoutes.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;

    @MockitoBean
    private AuthRateLimitInterceptor authRateLimitInterceptor;

    @ParameterizedTest
    @ValueSource(strings = {
            "/chatbot/status",
            "/actuator/health",
            "/actuator/health/readiness",
            "/swagger-ui/index.html",
            "/api-docs/swagger-config"
    })
    void publicGetEndpointsAllowAnonymousAccess(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void publicAuthEndpointIsLimitedToConfiguredHttpMethod() throws Exception {
        mockMvc.perform(post("/auth/login")).andExpect(status().isOk());

        mockMvc.perform(get("/auth/login")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/users/my-info",
            "/users/my-patient-profile",
            "/chat",
            "/chat/sessions",
            "/usage/quota",
            "/usage/costs",
            "/notifications"
    })
    @WithMockUser(roles = "USER")
    void authenticatedEndpointsAllowAuthenticatedUser(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/users/my-info",
            "/chat",
            "/patients",
            "/admin/users"
    })
    void protectedEndpointsRejectAnonymousAccess(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1101))
                .andExpect(jsonPath("$.message").value("Bạn cần đăng nhập để tiếp tục."));
    }

    @Test
    @WithMockUser(roles = "USER")
    void patientEndpointsRejectOrdinaryUser() throws Exception {
        mockMvc.perform(get("/patients/patient-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1102));
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void patientEndpointsAllowDoctor() throws Exception {
        mockMvc.perform(get("/patients/patient-1")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/users",
            "/admin/audit-logs",
            "/admin/analytics/cache"
    })
    @WithMockUser(roles = "ADMIN")
    void adminEndpointsAllowAdmin(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/users",
            "/admin/audit-logs",
            "/admin/analytics/cache"
    })
    @WithMockUser(roles = "DOCTOR")
    void adminEndpointsRejectNonAdmin(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void unlistedEndpointIsDeniedEvenWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/unlisted")).andExpect(status().isForbidden());
    }

    @Test
    void metricsAreForAdminsOnly() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void metricsRejectNonAdmin() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void metricsAllowAdmin() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    // ----- Access tokens, through the whole resource-server chain -----

    @Test
    void validAccessTokenAuthenticatesWithTheRoleFromTheDatabase() throws Exception {
        User doctor = storedUser(UserRole.DOCTOR, UserStatus.ACTIVE, 2);

        mockMvc.perform(get("/patients").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
                .andExpect(status().isOk());
    }

    @Test
    void demotedUserLosesAccessBeforeTheTokenExpires() throws Exception {
        User user = storedUser(UserRole.DOCTOR, UserStatus.ACTIVE, 0);
        String issuedAsDoctor = bearer(user);
        user.setRole(UserRole.USER);

        mockMvc.perform(get("/patients").header(HttpHeaders.AUTHORIZATION, issuedAsDoctor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1102));
    }

    @Test
    void tokenOfAnOlderVersionIsRevoked() throws Exception {
        User user = storedUser(UserRole.USER, UserStatus.ACTIVE, 1);
        String oldToken = bearer(user);
        user.incrementTokenVersion();

        mockMvc.perform(get("/users/my-info").header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1106))
                .andExpect(jsonPath("$.message").value("Phiên đăng nhập đã hết hiệu lực."));
    }

    @Test
    void lockedAccountIsForbidden() throws Exception {
        User user = storedUser(UserRole.USER, UserStatus.LOCKED, 0);

        mockMvc.perform(get("/users/my-info").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1109))
                .andExpect(jsonPath("$.message").value("Tài khoản đã bị khóa."));
    }

    @Test
    void expiredTokenIsReportedAsExpired() throws Exception {
        User user = storedUser(UserRole.USER, UserStatus.ACTIVE, 0);
        // Expired five minutes ago: beyond the decoder's one-minute clock skew.
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim(JwtTokenProvider.TOKEN_VERSION_CLAIM, 0)
                .issuedAt(now.minus(Duration.ofMinutes(10)))
                .expiresAt(now.minus(Duration.ofMinutes(5)))
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                .getTokenValue();

        mockMvc.perform(get("/users/my-info").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1105));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-jwt",
            // Signed with another secret.
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ.c2lnbmF0dXJl"
    })
    void malformedOrForeignTokenIsInvalid(String token) throws Exception {
        mockMvc.perform(get("/users/my-info").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1104));
    }

    @Test
    void queryParameterTokenIsAcceptedOnlyOnTheNotificationStream() throws Exception {
        User user = storedUser(UserRole.USER, UserStatus.ACTIVE, 0);
        String token = jwtTokenProvider.generateToken(user);

        mockMvc.perform(get("/notifications/stream").queryParam("token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/notifications").queryParam("token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1101));
    }

    private User storedUser(UserRole role, UserStatus status, int tokenVersion) {
        User user = TestUsers.user(UUID.randomUUID(), role.name().toLowerCase(Locale.ROOT) + "_demo", role);
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateToken(user);
    }

    @RestController
    public static class SecurityRoutes {

        @RequestMapping({
                "/chatbot/status",
                "/actuator/health",
                "/actuator/health/readiness",
                "/actuator/prometheus",
                "/swagger-ui/index.html",
                "/api-docs/swagger-config",
                "/users/my-info",
                "/users/my-patient-profile",
                "/chat",
                "/chat/sessions",
                "/usage/quota",
                "/usage/costs",
                "/notifications",
                "/notifications/stream",
                "/patients",
                "/patients/patient-1",
                "/admin/users",
                "/admin/audit-logs",
                "/admin/analytics/cache",
                "/unlisted"
        })
        Map<String, String> route() {
            return Map.of("status", "ok");
        }

        @GetMapping("/auth/login")
        Map<String, String> loginWithGet() {
            return Map.of("status", "ok");
        }

        @PostMapping("/auth/login")
        Map<String, String> login() {
            return Map.of("status", "ok");
        }
    }
}
