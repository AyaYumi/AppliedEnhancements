package com.appliedenhancements.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AelisBrandingTest {
    @Test
    void userFacingPlannerNameIsAelisInBothLocales() throws Exception {
        JsonObject english = locale("en_us");
        JsonObject chinese = locale("zh_cn");

        assertEquals("AELIS", text(english,
                "gui.appliedenhancements.calculation_result.path.aelis"));
        assertEquals("AELIS", text(chinese,
                "gui.appliedenhancements.calculation_result.path.aelis"));
        assertEquals("AELIS", text(english,
                "gui.appliedenhancements.calculation_progress.engine.aelis"));
        assertEquals("AELIS", text(chinese,
                "gui.appliedenhancements.calculation_progress.engine.aelis"));
        assertEquals("AELIS Planner", text(english,
                "appliedenhancements.config.section.aelis"));
        assertEquals("AELIS 规划器", text(chinese,
                "appliedenhancements.config.section.aelis"));
    }

    private static JsonObject locale(String language) throws Exception {
        String path = "assets/appliedenhancements/lang/" + language + ".json";
        var stream = AelisBrandingTest.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Missing locale: " + path);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String text(JsonObject locale, String key) {
        return locale.get(key).getAsString();
    }
}
