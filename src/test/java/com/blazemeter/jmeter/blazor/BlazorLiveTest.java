package com.blazemeter.jmeter.blazor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.blazemeter.jmeter.blazor.client.BlazorSession;
import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import com.blazemeter.jmeter.blazor.recorder.RecordedStep;
import com.blazemeter.jmeter.blazor.recorder.RecordingProxy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlazorLiveTest {

    private static final String BASE = System.getProperty("blazor.url", "http://192.168.1.101:5000/");

    @BeforeAll
    static void requireHarbor() {
        String flag = System.getProperty("blazor.live", "auto");
        if ("false".equalsIgnoreCase(flag)) {
            assumeTrue(false, "Live Blazor checks are disabled");
        }
        assumeTrue(reachable(), "Blazor app is not reachable at " + BASE);
    }

    @Test
    void sessionOpensHomeAndMovesToMarketAndStudio() throws Exception {
        BlazorSession session = new BlazorSession(20_000);
        try {
            String opened = session.open(BASE);
            assertHealthy("open", opened);
            assertTrue(opened.contains("StartCircuit completed"), opened);
            String market = session.navigate("/market");
            assertHealthy("market", market);
            assertTrue(market.contains("JS.RenderBatch") || market.contains("JS.EndUpdateRootComponents"), market);
            String studio = session.navigate("/studio");
            assertHealthy("studio", studio);
        } finally {
            session.close();
        }
    }

    @Test
    void samplerSamplesTheSameCircuit() {
        BlazorSampler connect = sampler("Connect home", BlazorSampler.CONNECT);
        connect.setServerUrl(BASE);
        SampleResult opened = connect.sample(null);
        assertTrue(opened.isSuccessful(), opened.getResponseDataAsString());

        BlazorSampler navigate = sampler("Go to desk", BlazorSampler.NAVIGATE);
        navigate.setLocation("/desk");
        SampleResult moved = navigate.sample(null);
        assertTrue(moved.isSuccessful(), moved.getResponseDataAsString());
        assertHealthy("desk", moved.getResponseDataAsString());

        BlazorSampler disconnect = sampler("Disconnect", BlazorSampler.DISCONNECT);
        SampleResult closed = disconnect.sample(null);
        assertTrue(closed.isSuccessful(), closed.getResponseDataAsString());
    }

    @Test
    void proxyRecordsANavigation() throws Exception {
        RecordingProxy proxy = new RecordingProxy();
        proxy.start(0);
        BlazorSession session = new BlazorSession(20_000, new InetSocketAddress("127.0.0.1", proxy.port()));
        try {
            String opened = session.open(BASE);
            assertHealthy("proxied open", opened);
            String market = session.navigate("/market");
            assertHealthy("proxied market", market);
        } finally {
            session.close();
            proxy.stop();
        }
        boolean connected = false;
        boolean navigated = false;
        for (RecordedStep step : proxy.steps()) {
            if (step.kind() == RecordedStep.Kind.CONNECT) {
                connected = true;
            }
            if (step.kind() == RecordedStep.Kind.NAVIGATE && step.value().contains("/market")) {
                navigated = true;
            }
        }
        assertTrue(connected, "page=" + proxy.recordedPage() + " seen=" + proxy.seenRequests()
                + " steps=" + proxy.steps());
        assertTrue(navigated, proxy.steps().toString());
    }

    @Test
    void browserStyleUpgradeIsRecorded() throws Exception {
        RecordingProxy proxy = new RecordingProxy();
        proxy.start(0);
        try {
            String page = http(proxy.port(), "GET", BASE, null);
            assertTrue(page.contains(" 200 "), page.substring(0, Math.min(page.length(), 180)));
            String negotiate = http(proxy.port(), "POST", origin(BASE) + "/_blazor/negotiate?negotiateVersion=1", null);
            int tokenAt = negotiate.indexOf("\"connectionToken\":\"");
            assertTrue(tokenAt > 0, negotiate);
            int tokenStart = tokenAt + "\"connectionToken\":\"".length();
            String token = negotiate.substring(tokenStart, negotiate.indexOf('"', tokenStart));
            try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                String upgrade = "GET " + origin(BASE) + "/_blazor?id=" + token + " HTTP/1.1\r\n"
                        + "Host: " + URI.create(BASE).getAuthority() + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\n\r\n";
                out.write(upgrade.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String headers = readHeaders(socket.getInputStream());
                assertTrue(headers.contains(" 101 "), headers);
                out.write(clientFrame(0x1, BlazorPack.HANDSHAKE.getBytes(StandardCharsets.UTF_8)));
                out.write(clientFrame(0x2, BlazorPack.invocation(null, "OnLocationChanged",
                        Arrays.asList(origin(BASE) + "/atlas", null, false))));
                out.flush();
                Thread.sleep(400);
            }
        } finally {
            proxy.stop();
        }
        boolean navigated = proxy.steps().stream().anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE
                && step.value().contains("/atlas"));
        assertTrue(navigated, proxy.steps().toString());
    }

    private static String http(int port, String method, String url, String cookie) throws Exception {
        URI uri = URI.create(url);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(url).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(uri.getAuthority()).append("\r\n");
            request.append("Connection: close\r\n");
            if (cookie != null) {
                request.append("Cookie: ").append(cookie).append("\r\n");
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int bite = in.read();
            if (bite < 0) {
                break;
            }
            raw.write(bite);
            if ((matched == 0 || matched == 2) && bite == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && bite == '\n') {
                matched++;
            } else {
                matched = bite == '\r' ? 1 : 0;
            }
        }
        return raw.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] clientFrame(int opcode, byte[] payload) {
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | opcode);
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else {
            out.write(0x80 | 126);
            out.write(payload.length >> 8);
            out.write(payload.length);
        }
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
        return out.toByteArray();
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        String value = uri.getScheme() + "://" + uri.getAuthority();
        return url.endsWith("/") && !value.endsWith("/") ? value : value;
    }

    private static BlazorSampler sampler(String name, String action) {
        BlazorSampler sampler = new BlazorSampler();
        sampler.setName(name);
        sampler.setAction(action);
        sampler.setConnection("blazor");
        sampler.setTimeout("20000");
        return sampler;
    }

    private static void assertHealthy(String step, String transcript) {
        assertFalse(transcript.contains("JS.Error"), step + "\n" + transcript);
        assertFalse(transcript.contains("Server closed"), step + "\n" + transcript);
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            java.net.URI uri = java.net.URI.create(BASE);
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            socket.connect(new InetSocketAddress(uri.getHost(), port), 800);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
