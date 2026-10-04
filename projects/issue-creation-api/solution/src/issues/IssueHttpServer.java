package issues;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IssueHttpServer {

    private static final Pattern ITEM = Pattern.compile("/issues/([^/]+)/?");

    private final IssueService service;
    private HttpServer server;
    private ExecutorService executor;

    public IssueHttpServer(IssueService service) {
        this.service = service;
    }

    /** Provided. Starts listening on 127.0.0.1; port 0 picks a free port. Returns the bound port. */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    /** Provided. */
    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String token = bearer(ex);
            if (path.equals("/issues") || path.equals("/issues/")) {
                switch (method) {
                    case "POST" -> create(ex, token);
                    case "GET" -> list(ex, token);
                    default -> sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
                return;
            }
            Matcher m = ITEM.matcher(path);
            if (m.matches()) {
                long id = parseLong(m.group(1), "issue id");
                switch (method) {
                    case "GET" -> {
                        Issue issue = service.get(token, id);
                        ex.getResponseHeaders().set("ETag", "\"" + issue.version() + "\"");
                        sendJson(ex, 200, issue.toJsonMap());
                    }
                    case "PATCH" -> update(ex, token, id);
                    default -> sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
                return;
            }
            sendJson(ex, 404, Map.of("error", "no such route"));
        } catch (ApiException e) {
            if (e.status() == 401) ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendJson(ex, e.status(), Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", "internal error"));
        }
    }

    private void create(HttpExchange ex, String token) throws IOException {
        service.authenticate(token); // 401 before we bother parsing the body
        Map<String, Object> body = readJson(ex);
        CreateResult result = service.create(token, ex.getRequestHeaders().getFirst("Idempotency-Key"),
                stringField(body, "title"), stringField(body, "body"));
        Issue issue = result.issue();
        ex.getResponseHeaders().set("Location", "/issues/" + issue.id());
        if (result.replayed()) ex.getResponseHeaders().set("Idempotent-Replay", "true");
        sendJson(ex, result.replayed() ? 200 : 201, issue.toJsonMap());
    }

    private void update(HttpExchange ex, String token, long id) throws IOException {
        service.authenticate(token);
        String ifMatch = ex.getRequestHeaders().getFirst("If-Match");
        if (ifMatch == null || ifMatch.isBlank()) throw new ApiException(428, "If-Match header required");
        long expected = parseLong(ifMatch.trim().replace("\"", ""), "If-Match");
        Map<String, Object> body = readJson(ex);
        Issue updated = service.update(token, id, expected, stringField(body, "title"));
        ex.getResponseHeaders().set("ETag", "\"" + updated.version() + "\"");
        sendJson(ex, 200, updated.toJsonMap());
    }

    private void list(HttpExchange ex, String token) throws IOException {
        Map<String, String> q = query(ex);
        int limit = q.containsKey("limit") ? (int) parseLong(q.get("limit"), "limit") : 20;
        Page page = service.list(token, limit, q.get("cursor"));
        List<Object> items = new ArrayList<>();
        for (Issue i : page.items()) items.add(i.toJsonMap());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", page.nextCursor());
        sendJson(ex, 200, out);
    }

    private static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "malformed JSON body");
        }
    }

    private static String stringField(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (v == null) return null;
        if (!(v instanceof String s)) throw new ApiException(400, name + " must be a string");
        return s;
    }

    private static long parseLong(String raw, String what) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid " + what);
        }
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        return h.substring("Bearer ".length()).trim();
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    /** Provided helper. */
    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
