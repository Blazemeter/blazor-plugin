package com.blazemeter.jmeter.blazor.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingHttpsTest {

    @TempDir
    Path temp;

    @Test
    void httpsTunnelIsDecryptedAndRecorded() throws Exception {
        RecordingTls originTls = new RecordingTls(temp.resolve("origin"));
        SSLContext originContext = originTls.serverContext("127.0.0.1");
        SSLServerSocket origin = (SSLServerSocket) originContext.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        origin.setSoTimeout(15_000);
        Thread server = new Thread(() -> answer(origin), "blazor-recorder-https-origin");
        server.setDaemon(true);
        server.start();

        RecordingProxy proxy = new RecordingProxy();
        proxy.setCertificateDirectory(temp.resolve("proxy"));
        List<RecordedStep> live = new CopyOnWriteArrayList<>();
        proxy.setStepListener(live::add);
        proxy.start(0);
        try {
            Path caFile = proxy.ensureCertificate();
            String authority = "127.0.0.1:" + origin.getLocalPort();
            try (SSLSocket page = handshake(proxy, caFile, authority)) {
                X509Certificate leaf = (X509Certificate) page.getSession().getPeerCertificates()[0];
                X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(Files.newInputStream(caFile));
                leaf.verify(ca.getPublicKey());
                assertTrue(names(leaf).contains("127.0.0.1"), leaf.getSubjectAlternativeNames().toString());
                assertEquals("http/1.1", page.getApplicationProtocol());
                OutputStream out = page.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: " + authority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String html = readHeaders(page.getInputStream());
                assertTrue(html.contains(" 200 "), html);
            }
            try (SSLSocket circuit = handshake(proxy, caFile, authority)) {
                OutputStream out = circuit.getOutputStream();
                out.write(("GET /_blazor?id=abc HTTP/1.1\r\n"
                        + "Host: " + authority + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String switched = readHeaders(circuit.getInputStream());
                assertTrue(switched.contains(" 101 "), switched);
                out.write(clientFrame(0x2, BlazorPack.invocation(null, "OnLocationChanged",
                        java.util.Arrays.asList("https://" + authority + "/atlas", null, false))));
                out.flush();
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && live.stream()
                        .noneMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE)) {
                    Thread.sleep(50);
                }
            }
            assertTrue(live.stream().anyMatch(step -> step.kind() == RecordedStep.Kind.CONNECT
                    && step.value().startsWith("https://" + authority)), live.toString());
            assertTrue(live.stream().anyMatch(step -> step.kind() == RecordedStep.Kind.NAVIGATE
                    && step.value().contains("/atlas")), live.toString());
            assertTrue(proxy.seenRequests().stream().anyMatch(line -> line.contains("not in the Java trust store")),
                    proxy.seenRequests().toString());
        } finally {
            proxy.stop();
            origin.close();
        }
        server.join(1000);
    }

    private SSLSocket handshake(RecordingProxy proxy, Path caFile, String authority) throws Exception {
        Socket tunnel = new Socket("127.0.0.1", proxy.port());
        boolean open = false;
        try {
            tunnel.setSoTimeout(15_000);
            OutputStream out = tunnel.getOutputStream();
            out.write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String established = readHeaders(tunnel.getInputStream());
            assertTrue(established.contains(" 200 "), established);
            SSLSocket ssl = (SSLSocket) clientContext(caFile).getSocketFactory()
                    .createSocket(tunnel, "127.0.0.1", originPort(authority), true);
            ssl.setUseClientMode(true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"http/1.1"});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            open = true;
            return ssl;
        } finally {
            if (!open) {
                tunnel.close();
            }
        }
    }

    private static int originPort(String authority) {
        return Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
    }

    private static void answer(SSLServerSocket origin) {
        int served = 0;
        while (served < 2) {
            try (SSLSocket socket = (SSLSocket) origin.accept()) {
                socket.setSoTimeout(15_000);
                socket.startHandshake();
                String request = readHeaders(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                if (request.contains("Upgrade: websocket")) {
                    if (request.toLowerCase().contains("sec-websocket-extensions")) {
                        out.write("HTTP/1.1 400 extension still offered\r\nContent-Length: 0\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    served++;
                    Thread.sleep(3000);
                    return;
                }
                byte[] body = "<html><!--Blazor:{}--></html>".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                served++;
            } catch (Exception ignored) {
                // The recorder's first handshake is rejected until it trusts the site certificate.
            }
        }
    }

    private static SSLContext clientContext(Path caFile) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream in = Files.newInputStream(caFile)) {
            ca = factory.generateCertificate(in);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("recorder", ca);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static String names(X509Certificate certificate) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        StringBuilder text = new StringBuilder();
        if (names != null) {
            for (List<?> name : names) {
                text.append(name.get(1)).append(' ');
            }
        }
        return text.toString();
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
}
