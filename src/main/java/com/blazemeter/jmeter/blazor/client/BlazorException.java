package com.blazemeter.jmeter.blazor.client;

/**
 * A circuit failed, or a hub call was rejected. {@link #transcript()} is the
 * exchange recorded up to the failure, suitable for a JMeter sample response.
 */
public class BlazorException extends Exception {

    private final String transcript;

    public BlazorException(String message, String transcript) {
        super(message);
        this.transcript = transcript == null ? "" : transcript;
    }

    public BlazorException(String message, String transcript, Throwable cause) {
        super(message, cause);
        this.transcript = transcript == null ? "" : transcript;
    }

    public String transcript() {
        return transcript;
    }

    public String report() {
        if (transcript.isEmpty()) {
            return getMessage() == null ? "" : getMessage();
        }
        return getMessage() + "\n" + transcript;
    }
}
