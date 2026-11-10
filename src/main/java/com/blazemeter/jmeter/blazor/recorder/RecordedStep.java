package com.blazemeter.jmeter.blazor.recorder;

/**
 * One sampler the recorder will insert. Lifecycle chatter (StartCircuit,
 * render acknowledgements, pings) is not a step.
 */
public final class RecordedStep {

    public enum Kind {
        CONNECT,
        NAVIGATE,
        INVOKE,
        DISCONNECT
    }

    private final Kind kind;
    private final String value;
    private final String arguments;
    private final String label;

    private RecordedStep(Kind kind, String value, String arguments, String label) {
        this.kind = kind;
        this.value = value;
        this.arguments = arguments == null ? "" : arguments;
        this.label = label;
    }

    public static RecordedStep connect(String url) {
        return new RecordedStep(Kind.CONNECT, url, "", "Blazor connect " + path(url));
    }

    public static RecordedStep navigate(String url) {
        return new RecordedStep(Kind.NAVIGATE, url, "", "Blazor go to " + path(url));
    }

    public static RecordedStep invoke(String target, String argumentsJson) {
        return new RecordedStep(Kind.INVOKE, target, argumentsJson, "Blazor " + target);
    }

    public static RecordedStep disconnect() {
        return new RecordedStep(Kind.DISCONNECT, "", "", "Blazor disconnect");
    }

    public Kind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public String arguments() {
        return arguments;
    }

    public String label() {
        return label;
    }

    private static String path(String url) {
        try {
            String path = java.net.URI.create(url).getRawPath();
            if (path == null || path.isEmpty()) {
                return "/";
            }
            return path;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }
}
