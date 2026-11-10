package com.blazemeter.jmeter.blazor.client;

import com.blazemeter.jmeter.blazor.protocol.BlazorPack;
import com.blazemeter.jmeter.blazor.protocol.Json;
import com.blazemeter.jmeter.blazor.protocol.PageDocument;
import com.blazemeter.jmeter.blazor.protocol.PageDocument.Marker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Blazor Server circuit. The browser sequence this follows is:
 * load the page, negotiate, open the websocket, handshake blazorpack,
 * {@code StartCircuit}, then {@code UpdateRootComponents} from the markers
 * in the HTML. Later navigations send {@code OnLocationChanged} and a fresh
 * root-component batch parsed from the enhanced-navigation response.
 * Render batches and JS calls are acknowledged so the circuit stays open.
 * HTTP and the WebSocket are Jetty 12. One shared client serves every direct
 * circuit; a session opened through a proxy gets its own client so other
 * threads are not sent through that proxy. Cookies stay on the session, so
 * virtual users do not share a jar.
 */
public final class BlazorSession {

    static final String USER_AGENT = "bzm-blazor-plugin/0.1";
    private static final long SETTLE_MS = 600;
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(BlazorSession.class);
    private static final Object CLIENTS = new Object();
    private static volatile JettyStack sharedStack;

    private final int timeoutMs;
    private final InetSocketAddress proxy;
    private final StringBuilder transcript = new StringBuilder();
    private final List<String> cookies = new ArrayList<>();
    private final LinkedBlockingQueue<Incoming> incoming = new LinkedBlockingQueue<>();
    private final LinkedHashMap<String, ActiveRoot> roots = new LinkedHashMap<>();

    private JettyStack transport;
    private Session webSocket;
    private ScheduledExecutorService pings;
    private String baseUri = "";
    private String pageUri = "";
    private int nextSsrId = 1;
    private int nextUniqueId;
    private int nextBatchId = 1;
    private int mark;

    public BlazorSession(int timeoutMs) {
        this(timeoutMs, null);
    }

    public BlazorSession(int timeoutMs, InetSocketAddress proxy) {
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.proxy = proxy;
    }

    public synchronized String open(String pageUrl) throws BlazorException {
        beginCall();
        try {
            this.transport = JettyStack.open(proxy);
            URI requested = URI.create(pageUrl);
            HttpResult home = get(requested, null);
            requireHttp(home, "page");
            PageDocument document = PageDocument.parse(home.finalUri, home.body);
            baseUri = document.baseUri();
            pageUri = document.location();
            log("GET " + home.finalUri + " -> " + home.status + ", server roots " + document.serverMarkers().size());
            try {
                get(URI.create(baseUri).resolve("_blazor/initializers"), null);
            } catch (Exception ex) {
                log("initializers skipped: " + ex.getMessage());
            }
            HttpResult negotiate = post(URI.create(baseUri).resolve("_blazor/negotiate?negotiateVersion=1"));
            requireHttp(negotiate, "negotiate");
            String token = connectionToken(negotiate.body);
            connectSocket(token);
            sendText(BlazorPack.HANDSHAKE);
            log("-> blazorpack handshake");
            awaitHandshake();
            sendRaw(BlazorPack.ping());
            log("-> ping");
            sendInvocation("1", "StartCircuit", List.of(baseUri, pageUri, "[]", document.applicationState()));
            log("-> StartCircuit");
            awaitCompletion("1");
            publishRoots(document.serverMarkers(), document.applicationState());
            startPings();
            return callText();
        } catch (BlazorException ex) {
            closeQuietly();
            throw ex;
        } catch (Exception ex) {
            closeQuietly();
            throw failure(ex);
        }
    }

