package com.blazemeter.jmeter.blazor;

import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Placeholder element for {@link BlazorRecorderGui}. The proxy lives on the
 * GUI because it is not part of a running test and must not be saved in the jmx.
 */
public class BlazorRecorder extends AbstractTestElement {

    private static final long serialVersionUID = 1L;

    public static final String PORT = "BlazorRecorder.port";

    public void setPort(String port) {
        setProperty(PORT, port);
    }

    public String getPort() {
        String port = getPropertyAsString(PORT, "8888");
        return port == null || port.isBlank() ? "8888" : port;
    }
}
