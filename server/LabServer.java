import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Build Lab local server. Pure JDK, no dependencies.
 *
 * Run from the practice-lab folder:   java server/LabServer.java   [port]
 *
 * Layout it expects:
 *   web/                       static UI
 *   testkit/src/               shared mini test framework
 *   projects/catalog.json      project metadata
 *   projects/<id>/README.md    the spec
 *   projects/<id>/starter/src  starting code (copied into workspace on first open)
 *   projects/<id>/tests/src    test suite
 *   projects/<id>/solution/src reference solution
 *   workspace/<id>/src         YOUR code (edited from the UI or your IDE)
 *   workspace/progress.json    status per project
 */
public class LabServer {

    static Path root;
    static Path projects;
    static Path workspace;
    static Path build;

    /**
     * Hosted mode (--hosted or LAB_MODE=hosted): multi-user website. The server keeps no user state;
     * each visitor's code and progress live in their own browser and are sent with every test run.
     * Runs are compiled in temp folders and executed under a restrictive security policy.
     */
    static boolean hosted;
    static final java.util.concurrent.Semaphore RUN_SLOTS = new java.util.concurrent.Semaphore(
            Math.max(1, Integer.parseInt(System.getenv().getOrDefault("LAB_PARALLEL_RUNS", "2"))), true);
    static final Map<String, java.util.ArrayDeque<Long>> RUNS_BY_CLIENT = new java.util.HashMap<>();
    static final int RUNS_PER_WINDOW = 30;
    static final long RATE_WINDOW_MS = 10 * 60 * 1000L;

