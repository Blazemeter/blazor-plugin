package com.blazemeter.jmeter.blazor.recorder;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one WebSocket frame without changing the bytes that have to be
 * forwarded. Client frames stay masked on the wire; {@link #payload(byte[])}
 * returns the unmasked message for protocol decoding.
 */
public final class WebSocketFrames {

    private WebSocketFrames() {
    }

    public static byte[] readRaw(InputStream in) throws IOException {
        int first = in.read();
        int second = in.read();
        if (first < 0 || second < 0) {
            throw new EOFException("WebSocket closed");
        }
        int lengthCode = second & 0x7f;
        boolean masked = (second & 0x80) != 0;
        int extra = 0;
        if (lengthCode == 126) {
            extra = 2;
        } else if (lengthCode == 127) {
            extra = 8;
        }
        if (masked) {
            extra += 4;
        }
        byte[] rest = extra == 0 ? new byte[0] : in.readNBytes(extra);
        if (rest.length != extra) {
            throw new EOFException("Truncated WebSocket header");
        }
        long length = lengthOf(lengthCode, rest);
        if (length < 0 || length > 16_000_000) {
            throw new IOException("WebSocket frame is too large (" + length + ")");
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length != length) {
            throw new EOFException("Truncated WebSocket frame");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(2 + rest.length + body.length);
        raw.write(first);
        raw.write(second);
        raw.write(rest);
        raw.write(body);
        return raw.toByteArray();
    }

    public static boolean fin(byte[] raw) {
        return (raw[0] & 0x80) != 0;
    }

    public static int opcode(byte[] raw) {
        return raw[0] & 0x0f;
    }

    public static byte[] payload(byte[] raw) {
        int second = raw[1] & 0xff;
        boolean masked = (second & 0x80) != 0;
        int lengthCode = second & 0x7f;
        int offset = 2;
        long length;
        if (lengthCode == 126) {
            length = ((raw[2] & 0xff) << 8) | (raw[3] & 0xff);
            offset = 4;
        } else if (lengthCode == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (raw[2 + i] & 0xff);
            }
            offset = 10;
        } else {
            length = lengthCode;
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[] {raw[offset], raw[offset + 1], raw[offset + 2], raw[offset + 3]};
            offset += 4;
        }
        byte[] data = new byte[(int) length];
        System.arraycopy(raw, offset, data, 0, data.length);
        if (mask != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] ^= mask[i & 3];
            }
        }
        return data;
    }

    private static long lengthOf(int lengthCode, byte[] rest) {
        if (lengthCode < 126) {
            return lengthCode;
        }
        if (lengthCode == 126) {
            return ((rest[0] & 0xffL) << 8) | (rest[1] & 0xffL);
        }
        long length = 0;
        for (int i = 0; i < 8; i++) {
            length = (length << 8) | (rest[i] & 0xffL);
        }
        return length;
    }
}
