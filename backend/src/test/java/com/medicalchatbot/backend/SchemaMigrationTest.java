package com.medicalchatbot.backend;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Applies every Flyway migration to a disposable PostgreSQL database and asks
 * Hibernate to validate all entities against the resulting schema.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SchemaMigrationTest {

    private static final String URL = System.getenv("TEST_DB_URL");
    private static final String USER = System.getenv().getOrDefault("TEST_DB_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_DB_PASSWORD", "test");

    @Test
    void migrationsBuildSchemaThatMatchesEntities() throws Exception {
        String databaseName = URL.substring(URL.lastIndexOf('/') + 1).split("\\?", 2)[0].toLowerCase();
        assertThat(databaseName.contains("test") || databaseName.contains("tmp"))
                .as("TEST_DB_URL must name an explicitly disposable test/tmp database before Flyway clean runs")
                .isTrue();

        // The name guard above must pass before clean removes the complete public schema.
        Flyway flyway = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);

        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", USER)
                .applySetting("hibernate.connection.password", PASSWORD)
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (var candidate : scanner.findCandidateComponents("com.medicalchatbot.backend.entity")) {
                sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
            }
            sources.buildMetadata().buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
