package com.blazemeter.jmeter.blazor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import com.blazemeter.jmeter.blazor.protocol.Json;
import com.blazemeter.jmeter.blazor.protocol.PageDocument;
import com.blazemeter.jmeter.blazor.recorder.RecordedStep;
import com.blazemeter.jmeter.blazor.recorder.RecordingProxy;
import com.blazemeter.jmeter.blazor.recorder.WebSocketFrames;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void pingFrameMatchesSignalR() {
        assertEquals("029106", hex(BlazorPack.ping()));
    }

    @Test
    void invocationFrameRoundTrips() {
        byte[] frame = BlazorPack.invocation(null, "T", List.of());
        assertEquals("08960180c0a1549090", hex(frame));
        List<List<Object>> messages = BlazorPack.decode(frame);
        assertEquals(1, messages.size());
        assertEquals("T", BlazorPack.target(messages.get(0)));
        assertTrue(BlazorPack.arguments(messages.get(0)).isEmpty());
    }

    @Test
    void startCircuitArgumentsSurviveTheFrame() {
        byte[] frame = hexToBytes("31960180a131ac53746172744369726375697494a9687474703a2f2f682fa9687474703a2f2f682fa25b5da5535441544590");
        List<Object> arguments = BlazorPack.arguments(BlazorPack.decode(frame).get(0));
        assertEquals("StartCircuit", BlazorPack.target(BlazorPack.decode(frame).get(0)));
        assertEquals("STATE", arguments.get(3));
        assertEquals("1", BlazorPack.invocationId(BlazorPack.decode(frame).get(0)));
    }

    @Test
    void renderCompletionMatchesTheBrowserShape() {
        byte[] frame = BlazorPack.invocation(null, "OnRenderCompleted", java.util.Arrays.asList(2L, null));
        assertEquals("1a960180c0b14f6e52656e646572436f6d706c657465649202c090", hex(frame));
    }

    @Test
    void jsonKeepsDescriptorEscapes() {
        Object parsed = Json.parse("{\"descriptor\":\"CfDJ\\u002Babc\",\"n\":2,\"ok\":true,\"z\":null}");
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) parsed;
        assertEquals("CfDJ+abc", object.get("descriptor"));
        assertEquals(2L, object.get("n"));
        assertEquals(Boolean.TRUE, object.get("ok"));
        assertEquals("{\"descriptor\":\"CfDJ+abc\",\"n\":2,\"ok\":true,\"z\":null}", Json.write(parsed));
    }

    @Test
    void pageMarkersIgnoreTheClosingComment() {
        String html = """
                <html><head><base href="/"></head><body>
                <!--Blazor:{"type":"server","prerenderId":"abc","key":{"locationHash":"HASH:5","formattedComponentKey":""},"sequence":0,"descriptor":"CfDJ\\u002Babc"}-->
                <section></section>
                <!--Blazor:{"prerenderId":"abc"}-->
                <!--Blazor-Server-Component-State:STATE+/=-->
                </body></html>
                """;
        PageDocument document = PageDocument.parse(URI.create("http://192.168.1.101:5000/market"), html);
        assertEquals("http://192.168.1.101:5000/", document.baseUri());
        assertEquals("http://192.168.1.101:5000/market", document.location());
        assertEquals("STATE+/=", document.applicationState());
        assertEquals(1, document.serverMarkers().size());
        assertEquals("HASH:5", document.serverMarkers().get(0).locationHash());
        assertEquals("CfDJ+abc", document.serverMarkers().get(0).descriptor());
    }

    @Test
    void recorderDropsCircuitBookkeeping() {
        byte[] start = BlazorPack.invocation("1", "StartCircuit", List.of("http://app/", "http://app/", "[]", "STATE"));
        byte[] move = BlazorPack.invocation(null, "OnLocationChanged",
                java.util.Arrays.asList("http://app/market", null, false));
        byte[] click = BlazorPack.invocation(null, "BeginInvokeDotNetFromJS",
                List.of("", "", "DispatchEventAsync", 0L, "[]"));
        assertTrue(RecordingProxy.userSteps(start, "http://app/").isEmpty());
        List<RecordedStep> steps = RecordingProxy.userSteps(concatenate(move, click), "http://app/");
        assertEquals(RecordedStep.Kind.NAVIGATE, steps.get(0).kind());
        assertEquals("http://app/market", steps.get(0).value());
        assertEquals(RecordedStep.Kind.INVOKE, steps.get(1).kind());
        assertEquals("BeginInvokeDotNetFromJS", steps.get(1).value());
        assertTrue(steps.get(1).arguments().contains("DispatchEventAsync"));
        assertFalse(RecordingProxy.userSteps(move, "http://app/market").stream()
                .anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE));
    }

    @Test
    void firefoxDeflateOfferIsStrippedAndTheNavigationIsRecorded() throws Exception {
        try (ServerSocket origin = new ServerSocket(0)) {
            origin.setSoTimeout(4000);
            Thread server = new Thread(() -> answerUpgrade(origin), "blazor-recorder-test-origin");
            server.setDaemon(true);
            server.start();
            RecordingProxy proxy = new RecordingProxy();
            List<RecordedStep> live = new CopyOnWriteArrayList<>();
            proxy.setStepListener(live::add);
            proxy.start(0);
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(4000);
                OutputStream out = client.getOutputStream();
                String authority = "127.0.0.1:" + origin.getLocalPort();
                out.write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String established = readHeaders(client.getInputStream());
                assertTrue(established.contains(" 200 "), established);
                out.write(("GET /_blazor?id=abc HTTP/1.1\r\n"
                        + "Host: " + authority + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String switched = readHeaders(client.getInputStream());
                assertTrue(switched.contains(" 101 "), switched);
                out.write(clientFrame(0x1, BlazorPack.HANDSHAKE.getBytes(StandardCharsets.UTF_8)));
                out.write(clientFrame(0x2, BlazorPack.invocation(null, "OnLocationChanged",
                        java.util.Arrays.asList("http://" + authority + "/atlas", null, false))));
                out.flush();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline && live.stream()
                        .noneMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE)) {
                    Thread.sleep(50);
                }
            } finally {
                proxy.stop();
            }
            assertTrue(live.stream().anyMatch(step -> step.kind() == RecordedStep.Kind.CONNECT), live.toString());
            assertTrue(live.stream().anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE
                    && step.value().contains("/atlas")), live.toString());
            server.join(1000);
        }
    }

    @Test
    void socks5UpgradeIsRecorded() throws Exception {
        try (ServerSocket origin = new ServerSocket(0)) {
            origin.setSoTimeout(4000);
            Thread server = new Thread(() -> answerUpgrade(origin), "blazor-recorder-socks-origin");
            server.setDaemon(true);
            server.start();
            RecordingProxy proxy = new RecordingProxy();
            List<RecordedStep> live = new CopyOnWriteArrayList<>();
            proxy.setStepListener(live::add);
            proxy.start(0);
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(4000);
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                out.write(new byte[] {0x05, 0x01, 0x00});
                out.flush();
                byte[] method = in.readNBytes(2);
                assertEquals(0x05, method[0] & 0xff);
                assertEquals(0x00, method[1] & 0xff);
                int port = origin.getLocalPort();
                out.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                        (byte) (port >> 8), (byte) port});
                out.flush();
                byte[] granted = in.readNBytes(10);
                assertEquals(0x00, granted[1] & 0xff, "socks status");
                out.write(("GET /_blazor?id=abc HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:" + port + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String switched = readHeaders(in);
                assertTrue(switched.contains(" 101 "), switched);
                out.write(clientFrame(0x2, BlazorPack.invocation(null, "OnLocationChanged",
                        java.util.Arrays.asList("http://127.0.0.1:" + port + "/market", null, false))));
                out.flush();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline && live.stream()
                        .noneMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE)) {
                    Thread.sleep(50);
                }
            } finally {
                proxy.stop();
            }
            assertTrue(live.stream().anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE
                    && step.value().contains("/market")), live.toString());
            server.join(1000);
        }
    }

    private static void answerUpgrade(ServerSocket origin) {
        try (Socket socket = origin.accept()) {
            String request = readHeaders(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            if (request.toLowerCase().contains("sec-websocket-extensions")) {
                out.write("HTTP/1.1 400 extension still offered\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(3000);
        } catch (Exception ignored) {
            // The proxy closes this socket when the browser side finishes.
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

    @Test
    void maskedClientFrameUnmasksWithoutChangingTheLength() {
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] mask = {0x01, 0x02, 0x03, 0x04};
        byte[] raw = new byte[] {
                (byte) 0x82, (byte) 0x82,
                mask[0], mask[1], mask[2], mask[3],
                (byte) (payload[0] ^ mask[0]), (byte) (payload[1] ^ mask[1])
        };
        assertEquals(2, WebSocketFrames.opcode(raw));
        assertEquals("hi", new String(WebSocketFrames.payload(raw), StandardCharsets.UTF_8));
    }

    private static byte[] concatenate(byte[] left, byte[] right) {
        byte[] both = new byte[left.length + right.length];
        System.arraycopy(left, 0, both, 0, left.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte bite : data) {
            out.append(String.format("%02x", bite));
        }
        return out.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
