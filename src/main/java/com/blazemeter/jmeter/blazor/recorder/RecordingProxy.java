package com.blazemeter.jmeter.blazor.recorder;

import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import com.blazemeter.jmeter.blazor.protocol.Json;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * HTTP proxy that forwards a browser and keeps the Blazor user actions:
 * the document URL that opened each circuit, location changes, and hub
 * invocations such as {@code BeginInvokeDotNetFromJS}.
 */
public final class RecordingProxy {

    private static final int MAX_CAPTURE = 5_000_000;
    private static final Set<String> IGNORED = Set.of(
            "StartCircuit",
            "UpdateRootComponents",
            "OnRenderCompleted",
            "EndInvokeJSFromDotNet",
            "ReceiveByteArray",
            "OnLocationChanging");

    private final List<RecordedStep> steps = new ArrayList<>();
    private final List<String> seenRequests = new ArrayList<>();
    private final List<Socket> active = new CopyOnWriteArrayList<>();
    private final List<ServerSocket> listeners = new ArrayList<>();

    private ServerSocket server;
    private volatile boolean stopped;
    private volatile Consumer<RecordedStep> stepListener;
    private String lastFullPage;
    private String circuitPage;
    private boolean circuitOpen;

    public synchronized void start(int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Recorder is already running on port " + server.getLocalPort());
        }
        stopped = false;
        ServerSocket ipv4 = new ServerSocket();
        ipv4.bind(new InetSocketAddress("127.0.0.1", port));
        listeners.add(ipv4);
        this.server = ipv4;
        try {
            ServerSocket ipv6 = new ServerSocket();
            ipv6.bind(new InetSocketAddress(InetAddress.getByName("::1"), ipv4.getLocalPort()));
            listeners.add(ipv6);
        } catch (IOException ignored) {
            // IPv4 loopback is enough when this host has no IPv6.
        }
        for (ServerSocket socket : listeners) {
            Thread accept = new Thread(() -> acceptLoop(socket), "blazor-recorder");
            accept.setDaemon(true);
            accept.start();
        }
    }

    /**
     * Called on the proxy thread each time a sampler step is recorded.
     * The GUI uses this to insert samplers while the browser is still open.
     */
    public void setStepListener(Consumer<RecordedStep> stepListener) {
        this.stepListener = stepListener;
    }

    public int port() {
        ServerSocket socket = this.server;
        return socket == null ? -1 : socket.getLocalPort();
    }

    public void stop() {
        RecordedStep disconnect = null;
        List<ServerSocket> sockets;
        synchronized (this) {
            stopped = true;
            if (circuitOpen) {
                circuitOpen = false;
                disconnect = RecordedStep.disconnect();
                steps.add(disconnect);
            }
            sockets = new ArrayList<>(listeners);
            listeners.clear();
            this.server = null;
        }
        if (disconnect != null) {
            notifyStep(disconnect);
        }
        for (ServerSocket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing the accept socket is enough to end the loop.
            }
        }
        for (Socket client : active) {
            try {
                client.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    public synchronized List<RecordedStep> steps() {
        return List.copyOf(steps);
    }

    public synchronized String recordedPage() {
        return lastFullPage;
    }

    public synchronized List<String> seenRequests() {
        return List.copyOf(seenRequests);
    }

    /**
     * Turns one client WebSocket message into user-level steps. SignalR
     * lifecycle messages are dropped.
     */
    public static List<RecordedStep> userSteps(byte[] payload, String currentPage) {
        if (payload == null || payload.length == 0 || payload[0] == '{') {
            return List.of();
        }
        List<List<Object>> messages;
        try {
            messages = BlazorPack.decode(payload);
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<RecordedStep> steps = new ArrayList<>();
        for (List<Object> message : messages) {
            if (BlazorPack.type(message) != BlazorPack.INVOCATION) {
                continue;
            }
            String target = BlazorPack.target(message);
            if (target == null || IGNORED.contains(target)) {
                continue;
            }
            List<Object> arguments = BlazorPack.arguments(message);
            if ("OnLocationChanged".equals(target)) {
                if (!arguments.isEmpty() && arguments.get(0) instanceof String url && !samePage(url, currentPage)) {
                    steps.add(RecordedStep.navigate(url));
                }
                continue;
            }
            steps.add(RecordedStep.invoke(target, Json.write(arguments)));
        }
        return steps;
    }

    private void acceptLoop(ServerSocket socket) {
        while (!stopped) {
            try {
                Socket client = socket.accept();
                active.add(client);
                Thread worker = new Thread(() -> {
                    try {
                        handle(client);
                    } catch (Exception ignored) {
                        // The browser closed the connection, or recording stopped.
                    } finally {
                        active.remove(client);
                        try {
                            client.close();
                        } catch (IOException ignored) {
                            // Already closed.
                        }
                    }
                }, "blazor-recorder-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException ex) {
                return;
            }
        }
    }

    private void handle(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        PushbackInputStream in = new PushbackInputStream(client.getInputStream(), 1);
        OutputStream out = client.getOutputStream();
        int first = in.read();
        if (first < 0) {
            return;
        }
        in.unread(first);
        if (first == 0x05) {
            socks5(in, out);
            return;
        }
        if (first == 0x04) {
            socks4(in, out);
            return;
        }
        String requestLine = readLine(in);
        synchronized (this) {
            seenRequests.add(requestLine);
        }
        if (requestLine.isEmpty()) {
            return;
        }
        List<String> headers = readHeaders(in);
        if (requestLine.startsWith("CONNECT ")) {
            tunnel(requestLine, in, out);
            return;
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            return;
        }
        String target = absoluteTarget(parts[1], headers);
        URI uri = URI.create(target);
        byte[] requestBody = readBody(in, headers);
        try (Socket origin = new Socket()) {
            origin.connect(new InetSocketAddress(uri.getHost(), portOf(uri)), 15_000);
            origin.setTcpNoDelay(true);
            active.add(origin);
            try {
                OutputStream originOut = origin.getOutputStream();
                InputStream originIn = origin.getInputStream();
                writeOriginRequest(originOut, parts[0], uri, headers, requestBody);
                String status = readLine(originIn);
                List<String> responseHeaders = readHeaders(originIn);
                writeHead(out, status, responseHeaders);
                if (isWebSocket(headers, status)) {
                    if (uri.getPath() != null && uri.getPath().contains("_blazor")) {
                        openCircuit(headers, uri);
                    }
                    splice(client, origin);
                    return;
                }
                byte[] responseBody = copyBody(originIn, out, responseHeaders);
                notePage(headers, uri, status, responseHeaders, responseBody);
            } finally {
                active.remove(origin);
            }
        }
    }

    private void openCircuit(List<String> headers, URI webSocketUri) {
        List<RecordedStep> added = new ArrayList<>();
        synchronized (this) {
            String page = lastFullPage;
            if (page == null) {
                page = header(headers, "Referer");
            }
            if (page == null) {
                page = webSocketUri.getScheme() + "://" + webSocketUri.getRawAuthority() + "/";
            }
            if (circuitOpen) {
                RecordedStep end = RecordedStep.disconnect();
                steps.add(end);
                added.add(end);
            }
            RecordedStep connect = RecordedStep.connect(page);
            steps.add(connect);
            added.add(connect);
            circuitOpen = true;
            circuitPage = page;
        }
        added.forEach(this::notifyStep);
    }

    private void notifyStep(RecordedStep step) {
        Consumer<RecordedStep> listener = this.stepListener;
        if (listener != null) {
            listener.accept(step);
        }
    }

    private void notePage(List<String> requestHeaders, URI uri, String status,
            List<String> responseHeaders, byte[] body) {
        if (status == null || !status.contains(" 200 ")) {
            return;
        }
        String type = header(responseHeaders, "Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).contains("text/html") || body == null) {
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.contains("<!--Blazor") && !text.contains("blazor.web")) {
            return;
        }
        String accept = header(requestHeaders, "Accept");
        if (accept != null && accept.contains("blazor-enhanced-nav")) {
            return;
        }
        synchronized (this) {
            lastFullPage = uri.toString();
        }
    }

    private void splice(Socket client, Socket origin) throws IOException {
        splice(client.getInputStream(), client.getOutputStream(), origin.getInputStream(), origin.getOutputStream(),
                client, origin);
    }

    private void splice(InputStream clientIn, OutputStream clientOut, InputStream originIn, OutputStream originOut,
            Socket client, Socket origin) {
        Thread upstream = new Thread(() -> {
            try {
                pump(clientIn, originOut, true);
            } catch (IOException ignored) {
                closeBoth(client, origin);
            }
        }, "blazor-recorder-up");
        Thread downstream = new Thread(() -> {
            try {
                pump(originIn, clientOut, false);
            } catch (IOException ignored) {
                closeBoth(client, origin);
            }
        }, "blazor-recorder-down");
        upstream.setDaemon(true);
        downstream.setDaemon(true);
        upstream.start();
        downstream.start();
        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closeBoth(client, origin);
        }
    }

    private void pump(InputStream in, OutputStream out, boolean record) throws IOException {
        ByteArrayOutputStream fragment = new ByteArrayOutputStream();
        int fragmentOpcode = -1;
        while (!stopped) {
            byte[] raw = WebSocketFrames.readRaw(in);
            out.write(raw);
            out.flush();
            if (!record) {
                continue;
            }
            int opcode = WebSocketFrames.opcode(raw);
            boolean fin = WebSocketFrames.fin(raw);
            if (opcode == 0x1 || opcode == 0x2) {
                if (fin) {
                    recordPayload(WebSocketFrames.payload(raw));
                } else {
                    fragment.reset();
                    fragment.write(WebSocketFrames.payload(raw));
                    fragmentOpcode = opcode;
                }
            } else if (opcode == 0x0 && fragmentOpcode >= 0) {
                fragment.write(WebSocketFrames.payload(raw));
                if (fin) {
                    recordPayload(fragment.toByteArray());
                    fragment.reset();
                    fragmentOpcode = -1;
                }
            }
        }
    }

    private void recordPayload(byte[] payload) {
        String page;
        synchronized (this) {
            page = circuitPage;
        }
        List<RecordedStep> translated = userSteps(payload, page);
        if (translated.isEmpty()) {
            return;
        }
        synchronized (this) {
            steps.addAll(translated);
            for (RecordedStep step : translated) {
                if (step.kind() == RecordedStep.Kind.NAVIGATE) {
                    circuitPage = step.value();
                }
            }
        }
        translated.forEach(this::notifyStep);
    }

    private void tunnel(String requestLine, InputStream clientIn, OutputStream clientOut) throws IOException {
        String hostPort = requestLine.split(" ")[1];
        String host = hostPort;
        int port = 443;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && colon < hostPort.length() - 1) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        }
        try (Socket origin = new Socket()) {
            origin.connect(new InetSocketAddress(host, port), 15_000);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            PushbackInputStream push = new PushbackInputStream(clientIn, 1);
            int first = push.read();
            if (first < 0) {
                return;
            }
            push.unread(first);
            if (first >= 'A' && first <= 'Z') {
                proxyTunnelledHttp(push, clientOut, origin, hostPort);
                return;
            }
            relay(push, clientOut, origin);
        }
    }

    /**
     * Firefox's "Use this proxy for all protocols" also points SOCKS at this
     * port. The document still arrives as an HTTP proxy request, but the
     * Blazor WebSocket arrives as a SOCKS connection. Without this handshake
     * the page renders and nothing in it works.
     */
    private void socks5(InputStream clientIn, OutputStream clientOut) throws IOException {
        int version = clientIn.read();
        int methods = clientIn.read();
        if (version != 0x05 || methods < 0) {
            return;
        }
        byte[] offered = clientIn.readNBytes(methods);
        boolean anonymous = false;
        for (byte method : offered) {
            if (method == 0x00) {
                anonymous = true;
            }
        }
        if (!anonymous) {
            clientOut.write(new byte[] {0x05, (byte) 0xFF});
            clientOut.flush();
            return;
        }
        clientOut.write(new byte[] {0x05, 0x00});
        clientOut.flush();
        version = clientIn.read();
        int command = clientIn.read();
        clientIn.read();
        int addressType = clientIn.read();
        if (version != 0x05 || command != 0x01) {
            clientOut.write(socks5Reply(0x07));
            clientOut.flush();
            return;
        }
        String host = readSocks5Host(clientIn, addressType);
        int port = readPort(clientIn);
        if (host == null) {
            clientOut.write(socks5Reply(0x08));
            clientOut.flush();
            return;
        }
        openSocksTarget(clientIn, clientOut, host, port, socks5Reply(0x00), socks5Reply(0x05));
    }

    private void socks4(InputStream clientIn, OutputStream clientOut) throws IOException {
        int version = clientIn.read();
        int command = clientIn.read();
        int port = readPort(clientIn);
        byte[] ip = clientIn.readNBytes(4);
        if (version != 0x04 || command != 0x01 || ip.length != 4) {
            clientOut.write(new byte[] {0x00, 0x5B, 0, 0, 0, 0, 0, 0});
            clientOut.flush();
            return;
        }
        while (true) {
            int bite = clientIn.read();
            if (bite <= 0) {
                break;
            }
        }
        String host;
        if (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) {
            ByteArrayOutputStream name = new ByteArrayOutputStream();
            int bite;
            while ((bite = clientIn.read()) > 0) {
                name.write(bite);
            }
            host = name.toString(StandardCharsets.US_ASCII);
        } else {
            host = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
        }
        openSocksTarget(clientIn, clientOut, host, port, new byte[] {0x00, 0x5A, 0, 0, 0, 0, 0, 0},
                new byte[] {0x00, 0x5B, 0, 0, 0, 0, 0, 0});
    }

    private void openSocksTarget(InputStream clientIn, OutputStream clientOut, String host, int port,
            byte[] granted, byte[] failed) throws IOException {
        Socket origin = new Socket();
        try {
            origin.connect(new InetSocketAddress(host, port), 15_000);
        } catch (IOException ex) {
            clientOut.write(failed);
            clientOut.flush();
            return;
        }
        origin.setTcpNoDelay(true);
        active.add(origin);
        try (origin) {
            try {
                clientOut.write(granted);
                clientOut.flush();
                PushbackInputStream push = new PushbackInputStream(clientIn, 1);
                int first = push.read();
                if (first < 0) {
                    return;
                }
                push.unread(first);
                String authority = host.contains(":") && !host.startsWith("[")
                        ? "[" + host + "]:" + port
                        : host + ":" + port;
                if (first >= 'A' && first <= 'Z') {
                    proxyTunnelledHttp(push, clientOut, origin, authority);
                    return;
                }
                relay(push, clientOut, origin);
            } finally {
                active.remove(origin);
            }
        }
    }

    private static byte[] socks5Reply(int status) {
        return new byte[] {0x05, (byte) status, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    }

    private static String readSocks5Host(InputStream in, int addressType) throws IOException {
        if (addressType == 0x01) {
            byte[] ip = in.readNBytes(4);
            if (ip.length != 4) {
                return null;
            }
            return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
        }
        if (addressType == 0x03) {
            int length = in.read();
            if (length < 0) {
                return null;
            }
            byte[] name = in.readNBytes(length);
            if (name.length != length) {
                return null;
            }
            return new String(name, StandardCharsets.US_ASCII);
        }
        if (addressType == 0x04) {
            byte[] ip = in.readNBytes(16);
            if (ip.length != 16) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) {
                    text.append(':');
                }
                text.append(Integer.toHexString(((ip[i] & 0xff) << 8) | (ip[i + 1] & 0xff)));
            }
            return text.toString();
        }
        return null;
    }

    private static int readPort(InputStream in) throws IOException {
        int high = in.read();
        int low = in.read();
        if (high < 0 || low < 0) {
            throw new EOFException("Truncated SOCKS port");
        }
        return (high << 8) | low;
    }

    private static void relay(InputStream clientIn, OutputStream clientOut, Socket origin) {
        Thread up = new Thread(() -> copyRaw(clientIn, origin), "blazor-recorder-tunnel-up");
        up.setDaemon(true);
        up.start();
        copyRaw(origin.getInputStream(), clientOut);
        try {
            up.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Some clients, including the JDK WebSocket client, open a plain websocket
     * with CONNECT and only then send the upgrade. Browsers usually send the
     * upgrade as a proxied GET instead. Both have to be recorded.
     */
    private void proxyTunnelledHttp(InputStream clientIn, OutputStream clientOut, Socket origin, String hostPort)
            throws IOException {
        String requestLine = readLine(clientIn);
        synchronized (this) {
            seenRequests.add("TUNNEL " + requestLine);
        }
        List<String> headers = readHeaders(clientIn);
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            return;
        }
        String path = parts[1];
        URI uri = path.startsWith("http://") || path.startsWith("https://")
                ? URI.create(path)
                : URI.create("http://" + hostPort + (path.startsWith("/") ? path : "/" + path));
        byte[] body = readBody(clientIn, headers);
        writeOriginRequest(origin.getOutputStream(), parts[0], uri, headers, body);
        String status = readLine(origin.getInputStream());
        List<String> responseHeaders = readHeaders(origin.getInputStream());
        writeHead(clientOut, status, responseHeaders);
        if (isWebSocket(headers, status)) {
            if (uri.getPath() != null && uri.getPath().contains("_blazor")) {
                openCircuit(headers, uri);
            }
            splice(clientIn, clientOut, origin.getInputStream(), origin.getOutputStream(), null, origin);
            return;
        }
        copyBody(origin.getInputStream(), clientOut, responseHeaders);
    }

    private static void copyRaw(InputStream in, Socket destination) {
        try {
            OutputStream out = destination.getOutputStream();
            in.transferTo(out);
            destination.shutdownOutput();
        } catch (IOException ignored) {
            // Either side hung up.
        }
    }

    private static void copyRaw(InputStream in, OutputStream out) {
        try {
            in.transferTo(out);
            out.flush();
        } catch (IOException ignored) {
            // Either side hung up.
        }
    }

    private static void writeOriginRequest(OutputStream out, String method, URI uri,
            List<String> headers, byte[] body) throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        boolean upgrade = false;
        for (String header : headers) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("upgrade:") && lower.contains("websocket")) {
                upgrade = true;
            }
        }
        out.write((method + " " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
        for (String header : headers) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-connection:") || lower.startsWith("accept-encoding:")
                    || lower.startsWith("sec-websocket-extensions:")) {
                continue;
            }
            if (!upgrade && lower.startsWith("connection:")) {
                continue;
            }
            out.write((header + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        if (!upgrade) {
            out.write("Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static void writeHead(OutputStream out, String status, List<String> headers) throws IOException {
        out.write((status + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        for (String header : headers) {
            if (header.toLowerCase(Locale.ROOT).startsWith("sec-websocket-extensions:")) {
                continue;
            }
            out.write((header + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static byte[] copyBody(InputStream in, OutputStream out, List<String> headers) throws IOException {
        String encoding = header(headers, "Transfer-Encoding");
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return copyChunked(in, out);
        }
        String length = header(headers, "Content-Length");
        if (length != null) {
            int size = Integer.parseInt(length.trim());
            byte[] body = in.readNBytes(size);
            out.write(body);
            out.flush();
            return body;
        }
        byte[] rest = in.readAllBytes();
        out.write(rest);
        out.flush();
        return rest;
    }

    private static byte[] copyChunked(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            int size = Integer.parseInt(sizeLine.split(";", 2)[0].trim(), 16);
            if (size == 0) {
                String trailer;
                do {
                    trailer = readLine(in);
                    out.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } while (!trailer.isEmpty());
                out.flush();
                return capture.toByteArray();
            }
            byte[] data = in.readNBytes(size);
            if (data.length != size) {
                throw new EOFException("Truncated chunk");
            }
            out.write(data);
            if (capture.size() < MAX_CAPTURE) {
                capture.write(data, 0, Math.min(data.length, MAX_CAPTURE - capture.size()));
            }
            byte[] crlf = in.readNBytes(2);
            out.write(crlf);
        }
    }

    private static byte[] readBody(InputStream in, List<String> headers) throws IOException {
        String length = header(headers, "Content-Length");
        if (length == null) {
            return new byte[0];
        }
        return in.readNBytes(Integer.parseInt(length.trim()));
    }

    private static String absoluteTarget(String target, List<String> headers) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        String host = header(headers, "Host");
        if (host == null) {
            return target;
        }
        String scheme = "http";
        if (!target.startsWith("/")) {
            target = "/" + target;
        }
        return scheme + "://" + host + target;
    }

    private static int portOf(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isWebSocket(List<String> requestHeaders, String status) {
        String upgrade = header(requestHeaders, "Upgrade");
        return upgrade != null && upgrade.equalsIgnoreCase("websocket") && status.contains(" 101 ");
    }

    private static List<String> readHeaders(InputStream in) throws IOException {
        List<String> headers = new ArrayList<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            headers.add(line);
        }
        return headers;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int bite = in.read();
            if (bite < 0) {
                if (line.size() == 0) {
                    throw new EOFException();
                }
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

    private static boolean samePage(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return (uri.getScheme() == null ? "http" : uri.getScheme()) + "://" + uri.getRawAuthority() + path;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    private static void closeBoth(Socket left, Socket right) {
        if (left != null) {
            try {
                left.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        if (right != null) {
            try {
                right.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
