package com.appliedenhancements.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protects Mixin's owned package from ordinary runtime classes.
 *
 * <p>Mixin refuses to load a class from a package registered by a Mixin
 * configuration unless that class is itself declared by the configuration.
 * Keeping helpers in that package therefore compiles successfully but crashes
 * as soon as a transformed class tries to load the helper.</p>
 */
class MixinOwnedPackageGuardTest {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern TOP_LEVEL_TYPE_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern CONFIG_PACKAGE_PATTERN = Pattern.compile(
            "\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CONFIG_ENTRY_PATTERN = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"");

    @Test
    void ownedMixinPackageContainsOnlyConfiguredMixinTypes() throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path config = projectRoot.resolve("src/main/resources/appliedenhancements.mixins.json");

        Set<String> undeclared = findUndeclaredOwnedTypes(sourceRoot, config);

        assertEquals(Set.of(), undeclared, () -> "Mixin owned package contains ordinary runtime types: "
                + undeclared
                + ". Move helpers outside the configured Mixin package, or declare actual Mixins/Accessors "
                + "in appliedenhancements.mixins.json.");
    }

    @Test
    void regressionDetectsAllHelpersFromIllegalClassLoadCrash(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Path ownedPackage = sourceRoot.resolve("com/appliedenhancements/mixin");
        Files.createDirectories(ownedPackage);

        Set<String> historicHelpers = Set.of(
                "CraftingProgressSnapshotOrder",
                "CraftingProgressTaskBinding",
                "MolecularBalancedBatchScope",
                "NativeCraftingLongSafety",
                "NetworkStorageDetectionCache",
                "TerminalAwareFuture");
        for (String helper : historicHelpers) {
            Files.writeString(
                    ownedPackage.resolve(helper + ".java"),
                    "package com.appliedenhancements.mixin; public final class " + helper + " {}");
        }
        Files.writeString(
                ownedPackage.resolve("DeclaredMixin.java"),
                "package com.appliedenhancements.mixin; public final class DeclaredMixin {}");

        Path config = tempDir.resolve("appliedenhancements.mixins.json");
        Files.writeString(config, """
                {
                  "package": "com.appliedenhancements.mixin",
                  "mixins": ["DeclaredMixin"],
                  "client": []
                }
                """);

        Set<String> expected = new TreeSet<>();
        for (String helper : historicHelpers) {
            expected.add("com.appliedenhancements.mixin." + helper);
        }
        assertEquals(expected, findUndeclaredOwnedTypes(sourceRoot, config));
    }

    private static Set<String> findUndeclaredOwnedTypes(Path sourceRoot, Path mixinConfig) throws IOException {
        String configText = Files.readString(mixinConfig);
        String ownedPackage = requiredMatch(CONFIG_PACKAGE_PATTERN, configText, "Mixin package");
        Set<String> configuredTypes = configuredTypes(configText, ownedPackage);
        Path ownedPackageDirectory = sourceRoot.resolve(ownedPackage.replace('.', '/'));

        Set<String> undeclaredTypes = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(ownedPackageDirectory)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String sourceText = Files.readString(source);
                String sourcePackage = requiredMatch(PACKAGE_PATTERN, sourceText, "Java package in " + source);
                for (String type : topLevelTypes(sourceText)) {
                    String qualifiedName = sourcePackage + "." + type;
                    if (!configuredTypes.contains(qualifiedName)) {
                        undeclaredTypes.add(qualifiedName);
                    }
                }
            }
        }
        return undeclaredTypes;
    }

    private static Set<String> configuredTypes(String configText, String ownedPackage) {
        Set<String> configuredTypes = new LinkedHashSet<>();
        for (String section : Set.of("mixins", "client", "server")) {
            Pattern sectionPattern = Pattern.compile(
                    "\"" + Pattern.quote(section) + "\"\\s*:\\s*\\[(.*?)]",
                    Pattern.DOTALL);
            Matcher sectionMatcher = sectionPattern.matcher(configText);
            if (!sectionMatcher.find()) {
                continue;
            }
            Matcher entryMatcher = CONFIG_ENTRY_PATTERN.matcher(sectionMatcher.group(1));
            while (entryMatcher.find()) {
                configuredTypes.add(ownedPackage + "." + entryMatcher.group(1));
            }
        }
        return configuredTypes;
    }

    private static Set<String> topLevelTypes(String sourceText) {
        String codeOnly = maskCommentsAndLiterals(sourceText);
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = TOP_LEVEL_TYPE_PATTERN.matcher(codeOnly);
        int cursor = 0;
        int braceDepth = 0;
        while (matcher.find()) {
            for (int index = cursor; index < matcher.start(); index++) {
                char character = codeOnly.charAt(index);
                if (character == '{') {
                    braceDepth++;
                } else if (character == '}') {
                    braceDepth--;
                }
            }
            if (braceDepth == 0) {
                types.add(matcher.group(1));
            }
            cursor = matcher.end();
        }
        return types;
    }

    private static String maskCommentsAndLiterals(String source) {
        StringBuilder masked = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean stringLiteral = false;
        boolean characterLiteral = false;
        boolean escaped = false;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    masked.append(current);
                } else {
                    masked.append(' ');
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    masked.append("  ");
                    index++;
                    blockComment = false;
                } else {
                    masked.append(current == '\n' || current == '\r' ? current : ' ');
                }
            } else if (stringLiteral || characterLiteral) {
                masked.append(current == '\n' || current == '\r' ? current : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((stringLiteral && current == '"') || (characterLiteral && current == '\'')) {
                    stringLiteral = false;
                    characterLiteral = false;
                }
            } else if (current == '/' && next == '/') {
                masked.append("  ");
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                masked.append("  ");
                index++;
                blockComment = true;
            } else if (current == '"') {
                masked.append(' ');
                stringLiteral = true;
            } else if (current == '\'') {
                masked.append(' ');
                characterLiteral = true;
            } else {
                masked.append(current);
            }
        }
        return masked.toString();
    }

    private static String requiredMatch(Pattern pattern, String input, String description) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            throw new IllegalArgumentException(description + " was not found");
        }
        return matcher.group(1);
    }
}
