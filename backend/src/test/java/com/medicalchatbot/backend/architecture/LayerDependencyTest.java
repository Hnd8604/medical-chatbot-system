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
 * Lightweight architecture checks without another test dependency. They protect the dependency
 * direction of the layers: controller → service → repository/client, with DTOs at the edge.
 */
class LayerDependencyTest {

    private static final String BASE = "com.medicalchatbot.backend.";
    private static final Path SOURCE_ROOT = locateSourceRoot();

    @Test
    void persistenceLayerDoesNotDependOnApiDtos() throws IOException {
        assertSourcesDoNotContain("entity", BASE + "dto.");
        assertSourcesDoNotContain("repository", BASE + "dto.");
    }

    @Test
    void dtosDoNotExposeEntities() throws IOException {
        assertSourcesDoNotContain("dto", BASE + "entity.");
    }

    @Test
    void controllersOnlyTalkToServices() throws IOException {
        assertSourcesDoNotContain("controller", BASE + "client.");
        assertSourcesDoNotContain("controller", BASE + "repository.");
        assertSourcesDoNotContain("controller", BASE + "entity.");
    }

    @Test
    void outboundClientsDoNotDependOnApplicationServices() throws IOException {
        assertSourcesDoNotContain("client", BASE + "service.");
        assertSourcesDoNotContain("client", BASE + "repository.");
    }

    @Test
    void applicationServicesDoNotOwnSqlQueries() throws IOException {
        assertSourcesDoNotContain("service", "JdbcTemplate");
    }

    @Test
    void applicationServicesDoNotThrowWebTransportExceptions() throws IOException {
        assertSourcesDoNotContain("service", "ResponseStatusException");
        assertSourcesDoNotContain("service", "org.springframework.http.HttpStatus");
    }

    private static void assertSourcesDoNotContain(String layer, String forbiddenText) throws IOException {
        Path directory = SOURCE_ROOT.resolve(layer);
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
