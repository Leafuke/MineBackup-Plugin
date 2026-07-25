package com.leafuke.minebackup.plugin.knotlink.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnotLinkCodecTest {
    @Test
    void roundTripsUtf8AndReservedCharacters() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("cmd", "BACKUP");
        fields.put("comment", "中文; 100% / ok");
        String serialized = KnotLinkCodec.serialize(fields);
        assertEquals(fields, KnotLinkCodec.parse(serialized));
        assertTrue(serialized.contains("%E4%B8%AD%E6%96%87"));
    }

    @Test
    void rejectsDuplicateInvalidAndUnescapedFields() {
        assertThrows(KnotLinkProtocolException.class, () -> KnotLinkCodec.parse("a=1;a=2"));
        assertThrows(KnotLinkProtocolException.class, () -> KnotLinkCodec.parse("bad-key=value"));
        assertThrows(KnotLinkProtocolException.class, () -> KnotLinkCodec.parse("value=has space"));
        assertThrows(KnotLinkProtocolException.class, () -> KnotLinkCodec.parse("empty"));
    }

    @Test
    void requestAndResponseUseStrictV2Metadata() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String request = KnotLinkRequest.command("backup").conversation(id)
                .field("current_save", true).serialize();
        Map<String, String> fields = KnotLinkCodec.parse(request);
        assertEquals("BACKUP", fields.get("cmd"));
        assertEquals("minebackup.mod", fields.get("from"));
        assertEquals(id.toString(), fields.get("request_id"));

        KnotLinkResponse response = KnotLinkResponse.parse("status=ok;message=done;data=file.7z");
        assertTrue(response.isOk());
        assertEquals("done", response.displayMessage());
        assertThrows(KnotLinkProtocolException.class, () -> KnotLinkResponse.parse("OK%3Adone=value"));
    }
}
