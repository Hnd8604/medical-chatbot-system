package com.medicalchatbot.backend;

import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.ChatMessageRepository;
import com.medicalchatbot.backend.repository.ChatSessionRepository;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.service.ChatbotServiceClient;
import com.medicalchatbot.backend.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class SpringBackendApplicationTests {

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private NotificationRepository notificationRepository;

	@MockitoBean
	private ChatSessionRepository chatSessionRepository;

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private UsageLogRepository usageLogRepository;

	@MockitoBean
	private AuditLogRepository auditLogRepository;

	@MockitoBean
	private ModelPricingRepository modelPricingRepository;

	@MockitoBean
	private QuotaPolicyRepository quotaPolicyRepository;

	@MockitoBean
	private ChatbotServiceClient chatbotServiceClient;

	@Test
	void contextLoads() {
	}

}
