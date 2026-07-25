/*
 * KnotLink SDK - Java
 * Copyright (c) 2024-2026 KnotLink Contributors
 * SPDX-License-Identifier: MIT
 */

package com.leafuke.minebackup.plugin.knotlink.sdk;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class FrameCodec {
    static final byte[] MAGIC_V2 = {0x4B, 0x4B, 0x00, 0x02};

    private FrameCodec() {
    }

    static byte[] encode(String message, int maxMessageBytes) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        validateLength(payload.length, maxMessageBytes);
        return ByteBuffer.allocate(MAGIC_V2.length + Integer.BYTES + payload.length)
                .put(MAGIC_V2)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    static String read(InputStream input, int maxMessageBytes) throws IOException {
        byte[] magic = readFully(input, MAGIC_V2.length, true);
        if (!Arrays.equals(MAGIC_V2, magic)) {
            throw new IOException("Invalid KnotLink 2.0 magic header");
        }
        int length = ByteBuffer.wrap(readFully(input, Integer.BYTES, false)).getInt();
        validateLength(length, maxMessageBytes);
        byte[] payload = readFully(input, length, false);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("KnotLink frame is not valid UTF-8", exception);
        }
    }

    private static void validateLength(int length, int maxMessageBytes) throws IOException {
        if (length < 0 || length > maxMessageBytes) {
            throw new IOException("Invalid KnotLink message length: " + length);
        }
    }

    private static byte[] readFully(InputStream input, int length, boolean allowCleanEof) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) {
                if (allowCleanEof && offset == 0) {
                    throw new EOFException("KnotLink connection closed");
                }
                throw new EOFException("KnotLink connection closed during a frame");
            }
            if (read == 0) {
                continue;
            }
            offset += read;
        }
        return data;
    }
}
