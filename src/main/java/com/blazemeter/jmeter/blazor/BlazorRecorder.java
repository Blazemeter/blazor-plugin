package com.blazemeter.jmeter.blazor;

import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Element for {@link BlazorRecorderGui}. The running proxy is not part of a
 * test; the element stores the listen port and is what the recorder template adds.
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
