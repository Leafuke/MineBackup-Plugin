package com.leafuke.minebackup.plugin.message;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCatalogTest {
    private static final Pattern FORMAT = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");

    @Test
    void englishAndChineseCatalogsHaveIdenticalKeys() throws Exception {
        Map<String, String> english = load("en_us");
        Map<String, String> chinese = load("zh_cn");
        assertEquals(english.keySet(), chinese.keySet());
        assertTrue(english.size() >= 75, "The release catalog should cover the full command/runtime surface");
        for (String key : english.keySet()) {
            assertEquals(formatCount(english.get(key)), formatCount(chinese.get(key)),
                    "Translation placeholder mismatch for " + key);
        }
    }

    private static Map<String, String> load(String language) throws Exception {
        try (var stream = MessageCatalogTest.class.getClassLoader()
                .getResourceAsStream("lang/" + language + ".json")) {
            return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
        }
    }

    private static long formatCount(String value) {
        return FORMAT.matcher(value).results().count();
    }
}
