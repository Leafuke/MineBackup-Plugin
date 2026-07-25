package com.leafuke.minebackup.plugin.message;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageCatalogTest {
    @Test
    void englishAndChineseCatalogsHaveIdenticalKeys() throws Exception {
        assertEquals(load("en_us").keySet(), load("zh_cn").keySet());
    }

    private static Map<String, String> load(String language) throws Exception {
        try (var stream = MessageCatalogTest.class.getClassLoader()
                .getResourceAsStream("lang/" + language + ".json")) {
            return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
        }
    }
}
