package com.blazemeter.jmeter.blazor.recorder;

import java.net.URI;
import java.util.Map;

/**
 * The recorder stores each application origin in its own User Defined
 * Variable: {@code SERVER_1}, {@code SERVER_2}, and so on. Sampler URLs on
 * that origin are written as {@code ${SERVER_1}/...} so a later flow can
 * target a different host without changing the first one.
 */
public final class RecordedServer {

    public static final String VARIABLE = "SERVER";

    private RecordedServer() {
    }

    /**
     * Variable that already holds {@code origin}, or the next free
     * {@code SERVER_n}. An unnumbered {@code SERVER} counts as the first flow.
     */
    public static String variableFor(Map<String, String> defined, String origin) {
        String same = null;
        boolean plain = false;
        if (defined != null) {
            for (Map.Entry<String, String> entry : defined.entrySet()) {
                String name = entry.getKey();
                if (VARIABLE.equals(name)) {
                    plain = true;
                } else if (index(name) < 1) {
                    continue;
                }
                if (origin != null && origin.equals(entry.getValue()) && same == null) {
                    same = name;
                }
            }
        }
        if (same != null) {
            return same;
        }
        int next = plain ? 2 : 1;
        while (defined != null && defined.containsKey(VARIABLE + "_" + next)) {
            next++;
        }
        return VARIABLE + "_" + next;
    }

    /** {@code SERVER_1} is 1. Any other name, including plain {@code SERVER}, is 0. */
    public static int index(String name) {
        String prefix = VARIABLE + "_";
        if (name == null || !name.startsWith(prefix)) {
            return 0;
        }
        try {
            int index = Integer.parseInt(name.substring(prefix.length()));
            return index > 0 ? index : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Scheme and authority, without a path. Empty when {@code url} is not absolute.
     */
    public static String origin(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    /**
     * Rewrites an absolute URL on {@code origin} so the host comes from {@code ${variable}}.
     * Anything else, including a path, is returned unchanged.
     */
    public static String reference(String url, String origin, String variable) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.isEmpty() || value.startsWith("${")) {
            return value;
        }
        if (origin == null || origin.isBlank() || variable == null || variable.isBlank()) {
            return value;
        }
        String reference = "${" + variable + "}";
        if (value.equals(origin)) {
            return reference + "/";
        }
        if (!value.startsWith(origin)) {
            return value;
        }
        String rest = value.substring(origin.length());
        if (rest.startsWith("/")) {
            return reference + rest;
        }
        if (rest.startsWith("?") || rest.startsWith("#")) {
            return reference + "/" + rest;
        }
        return value;
    }
}