    public synchronized String navigate(String location) throws BlazorException {
        beginCall();
        ensureOpen();
        try {
            URI target = resolve(location);
            sendInvocation(null, "OnLocationChanged", Arrays.asList(target.toString(), null, false));
            log("-> OnLocationChanged " + target);
            HttpResult page = get(target, "text/html; blazor-enhanced-nav=on");
            requireHttp(page, "navigation");
            PageDocument document = PageDocument.parse(page.finalUri, unframe(page));
            pageUri = document.location();
            log("GET " + page.finalUri + " -> " + page.status + ", server roots " + document.serverMarkers().size());
            publishRoots(document.serverMarkers(), document.applicationState());
            return callText();
        } catch (BlazorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure(ex);
        }
    }

    public synchronized String invoke(String target, List<Object> arguments) throws BlazorException {
        beginCall();
        ensureOpen();
        try {
            List<Object> args = arguments == null ? List.of() : arguments;
            sendInvocation(null, target, args);
            log("-> " + target + " " + BlazorPack.describe(args));
            settle();
            return callText();
        } catch (BlazorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure(ex);
        }
    }

    public synchronized String close() {
        beginCall();
        closeQuietly();
        log("disconnected");
        return callText();
    }

    private void publishRoots(List<Marker> markers, String state) throws BlazorException {
        List<Object> operations = diff(markers);
        if (operations.isEmpty()) {
            log("no root-component changes");
            settle();
            return;
        }
        int batchId = nextBatchId++;
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", batchId);
        batch.put("operations", operations);
        String json = Json.write(batch);
        sendInvocation(null, "UpdateRootComponents", List.of(json, state == null ? "" : state));
        log("-> UpdateRootComponents batch " + batchId + " (" + operations.size() + " operations)");
        settle();
    }

    private List<Object> diff(List<Marker> markers) {
        List<String> seen = new ArrayList<>();
        for (Marker marker : markers) {
            seen.add(marker.identity());
        }
        List<Object> operations = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (ActiveRoot active : roots.values()) {
            if (!seen.contains(active.identity)) {
                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put("type", "remove");
                operation.put("ssrComponentId", active.ssrComponentId);
                operations.add(operation);
                removed.add(active.identity);
            }
        }
        for (String identity : removed) {
            roots.remove(identity);
        }
        for (Marker marker : markers) {
            ActiveRoot existing = roots.get(marker.identity());
            if (existing == null) {
                int ssrId = nextSsrId++;
                int uniqueId = nextUniqueId++;
                operations.add(addOperation(ssrId, uniqueId, marker));
                roots.put(marker.identity(), new ActiveRoot(marker.identity(), ssrId, uniqueId, marker.descriptor()));
            } else if (!existing.descriptor.equals(marker.descriptor())) {
                operations.add(updateOperation(existing, marker));
                existing.descriptor = marker.descriptor();
            }
        }
        return operations;
    }

