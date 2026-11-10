package com.blazemeter.jmeter.blazor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jmeter.threads.JMeterContextService;

/**
 * One circuit per JMeter thread and connection name. The websocket cannot be
 * stored on the sampler: JMeter serializes test elements into the jmx file.
 */
public final class SessionRegistry {

    private static final ConcurrentHashMap<String, BlazorSession> OPEN = new ConcurrentHashMap<>();

    private SessionRegistry() {
    }

    public static BlazorSession get(String connectionName) {
        return OPEN.get(key(connectionName));
    }

    public static void replace(String connectionName, BlazorSession session) {
        BlazorSession previous = OPEN.put(key(connectionName), session);
        if (previous != null && previous != session) {
            previous.close();
        }
    }

    public static void remove(String connectionName, BlazorSession session) {
        OPEN.remove(key(connectionName), session);
    }

    public static BlazorSession take(String connectionName) {
        return OPEN.remove(key(connectionName));
    }

    public static void closeCurrentThread() {
        String prefix = threadName() + "|";
        List<String> keys = new ArrayList<>();
        for (String key : OPEN.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            BlazorSession session = OPEN.remove(key);
            if (session != null) {
                session.close();
            }
        }
    }

    private static String key(String connectionName) {
        String name = connectionName == null || connectionName.isBlank() ? "blazor" : connectionName.trim();
        return threadName() + "|" + name;
    }

    private static String threadName() {
        try {
            var context = JMeterContextService.getContext();
            if (context != null && context.getThread() != null && context.getThread().getThreadName() != null) {
                return context.getThread().getThreadName();
            }
        } catch (RuntimeException ignored) {
            // Running outside a JMeter thread, for example a unit test.
        }
        return Thread.currentThread().getName();
    }
}