    /** An error that maps to a specific HTTP status. */
    static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static void main(String[] args) throws Exception {
        root = Paths.get("").toAbsolutePath();
        List<String> rest = new ArrayList<>(List.of(args));
        hosted = "hosted".equalsIgnoreCase(System.getenv("LAB_MODE")) || rest.remove("--hosted");
        // optional: --root <dir>
        int r = rest.indexOf("--root");
        if (r >= 0 && r + 1 < rest.size()) {
            root = Paths.get(rest.get(r + 1)).toAbsolutePath().normalize();
            rest.remove(r + 1);
            rest.remove(r);
        }
        args = rest.toArray(new String[0]);
        if (!Files.isDirectory(root.resolve("projects")) && Files.isDirectory(root.resolve("../projects"))) {
            root = root.resolve("..").normalize();
        } else if (!Files.isDirectory(root.resolve("projects")) && Files.isDirectory(root.resolve("practice-lab/projects"))) {
            root = root.resolve("practice-lab");
        }
        projects = root.resolve("projects");
        workspace = root.resolve("workspace");
        build = root.resolve("build");
        if (!Files.exists(projects.resolve("catalog.json"))) {
            System.err.println("Run this from the practice-lab folder: java server/LabServer.java");
            System.exit(1);
        }
        if (!hosted) Files.createDirectories(workspace);
        if (hosted) build = Files.createTempDirectory("buildlab-");

        if (args.length >= 2 && args[0].equals("test")) {
            cli(args[1], args.length >= 3 ? args[2] : "workspace");
            return;
        }

        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        String bind = hosted ? "0.0.0.0" : "127.0.0.1";
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/api/", LabServer::api);
        server.createContext("/", LabServer::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        kitDir(); // compile the test kit once up front

        String url = "http://localhost:" + port + "/";
        System.out.println("Build Lab (" + (hosted ? "hosted" : "local") + " mode) running at " + url + "  (Ctrl+C to stop)");
        if (!hosted) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ignored) {
                // headless or no browser; the URL is printed above
            }
        }
    }

    // ------------------------------------------------------------------ routing

    static void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex);
            String[] parts = path.substring("/api/".length()).split("/");

            if (path.equals("/api/config") && method.equals("GET")) {
                send(ex, 200, "application/json", "{\"mode\":\"" + (hosted ? "hosted" : "local") + "\"}");
            } else if (path.equals("/api/projects") && method.equals("GET")) {
                send(ex, 200, "application/json", catalogWithProgress());
            } else if (parts[0].equals("tests") && parts.length == 2 && method.equals("GET")) {
                send(ex, 200, "application/json", testsJson(id(parts[1])));
            } else if (parts[0].equals("project") && parts.length == 2 && method.equals("GET")) {
                send(ex, 200, "application/json", projectDetail(id(parts[1])));
            } else if (hosted && parts[0].equals("file") && method.equals("GET")) {
                String area = q.getOrDefault("area", "starter");
                if (!area.matches("starter|tests|solution")) throw new IllegalArgumentException("bad area");
                Path file = resolveFile(id(q.get("project")), area, q.get("path"));
                send(ex, 200, "text/plain; charset=utf-8", Files.readString(file));
            } else if (hosted && parts[0].equals("run") && parts.length == 2 && method.equals("POST")) {
                String pid = id(parts[1]);
                send(ex, 200, "application/json",
                        hostedRun(pid, q.getOrDefault("mode", "workspace"), selection(pid, q.get("tests")), ex));
            } else if (hosted) {
                send(ex, 404, "application/json", "{\"error\":\"not available on the hosted site\"}");
            } else if (parts[0].equals("file") && method.equals("GET")) {
                Path file = resolveFile(id(q.get("project")), q.getOrDefault("area", "workspace"), q.get("path"));
                send(ex, 200, "text/plain; charset=utf-8", Files.readString(file));
            } else if (parts[0].equals("file") && method.equals("PUT")) {
                Path file = resolveFile(id(q.get("project")), "workspace", q.get("path"));
                Files.createDirectories(file.getParent());
                Files.write(file, ex.getRequestBody().readAllBytes());
                send(ex, 200, "application/json", "{\"ok\":true}");
            } else if (parts[0].equals("run") && parts.length == 2 && method.equals("POST")) {
                String mode = q.getOrDefault("mode", "workspace");
                String pid = id(parts[1]);
                send(ex, 200, "application/json", runTests(pid, mode, selection(pid, q.get("tests"))));
            } else if (parts[0].equals("reset") && parts.length == 2 && method.equals("POST")) {
                String id = id(parts[1]);
                deleteTree(workspace.resolve(id));
                ensureWorkspace(id);
                send(ex, 200, "application/json", "{\"ok\":true}");
            } else if (parts[0].equals("status") && parts.length == 2 && method.equals("POST")) {
                String status = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                setProgress(id(parts[1]), status);
                send(ex, 200, "application/json", "{\"ok\":true}");
            } else {
                send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } catch (HttpError e) {
            send(ex, e.status, "application/json", "{\"error\":" + json(e.getMessage()) + "}");
        } catch (IllegalArgumentException | SecurityException e) {
            send(ex, 400, "application/json", "{\"error\":" + json(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", "{\"error\":" + json(String.valueOf(e)) + "}");
        }
    }

    static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path web = root.resolve("web");
        Path file = web.resolve(path.substring(1)).normalize();
        if (!file.startsWith(web) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ handlers

    static String catalogWithProgress() throws IOException {
        String catalog = Files.readString(projects.resolve("catalog.json"));
        String progress = !hosted && Files.exists(progressFile()) ? Files.readString(progressFile()) : "{}";
        return "{\"projects\":" + catalog + ",\"progress\":" + progress + "}";
    }

    static String projectDetail(String id) throws IOException {
        Path dir = projects.resolve(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("unknown project " + id);
        if (!hosted) ensureWorkspace(id);
        String readme = Files.readString(dir.resolve("README.md"));
        Path editable = hosted ? dir.resolve("starter/src") : workspace.resolve(id).resolve("src");
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(json(id)).append(',');
        sb.append("\"readme\":").append(json(readme)).append(',');
        sb.append("\"workspace\":").append(jsonList(listJava(editable))).append(',');
        sb.append("\"tests\":").append(jsonList(listJava(dir.resolve("tests/src")))).append(',');
        sb.append("\"solution\":").append(jsonList(listJava(dir.resolve("solution/src")))).append(',');
        sb.append("\"workspacePath\":").append(hosted ? "null" : json(editable.toString()));
        sb.append('}');
        return sb.toString();
    }

    /** CLI: java server/LabServer.java test <project-id> [workspace|solution|starter] */
    static void cli(String id, String mode) throws Exception {
        String json = runTests(id(id), mode);
        boolean compileFailed = json.startsWith("{\"phase\":\"compile\"");
        java.util.regex.Matcher out = java.util.regex.Pattern.compile("\"output\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        String output = out.find() ? unjson(out.group(1)) : "";
        if (compileFailed) {
            System.out.println("COMPILATION FAILED\n" + output);
            System.exit(2);
        }
        java.util.regex.Matcher row = java.util.regex.Pattern.compile(
                "\\{\"status\":\"(PASS|FAIL)\",\"suite\":\"(?:[^\"\\\\]|\\\\.)*\",\"method\":\"(?:[^\"\\\\]|\\\\.)*\",\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"millis\":\"(\\d+)\",\"message\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}")
                .matcher(json);
        int failed = 0, total = 0;
        while (row.find()) {
            total++;
            boolean pass = row.group(1).equals("PASS");
            if (!pass) failed++;
            System.out.println((pass ? "  PASS  " : "  FAIL  ") + unjson(row.group(2)) + "  (" + row.group(3) + " ms)");
            if (!pass) System.out.println("        " + unjson(row.group(4)));
        }
        if (!output.isBlank()) System.out.println(output);
        System.out.println();
        System.out.println(failed == 0 && total > 0 ? "ALL " + total + " TESTS PASSED" : failed + " of " + total + " FAILED");
        System.exit(failed == 0 && total > 0 ? 0 : 1);
    }

    static String unjson(String s) {
        return s.replace("\\n", "\n").replace("\\r", "").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String runTests(String id, String mode) throws Exception {
        return runTests(id, mode, null);
    }

    static String runTests(String id, String mode, List<String> only) throws Exception {
        Path dir = projects.resolve(id);
        Path src = switch (mode) {
            case "solution" -> dir.resolve("solution/src");
            case "starter" -> dir.resolve("starter/src");
            default -> {
                ensureWorkspace(id);
                yield workspace.resolve(id).resolve("src");
            }
        };
        Suite s = runSuite(id, src, build.resolve(id).resolve(mode), hosted, only);
        if (!hosted && mode.equals("workspace") && only == null) { // a partial run proves nothing about "solved"
            if (s.ok) setProgress(id, "solved");
            else setProgressIfEmpty(id, "attempted");
        }
        return s.json;
    }

    record Suite(String json, boolean ok) {}

    /** Fully qualified names of the project's test classes. */
    static List<String> testClasses(String id) throws IOException {
        Path tests = projects.resolve(id).resolve("tests/src");
        List<String> out = new ArrayList<>();
        for (Path p : listJavaAbs(tests)) {
            String rel = tests.relativize(p).toString().replace('\\', '/');
            if (rel.endsWith("Test.java")) out.add(rel.substring(0, rel.length() - 5).replace('/', '.'));
        }
        return out;
    }

    /**
     * Parses ?tests=SuiteTest#method,SuiteTest#other into runner arguments (pkg.SuiteTest#method).
     * Returns null for "run everything".
     */
    static List<String> selection(String id, String raw) throws IOException {
        if (raw == null || raw.isBlank()) return null;
        Map<String, String> bySimpleName = new LinkedHashMap<>();
        for (String fqn : testClasses(id)) bySimpleName.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (!t.matches("[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("bad test id " + t);
            String fqn = bySimpleName.get(t.substring(0, t.indexOf('#')));
            if (fqn == null) throw new IllegalArgumentException("unknown test suite in " + t);
            out.add(fqn + t.substring(t.indexOf('#')));
        }
        if (out.isEmpty()) return null;
        if (out.size() > 500) throw new IllegalArgumentException("too many tests selected");
        return out;
    }

    static final java.util.regex.Pattern TEST_ANNOTATION = java.util.regex.Pattern.compile(
            "@Test\\b(?:\\s*\\(\\s*(?:value\\s*=\\s*)?(?:\"((?:[^\"\\\\]|\\\\.)*)\")?[^)]*\\))?");
    static final java.util.regex.Pattern METHOD_DECL = java.util.regex.Pattern.compile("void\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    /** All tests in source order, read from the test files: [{suite, method, name, file, line}]. */
    static String testsJson(String id) throws IOException {
        Path tests = projects.resolve(id).resolve("tests/src");
        if (!Files.isDirectory(tests)) throw new IllegalArgumentException("unknown project " + id);
        StringBuilder sb = new StringBuilder("[");
        for (Path p : listJavaAbs(tests)) {
            String rel = tests.relativize(p).toString().replace('\\', '/');
            if (!rel.endsWith("Test.java")) continue;
            String suite = p.getFileName().toString().replace(".java", "");
            String text = Files.readString(p);
            java.util.regex.Matcher a = TEST_ANNOTATION.matcher(text);
            while (a.find()) {
                java.util.regex.Matcher m = METHOD_DECL.matcher(text);
                if (!m.find(a.end())) break;
                String method = m.group(1);
                String name = a.group(1) == null ? method : unjson(a.group(1));
                int line = 1;
                for (int i = 0; i < m.start(); i++) if (text.charAt(i) == '\n') line++;
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"suite\":").append(json(suite))
                        .append(",\"method\":").append(json(method))
                        .append(",\"name\":").append(json(name))
                        .append(",\"file\":").append(json(rel))
                        .append(",\"line\":").append(line).append('}');
            }
        }
        return sb.append(']').toString();
    }

    /** Hosted: the visitor's files arrive in the request body; nothing is kept afterwards. */
    static String hostedRun(String id, String mode, List<String> only, HttpExchange ex) throws Exception {
        if (!Files.isDirectory(projects.resolve(id))) throw new IllegalArgumentException("unknown project " + id);
        Map<String, String> files = mode.equals("solution") ? Map.of() : parseFlatJson(readBody(ex, 1_000_000));
        if (!mode.equals("solution")) {
            if (files.isEmpty() || files.size() > 30) throw new IllegalArgumentException("send 1..30 files");
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (!e.getKey().matches("[a-z][a-z0-9]*(/[A-Za-z_][A-Za-z0-9_]*)*\\.java")) {
                    throw new IllegalArgumentException("bad file name " + e.getKey());
                }
                if (e.getValue().length() > 100_000) throw new IllegalArgumentException("file too large: " + e.getKey());
            }
        }
        checkRateLimit(clientKey(ex));
        if (!RUN_SLOTS.tryAcquire(30, TimeUnit.SECONDS)) throw new HttpError(503, "The server is busy, try again in a moment.");
        Path tmp = Files.createTempDirectory(build, "run-");
        try {
            Path src;
            if (mode.equals("solution")) {
                src = projects.resolve(id).resolve("solution/src");
            } else {
                src = tmp.resolve("src");
                for (Map.Entry<String, String> e : files.entrySet()) {
                    Path f = src.resolve(e.getKey()).normalize();
                    if (!f.startsWith(src)) throw new IllegalArgumentException("bad path");
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, e.getValue());
                }
            }
            return runSuite(id, src, tmp.resolve("out"), true, only).json;
        } finally {
            RUN_SLOTS.release();
            try {
                deleteTree(tmp);
            } catch (IOException ignored) {
                // best effort; the OS temp cleaner gets the rest
            }
        }
    }

    /**
     * Compiles in three separate output roots so the security policy can tell them apart:
     *   kit   (our test framework)  -> trusted
     *   tests (our test suites)     -> trusted
     *   user  (code being tested)   -> sandboxed when {@code sandbox} is true
     * The run classpath puts kit and tests first, so user code can't shadow them.
     */
    static Suite runSuite(String id, Path src, Path out, boolean sandbox, List<String> only) throws Exception {
        Path tests = projects.resolve(id).resolve("tests/src");
        deleteTree(out);
        Path user = out.resolve("user");
        Path testOut = out.resolve("tests");
        Files.createDirectories(user);
        long t0 = System.currentTimeMillis();

        List<Path> userSources = listJavaAbs(src);
        String err = userSources.isEmpty() ? "No .java files to compile." : compile(userSources, user, user.toString());
        if (err == null) err = compile(listJavaAbs(tests), testOut, kitDir() + File.pathSeparator + user);
        if (err != null) {
            return new Suite("{\"phase\":\"compile\",\"ok\":false,\"output\":" + json(clean(err, src, tests))
                    + ",\"results\":[],\"passed\":0,\"failed\":0,\"total\":0,\"millis\":"
                    + (System.currentTimeMillis() - t0) + "}", false);
        }

        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String java = Paths.get(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString();
        String cp = kitDir() + File.pathSeparator + testOut + File.pathSeparator + user;
        String heap = sandbox ? System.getenv().getOrDefault("LAB_TEST_HEAP", "256m") : "512m";
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + heap, "-Xss1m", "-cp", cp));
        int timeout = 180;
        if (sandbox) {
            Path tmp = out.resolve("tmp");
            Files.createDirectories(tmp);
            Path policy = out.resolve("sandbox.policy");
            Files.writeString(policy, policy(kitDir(), testOut, user, tmp));
            cmd.add("-XX:ActiveProcessorCount=2");
            cmd.add("-Djava.security.manager=default");
            cmd.add("-Djava.security.policy==" + policy);
            cmd.add("-Djava.io.tmpdir=" + tmp);
            timeout = 90;
        }
        cmd.add("testkit.TestRunner");
        if (only != null) cmd.addAll(only);
        else cmd.addAll(testClasses(id));
        Proc run = exec(cmd, timeout);

        StringBuilder results = new StringBuilder("[");
        StringBuilder other = new StringBuilder();
        int passed = 0, failed = 0, total = 0;
        for (String line : run.output.split("\\R")) {
            String[] f = line.split("\\|", 6);
            if ((f[0].equals("PASS") || f[0].equals("FAIL")) && f.length >= 5) {
                if (results.length() > 1) results.append(',');
                results.append("{\"status\":").append(json(f[0]))
                        .append(",\"suite\":").append(json(f[1]))
                        .append(",\"method\":").append(json(f[2]))
                        .append(",\"name\":").append(json(f[3]))
                        .append(",\"millis\":").append(json(f[4]))
                        .append(",\"message\":").append(json(f.length > 5 ? f[5] : ""))
                        .append('}');
            } else if (f[0].equals("RESULT") && f.length >= 4) {
                passed = Integer.parseInt(f[1]);
                failed = Integer.parseInt(f[2]);
                total = Integer.parseInt(f[3]);
            } else if (!line.isBlank() && !line.startsWith("WARNING:")) { // JDK's security-manager deprecation notice
                other.append(line).append('\n');
            }
        }
        results.append(']');
        if (run.timedOut) other.append("Test process killed after ").append(timeout).append("s.\n");
        else if (total == 0) other.append("The test process ended before reporting results (did the code call System.exit or crash the JVM?).\n");
        boolean ok = total > 0 && failed == 0 && !run.timedOut;
        return new Suite("{\"phase\":\"test\",\"ok\":" + ok + ",\"partial\":" + (only != null)
                + ",\"output\":" + json(other.toString())
                + ",\"results\":" + results + ",\"passed\":" + passed + ",\"failed\":" + failed
                + ",\"total\":" + total + ",\"millis\":" + (System.currentTimeMillis() - t0) + "}", ok);
    }

    /** User code may touch only its temp folder, read properties, manage its own threads and use localhost sockets. */
    static String policy(Path kit, Path tests, Path user, Path tmp) {
        String t = tmp.toString().replace("\\", "\\\\");
        return "grant codeBase \"" + kit.toUri() + "-\" { permission java.security.AllPermission; };\n"
                + "grant codeBase \"" + tests.toUri() + "-\" { permission java.security.AllPermission; };\n"
                + "grant codeBase \"" + user.toUri() + "-\" {\n"
                + "  permission java.io.FilePermission \"" + t + "\", \"read,write\";\n"
                + "  permission java.io.FilePermission \"" + t + "${/}-\", \"read,write,delete\";\n"
                + "  permission java.util.PropertyPermission \"*\", \"read\";\n"
                + "  permission java.lang.RuntimePermission \"modifyThread\";\n"
                + "  permission java.lang.RuntimePermission \"modifyThreadGroup\";\n"
                + "  permission java.net.SocketPermission \"localhost:0\", \"listen,resolve\";\n"
                + "  permission java.net.SocketPermission \"localhost:1024-\", \"accept,connect,listen,resolve\";\n"
                + "  permission java.net.SocketPermission \"127.0.0.1:1024-\", \"accept,connect,listen,resolve\";\n"
                + "};\n";
    }

    static Path kitPath;

    /** The shared test kit, compiled once per server run. */
    static synchronized Path kitDir() throws IOException {
        if (kitPath != null) return kitPath;
        Path out = build.resolve("_kit");
        deleteTree(out);
        String err = compile(listJavaAbs(root.resolve("testkit/src")), out, null);
        if (err != null) throw new IllegalStateException("test kit failed to compile:\n" + err);
        kitPath = out;
        return out;
    }

    /** In-process javac. Annotation processing is off, so compiling never runs submitted code. */
    static String compile(List<Path> sources, Path out, String classpath) throws IOException {
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("A JDK is required (javac not found). Install JDK 17+.");
        Files.createDirectories(out);
        List<String> opts = new ArrayList<>(List.of("-encoding", "UTF-8", "-nowarn", "-proc:none", "-implicit:class",
                "-d", out.toString()));
        opts.add("-cp");
        opts.add(classpath == null ? out.toString() : classpath);
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diags = new javax.tools.DiagnosticCollector<>();
        try (javax.tools.StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            boolean ok = jc.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(sources)).call();
            if (ok) return null;
        }
        StringBuilder sb = new StringBuilder();
        for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR) continue;
            String file = d.getSource() == null ? "" : Paths.get(d.getSource().toUri()).toString();
            sb.append(file).append(':').append(d.getLineNumber()).append(": error: ")
                    .append(d.getMessage(java.util.Locale.ENGLISH)).append('\n');
        }
        return sb.length() == 0 ? "compilation failed" : sb.toString();
    }

    static String clientKey(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For"); // set by the hosting proxy
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static void checkRateLimit(String client) {
        long now = System.currentTimeMillis();
        synchronized (RUNS_BY_CLIENT) {
            if (RUNS_BY_CLIENT.size() > 10_000) RUNS_BY_CLIENT.clear(); // crude memory bound
            java.util.ArrayDeque<Long> q = RUNS_BY_CLIENT.computeIfAbsent(client, k -> new java.util.ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() <= now - RATE_WINDOW_MS) q.pollFirst();
            if (q.size() >= RUNS_PER_WINDOW) {
                throw new HttpError(429, "Too many test runs. Limit is " + RUNS_PER_WINDOW + " per 10 minutes.");
            }
            q.addLast(now);
        }
    }

    static String readBody(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) throw new HttpError(413, "request too large");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Parses a flat JSON object of string values: {"a/B.java": "source", ...} */
    static Map<String, String> parseFlatJson(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int[] i = {0};
        skipWs(s, i);
        expect(s, i, '{');
        skipWs(s, i);
        if (i[0] < s.length() && s.charAt(i[0]) == '}') return out;
        while (true) {
            skipWs(s, i);
            String key = jsonString(s, i);
            skipWs(s, i);
            expect(s, i, ':');
            skipWs(s, i);
            out.put(key, jsonString(s, i));
            skipWs(s, i);
            if (i[0] < s.length() && s.charAt(i[0]) == ',') {
                i[0]++;
                continue;
            }
            expect(s, i, '}');
            return out;
        }
    }

    static void skipWs(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
    }

    static void expect(String s, int[] i, char c) {
        if (i[0] >= s.length() || s.charAt(i[0]) != c) throw new IllegalArgumentException("malformed JSON body");
        i[0]++;
    }

    static String jsonString(String s, int[] i) {
        expect(s, i, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i[0] >= s.length()) throw new IllegalArgumentException("malformed JSON body");
            char c = s.charAt(i[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i[0] >= s.length()) throw new IllegalArgumentException("malformed JSON body");
            char e = s.charAt(i[0]++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i[0] + 4 > s.length()) throw new IllegalArgumentException("malformed JSON body");
                    sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16));
                    i[0] += 4;
                }
                default -> sb.append(e); // \" \\ \/
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    record Proc(int code, String output, boolean timedOut) {}

    static Proc exec(List<String> cmd, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            } catch (IOException ignored) {
            }
        });
        pump.start();
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        pump.join(2000);
        String text = buf.toString(StandardCharsets.UTF_8);
        if (text.length() > 200_000) text = text.substring(0, 200_000) + "\n... output truncated ...";
        return new Proc(done ? p.exitValue() : -1, text, !done);
    }

    static String clean(String output, Path src, Path tests) {
        return output.replace(src.toString() + java.io.File.separator, "")
                .replace(tests.toString() + java.io.File.separator, "[tests] ");
    }

    static void ensureWorkspace(String id) throws IOException {
        Path target = workspace.resolve(id).resolve("src");
        if (Files.isDirectory(target)) return;
        Path starter = projects.resolve(id).resolve("starter/src");
        Files.createDirectories(target);
        try (Stream<Path> s = Files.walk(starter)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = target.resolve(starter.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static Path resolveFile(String id, String area, String rel) {
        if (rel == null || rel.isBlank()) throw new IllegalArgumentException("path required");
        Path base = switch (area) {
            case "workspace" -> workspace.resolve(id).resolve("src");
            case "starter" -> projects.resolve(id).resolve("starter/src");
            case "tests" -> projects.resolve(id).resolve("tests/src");
            case "solution" -> projects.resolve(id).resolve("solution/src");
            default -> throw new IllegalArgumentException("bad area");
        };
        Path file = base.resolve(rel).normalize();
        if (!file.startsWith(base) || !file.toString().endsWith(".java")) {
            throw new SecurityException("path outside project");
        }
        return file;
    }

    static List<String> listJava(Path base) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : listJavaAbs(base)) out.add(base.relativize(p).toString().replace('\\', '/'));
        return out;
    }

    static List<Path> listJavaAbs(Path base) throws IOException {
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static String id(String raw) {
        if (raw == null || !raw.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("bad project id");
        return raw;
    }

    static Path progressFile() {
        return workspace.resolve("progress.json");
    }

    static synchronized Map<String, String> readProgress() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(progressFile())) return map;
        String text = Files.readString(progressFile());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([a-z0-9-]+)\"\\s*:\\s*\"([a-z]+)\"").matcher(text);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    static synchronized void setProgress(String id, String status) throws IOException {
        if (!status.matches("todo|attempted|solved")) throw new IllegalArgumentException("bad status");
        Map<String, String> map = readProgress();
        if (status.equals("todo")) map.remove(id);
        else map.put(id, status);
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append(json(k)).append(':').append(json(v));
        });
        sb.append('}');
        Files.writeString(progressFile(), sb);
    }

    static synchronized void setProgressIfEmpty(String id, String status) throws IOException {
        if (!readProgress().containsKey(id)) setProgress(id, status);
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static String jsonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : items) {
            if (sb.length() > 1) sb.append(',');
            sb.append(json(s));
        }
        return sb.append(']').toString();
    }

    static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
