package com.blazemeter.jmeter.blazor.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * SignalR binary framing around MessagePack hub messages.
 * Each message is a varint length prefix followed by one MessagePack array.
 * The handshake itself is not a hub message: it is the text
 * {@code {"protocol":"blazorpack","version":1}} terminated by 0x1E.
 */
public final class BlazorPack {

    public static final int INVOCATION = 1;
    public static final int COMPLETION = 3;
    public static final int PING = 6;
    public static final int CLOSE = 7;
    public static final int ERROR_RESULT = 1;

    public static final String HANDSHAKE = "{\"protocol\":\"blazorpack\",\"version\":1}\u001e";

    private BlazorPack() {
    }

    public static byte[] ping() {
        return frame(List.of(PING));
    }

    public static byte[] close() {
        return frame(Arrays.asList(CLOSE, null, false));
    }

    public static byte[] invocation(String invocationId, String target, List<Object> arguments) {
        List<Object> message = new ArrayList<>(6);
        message.add(INVOCATION);
        message.add(Map.of());
        message.add(invocationId);
        message.add(target);
        message.add(arguments == null ? List.of() : arguments);
        message.add(List.of());
        return frame(message);
    }

    public static byte[] frame(Object value) {
        byte[] payload = MsgPack.write(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 5);
        writeVarint(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    public static List<List<Object>> decode(byte[] data) {
        List<List<Object>> messages = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            long length = 0;
            int shift = 0;
            while (true) {
                if (offset >= data.length) {
                    throw new IllegalArgumentException("Truncated SignalR length prefix");
                }
                int bite = data[offset++] & 0xff;
                length |= (long) (bite & 0x7f) << shift;
                if ((bite & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("SignalR length prefix is too wide");
                }
            }
            if (length < 0 || length > Integer.MAX_VALUE || offset + length > data.length) {
                throw new IllegalArgumentException("Truncated SignalR message");
            }
            Object value = MsgPack.read(data, offset, (int) length);
            offset += (int) length;
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException("Hub message is not a MessagePack array");
            }
            List<Object> message = new ArrayList<>(list.size());
            for (Object item : list) {
                message.add(item);
            }
            messages.add(message);
        }
        return messages;
    }

    public static int type(List<?> message) {
        if (message == null || message.isEmpty() || !(message.get(0) instanceof Number number)) {
            return -1;
        }
        return number.intValue();
    }

    public static String target(List<?> message) {
        if (type(message) != INVOCATION || message.size() < 4 || message.get(3) == null) {
            return null;
        }
        return String.valueOf(message.get(3));
    }

    public static String invocationId(List<?> message) {
        if (message.size() < 3 || !(message.get(2) instanceof String id) || id.isEmpty()) {
            return null;
        }
        return id;
    }

    public static List<Object> arguments(List<?> message) {
        if (message.size() < 5 || !(message.get(4) instanceof List<?> list)) {
            return List.of();
        }
        List<Object> arguments = new ArrayList<>(list.size());
        for (Object argument : list) {
            arguments.add(argument);
        }
        return arguments;
    }

    public static String summarize(List<?> message) {
        int messageType = type(message);
        if (messageType == INVOCATION) {
            return "<- " + target(message) + " " + describe(arguments(message));
        }
        if (messageType == COMPLETION) {
            int kind = message.size() > 3 && message.get(3) instanceof Number number ? number.intValue() : -1;
            if (kind == ERROR_RESULT) {
                return "<- completion error " + (message.size() > 4 ? message.get(4) : "");
            }
            return "<- completion " + invocationId(message);
        }
        if (messageType == PING) {
            return "<- ping";
        }
        if (messageType == CLOSE) {
            return "<- close " + (message.size() > 1 ? message.get(1) : "");
        }
        return "<- message type " + messageType;
    }

    public static String describe(List<?> values) {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(describeValue(values.get(i)));
        }
        out.append(']');
        return out.toString();
    }

    private static String describeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return "binary[" + bytes.length + "]";
        }
        if (value instanceof String string) {
            String shown = string.length() > 180 ? string.substring(0, 180) + "..." : string;
            return '"' + shown + '"';
        }
        if (value instanceof List<?> list) {
            return "array[" + list.size() + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return "map[" + map.size() + "]";
        }
        return String.valueOf(value);
    }

    private static void writeVarint(ByteArrayOutputStream out, int length) {
        long remaining = length;
        do {
            int bite = (int) (remaining & 0x7f);
            remaining >>= 7;
            if (remaining > 0) {
                bite |= 0x80;
            }
            out.write(bite);
        } while (remaining > 0);
    }
}
