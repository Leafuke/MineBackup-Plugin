package com.leafuke.minebackup.plugin.knotlink.protocol;

import java.util.Locale;
import java.util.Map;

public record KnotLinkResponse(Status status, String message, String data, Map<String, String> fields) {
    public enum Status {
        OK,
        ERROR
    }

    public KnotLinkResponse {
        fields = Map.copyOf(fields);
    }

    public static KnotLinkResponse parse(String payload) throws KnotLinkProtocolException {
        Map<String, String> fields = KnotLinkCodec.parse(payload);
        String rawStatus = fields.get("status");
        if (rawStatus == null) {
            throw new KnotLinkProtocolException("KnotLink response is missing status");
        }
        Status status = switch (rawStatus.toLowerCase(Locale.ROOT)) {
            case "ok" -> Status.OK;
            case "error" -> Status.ERROR;
            default -> throw new KnotLinkProtocolException("Unknown KnotLink status: " + rawStatus);
        };
        return new KnotLinkResponse(status, fields.get("message"), fields.get("data"), fields);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public String displayMessage() {
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (data != null && !data.isBlank()) {
            return data;
        }
        return isOk() ? "OK" : "KnotLink request failed";
    }
}
