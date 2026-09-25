package com.medicalchatbot.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Lightweight architecture checks without adding another test dependency.
 * These rules protect the dependency direction of the layered application.
 */
class LayerDependencyTest {

    private static final Path SOURCE_ROOT = locateSourceRoot();

    @Test
    void persistenceLayerDoesNotDependOnApiDtos() throws IOException {
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("entity"), "com.medicalchatbot.backend.dto.");
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("repository"), "com.medicalchatbot.backend.dto.");
    }

    @Test
    void controllersDoNotCallIntegrationAdaptersDirectly() throws IOException {
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("controller"), "com.medicalchatbot.backend.integration.");
    }

    @Test
    void outboundAdaptersDoNotDependOnApplicationServices() throws IOException {
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("integration"), "com.medicalchatbot.backend.service.");
    }

    @Test
    void applicationServicesDoNotOwnSqlQueries() throws IOException {
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("service"), "JdbcTemplate");
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("service"), "NamedParameterJdbcTemplate");
    }

    @Test
    void applicationServicesDoNotThrowWebTransportExceptions() throws IOException {
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("service"), "ResponseStatusException");
        assertSourcesDoNotContain(SOURCE_ROOT.resolve("service"), "org.springframework.http.HttpStatus");
    }

    private static void assertSourcesDoNotContain(Path directory, String forbiddenText) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(directory)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(source).contains(forbiddenText)) {
                    violations.add(SOURCE_ROOT.relativize(source).toString());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Forbidden dependency '" + forbiddenText + "' found in: " + String.join(", ", violations));
    }

    private static Path locateSourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "medicalchatbot", "backend");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        Path repositoryRoot = Path.of("backend", "src", "main", "java", "com", "medicalchatbot", "backend");
        assertTrue(Files.isDirectory(repositoryRoot), "Cannot locate Spring backend source root");
        return repositoryRoot;
    }
}
