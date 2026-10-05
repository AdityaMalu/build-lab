package issues;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import testkit.Test;

import static testkit.Assert.*;

public class IssueHttpTest {

    interface Body {
        void run(Client c) throws Exception;
    }

    static final class Client {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final String base;

        Client(int port) {
            base = "http://127.0.0.1:" + port;
        }

        HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
            for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static void withServer(Body body) throws Exception {
        IssueService svc = new IssueService(Map.of(
                "w", new User("wendy", Role.WRITER),
                "r", new User("rita", Role.READER)));
        IssueHttpServer server = new IssueHttpServer(svc);
        int port = server.start(0);
        try {
            body.run(new Client(port));
        } finally {
            server.stop();
        }
    }

    static Map<String, Object> json(HttpResponse<String> r) {
        return Json.parseObject(r.body());
    }

    @Test("POST /issues creates (201) with Location; retry replays (200)")
    public void createAndReplay() throws Exception {
        withServer(c -> {
            String payload = "{\"title\":\"Login broken\",\"body\":\"500 on submit\"}";
            HttpResponse<String> r1 = c.send("POST", "/issues", payload,
                    "Authorization", "Bearer w", "Idempotency-Key", "abc", "Content-Type", "application/json");
            assertEquals(201, r1.statusCode(), "create status, body=" + r1.body());
            assertEquals("/issues/1", r1.headers().firstValue("Location").orElse(null));
            assertTrue(r1.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "json content type");
            Map<String, Object> issue = json(r1);
            assertEquals(1L, issue.get("id"));
            assertEquals("Login broken", issue.get("title"));
            assertEquals("wendy", issue.get("author"));
            assertEquals(1L, issue.get("version"));

            HttpResponse<String> r2 = c.send("POST", "/issues", payload,
                    "Authorization", "Bearer w", "Idempotency-Key", "abc");
            assertEquals(200, r2.statusCode(), "replay status");
            assertEquals("true", r2.headers().firstValue("Idempotent-Replay").orElse(null));
            assertEquals(1L, json(r2).get("id"));
        });
    }

    @Test("error statuses: 401, 403, 400, 409")
    public void createErrors() throws Exception {
        withServer(c -> {
            String ok = "{\"title\":\"t\"}";
            assertEquals(401, c.send("POST", "/issues", ok, "Idempotency-Key", "k").statusCode());
            assertEquals(401, c.send("POST", "/issues", ok, "Authorization", "Bearer nope", "Idempotency-Key", "k").statusCode());
            assertEquals(403, c.send("POST", "/issues", ok, "Authorization", "Bearer r", "Idempotency-Key", "k").statusCode());
            assertEquals(400, c.send("POST", "/issues", ok, "Authorization", "Bearer w").statusCode(), "missing key");
            assertEquals(400, c.send("POST", "/issues", "{not json", "Authorization", "Bearer w", "Idempotency-Key", "k").statusCode());
            assertEquals(400, c.send("POST", "/issues", "{\"title\":42}", "Authorization", "Bearer w", "Idempotency-Key", "k").statusCode(),
                    "title must be a string");
            assertEquals(201, c.send("POST", "/issues", ok, "Authorization", "Bearer w", "Idempotency-Key", "k").statusCode());
            HttpResponse<String> conflict = c.send("POST", "/issues", "{\"title\":\"different\"}",
                    "Authorization", "Bearer w", "Idempotency-Key", "k");
            assertEquals(409, conflict.statusCode());
            assertNotNull(json(conflict).get("error"), "error body has an 'error' field");
        });
    }

    @Test("GET /issues/{id} returns ETag; unknown is 404; bad id is 400")
    public void getIssue() throws Exception {
        withServer(c -> {
            c.send("POST", "/issues", "{\"title\":\"t\"}", "Authorization", "Bearer w", "Idempotency-Key", "k");
            HttpResponse<String> r = c.send("GET", "/issues/1", null, "Authorization", "Bearer r");
            assertEquals(200, r.statusCode());
            assertEquals("\"1\"", r.headers().firstValue("ETag").orElse(null));
            assertEquals(404, c.send("GET", "/issues/77", null, "Authorization", "Bearer r").statusCode());
            assertEquals(400, c.send("GET", "/issues/abc", null, "Authorization", "Bearer r").statusCode());
            assertEquals(401, c.send("GET", "/issues/1", null).statusCode());
        });
    }

    @Test("PATCH uses If-Match: 200, then 412 for stale, 428 when missing")
    public void patch() throws Exception {
        withServer(c -> {
            c.send("POST", "/issues", "{\"title\":\"old\"}", "Authorization", "Bearer w", "Idempotency-Key", "k");
            HttpResponse<String> ok = c.send("PATCH", "/issues/1", "{\"title\":\"new\"}",
                    "Authorization", "Bearer w", "If-Match", "\"1\"");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("new", json(ok).get("title"));
            assertEquals(2L, json(ok).get("version"));
            assertEquals(412, c.send("PATCH", "/issues/1", "{\"title\":\"stale\"}",
                    "Authorization", "Bearer w", "If-Match", "\"1\"").statusCode());
            assertEquals(428, c.send("PATCH", "/issues/1", "{\"title\":\"x\"}", "Authorization", "Bearer w").statusCode());
            assertEquals(403, c.send("PATCH", "/issues/1", "{\"title\":\"x\"}",
                    "Authorization", "Bearer r", "If-Match", "\"2\"").statusCode());
        });
    }

    @Test("GET /issues pages with an opaque cursor")
    @SuppressWarnings("unchecked")
    public void listPaging() throws Exception {
        withServer(c -> {
            for (int i = 1; i <= 5; i++) {
                c.send("POST", "/issues", "{\"title\":\"t" + i + "\"}", "Authorization", "Bearer w", "Idempotency-Key", "k" + i);
            }
            HttpResponse<String> p1 = c.send("GET", "/issues?limit=2", null, "Authorization", "Bearer r");
            assertEquals(200, p1.statusCode());
            List<Object> items = (List<Object>) json(p1).get("items");
            assertEquals(2, items.size());
            String cursor = (String) json(p1).get("nextCursor");
            assertNotNull(cursor, "first page has a cursor");
            HttpResponse<String> p2 = c.send("GET", "/issues?limit=10&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8"),
                    null, "Authorization", "Bearer r");
            Map<String, Object> page2 = json(p2);
            assertEquals(3, ((List<Object>) page2.get("items")).size());
            assertTrue(page2.containsKey("nextCursor"), "nextCursor key present");
            assertNull(page2.get("nextCursor"), "end of list");
            List<Object> all = (List<Object>) json(c.send("GET", "/issues", null, "Authorization", "Bearer r")).get("items");
            assertEquals(5, all.size(), "default limit (20) returns all 5");
            assertEquals(400, c.send("GET", "/issues?limit=0", null, "Authorization", "Bearer r").statusCode());
            assertEquals(400, c.send("GET", "/issues?cursor=zzz", null, "Authorization", "Bearer r").statusCode());
        });
    }

    @Test("unknown routes are 404, wrong methods are 405")
    public void routing() throws Exception {
        withServer(c -> {
            assertEquals(404, c.send("GET", "/nothing", null, "Authorization", "Bearer r").statusCode());
            assertEquals(405, c.send("DELETE", "/issues", null, "Authorization", "Bearer w").statusCode());
            assertEquals(405, c.send("PUT", "/issues/1", "{}", "Authorization", "Bearer w").statusCode());
        });
    }
}
