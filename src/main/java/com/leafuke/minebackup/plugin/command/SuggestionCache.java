package com.leafuke.minebackup.plugin.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SuggestionCache {
    private final Map<String, List<String>> values = new ConcurrentHashMap<>();

    public List<String> get(String key) {
        return values.getOrDefault(key, List.of());
    }

    public void put(String key, List<String> suggestions) {
        values.put(key, List.copyOf(suggestions));
    }

    public void clear() {
        values.clear();
    }
}
