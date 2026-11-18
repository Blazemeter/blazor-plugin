package com.blazemeter.jmeter.blazor.recorder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blazemeter.jmeter.blazor.client.BlazorException;
import com.blazemeter.jmeter.blazor.client.BlazorSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayUntrustedHttpsTest {

    @TempDir
    Path temp;

    @Test
    void replayContinuesWhenJavaDoesNotTrustTheSite() throws Exception {
        RecordingTls tls = new RecordingTls(temp);
        SSLContext context = tls.serverContext("127.0.0.1");
        SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(15_000);
        Thread thread = new Thread(() -> serve(server), "blazor-untrusted-https");
        thread.setDaemon(true);
        thread.start();
        BlazorSession session = new BlazorSession(8_000);
        try {
            BlazorException failure = assertThrows(BlazorException.class,
                    () -> session.open("https://127.0.0.1:" + server.getLocalPort() + "/"));
            assertTrue(failure.transcript().contains("not in the Java trust store"), failure.report());
            assertTrue(failure.transcript().contains("-> 200"), failure.report());
            assertFalse(failure.report().contains("PKIX"), failure.report());
        } finally {
            session.close();
            server.close();
        }
    }

    private static void serve(SSLServerSocket server) {
        while (!server.isClosed()) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(8_000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"http/1.1"});
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                String request = readHeaders(socket.getInputStream());
                byte[] body;
                String type;
                int status = 200;
                if (request.contains("negotiate")) {
                    body = "{\"connectionToken\":\"abc\",\"availableTransports\":[{\"transport\":\"WebSockets\"}]}"
                            .getBytes(StandardCharsets.UTF_8);
                    type = "application/json";
                } else if (request.startsWith("GET / ") || request.startsWith("GET /?")) {
                    body = "<html><head><base href=\"/\"></head><body></body></html>"
                            .getBytes(StandardCharsets.UTF_8);
                    type = "text/html";
                } else {
                    body = new byte[0];
                    type = "text/plain";
                    status = 404;
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: " + type + "\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (Exception ignored) {
                // The first handshake is rejected until replay accepts the site certificate.
            }
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
}
