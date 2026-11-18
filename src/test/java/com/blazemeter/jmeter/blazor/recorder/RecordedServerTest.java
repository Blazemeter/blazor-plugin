package com.blazemeter.jmeter.blazor.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordedServerTest {

    @Test
    void originDropsThePath() {
        assertEquals("http://192.168.1.101:5000", RecordedServer.origin("http://192.168.1.101:5000/"));
        assertEquals("http://192.168.1.101:5000", RecordedServer.origin("http://192.168.1.101:5000/market?x=1"));
        assertEquals("https://app.example", RecordedServer.origin("https://app.example/desk"));
        assertEquals("", RecordedServer.origin("/market"));
        assertEquals("", RecordedServer.origin(""));
    }

    @Test
    void absoluteUrlsOnThatOriginUseTheVariable() {
        String origin = "http://192.168.1.101:5000";
        assertEquals("${SERVER_1}/", RecordedServer.reference(origin, origin, "SERVER_1"));
        assertEquals("${SERVER_1}/", RecordedServer.reference(origin + "/", origin, "SERVER_1"));
        assertEquals("${SERVER_1}/market", RecordedServer.reference(origin + "/market", origin, "SERVER_1"));
        assertEquals("${SERVER_2}/market?tab=1", RecordedServer.reference(origin + "/market?tab=1", origin, "SERVER_2"));
        assertEquals("${SERVER_1}/?x=1", RecordedServer.reference(origin + "?x=1", origin, "SERVER_1"));
        assertEquals("/market", RecordedServer.reference("/market", origin, "SERVER_1"));
        assertEquals("http://other/market", RecordedServer.reference("http://other/market", origin, "SERVER_1"));
        assertEquals("${SERVER_1}/market", RecordedServer.reference("${SERVER_1}/market", origin, "SERVER_1"));
    }

    @Test
    void eachDistinctOriginGetsTheNextNumber() {
        Map<String, String> defined = new LinkedHashMap<>();
        String first = RecordedServer.variableFor(defined, "http://192.168.1.101:5000");
        assertEquals("SERVER_1", first);
        defined.put(first, "http://192.168.1.101:5000");

        assertEquals("SERVER_1", RecordedServer.variableFor(defined, "http://192.168.1.101:5000"));

        String second = RecordedServer.variableFor(defined, "http://10.0.0.8:8080");
        assertEquals("SERVER_2", second);
        defined.put(second, "http://10.0.0.8:8080");

        assertEquals("SERVER_3", RecordedServer.variableFor(defined, "https://app.example"));
    }

    @Test
    void plainServerCountsAsTheFirstFlow() {
        Map<String, String> defined = new LinkedHashMap<>();
        defined.put("SERVER", "http://192.168.1.101:5000");
        assertEquals("SERVER", RecordedServer.variableFor(defined, "http://192.168.1.101:5000"));
        assertEquals("SERVER_2", RecordedServer.variableFor(defined, "http://10.0.0.8:8080"));
    }
}
