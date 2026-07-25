package com.leafuke.minebackup.plugin.knotlink.protocol;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class KnotLinkRequest {
    public static final String CALLER_ID = "minebackup.mod";

    private final Map<String, String> fields = new LinkedHashMap<>();

    private KnotLinkRequest(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("KnotLink command must not be blank");
        }
        fields.put("cmd", command.trim().toUpperCase(Locale.ROOT));
    }

    public static KnotLinkRequest command(String command) {
        return new KnotLinkRequest(command);
    }

    public KnotLinkRequest conversation() {
        return conversation(UUID.randomUUID());
    }

    public KnotLinkRequest conversation(UUID requestId) {
        fields.put("from", CALLER_ID);
        fields.put("request_id", Objects.requireNonNull(requestId, "requestId").toString());
        return this;
    }

    public KnotLinkRequest field(String key, Object value) {
        if (key == null || key.isBlank() || "cmd".equalsIgnoreCase(key)
                || fields.containsKey(key.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid or duplicate request field: " + key);
        }
        fields.put(key, value == null ? "" : String.valueOf(value));
        return this;
    }

    public String commandName() {
        return fields.get("cmd");
    }

    public String serialize() {
        return KnotLinkCodec.serialize(fields);
    }
}
