package com.medicalchatbot.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.repository.AlertRepository;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.BackupHistoryRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.MessageFeedbackRepository;
import com.medicalchatbot.backend.repository.NotificationRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.RestoreHistoryRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.service.AdminUserService;
import com.medicalchatbot.backend.service.PatientService;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The whole application context wires up (configuration properties, beans, security) without a database. */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        // Without a DataSource there is no "db" health contributor for the readiness group.
        "management.endpoint.health.validate-group-membership=false"
})
class SpringBackendApplicationTests {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserPatientLinkRepository userPatientLinkRepository;

    @MockitoBean
    private NotificationRepository notificationRepository;

    @MockitoBean
    private ChatSessionRepository chatSessionRepository;

    @MockitoBean
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private UsageLogRepository usageLogRepository;

    @MockitoBean
    private LlmCallUsageRepository llmCallUsageRepository;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private AlertRepository alertRepository;

    @MockitoBean
    private BackupHistoryRepository backupHistoryRepository;

    @MockitoBean
    private RestoreHistoryRepository restoreHistoryRepository;

    @MockitoBean
    private MessageFeedbackRepository messageFeedbackRepository;

    @MockitoBean
    private QuotaPolicyRepository quotaPolicyRepository;

    @MockitoBean
    private ChatbotServiceClient chatbotServiceClient;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    // @EnableJpaAuditing needs a mapping context, which the excluded JPA setup would provide.
    @MockitoBean
    private JpaMetamodelMappingContext jpaMappingContext;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private PatientService patientService;

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void contextLoads() {
    }

    /** Second layer behind the URL rules: the services themselves check the role. */
    @Test
    void servicesEnforceTheirRoleEvenWhenCalledDirectly() {
        TestUsers.signIn(TestUsers.user(UserRole.USER));

        assertThrows(AccessDeniedException.class, () -> adminUserService.list(0, 20, null));
        assertThrows(AccessDeniedException.class, () -> patientService.getPatient("BN2026-00001"));
    }
}
