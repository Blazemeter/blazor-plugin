package com.blazemeter.jmeter.blazor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blazemeter.jmeter.blazor.client.BlazorSession;
import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import com.blazemeter.jmeter.blazor.recorder.RecordedStep;
import com.blazemeter.jmeter.blazor.recorder.RecordingProxy;
import com.blazemeter.jmeter.blazor.recorder.WebSocketFrames;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A local circuit, so the Jetty client is checked without the Harbor app:
 * redirects, cookies, the blazorpack handshake, and a proxied websocket.
 */
class JettyCircuitTest {

    @Test
    void sessionFollowsTheRedirectAndCompletesStartCircuit() throws Exception {
        try (FakeCircuit origin = new FakeCircuit()) {
            BlazorSession session = new BlazorSession(8_000);
            try {
                String opened = session.open(origin.url("/start"));
                assertTrue(opened.contains("GET " + origin.url("/home")), opened);
                assertTrue(opened.contains("StartCircuit completed"), opened);
                assertFalse(opened.contains("JS.Error"), opened);
                String moved = session.navigate("/market");
                assertTrue(moved.contains("/market"), moved);
            } finally {
                session.close();
            }
            assertTrue(origin.negotiateCookie.get().contains("session=abc"), origin.negotiateCookie.get());
        }
    }

    @Test
    void proxiedSessionIsRecorded() throws Exception {
        try (FakeCircuit origin = new FakeCircuit()) {
            RecordingProxy proxy = new RecordingProxy();
            proxy.start(0);
            BlazorSession session = new BlazorSession(8_000, new InetSocketAddress("127.0.0.1", proxy.port()));
            try {
                String opened = session.open(origin.url("/start"));
                assertTrue(opened.contains("StartCircuit completed"), opened);
                session.navigate("/market");
            } finally {
                session.close();
                proxy.stop();
            }
            assertTrue(proxy.steps().stream().anyMatch(step -> step.kind() == RecordedStep.Kind.CONNECT),
                    proxy.steps() + " seen=" + proxy.seenRequests());
            assertTrue(proxy.steps().stream().anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE
                    && step.value().contains("/market")), proxy.steps().toString());
        }
    }

    private static final class FakeCircuit implements AutoCloseable {
        private final ServerSocket listen;
        private final AtomicReference<String> negotiateCookie = new AtomicReference<>("");
        private volatile boolean open = true;

        private FakeCircuit() throws Exception {
            listen = new ServerSocket(0);
            Thread accept = new Thread(this::acceptLoop, "fake-blazor");
            accept.setDaemon(true);
            accept.start();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + listen.getLocalPort() + path;
        }

        @Override
        public void close() {
            open = false;
            try {
                listen.close();
            } catch (Exception ignored) {
                // The accept loop ends when the socket closes.
            }
        }

        private void acceptLoop() {
            while (open) {
                try {
                    Socket socket = listen.accept();
                    Thread worker = new Thread(() -> {
                        try {
                            handle(socket);
                        } catch (Exception ignored) {
                            // The client closed the exchange.
                        } finally {
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                                // Already closed.
                            }
                        }
                    }, "fake-blazor-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception ex) {
                    return;
                }
            }
        }

        private void handle(Socket socket) throws Exception {
            socket.setSoTimeout(8_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            List<String> headers = readHeaders(in);
            String length = header(headers, "content-length");
            if (length != null) {
                in.readNBytes(Integer.parseInt(length.trim()));
            }
            String target = requestLine.split(" ", 3)[1];
            URI uri = target.startsWith("http://") || target.startsWith("https://") ? URI.create(target) : URI.create("http://local" + target);
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            if (header(headers, "upgrade") != null && header(headers, "upgrade").equalsIgnoreCase("websocket")) {
                upgrade(in, out, header(headers, "sec-websocket-key"));
                return;
            }
            if ("/start".equals(path)) {
                byte[] body = ("HTTP/1.1 302 Found\r\nConnection: close\r\nLocation: " + url("/home")
                        + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                out.write(body);
                out.flush();
                return;
            }
            if ("/_blazor/negotiate".equals(path)) {
                negotiateCookie.set(header(headers, "cookie") == null ? "" : header(headers, "cookie"));
                byte[] json = ("{\"connectionToken\":\"abc\",\"availableTransports\":[{\"transport\":\"WebSockets\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "
                        + json.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(json);
                out.flush();
                return;
            }
            if (path.startsWith("/_blazor")) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            byte[] html = "<html><head><base href=\"/\"></head><body>ok</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Set-Cookie: session=abc; Path=/\r\nContent-Length: " + html.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(html);
            out.flush();
        }

        private void upgrade(InputStream in, OutputStream out, String key) throws Exception {
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            while (true) {
                byte[] raw = WebSocketFrames.readRaw(in);
                int opcode = WebSocketFrames.opcode(raw);
                byte[] payload = WebSocketFrames.payload(raw);
                if (opcode == 0x1) {
                    out.write(serverFrame(0x1, "{}\u001e".getBytes(StandardCharsets.UTF_8)));
                    out.flush();
                } else if (opcode == 0x2) {
                    for (List<Object> message : BlazorPack.decode(payload)) {
                        if ("1".equals(BlazorPack.invocationId(message))) {
                            out.write(serverFrame(0x2, BlazorPack.frame(Arrays.asList(
                                    BlazorPack.COMPLETION, Map.of(), "1", 2, null))));
                            out.flush();
                        }
                    }
                } else if (opcode == 0x8) {
                    return;
                }
            }
        }

        private static byte[] serverFrame(int opcode, byte[] payload) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else {
                frame.write(126);
                frame.write(payload.length >> 8);
                frame.write(payload.length);
            }
            frame.writeBytes(payload);
            return frame.toByteArray();
        }

        private static List<String> readHeaders(InputStream in) throws Exception {
            java.util.ArrayList<String> headers = new java.util.ArrayList<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                headers.add(line);
            }
            return headers;
        }

        private static String readLine(InputStream in) throws Exception {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int bite = in.read();
                if (bite < 0) {
                    break;
                }
                if (bite == '\n') {
                    break;
                }
                if (bite != '\r') {
                    line.write(bite);
                }
            }
            return line.toString(StandardCharsets.ISO_8859_1);
        }

        private static String header(List<String> headers, String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            for (String header : headers) {
                if (header.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return header.substring(prefix.length()).trim();
                }
            }
            return null;
        }
    }
}
