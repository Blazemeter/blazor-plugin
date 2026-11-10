package com.blazemeter.jmeter.blazor.protocol;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values a Blazor Server page embeds in the HTML document: the base href,
 * the protected prerender state, and the interactive root markers.
 */
public final class PageDocument {

    private static final Pattern BASE_HREF = Pattern.compile(
            "<base\\s+[^>]*href\\s*=\\s*['\"]([^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPONENT_STATE = Pattern.compile(
            "<!--\\s*Blazor-Server-Component-State:([A-Za-z0-9+/=\\s]+)-->");

    private final URI pageUri;
    private final String baseUri;
    private final String applicationState;
    private final List<Marker> markers;

    private PageDocument(URI pageUri, String baseUri, String applicationState, List<Marker> markers) {
        this.pageUri = pageUri;
        this.baseUri = baseUri;
        this.applicationState = applicationState;
        this.markers = markers;
    }

    public static PageDocument parse(URI pageUri, String html) {
        String href = "/";
        Matcher base = BASE_HREF.matcher(html);
        if (base.find() && !base.group(1).isBlank()) {
            href = base.group(1);
        }
        String resolvedBase = pageUri.resolve(href).toString();
        if (!resolvedBase.endsWith("/")) {
            resolvedBase = resolvedBase + "/";
        }

        String state = "";
        Matcher stateMatcher = COMPONENT_STATE.matcher(html);
        if (stateMatcher.find()) {
            state = stateMatcher.group(1).replaceAll("\\s", "");
        }

        List<Marker> markers = new ArrayList<>();
        int cursor = 0;
        while (cursor < html.length()) {
            int comment = html.indexOf("<!--Blazor:", cursor);
            if (comment < 0) {
                break;
            }
            int jsonAt = comment + "<!--Blazor:".length();
            while (jsonAt < html.length() && Character.isWhitespace(html.charAt(jsonAt))) {
                jsonAt++;
            }
            if (jsonAt >= html.length() || html.charAt(jsonAt) != '{') {
                cursor = Math.max(jsonAt, comment + 1);
                continue;
            }
            int[] index = {jsonAt};
            Object value;
            try {
                value = Json.read(html, index);
            } catch (RuntimeException ex) {
                cursor = jsonAt + 1;
                continue;
            }
            cursor = index[0];
            if (value instanceof Map<?, ?> map && map.get("type") instanceof String type
                    && (type.equals("server") || type.equals("webassembly") || type.equals("auto"))) {
                markers.add(new Marker(type, copyMap(map)));
            }
        }
        return new PageDocument(pageUri, resolvedBase, state, List.copyOf(markers));
    }

    public URI pageUri() {
        return pageUri;
    }

    public String baseUri() {
        return baseUri;
    }

    public String location() {
        String value = pageUri.toString();
        if (pageUri.getPath() == null || pageUri.getPath().isEmpty()) {
            return value + "/";
        }
        return value;
    }

    public String applicationState() {
        return applicationState;
    }

    public List<Marker> markers() {
        return markers;
    }

    public List<Marker> serverMarkers() {
        List<Marker> server = new ArrayList<>();
        for (Marker marker : markers) {
            if ("server".equals(marker.type()) || "auto".equals(marker.type())) {
                server.add(marker);
            }
        }
        return server;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    public static final class Marker {
        private final String type;
        private final Map<String, Object> raw;

        private Marker(String type, Map<String, Object> raw) {
            this.type = type;
            this.raw = raw;
        }

        public String type() {
            return type;
        }

        public Map<String, Object> raw() {
            return raw;
        }

        public String locationHash() {
            Object key = raw.get("key");
            if (key instanceof Map<?, ?> map && map.get("locationHash") != null) {
                return String.valueOf(map.get("locationHash"));
            }
            return "";
        }

        public String formattedComponentKey() {
            Object key = raw.get("key");
            if (key instanceof Map<?, ?> map && map.get("formattedComponentKey") != null) {
                return String.valueOf(map.get("formattedComponentKey"));
            }
            return "";
        }

        public String descriptor() {
            Object descriptor = raw.get("descriptor");
            return descriptor == null ? "" : String.valueOf(descriptor);
        }

        public String identity() {
            return locationHash() + "\n" + formattedComponentKey();
        }
    }
}
