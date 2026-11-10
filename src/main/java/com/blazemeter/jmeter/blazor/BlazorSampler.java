package com.blazemeter.jmeter.blazor;

import com.blazemeter.jmeter.blazor.client.BlazorException;
import com.blazemeter.jmeter.blazor.client.BlazorSession;
import com.blazemeter.jmeter.blazor.client.SessionRegistry;
import com.blazemeter.jmeter.blazor.protocol.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;

/**
 * Drives one step of a Blazor Server circuit. Connect opens the circuit from
 * a live page. Navigate and Invoke reuse it. Disconnect closes it.
 */
public class BlazorSampler extends AbstractSampler implements ThreadListener {

    private static final long serialVersionUID = 1L;

    public static final String SERVER_URL = "BlazorSampler.serverUrl";
    public static final String ACTION = "BlazorSampler.action";
    public static final String CONNECTION = "BlazorSampler.connection";
    public static final String LOCATION = "BlazorSampler.location";
    public static final String TARGET = "BlazorSampler.target";
    public static final String ARGUMENTS = "BlazorSampler.arguments";
    public static final String TIMEOUT = "BlazorSampler.timeout";

    public static final String CONNECT = "Connect";
    public static final String NAVIGATE = "Navigate";
    public static final String INVOKE = "Invoke";
    public static final String DISCONNECT = "Disconnect";

    public void setServerUrl(String serverUrl) {
        setProperty(SERVER_URL, serverUrl);
    }

    public String getServerUrl() {
        return getPropertyAsString(SERVER_URL, "");
    }

    public void setAction(String action) {
        setProperty(ACTION, action);
    }

    public String getAction() {
        String action = getPropertyAsString(ACTION, CONNECT);
        return action == null || action.isBlank() ? CONNECT : action;
    }

    public void setConnection(String connection) {
        setProperty(CONNECTION, connection);
    }

    public String getConnection() {
        String connection = getPropertyAsString(CONNECTION, "blazor");
        return connection == null || connection.isBlank() ? "blazor" : connection;
    }

    public void setLocation(String location) {
        setProperty(LOCATION, location);
    }

    public String getLocation() {
        return getPropertyAsString(LOCATION, "");
    }

    public void setTarget(String target) {
        setProperty(TARGET, target);
    }

    public String getTarget() {
        return getPropertyAsString(TARGET, "");
    }

    public void setArguments(String arguments) {
        setProperty(ARGUMENTS, arguments);
    }

    public String getArguments() {
        return getPropertyAsString(ARGUMENTS, "");
    }

    public void setTimeout(String timeout) {
        setProperty(TIMEOUT, timeout);
    }

    public String getTimeout() {
        return getPropertyAsString(TIMEOUT, "20000");
    }

    public int timeoutMs() {
        try {
            return Math.max(1000, Integer.parseInt(getTimeout().trim()));
        } catch (NumberFormatException ex) {
            return 20_000;
        }
    }

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setDataType(SampleResult.TEXT);
        result.setContentType("text/plain; charset=utf-8");
        result.setSamplerData(describe());
        result.sampleStart();
        try {
            String body = perform();
            result.setResponseData(body, StandardCharsets.UTF_8.name());
            result.setResponseCode("200");
            result.setResponseMessage("OK");
            result.setSuccessful(true);
        } catch (BlazorException ex) {
            fail(result, ex.getMessage(), ex.report());
        } catch (Exception ex) {
            StringWriter stack = new StringWriter();
            ex.printStackTrace(new PrintWriter(stack));
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            fail(result, message, stack.toString());
        } finally {
            result.sampleEnd();
        }
        return result;
    }

    private String perform() throws Exception {
        String action = getAction();
        if (CONNECT.equalsIgnoreCase(action)) {
            if (getServerUrl().isBlank()) {
                throw new IllegalArgumentException("Server URL is empty");
            }
            BlazorSession session = new BlazorSession(timeoutMs());
            SessionRegistry.replace(getConnection(), session);
            try {
                return session.open(getServerUrl().trim());
            } catch (Exception ex) {
                SessionRegistry.remove(getConnection(), session);
                session.close();
                throw ex;
            }
        }
        if (NAVIGATE.equalsIgnoreCase(action)) {
            if (getLocation().isBlank()) {
                throw new IllegalArgumentException("Location is empty");
            }
            return session().navigate(getLocation().trim());
        }
        if (INVOKE.equalsIgnoreCase(action)) {
            if (getTarget().isBlank()) {
                throw new IllegalArgumentException("Hub method is empty");
            }
            return session().invoke(getTarget().trim(), Json.parseArray(getArguments()));
        }
        if (DISCONNECT.equalsIgnoreCase(action)) {
            BlazorSession session = SessionRegistry.take(getConnection());
            if (session == null) {
                return "No open circuit";
            }
            return session.close();
        }
        throw new IllegalArgumentException("Unknown action " + action);
    }

    private BlazorSession session() {
        BlazorSession session = SessionRegistry.get(getConnection());
        if (session == null) {
            throw new IllegalStateException("No open Blazor circuit named '" + getConnection()
                    + "'. Add a Connect sampler before this one.");
        }
        return session;
    }

    private String describe() {
        String action = getAction();
        if (CONNECT.equalsIgnoreCase(action)) {
            return "Connect " + getServerUrl();
        }
        if (NAVIGATE.equalsIgnoreCase(action)) {
            return "Navigate " + getLocation();
        }
        if (INVOKE.equalsIgnoreCase(action)) {
            return "Invoke " + getTarget() + "\n" + getArguments();
        }
        return "Disconnect " + getConnection();
    }

    private static void fail(SampleResult result, String message, String body) {
        result.setSuccessful(false);
        result.setResponseCode("500");
        result.setResponseMessage(message == null ? "Blazor circuit failed" : shorten(message));
        result.setResponseData(body == null ? "" : body, StandardCharsets.UTF_8.name());
    }

    private static String shorten(String message) {
        String line = message.replace('\n', ' ').trim();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    @Override
    public void threadStarted() {
        // Circuits are opened by the Connect action.
    }

    @Override
    public void threadFinished() {
        SessionRegistry.closeCurrentThread();
    }
}
