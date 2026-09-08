package com.appliedenhancements.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.api.PatternBatchMoveApi;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LocalizationCompletenessTest {
    private static final String ENGLISH_LANGUAGE =
            "assets/appliedenhancements/lang/en_us.json";
    private static final String CHINESE_LANGUAGE =
            "assets/appliedenhancements/lang/zh_cn.json";

    private static final Pattern TRANSLATION_KEY = Pattern.compile(
            "\"((?:gui\\.appliedenhancements|message\\.appliedenhancements"
                    + "|key\\.appliedenhancements|appliedenhancements\\.config)"
                    + "\\.[A-Za-z0-9_.-]+"
                    + "|key\\.categories\\.appliedenhancements)\"");
    private static final Pattern CONFIG_DEFINE = Pattern.compile(
            "\\.define(?:InRange|Enum)?\\s*\\(");
    private static final Pattern CONFIG_TRANSLATED_DEFINE = Pattern.compile(
            "(?s)\\.translation\\(\"([^\"]+)\"\\)\\s*"
                    + "\\.define(?:InRange|Enum)?\\s*\\(");
    private static final Pattern CONFIG_COMMENT = Pattern.compile(
            "(?s)\\.comment\\((.*?)\\)\\s*"
                    + "(?:\\.translation\\(\"[^\"]+\"\\)\\s*)?"
                    + "\\.(?:translation|push)\\(");
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");
    private static final Pattern ENGLISH_CHARACTER = Pattern.compile("[A-Za-z]");
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile(
            "%(?:\\d+\\$)?([a-zA-Z%])");

    private static final Set<String> INTENTIONALLY_LANGUAGE_NEUTRAL = Set.of(
            "gui.appliedenhancements.calculation_result.path.aelis",
            "gui.appliedenhancements.calculation_result.path.ae2_native",
            "gui.appliedenhancements.calculation_progress.engine.aelis",
            "gui.appliedenhancements.calculation_progress.engine.ae2_native",
            "key.categories.appliedenhancements");

    @Test
    void englishAndChineseContainTheSameNonEmptyKeysAndFormatArguments() throws Exception {
        Map<String, String> english = readLanguage(ENGLISH_LANGUAGE);
        Map<String, String> chinese = readLanguage(CHINESE_LANGUAGE);

        assertEquals(english.keySet(), chinese.keySet(),
                "en_us.json and zh_cn.json must contain exactly the same keys");
        for (String key : english.keySet()) {
            String englishValue = english.get(key);
            String chineseValue = chinese.get(key);
            assertFalse(englishValue.isBlank(), key + " has an empty English value");
            assertFalse(chineseValue.isBlank(), key + " has an empty Chinese value");
            assertEquals(formatSignature(englishValue), formatSignature(chineseValue),
                    key + " uses different format arguments between languages");
            if (!INTENTIONALLY_LANGUAGE_NEUTRAL.contains(key)) {
                assertTrue(HAN_CHARACTER.matcher(chineseValue).find(),
                        key + " does not contain a Chinese translation: " + chineseValue);
            }
        }
    }

    @Test
    void everyLiteralTranslationKeyUsedByTheModExistsInBothLanguages() throws Exception {
        Map<String, String> english = readLanguage(ENGLISH_LANGUAGE);
        Map<String, String> chinese = readLanguage(CHINESE_LANGUAGE);
        Set<String> usedKeys = new HashSet<>();

        for (Path source : javaSources()) {
            var matcher = TRANSLATION_KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!key.endsWith(".")) {
                    usedKeys.add(key);
                }
            }
        }

        var missingEnglish = new ArrayList<String>();
        var missingChinese = new ArrayList<String>();
        for (String key : usedKeys) {
            if (!english.containsKey(key)) {
                missingEnglish.add(key);
            }
            if (!chinese.containsKey(key)) {
                missingChinese.add(key);
            }
        }
        missingEnglish.sort(String::compareTo);
        missingChinese.sort(String::compareTo);
        assertTrue(missingEnglish.isEmpty(), "Missing English keys: " + missingEnglish);
        assertTrue(missingChinese.isEmpty(), "Missing Chinese keys: " + missingChinese);
    }

    @Test
    void dynamicallyComposedTranslationKeysExistInBothLanguages() throws Exception {
        Map<String, String> english = readLanguage(ENGLISH_LANGUAGE);
        Map<String, String> chinese = readLanguage(CHINESE_LANGUAGE);
        var dynamicKeys = new ArrayList<String>();

        for (CraftingCalculationProgressPhase phase : CraftingCalculationProgressPhase.values()) {
            dynamicKeys.add("gui.appliedenhancements.calculation_progress.phase."
                    + phase.name().toLowerCase(Locale.ROOT));
        }
        for (PatternBatchMoveApi.Failure failure : PatternBatchMoveApi.Failure.values()) {
            String suffix = failure == PatternBatchMoveApi.Failure.NONE
                    ? "unknown"
                    : failure.name().toLowerCase(Locale.ROOT);
            dynamicKeys.add("message.appliedenhancements.pattern_batch_move.failure." + suffix);
        }

        for (String key : dynamicKeys) {
            assertTrue(english.containsKey(key), "Missing dynamic English key: " + key);
            assertTrue(chinese.containsKey(key), "Missing dynamic Chinese key: " + key);
        }
    }

    @Test
    void everyConfigValueHasALocalizedNameAndBilingualTomlComments() throws Exception {
        Map<String, String> english = readLanguage(ENGLISH_LANGUAGE);
        Map<String, String> chinese = readLanguage(CHINESE_LANGUAGE);

        for (Path source : javaSources()) {
            String text = Files.readString(source);
            if (!text.contains("ForgeConfigSpec.Builder")) {
                continue;
            }
            String relative = projectRoot().relativize(source).toString();
            var comments = CONFIG_COMMENT.matcher(text);
            while (comments.find()) {
                String comment = comments.group(1);
                assertTrue(ENGLISH_CHARACTER.matcher(comment).find(),
                        relative + " contains a config comment without English text");
                assertTrue(HAN_CHARACTER.matcher(comment).find(),
                        relative + " contains a config comment without Chinese text");
            }

            int defineCount = 0;
            var defines = CONFIG_DEFINE.matcher(text);
            while (defines.find()) {
                defineCount++;
            }
            int translatedDefineCount = 0;
            var translations = CONFIG_TRANSLATED_DEFINE.matcher(text);
            while (translations.find()) {
                translatedDefineCount++;
                String key = translations.group(1);
                assertTrue(english.containsKey(key),
                        relative + " config key is missing from en_us.json: " + key);
                assertTrue(chinese.containsKey(key),
                        relative + " config key is missing from zh_cn.json: " + key);
            }
            assertEquals(defineCount, translatedDefineCount,
                    relative + " contains a config value without .translation(...)");
        }
    }

    private static Map<String, String> readLanguage(String path) throws IOException {
        try (var stream = LocalizationCompletenessTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertTrue(stream != null, "Missing language resource: " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var result = new HashMap<String, String>();
            for (var entry : json.entrySet()) {
                assertTrue(entry.getValue().isJsonPrimitive()
                                && entry.getValue().getAsJsonPrimitive().isString(),
                        path + " contains a non-string value for " + entry.getKey());
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return Map.copyOf(result);
        }
    }

    private static Map<Character, Integer> formatSignature(String value) {
        var result = new HashMap<Character, Integer>();
        var matcher = FORMAT_SPECIFIER.matcher(value);
        while (matcher.find()) {
            char conversion = matcher.group(1).charAt(0);
            if (conversion != '%') {
                result.merge(Character.toLowerCase(conversion), 1, Integer::sum);
            }
        }
        return Map.copyOf(result);
    }

    private static List<Path> javaSources() throws Exception {
        Path sourceRoot = projectRoot().resolve("src/main/java");
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    private static Path projectRoot() throws Exception {
        Path candidate = Path.of(AppliedEnhancements.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the project root from compiled classes");
    }

}
