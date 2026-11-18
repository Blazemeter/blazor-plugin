package com.blazemeter.jmeter.blazor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.jetty.client.Origin;
import org.junit.jupiter.api.Test;

/**
 * TLS offers HTTP/2 and HTTP/1.1. Clear-text starts on h2c; a failed
 * attempt is retried as HTTP/1.1 by the session.
 */
class HttpNegotiationTest {

    @Test
    void tlsOffersHttp2ThenHttp1() throws Exception {
        Origin.Protocol protocol = BlazorSession.originFor("https://app.example/").getProtocol();
        assertTrue(protocol.isNegotiate(), protocol.toString());
        assertEquals(List.of("h2", "http/1.1"), protocol.getProtocols());
    }

    @Test
    void secureWebSocketOffersTheSameNegotiation() throws Exception {
        Origin.Protocol protocol = BlazorSession.originFor("wss://app.example/_blazor").getProtocol();
        assertTrue(protocol.isNegotiate(), protocol.toString());
        assertEquals(List.of("h2", "http/1.1"), protocol.getProtocols());
    }

    @Test
    void clearTextStartsOnHttp2() throws Exception {
        Origin.Protocol page = BlazorSession.originFor("http://app.example/").getProtocol();
        assertFalse(page.isNegotiate(), page.toString());
        assertEquals(List.of("h2c"), page.getProtocols());

        Origin.Protocol socket = BlazorSession.originFor("ws://app.example/_blazor").getProtocol();
        assertFalse(socket.isNegotiate(), socket.toString());
        assertEquals(List.of("h2c"), socket.getProtocols());
    }
}
