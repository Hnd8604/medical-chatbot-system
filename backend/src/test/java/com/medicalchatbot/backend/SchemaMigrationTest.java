package com.medicalchatbot.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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

    /** The development profile also loads the demo data, which must apply cleanly on top of the schema. */
    @Test
    void devSeedAppliesOnTopOfTheSchema() throws Exception {
        cleanAndMigrate("classpath:db/migration", "classpath:db/devmigration");

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet demoAccounts = statement.executeQuery(
                     "select count(*) from app_users where username in ('user_demo', 'doctor_demo', 'admin_demo')")) {
            demoAccounts.next();
            assertThat(demoAccounts.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void migrationsBuildSchemaThatMatchesEntities() throws Exception {
        var result = cleanAndMigrate("classpath:db/migration");

        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet accounts = statement.executeQuery("select count(*) from app_users")) {
            accounts.next();
            assertThat(accounts.getInt(1)).as("production migrations seed no account").isZero();
        }

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

    private static MigrateResult cleanAndMigrate(String... locations) {
        String databaseName = URL.substring(URL.lastIndexOf('/') + 1).split("\\?", 2)[0].toLowerCase();
        assertThat(databaseName.contains("test") || databaseName.contains("tmp"))
                .as("TEST_DB_URL must name an explicitly disposable test/tmp database before Flyway clean runs")
                .isTrue();

        // The name guard above must pass before clean removes the complete public schema.
        Flyway flyway = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(locations)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        return flyway.migrate();
    }
}
