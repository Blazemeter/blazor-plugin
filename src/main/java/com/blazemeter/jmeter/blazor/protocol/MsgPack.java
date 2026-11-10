package com.blazemeter.jmeter.blazor.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MessagePack subset used by SignalR's blazorpack hub protocol:
 * nil, bool, int, float, string, binary, array and map.
 */
public final class MsgPack {

    private MsgPack() {
    }

    public static byte[] write(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    public static Object read(byte[] data, int offset, int length) {
        Cursor cursor = new Cursor(data, offset, offset + length);
        Object value = readValue(cursor);
        if (cursor.pos != cursor.limit) {
            throw new IllegalArgumentException("Trailing MessagePack bytes");
        }
        return value;
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        if (value == null) {
            out.write(0xc0);
        } else if (value instanceof Boolean bool) {
            out.write(bool ? 0xc3 : 0xc2);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            writeLong(out, ((Number) value).longValue());
        } else if (value instanceof Float || value instanceof Double) {
            writeDouble(out, ((Number) value).doubleValue());
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof byte[] bytes) {
            writeBinary(out, bytes);
        } else if (value instanceof List<?> list) {
            writeLength(out, list.size(), 0x90, 0xdc, 0xdd);
            for (Object item : list) {
                write(out, item);
            }
        } else if (value instanceof Map<?, ?> map) {
            writeLength(out, map.size(), 0x80, 0xde, 0xdf);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                writeString(out, String.valueOf(entry.getKey()));
                write(out, entry.getValue());
            }
        } else {
            throw new IllegalArgumentException("Cannot pack " + value.getClass().getName());
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        if (value >= 0 && value <= 127) {
            out.write((int) value);
        } else if (value >= -32 && value < 0) {
            out.write((int) value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            out.write(0xd1);
            out.write((int) (value >> 8));
            out.write((int) value);
        } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            out.write(0xd2);
            writeFixed(out, value, 4);
        } else {
            out.write(0xd3);
            writeFixed(out, value, 8);
        }
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        out.write(0xcb);
        long bits = Double.doubleToLongBits(value);
        writeFixed(out, bits, 8);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            out.write(0xa0 | bytes.length);
        } else if (bytes.length <= 0xff) {
            out.write(0xd9);
            out.write(bytes.length);
        } else if (bytes.length <= 0xffff) {
            out.write(0xda);
            out.write(bytes.length >> 8);
            out.write(bytes.length);
        } else {
            out.write(0xdb);
            writeFixed(out, bytes.length, 4);
        }
        out.writeBytes(bytes);
    }

    private static void writeBinary(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length <= 0xff) {
            out.write(0xc4);
            out.write(bytes.length);
        } else if (bytes.length <= 0xffff) {
            out.write(0xc5);
            out.write(bytes.length >> 8);
            out.write(bytes.length);
        } else {
            out.write(0xc6);
            writeFixed(out, bytes.length, 4);
        }
        out.writeBytes(bytes);
    }

    private static void writeLength(ByteArrayOutputStream out, int size, int fixBase, int wide16, int wide32) {
        if (size < 16) {
            out.write(fixBase | size);
        } else if (size <= 0xffff) {
            out.write(wide16);
            out.write(size >> 8);
            out.write(size);
        } else {
            out.write(wide32);
            writeFixed(out, size, 4);
        }
    }

    private static void writeFixed(ByteArrayOutputStream out, long value, int width) {
        for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) {
            out.write((int) (value >> shift));
        }
    }

    private static Object readValue(Cursor cursor) {
        int marker = cursor.u8();
        if (marker <= 0x7f) {
            return (long) marker;
        }
        if (marker >= 0xe0) {
            return (long) (marker - 256);
        }
        if ((marker & 0xf0) == 0x80) {
            return readMap(cursor, marker & 0x0f);
        }
        if ((marker & 0xf0) == 0x90) {
            return readArray(cursor, marker & 0x0f);
        }
        if ((marker & 0xe0) == 0xa0) {
            return cursor.string(marker & 0x1f);
        }
        return switch (marker) {
            case 0xc0 -> null;
            case 0xc2 -> Boolean.FALSE;
            case 0xc3 -> Boolean.TRUE;
            case 0xc4 -> cursor.bytes(cursor.u8());
            case 0xc5 -> cursor.bytes(cursor.u16());
            case 0xc6 -> cursor.bytes(cursor.u32());
            case 0xca -> (double) Float.intBitsToFloat(cursor.i32());
            case 0xcb -> Double.longBitsToDouble(cursor.i64());
            case 0xcc -> (long) cursor.u8();
            case 0xcd -> (long) cursor.u16();
            case 0xce -> cursor.u32() & 0xffffffffL;
            case 0xcf -> cursor.u64();
            case 0xd0 -> (long) (byte) cursor.u8();
            case 0xd1 -> (long) (short) cursor.u16();
            case 0xd2 -> (long) cursor.i32();
            case 0xd3 -> cursor.i64();
            case 0xd9 -> cursor.string(cursor.u8());
            case 0xda -> cursor.string(cursor.u16());
            case 0xdb -> cursor.string(cursor.u32());
            case 0xdc -> readArray(cursor, cursor.u16());
            case 0xdd -> readArray(cursor, cursor.u32());
            case 0xde -> readMap(cursor, cursor.u16());
            case 0xdf -> readMap(cursor, cursor.u32());
            default -> throw new IllegalArgumentException(String.format("Unsupported MessagePack marker 0x%02x", marker));
        };
    }

    private static List<Object> readArray(Cursor cursor, int size) {
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readValue(cursor));
        }
        return list;
    }

    private static Map<String, Object> readMap(Cursor cursor, int size) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Object key = readValue(cursor);
            map.put(String.valueOf(key), readValue(cursor));
        }
        return map;
    }

    private static final class Cursor {
        private final byte[] data;
        private int pos;
        private final int limit;

        private Cursor(byte[] data, int offset, int limit) {
            this.data = data;
            this.pos = offset;
            this.limit = limit;
        }

        private int u8() {
            if (pos >= limit) {
                throw new IllegalArgumentException("Truncated MessagePack");
            }
            return data[pos++] & 0xff;
        }

        private int u16() {
            return (u8() << 8) | u8();
        }

        private int u32() {
            return (u16() << 16) | u16();
        }

        private int i32() {
            return u32();
        }

        private long i64() {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | u8();
            }
            return value;
        }

        private long u64() {
            long value = i64();
            if (value < 0) {
                throw new IllegalArgumentException("MessagePack uint64 does not fit in a signed long");
            }
            return value;
        }

        private String string(int length) {
            return new String(raw(length), StandardCharsets.UTF_8);
        }

        private byte[] bytes(int length) {
            return raw(length);
        }

        private byte[] raw(int length) {
            if (length < 0 || pos + length > limit) {
                throw new IllegalArgumentException("Truncated MessagePack payload");
            }
            byte[] slice = new byte[length];
            System.arraycopy(data, pos, slice, 0, length);
            pos += length;
            return slice;
        }
    }
}