    private static Map<String, Object> addOperation(int ssrId, int uniqueId, Marker marker) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "add");
        operation.put("ssrComponentId", ssrId);
        operation.put("marker", markerWithId(marker, uniqueId));
        return operation;
    }

    private static Map<String, Object> updateOperation(ActiveRoot existing, Marker marker) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "update");
        operation.put("ssrComponentId", existing.ssrComponentId);
        operation.put("marker", markerWithId(marker, existing.uniqueId));
        return operation;
    }

    private static Map<String, Object> markerWithId(Marker marker, int uniqueId) {
        Map<String, Object> copy = new LinkedHashMap<>(marker.raw());
        copy.put("uniqueId", uniqueId);
        return copy;
    }

    private void connectSocket(String token) throws Exception {
        URI http = URI.create(baseUri).resolve("_blazor?id=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        String scheme = "https".equalsIgnoreCase(http.getScheme()) ? "wss" : "ws";
        URI webSocketUri = new URI(scheme, http.getRawUserInfo(), http.getHost(), http.getPort(),
                http.getRawPath(), http.getRawQuery(), null);
        log("WebSocket " + webSocketUri);
        ClientUpgradeRequest upgrade = new ClientUpgradeRequest(webSocketUri);
        upgrade.setHeader("User-Agent", USER_AGENT);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            upgrade.setHeader("Cookie", cookie);
        }
        this.webSocket = transport.sockets.connect(new CircuitEndpoint(incoming), upgrade)
                .get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void awaitHandshake() throws BlazorException {
        long deadline = deadline();
        while (System.nanoTime() < deadline) {
            Incoming message = poll(deadline);
            if (message == null) {
                continue;
            }
            if (message.data != null && message.data.length > 0 && message.data[0] == '{') {
                log("<- handshake");
                return;
            }
            consume(message, null);
        }
        throw failure("Timed out waiting for the blazorpack handshake");
    }

    private void awaitCompletion(String invocationId) throws BlazorException {
        long deadline = deadline();
        while (System.nanoTime() < deadline) {
            Incoming message = poll(deadline);
            if (message == null) {
                continue;
            }
            if (consume(message, invocationId)) {
                log("StartCircuit completed");
                return;
            }
        }
        throw failure("Timed out waiting for StartCircuit to complete");
    }

    private void settle() throws BlazorException {
        long deadline = deadline();
        while (System.nanoTime() < deadline) {
            long slice = Math.min(SETTLE_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            Incoming message;
            try {
                message = incoming.poll(slice, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failure("Interrupted while reading the circuit");
            }
            if (message == null) {
                return;
            }
            consume(message, null);
        }
    }

    private boolean consume(Incoming message, String completionId) throws BlazorException {
        if (message.error != null) {
            throw failure(message.error);
        }
        if (message.data == null) {
            return false;
        }
        if (message.data.length > 0 && message.data[0] == '{') {
            log("<- " + new String(message.data, StandardCharsets.UTF_8).replace('\u001e', ' ').trim());
            return false;
        }
        List<List<Object>> messages = BlazorPack.decode(message.data);
        boolean completed = false;
        for (List<Object> hub : messages) {
            log(BlazorPack.summarize(hub));
            int type = BlazorPack.type(hub);
            if (type == BlazorPack.CLOSE) {
                Object reason = hub.size() > 1 ? hub.get(1) : null;
                throw failure("Server closed the circuit" + (reason == null ? "" : ": " + reason));
            }
            if (type == BlazorPack.COMPLETION && completionId != null && completionId.equals(BlazorPack.invocationId(hub))) {
                int kind = hub.size() > 3 && hub.get(3) instanceof Number number ? number.intValue() : -1;
                if (kind == BlazorPack.ERROR_RESULT) {
                    throw failure("StartCircuit failed: " + (hub.size() > 4 ? hub.get(4) : ""));
                }
                completed = true;
                continue;
            }
            acknowledge(hub);
        }
        return completed;
    }

    private void acknowledge(List<Object> hub) throws BlazorException {
        if (BlazorPack.type(hub) != BlazorPack.INVOCATION) {
            return;
        }
        String target = BlazorPack.target(hub);
        List<Object> arguments = BlazorPack.arguments(hub);
        if ("JS.Error".equals(target)) {
            throw failure(errorText(arguments));
        }
        if ("JS.RenderBatch".equals(target) && !arguments.isEmpty() && arguments.get(0) instanceof Number number) {
            long renderId = number.longValue();
            sendInvocation(null, "OnRenderCompleted", Arrays.asList(renderId, null));
            log("-> OnRenderCompleted " + renderId);
        } else if ("JS.BeginInvokeJS".equals(target) && !arguments.isEmpty()
                && arguments.get(0) instanceof Number number && number.longValue() != 0) {
            long handle = number.longValue();
            String identifier = arguments.size() > 1 && arguments.get(1) instanceof String name ? name : "";
            // Completing localStorage.hasOwnProperty with null ends the circuit, and
            // completing it with false makes a signed-out nav render again forever.
            // The form is already on the page, so this one call stays pending.
            if ("localStorage.hasOwnProperty".equals(identifier)) {
                log("-> pending " + identifier);
            } else {
                sendInvocation(null, "EndInvokeJSFromDotNet",
                        List.of(handle, true, "[" + handle + ",true,null]"));
                log("-> EndInvokeJSFromDotNet " + handle);
            }
        }
    }

    private static String errorText(List<Object> arguments) {
        StringBuilder text = new StringBuilder("Server reported JS.Error");
        for (Object argument : arguments) {
            if (argument instanceof String string && !string.isBlank()) {
                text.append(": ").append(string.length() > 2000 ? string.substring(0, 2000) : string);
                break;
            }
        }
        return text.toString();
    }

    private void sendInvocation(String invocationId, String target, List<Object> arguments) throws BlazorException {
        sendRaw(BlazorPack.invocation(invocationId, target, arguments));
    }

    private void sendRaw(byte[] data) throws BlazorException {
        Session socket = this.webSocket;
        if (socket == null) {
            throw failure("The circuit is not connected");
        }
        awaitSend(callback -> socket.sendBinary(ByteBuffer.wrap(data), callback), "Failed to send on the circuit");
    }

    private void sendText(String text) throws BlazorException {
        Session socket = this.webSocket;
        if (socket == null) {
            throw failure("The circuit is not connected");
        }
        awaitSend(callback -> socket.sendText(text, callback), "Failed to send the handshake");
    }

    private void awaitSend(Consumer<Callback> send, String failure) throws BlazorException {
        CompletableFuture<Void> sent = new CompletableFuture<>();
        send.accept(Callback.from(() -> sent.complete(null), sent::completeExceptionally));
        try {
            sent.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while sending");
        } catch (ExecutionException | TimeoutException ex) {
            throw failure(failure + ": " + ex.getMessage());
        }
    }

    private HttpResult get(URI uri, String accept) throws Exception {
        Request request = request(uri).method(HttpMethod.GET);
        if (accept != null) {
            request.headers(headers -> headers.put(HttpHeader.ACCEPT, accept));
        }
        return exchange(request);
    }

    private HttpResult post(URI uri) throws Exception {
        return exchange(request(uri).method(HttpMethod.POST).body(new BytesRequestContent(new byte[0])));
    }

    private HttpResult exchange(Request request) throws Exception {
        AtomicReference<URI> landed = new AtomicReference<>(request.getURI());
        request.onResponseBegin(response -> landed.set(response.getRequest().getURI()));
        ContentResponse response = new CompletableResponseListener(request, MAX_BODY).send()
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        remember(response.getHeaders());
        URI finalUri = landed.get() == null ? response.getRequest().getURI() : landed.get();
        byte[] content = response.getContent() == null ? new byte[0] : response.getContent();
        if (header(response.getHeaders(), HttpHeader.CONTENT_ENCODING).toLowerCase(Locale.ROOT).contains("gzip")) {
            content = gunzip(content);
        }
        return new HttpResult(response.getStatus(), finalUri, new String(content, StandardCharsets.UTF_8),
                header(response.getHeaders(), "ssr-framing"));
    }

    private Request request(URI uri) {
        Request request = transport.http.newRequest(uri)
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .headers(headers -> headers.put(HttpHeader.ACCEPT_ENCODING, "identity"));
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            request.headers(headers -> headers.put(HttpHeader.COOKIE, cookie));
        }
        return request;
    }

    private void remember(HttpFields headers) {
        for (HttpField field : headers) {
            if (field.getHeader() != HttpHeader.SET_COOKIE) {
                continue;
            }
            String pair = field.getValue().split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals);
            cookies.removeIf(existing -> existing.startsWith(name + "="));
            cookies.add(pair);
        }
    }

    private static String header(HttpFields headers, HttpHeader name) {
        for (HttpField field : headers) {
            if (field.getHeader() == name) {
                return field.getValue() == null ? "" : field.getValue();
            }
        }
        return "";
    }

    private static String header(HttpFields headers, String name) {
        for (HttpField field : headers) {
            if (name.equalsIgnoreCase(field.getName())) {
                return field.getValue() == null ? "" : field.getValue();
            }
        }
        return "";
    }

    private static byte[] gunzip(byte[] content) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(content))) {
            return in.readAllBytes();
        }
    }

    private String cookieHeader() {
        return String.join("; ", cookies);
    }

    private String connectionToken(String body) throws BlazorException {
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw failure("Negotiate response was not a JSON object");
        }
        Object transports = map.get("availableTransports");
        if (transports instanceof List<?> list && !list.isEmpty()) {
            boolean sockets = false;
            for (Object transport : list) {
                if (transport instanceof Map<?, ?> item && "WebSockets".equals(String.valueOf(item.get("transport")))) {
                    sockets = true;
                }
            }
            if (!sockets) {
                throw failure("The server negotiate response did not offer WebSockets");
            }
        }
        Object token = map.get("connectionToken");
        if (token == null) {
            token = map.get("connectionId");
        }
        if (token == null || String.valueOf(token).isBlank()) {
            throw failure("Negotiate response did not include a connection token");
        }
        return String.valueOf(token);
    }

    private URI resolve(String location) {
        URI target = URI.create(location);
        if (target.isAbsolute()) {
            return target;
        }
        String base = pageUri.isEmpty() ? baseUri : pageUri;
        return URI.create(base).resolve(location);
    }

    private static String unframe(HttpResult page) {
        String boundary = page.framing;
        if (boundary.isEmpty() || !page.body.contains(boundary)) {
            return page.body;
        }
        String[] parts = page.body.split(java.util.regex.Pattern.quote(boundary));
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].contains("<")) {
                return parts[i];
            }
        }
        return page.body;
    }

    private void requireHttp(HttpResult result, String what) throws BlazorException {
        if (result.status >= 400) {
            String snippet = result.body.length() > 300 ? result.body.substring(0, 300) : result.body;
            throw failure(what + " request returned HTTP " + result.status + " " + snippet);
        }
    }

    private void ensureOpen() throws BlazorException {
        if (webSocket == null) {
            throw failure("No open Blazor circuit. Run a Connect sampler on this thread first.");
        }
    }

    private void startPings() {
        pings = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "blazor-ping");
            thread.setDaemon(true);
            return thread;
        });
        pings.scheduleAtFixedRate(this::safePing, 15, 15, TimeUnit.SECONDS);
    }

    private void safePing() {
        try {
            synchronized (this) {
                if (webSocket != null) {
                    sendRaw(BlazorPack.ping());
                }
            }
        } catch (Exception ex) {
            LOG.debug("Blazor ping failed: {}", ex.toString());
        }
    }

    private void closeQuietly() {
        if (pings != null) {
            pings.shutdownNow();
            pings = null;
        }
        Session socket = this.webSocket;
        this.webSocket = null;
        JettyStack stack = this.transport;
        this.transport = null;
        if (socket != null) {
            try {
                CompletableFuture<Void> sent = new CompletableFuture<>();
                socket.sendBinary(ByteBuffer.wrap(BlazorPack.close()),
                        Callback.from(() -> sent.complete(null), sent::completeExceptionally));
                sent.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The circuit may already be gone.
            }
            try {
                CompletableFuture<Void> closed = new CompletableFuture<>();
                socket.close(StatusCode.NORMAL, "",
                        Callback.from(() -> closed.complete(null), closed::completeExceptionally));
                closed.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                socket.disconnect();
            }
        }
        if (stack != null) {
            stack.closeIfOwned();
        }
    }

    private Incoming poll(long deadline) throws BlazorException {
        try {
            long wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            return incoming.poll(wait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while reading the circuit");
        }
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private void beginCall() {
        mark = transcript.length();
    }

    private String callText() {
        return transcript.substring(mark);
    }

    private void log(String line) {
        transcript.append(line).append('\n');
        LOG.debug(line);
    }

    private BlazorException failure(String message) {
        return new BlazorException(message, callText());
    }

    private BlazorException failure(Exception ex) {
        if (ex instanceof BlazorException blazor) {
            return blazor;
        }
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return new BlazorException(message, callText(), ex);
    }

    /**
     * Public so Jetty can deliver events. Messages are copied out of Jetty's
     * buffer and queued; the sampler thread is the one that interprets them.
     */
    public static final class CircuitEndpoint implements Session.Listener.AutoDemanding {
        private final LinkedBlockingQueue<Incoming> incoming;

        CircuitEndpoint(LinkedBlockingQueue<Incoming> incoming) {
            this.incoming = incoming;
        }

        @Override
        public void onWebSocketText(String message) {
            incoming.offer(Incoming.data(message.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
            byte[] copy = new byte[payload.remaining()];
            payload.get(copy);
            incoming.offer(Incoming.data(copy));
            callback.succeed();
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            incoming.offer(Incoming.error("WebSocket closed (" + statusCode + " " + reason + ")"));
        }

        @Override
        public void onWebSocketError(Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            incoming.offer(Incoming.error("WebSocket error: " + message));
        }
    }

    private static final class JettyStack {
        private final HttpClient http;
        private final WebSocketClient sockets;
        private final boolean shared;

        private JettyStack(HttpClient http, WebSocketClient sockets, boolean shared) {
            this.http = http;
            this.sockets = sockets;
            this.shared = shared;
        }

        private static JettyStack open(InetSocketAddress proxy) throws Exception {
            if (proxy == null) {
                return shared();
            }
            HttpClient http = newHttpClient();
            http.getProxyConfiguration().addProxy(new HttpProxy(proxy.getHostString(), proxy.getPort()));
            return start(http, false);
        }

        private static JettyStack shared() throws Exception {
            JettyStack existing = sharedStack;
            if (existing != null) {
                return existing;
            }
            synchronized (CLIENTS) {
                if (sharedStack == null) {
                    sharedStack = start(newHttpClient(), true);
                }
                return sharedStack;
            }
        }

        private static HttpClient newHttpClient() {
            QueuedThreadPool executor = new QueuedThreadPool();
            executor.setName("blazor-jetty");
            executor.setDaemon(true);
            HttpClient http = new HttpClient();
            http.setName("blazor-http");
            http.setExecutor(executor);
            http.setConnectTimeout(10_000);
            http.setIdleTimeout(120_000);
            http.setFollowRedirects(true);
            // The shared client would otherwise mix cookies across virtual users.
            http.setHttpCookieStore(new HttpCookieStore.Empty());
            // One long-lived socket per virtual user, plus the short page fetches.
            http.setMaxConnectionsPerDestination(100_000);
            http.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, USER_AGENT));
            return http;
        }

        private static JettyStack start(HttpClient http, boolean sharedClient) throws Exception {
            WebSocketClient sockets = new WebSocketClient(http);
            sockets.setMaxBinaryMessageSize(MAX_BODY);
            sockets.setMaxTextMessageSize(1024 * 1024);
            sockets.setIdleTimeout(Duration.ofSeconds(120));
            sockets.start();
            return new JettyStack(http, sockets, sharedClient);
        }

        private void closeIfOwned() {
            if (shared) {
                return;
            }
            try {
                sockets.stop();
            } catch (Exception ignored) {
                // The session is already finished.
            }
            try {
                if (http.isRunning()) {
                    http.stop();
                }
            } catch (Exception ignored) {
                // Stopping the socket client usually stops this too.
            }
        }
    }

    private static final class Incoming {
        private final byte[] data;
        private final String error;

        private Incoming(byte[] data, String error) {
            this.data = data;
            this.error = error;
        }

        private static Incoming data(byte[] data) {
            return new Incoming(data, null);
        }

        private static Incoming error(String error) {
            return new Incoming(null, error);
        }
    }

    private static final class HttpResult {
        private final int status;
        private final URI finalUri;
        private final String body;
        private final String framing;

        private HttpResult(int status, URI finalUri, String body, String framing) {
            this.status = status;
            this.finalUri = finalUri;
            this.body = body;
            this.framing = framing == null ? "" : framing;
        }
    }

    private static final class ActiveRoot {
        private final String identity;
        private final int ssrComponentId;
        private final int uniqueId;
        private String descriptor;

        private ActiveRoot(String identity, int ssrComponentId, int uniqueId, String descriptor) {
            this.identity = identity;
            this.ssrComponentId = ssrComponentId;
            this.uniqueId = uniqueId;
            this.descriptor = descriptor;
        }
    }
}
