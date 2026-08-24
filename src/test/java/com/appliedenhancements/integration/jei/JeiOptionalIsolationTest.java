package com.appliedenhancements.integration.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Prevents optional JEI classes from leaking into always-loaded code. */
class JeiOptionalIsolationTest {
    @Test
    void jeiImportsStayInsideOptionalIntegrationPackage() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java");
        Path allowedRoot = sourceRoot.resolve(
                "com/appliedenhancements/integration/jei");
        Set<String> leaked = new TreeSet<>();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                if (!source.startsWith(allowedRoot)
                        && Files.readString(source).contains("mezz.jei")) {
                    leaked.add(sourceRoot.relativize(source).toString());
                }
            }
        }

        assertEquals(Set.of(), leaked,
                () -> "JEI references escaped the optional integration package: "
                        + leaked);
    }
}
