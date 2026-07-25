package com.leafuke.minebackup.plugin.knotlink.sdk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {
    @Test
    void readsFragmentedUtf8Frame() throws Exception {
        byte[] frame = FrameCodec.encode("你好, KnotLink", 1_024);
        InputStream oneByteAtATime = new InputStream() {
            int index;

            @Override
            public int read() {
                return index < frame.length ? frame[index++] & 0xff : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                int value = read();
                if (value < 0) {
                    return -1;
                }
                target[offset] = (byte) value;
                return 1;
            }
        };
        assertEquals("你好, KnotLink", FrameCodec.read(oneByteAtATime, 1_024));
    }

    @Test
    void readsCoalescedFramesIndependently() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(FrameCodec.encode("first", 1_024));
        bytes.write(FrameCodec.encode("second", 1_024));
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals("first", FrameCodec.read(input, 1_024));
        assertEquals("second", FrameCodec.read(input, 1_024));
    }

    @Test
    void rejectsBadMagicOversizeAndInvalidUtf8() throws Exception {
        byte[] badMagic = FrameCodec.encode("ok", 1_024);
        badMagic[0] = 0;
        assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(badMagic), 1_024));

        byte[] oversized = ByteBuffer.allocate(8).put(FrameCodec.MAGIC_V2).putInt(2_000).array();
        assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(oversized), 1_024));

        byte[] invalidUtf8 = ByteBuffer.allocate(10)
                .put(FrameCodec.MAGIC_V2).putInt(2).put((byte) 0xC3).put((byte) 0x28).array();
        assertThrows(IOException.class, () -> FrameCodec.read(new ByteArrayInputStream(invalidUtf8), 1_024));
    }
}
