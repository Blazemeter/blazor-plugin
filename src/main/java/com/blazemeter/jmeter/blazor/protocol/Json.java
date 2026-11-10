package com.blazemeter.jmeter.blazor.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for the values BlazorPack actually carries: objects, arrays,
 * strings, numbers, booleans and null. Field order is preserved.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        int[] index = {0};
        Object value = read(text, index);
        skip(text, index);
        if (index[0] != text.length()) {
            throw new IllegalArgumentException("Trailing data in JSON at " + index[0]);
        }
        return value;
    }

    public static List<Object> parseArray(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Object value = parse(text);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        List<Object> copy = new ArrayList<>();
        for (Object item : list) {
            copy.add(item);
        }
        return copy;
    }

    public static Object read(String text, int[] index) {
        skip(text, index);
        if (index[0] >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = text.charAt(index[0]);
        if (c == '{') {
            return readObject(text, index);
        }
        if (c == '[') {
            return readArray(text, index);
        }
        if (c == '"') {
            return readString(text, index);
        }
        if (c == 't' || c == 'f') {
            return readLiteral(text, index, c == 't' ? "true" : "false", c == 't');
        }
        if (c == 'n') {
            readLiteral(text, index, "null", null);
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return readNumber(text, index);
        }
        throw new IllegalArgumentException("Unexpected JSON character '" + c + "' at " + index[0]);
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static Map<String, Object> readObject(String text, int[] index) {
        index[0]++;
        Map<String, Object> object = new LinkedHashMap<>();
        skip(text, index);
        if (peek(text, index) == '}') {
            index[0]++;
            return object;
        }
        while (true) {
            skip(text, index);
            if (peek(text, index) != '"') {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            String key = readString(text, index);
            skip(text, index);
            expect(text, index, ':');
            object.put(key, read(text, index));
            skip(text, index);
            char next = peek(text, index);
            index[0]++;
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' in JSON object");
            }
        }
    }

    private static List<Object> readArray(String text, int[] index) {
        index[0]++;
        List<Object> array = new ArrayList<>();
        skip(text, index);
        if (peek(text, index) == ']') {
            index[0]++;
            return array;
        }
        while (true) {
            array.add(read(text, index));
            skip(text, index);
            char next = peek(text, index);
            index[0]++;
            if (next == ']') {
                return array;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' in JSON array");
            }
        }
    }

    private static String readString(String text, int[] index) {
        index[0]++;
        StringBuilder out = new StringBuilder();
        while (index[0] < text.length()) {
            char c = text.charAt(index[0]++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index[0] >= text.length()) {
                throw new IllegalArgumentException("Unterminated JSON string escape");
            }
            char esc = text.charAt(index[0]++);
            switch (esc) {
                case '"', '\\', '/' -> out.append(esc);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (index[0] + 4 > text.length()) {
                        throw new IllegalArgumentException("Short JSON unicode escape");
                    }
                    int code = Integer.parseInt(text.substring(index[0], index[0] + 4), 16);
                    index[0] += 4;
                    out.append((char) code);
                }
                default -> throw new IllegalArgumentException("Unknown JSON escape \\" + esc);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static Object readLiteral(String text, int[] index, String literal, Object value) {
        if (!text.startsWith(literal, index[0])) {
            throw new IllegalArgumentException("Expected JSON literal " + literal);
        }
        index[0] += literal.length();
        return value;
    }

    private static Number readNumber(String text, int[] index) {
        int start = index[0];
        if (text.charAt(index[0]) == '-') {
            index[0]++;
        }
        while (index[0] < text.length() && isNumberChar(text.charAt(index[0]))) {
            index[0]++;
        }
        String raw = text.substring(start, index[0]);
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
            return Double.valueOf(raw);
        }
        return Long.valueOf(raw);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue() ? "true" : "false");
        } else if (value instanceof Number number) {
            writeNumber(out, number);
        } else if (value instanceof byte[] bytes) {
            out.append("{\"$binary\":");
            writeString(out, java.util.Base64.getEncoder().encodeToString(bytes));
            out.append('}');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 9_000_000_000_000_000L) {
                out.append((long) value);
            } else {
                out.append(Double.toString(value));
            }
            return;
        }
        out.append(number.longValue());
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void skip(String text, int[] index) {
        while (index[0] < text.length() && Character.isWhitespace(text.charAt(index[0]))) {
            index[0]++;
        }
    }

    private static char peek(String text, int[] index) {
        if (index[0] >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        return text.charAt(index[0]);
    }

    private static void expect(String text, int[] index, char expected) {
        if (peek(text, index) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at " + index[0]);
        }
        index[0]++;
    }
}
