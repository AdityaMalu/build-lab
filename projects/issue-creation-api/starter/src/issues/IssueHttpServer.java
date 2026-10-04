package issues;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IssueHttpServer {

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
        // TODO route POST /issues, GET /issues, GET /issues/{id}, PATCH /issues/{id}
        sendJson(ex, 501, Map.of("error", "not implemented"));
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
