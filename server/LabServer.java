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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build Lab server. Pure JDK, no dependencies.
 *
 * Run from the practice-lab folder:   java server/LabServer.java [--hosted] [--root dir] [port]
 * CLI:                                java server/LabServer.java test <project> [workspace|solution|starter] [java|python|go|cpp]
 *
 * Layout:
 *   web/                                  UI
 *   testkit/<lang>/                       per-language test harness
 *   projects/catalog.json                 project metadata
 *   projects/<id>/README.md               spec (Java flavoured; also the fallback)
 *   projects/<id>/<lang>/README.md        language-specific spec (optional)
 *   projects/<id>/<lang>/{starter,solution,tests}/src
 *   workspace/<id>/<lang>/src             YOUR code (local mode)
 *   workspace/progress.json               status per "lang:project" (local mode)
 */
public class LabServer {

    static final List<String> LANGS = List.of("java", "python", "go", "cpp");

    static Path root;
    static Path projects;
    static Path workspace;
    static Path build;
    static boolean win = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * Hosted mode (--hosted or LAB_MODE=hosted): multi-user website. The server keeps no user state;
     * each visitor's code and progress live in their own browser and are sent with every test run.
     * Runs are compiled in temp folders and executed as an unprivileged user with resource limits
     * (and Java's security manager / a seccomp filter installed by the harness).
     */
    static boolean hosted;

    /**
     * all (default): one process serves the site and runs tests (local mode, Render).
     * api: serves the site and queues hosted runs in Redis; worker: takes runs from Redis and executes them,
     * each in a fresh gVisor container. See docs/SCALING_PLAN.md (phase 2) and deploy/oracle.
     */
    static String role = "all";
    static final int PARALLEL_RUNS = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("LAB_PARALLEL_RUNS", "2")));
    static final java.util.concurrent.Semaphore RUN_SLOTS = new java.util.concurrent.Semaphore(PARALLEL_RUNS, true);
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
        int r = rest.indexOf("--root");
        if (r >= 0 && r + 1 < rest.size()) {
            root = Paths.get(rest.get(r + 1)).toAbsolutePath().normalize();
            rest.remove(r + 1);
            rest.remove(r);
        }
        int ro = rest.indexOf("--role");
        role = System.getenv().getOrDefault("LAB_ROLE", "all");
        if (ro >= 0 && ro + 1 < rest.size()) {
            role = rest.get(ro + 1);
            rest.remove(ro + 1);
            rest.remove(ro);
        }
        if (!role.matches("all|api|worker")) throw new IllegalArgumentException("--role must be all, api or worker");
        boolean runJob = rest.size() >= 2 && rest.get(0).equals("run-job");
        if (!role.equals("all") || runJob) hosted = true; // the fleet only exists for the public site
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
        if (hosted) {
            build = Files.createTempDirectory("buildlab-");
            openPermissions(build);
        } else {
            Files.createDirectories(workspace);
            migrateWorkspace();
        }

        if (args.length >= 2 && args[0].equals("test")) {
            cli(args[1], args.length >= 3 ? args[2] : "workspace", args.length >= 4 ? args[3] : "java");
            return;
        }
        if (runJob) { // inside a per-run gVisor container started by a worker
            runJobInContainer(Paths.get(args[1]));
            return;
        }
        if (role.equals("worker")) {
            startWorker();
            return;
        }
        if (role.equals("api")) redisUri(); // fail fast without LAB_REDIS_URL

        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        String bind = hosted ? "0.0.0.0" : "127.0.0.1";
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/api/", LabServer::api);
        server.createContext("/metrics", LabServer::metrics);
        server.createContext("/", LabServer::staticFile);
        startLogShipper();
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        kitDir(); // compile the Java test kit once up front

        String url = "http://localhost:" + port + "/";
        System.out.println("Build Lab (" + (hosted ? "hosted" : "local") + " mode) running at " + url + "  (Ctrl+C to stop)");
        System.out.println("Languages available here: " + availableLangs());
        if (hosted && !sandboxReady()) {
            System.out.println("WARNING: hosted mode without the OS sandbox (needs Linux, root, prlimit and setpriv).");
        }
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
                send(ex, 200, "application/json", "{\"mode\":\"" + (hosted ? "hosted" : "local") + "\",\"langs\":"
                        + jsonList(availableLangs()) + "}");
            } else if (path.equals("/api/projects") && method.equals("GET")) {
                send(ex, 200, "application/json", catalogWithProgress());
            } else if (parts[0].equals("project") && parts.length == 2 && method.equals("GET")) {
                send(ex, 200, "application/json", projectDetail(id(parts[1]), lang(q.get("lang"))));
            } else if (parts[0].equals("tests") && parts.length == 2 && method.equals("GET")) {
                send(ex, 200, "application/json", testsJson(id(parts[1]), lang(q.get("lang"))));
            } else if (parts[0].equals("file") && method.equals("GET")) {
                String area = q.getOrDefault("area", hosted ? "starter" : "workspace");
                if (hosted && area.equals("workspace")) throw new IllegalArgumentException("no workspace on the hosted site");
                Path file = resolveFile(id(q.get("project")), lang(q.get("lang")), area, q.get("path"));
                send(ex, 200, "text/plain; charset=utf-8", Files.readString(file));
            } else if (parts[0].equals("run") && parts.length == 2 && method.equals("POST")) {
                String pid = id(parts[1]);
                String lang = lang(q.get("lang"));
                List<String[]> only = selection(pid, lang, q.get("tests"));
                String mode = q.getOrDefault("mode", "workspace");
                send(ex, 200, "application/json", observedRun(pid, lang, mode, only,
                        () -> hosted ? hostedRun(pid, lang, mode, only, ex) : runTests(pid, lang, mode, only)));
            } else if (hosted) {
                send(ex, 404, "application/json", "{\"error\":\"not available on the hosted site\"}");
            } else if (parts[0].equals("file") && method.equals("PUT")) {
                Path file = resolveFile(id(q.get("project")), lang(q.get("lang")), "workspace", q.get("path"));
                Files.createDirectories(file.getParent());
                Files.write(file, ex.getRequestBody().readAllBytes());
                send(ex, 200, "application/json", "{\"ok\":true}");
            } else if (parts[0].equals("reset") && parts.length == 2 && method.equals("POST")) {
                String id = id(parts[1]);
                String lang = lang(q.get("lang"));
                deleteTree(workspace.resolve(id).resolve(lang));
                ensureWorkspace(id, lang);
                send(ex, 200, "application/json", "{\"ok\":true}");
            } else if (parts[0].equals("status") && parts.length == 2 && method.equals("POST")) {
                String status = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                setProgress(lang(q.get("lang")) + ":" + id(parts[1]), status);
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

    // ------------------------------------------------------------------ languages

    static String lang(String raw) {
        if (raw == null || raw.isBlank()) return "java";
        if (!LANGS.contains(raw)) throw new IllegalArgumentException("unknown language " + raw);
        return raw;
    }

    static Path langRoot(String id, String lang) {
        return projects.resolve(id).resolve(lang);
    }

    static List<String> langsOf(String id) {
        List<String> out = new ArrayList<>();
        for (String l : LANGS) if (Files.isDirectory(langRoot(id, l).resolve("starter/src"))) out.add(l);
        return out;
    }

    /** Languages whose toolchain is installed where this server runs. */
    static List<String> availableLangs() {
        List<String> out = new ArrayList<>(List.of("java"));
        if (tool("python") != null) out.add("python");
        if (tool("go") != null) out.add("go");
        if (tool("g++") != null) out.add("cpp");
        return out;
    }

    static boolean editable(String lang, String name) {
        return switch (lang) {
            case "java" -> name.endsWith(".java");
            case "python" -> name.endsWith(".py");
            case "go" -> name.endsWith(".go");
            case "cpp" -> name.endsWith(".cpp") || name.endsWith(".hpp") || name.endsWith(".h");
            default -> false;
        };
    }

    /** Valid file names for code sent to the hosted site. */
    static boolean validUploadName(String lang, String name) {
        return switch (lang) {
            case "java" -> name.matches("[a-z][a-z0-9]*(/[A-Za-z_][A-Za-z0-9_]*)*\\.java");
            case "python" -> name.matches("[a-z_][a-z0-9_]*(/[a-z_][a-z0-9_]*)*\\.py");
            case "go" -> name.matches("[a-z][a-z0-9_]*/[a-z][a-z0-9_]*\\.go") && !name.endsWith("_test.go");
            case "cpp" -> name.matches("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*\\.(cpp|hpp|h)");
            default -> false;
        };
    }

    static final Map<String, Path> TOOLS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Finds a toolchain executable: env override, PATH, then common install locations. Null if missing. */
    static Path tool(String name) {
        Path cached = TOOLS.get(name);
        if (cached != null) return cached;
        Path found = findTool(name);
        if (found != null) TOOLS.put(name, found);
        return found;
    }

    static Path findTool(String name) {
        String envKey = switch (name) {
            case "python" -> "LAB_PYTHON";
            case "go" -> "LAB_GO";
            case "g++" -> "LAB_GXX";
            default -> "LAB_" + name.toUpperCase();
        };
        String override = System.getenv(envKey);
        if (override != null && Files.isRegularFile(Paths.get(override))) return Paths.get(override);

        List<String> names = name.equals("python") ? List.of("python3", "python") : List.of(name);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String n : names) {
                    Path p = Paths.get(dir.isEmpty() ? "." : dir, win ? n + ".exe" : n);
                    // skip the Microsoft Store "python" stub
                    if (Files.isRegularFile(p) && !p.toString().contains("WindowsApps")) return p;
                }
            }
        }
        if (win) {
            String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
            List<Path> guesses = new ArrayList<>();
            switch (name) {
                case "python" -> {
                    for (String v : List.of("Python313", "Python312", "Python311")) {
                        guesses.add(Paths.get(local, "Programs", "Python", v, "python.exe"));
                        guesses.add(Paths.get("C:\\Program Files", v, "python.exe"));
                    }
                }
                case "go" -> guesses.add(Paths.get("C:\\Program Files\\Go\\bin\\go.exe"));
                case "g++" -> {
                    Path pkgs = Paths.get(local, "Microsoft", "WinGet", "Packages");
                    if (Files.isDirectory(pkgs)) {
                        try (Stream<Path> s = Files.list(pkgs)) {
                            s.filter(p -> p.getFileName().toString().startsWith("BrechtSanders.WinLibs"))
                                    .forEach(p -> guesses.add(p.resolve("mingw64/bin/g++.exe")));
                        } catch (IOException ignored) {
                        }
                    }
                }
                default -> {
                }
            }
            for (Path g : guesses) if (Files.isRegularFile(g)) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ handlers

    static String catalogWithProgress() throws IOException {
        String catalog = Files.readString(projects.resolve("catalog.json"));
        String progress = !hosted && Files.exists(progressFile()) ? Files.readString(progressFile()) : "{}";
        StringBuilder langs = new StringBuilder("{");
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([a-z0-9-]+)\"").matcher(catalog);
        while (m.find()) {
            if (langs.length() > 1) langs.append(',');
            langs.append(json(m.group(1))).append(':').append(jsonList(langsOf(m.group(1))));
        }
        langs.append('}');
        return "{\"projects\":" + catalog + ",\"progress\":" + progress + ",\"languages\":" + langs + "}";
    }

    static String projectDetail(String id, String lang) throws IOException {
        Path dir = projects.resolve(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("unknown project " + id);
        Path lr = langRoot(id, lang);
        if (!Files.isDirectory(lr.resolve("starter/src"))) throw new HttpError(404, "not available in " + lang + " yet");
        if (!hosted) ensureWorkspace(id, lang);
        Path langReadme = lr.resolve("README.md");
        String readme = Files.readString(Files.exists(langReadme) ? langReadme : dir.resolve("README.md"));
        Path editableDir = hosted ? lr.resolve("starter/src") : workspace.resolve(id).resolve(lang).resolve("src");
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(json(id)).append(',');
        sb.append("\"lang\":").append(json(lang)).append(',');
        sb.append("\"readme\":").append(json(readme)).append(',');
        sb.append("\"workspace\":").append(jsonList(listFiles(editableDir, lang))).append(',');
        sb.append("\"tests\":").append(jsonList(listFiles(lr.resolve("tests/src"), lang))).append(',');
        sb.append("\"solution\":").append(jsonList(listFiles(lr.resolve("solution/src"), lang))).append(',');
        sb.append("\"workspacePath\":").append(hosted ? "null" : json(editableDir.toString()));
        sb.append('}');
        return sb.toString();
    }

    /** CLI: java server/LabServer.java test <project-id> [workspace|solution|starter] [lang] */
    static void cli(String id, String mode, String lang) throws Exception {
        String json = runTests(id(id), lang(lang), mode, null);
        boolean compileFailed = json.startsWith("{\"phase\":\"compile\"");
        // a JSON string body, written as an unrolled loop so long outputs don't overflow the regex stack
        final String S = "[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+";
        Matcher out = Pattern.compile("\"output\":\"(" + S + ")\"").matcher(json);
        String output = out.find() ? unjson(out.group(1)) : "";
        if (compileFailed) {
            System.out.println("COMPILATION FAILED\n" + output);
            System.exit(2);
        }
        Matcher row = Pattern.compile(
                "\\{\"status\":\"(PASS|FAIL)\",\"suite\":\"" + S + "\",\"method\":\"" + S + "\",\"name\":\"(" + S
                        + ")\",\"millis\":\"([0-9-]+)\",\"message\":\"(" + S + ")\"\\}")
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

    static String runTests(String id, String lang, String mode, List<String[]> only) throws Exception {
        Path lr = langRoot(id, lang);
        if (!Files.isDirectory(lr.resolve("starter/src"))) throw new HttpError(404, "not available in " + lang + " yet");
        Path src = switch (mode) {
            case "solution" -> lr.resolve("solution/src");
            case "starter" -> lr.resolve("starter/src");
            default -> {
                ensureWorkspace(id, lang);
                yield workspace.resolve(id).resolve(lang).resolve("src");
            }
        };
        Suite s = runSuite(id, lang, src, build.resolve(id).resolve(lang).resolve(mode), hosted, only);
        if (!hosted && mode.equals("workspace") && only == null) { // a partial run proves nothing about "solved"
            if (s.ok) setProgress(lang + ":" + id, "solved");
            else setProgressIfEmpty(lang + ":" + id, "attempted");
        }
        return s.json;
    }

    record Suite(String json, boolean ok) {}

    /** Hosted: the visitor's files arrive in the request body; nothing is kept afterwards. */
    static String hostedRun(String id, String lang, String mode, List<String[]> only, HttpExchange ex) throws Exception {
        Path lr = langRoot(id, lang);
        if (!Files.isDirectory(lr.resolve("starter/src"))) throw new HttpError(404, "not available in " + lang + " yet");
        if (!availableLangs().contains(lang)) throw new HttpError(503, lang + " is not installed on this server");
        boolean solution = mode.equals("solution");
        Map<String, String> files = solution ? Map.of() : parseFlatJson(readBody(ex, 1_000_000));
        if (!solution) {
            if (files.isEmpty() || files.size() > 30) throw new IllegalArgumentException("send 1..30 files");
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (!validUploadName(lang, e.getKey())) throw new IllegalArgumentException("bad file name " + e.getKey());
                if (e.getValue().length() > 100_000) throw new IllegalArgumentException("file too large: " + e.getKey());
                if (lang.equals("go")) checkGoSource(e.getKey(), e.getValue());
            }
        }
        checkRateLimit(clientKey(ex));
        if (role.equals("api")) return submitJob(id, lang, mode, only, files); // a worker runs it
        long waitFrom = System.currentTimeMillis();
        if (!RUN_SLOTS.tryAcquire(30, TimeUnit.SECONDS)) throw new HttpError(503, "The server is busy, try again in a moment.");
        RunCtx ctx = RUN_CTX.get();
        if (ctx != null) {
            ctx.startedAt = System.currentTimeMillis();
            ctx.queueMs = ctx.startedAt - waitFrom;
        }
        try {
            return runUploaded(id, lang, solution, files, only);
        } finally {
            RUN_SLOTS.release();
        }
    }

    /** Runs already-validated files (or the reference solution) sandboxed in a temp folder that is deleted after. */
    static String runUploaded(String id, String lang, boolean solution, Map<String, String> files, List<String[]> only)
            throws Exception {
        Path tmp = Files.createTempDirectory(build, "run-");
        try {
            Path src;
            if (solution) {
                src = langRoot(id, lang).resolve("solution/src");
            } else {
                src = tmp.resolve("src");
                for (Map.Entry<String, String> e : files.entrySet()) {
                    Path f = src.resolve(e.getKey()).normalize();
                    if (!f.startsWith(src)) throw new IllegalArgumentException("bad path");
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, e.getValue());
                }
            }
            // createTempDirectory makes the folder owner-only; the runner user must be able to enter it
            openPermissions(tmp);
            return runSuite(id, lang, src, tmp.resolve("out"), true, only).json;
        } finally {
            try {
                deleteTree(tmp);
            } catch (IOException ignored) {
                // best effort; the OS temp cleaner gets the rest
            }
        }
    }

    /** Go features that would let submitted code run before, or outside of, the sandbox. */
    static void checkGoSource(String name, String text) {
        String[][] banned = {
                {"import \"C\"", "cgo is not allowed"},
                {"\"unsafe\"", "package unsafe is not allowed"},
                {"//go:linkname", "//go:linkname is not allowed"},
                {"//go:cgo", "cgo directives are not allowed"},
                {"//go:embed", "//go:embed is not allowed"},
                {"\"os/exec\"", "os/exec is not allowed"},
                {"\"plugin\"", "plugins are not allowed"},
        };
        for (String[] b : banned) {
            if (text.contains(b[0])) throw new IllegalArgumentException(name + ": " + b[1] + " on the hosted site");
        }
    }

    // ------------------------------------------------------------------ test discovery & selection

    record TestInfo(String suite, String method, String name, String file, int line) {}

    static final Pattern JAVA_TEST = Pattern.compile(
            "@Test\\b(?:\\s*\\(\\s*(?:value\\s*=\\s*)?(?:\"((?:[^\"\\\\]|\\\\.)*)\")?[^)]*\\))?");
    static final Pattern JAVA_METHOD = Pattern.compile("void\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    static final Pattern PY_CLASS = Pattern.compile("^class\\s+([A-Za-z_][A-Za-z0-9_]*)");
    static final Pattern PY_TEST = Pattern.compile("^\\s*@test\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    static final Pattern PY_DEF = Pattern.compile("^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    static final Pattern GO_NAME = Pattern.compile("^//\\s*Test:\\s*(.+?)\\s*$");
    static final Pattern GO_FUNC = Pattern.compile("^func\\s+(Test[A-Za-z0-9_]*)\\s*\\(\\s*t\\s+\\*testing\\.T\\s*\\)");
    static final Pattern CPP_TEST = Pattern.compile(
            "LAB_TEST\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** All tests of a project in source order, read from the test files. */
    static List<TestInfo> discoverTests(String id, String lang) throws IOException {
        Path tests = langRoot(id, lang).resolve("tests/src");
        if (!Files.isDirectory(tests)) throw new IllegalArgumentException("unknown project " + id);
        List<TestInfo> out = new ArrayList<>();
        for (Path p : listFilesAbs(tests, lang)) {
            String rel = tests.relativize(p).toString().replace('\\', '/');
            String fileName = p.getFileName().toString();
            String text = Files.readString(p);
            String[] lines = text.split("\n", -1);
            switch (lang) {
                case "java" -> {
                    if (!fileName.endsWith("Test.java")) continue;
                    String suite = fileName.replace(".java", "");
                    Matcher a = JAVA_TEST.matcher(text);
                    while (a.find()) {
                        Matcher m = JAVA_METHOD.matcher(text);
                        if (!m.find(a.end())) break;
                        String name = a.group(1) == null ? m.group(1) : unjson(a.group(1));
                        out.add(new TestInfo(suite, m.group(1), name, rel, lineOf(text, m.start())));
                    }
                }
                case "python" -> {
                    if (!fileName.endsWith("_test.py")) continue;
                    String cls = null;
                    String pending = null;
                    for (int i = 0; i < lines.length; i++) {
                        Matcher c = PY_CLASS.matcher(lines[i]);
                        if (c.find()) {
                            cls = c.group(1);
                            continue;
                        }
                        Matcher t = PY_TEST.matcher(lines[i]);
                        if (t.find()) {
                            pending = unjson(t.group(1));
                            continue;
                        }
                        Matcher d = PY_DEF.matcher(lines[i]);
                        if (d.find() && pending != null && cls != null) {
                            out.add(new TestInfo(cls, d.group(1), pending, rel, i + 1));
                            pending = null;
                        }
                    }
                }
                case "go" -> {
                    if (!fileName.endsWith("_test.go")) continue;
                    String suite = fileName.substring(0, fileName.length() - "_test.go".length());
                    String pending = null;
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].stripTrailing();
                        Matcher n = GO_NAME.matcher(line);
                        if (n.find()) {
                            pending = n.group(1);
                            continue;
                        }
                        Matcher f = GO_FUNC.matcher(line);
                        if (f.find()) {
                            out.add(new TestInfo(suite, f.group(1), pending == null ? f.group(1) : pending, rel, i + 1));
                        }
                        if (!line.startsWith("//")) pending = null;
                    }
                }
                case "cpp" -> {
                    if (!fileName.endsWith(".cpp")) continue;
                    Matcher m = CPP_TEST.matcher(text);
                    while (m.find()) {
                        out.add(new TestInfo(m.group(1), m.group(2), unjson(m.group(3)), rel, lineOf(text, m.start())));
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    static String testsJson(String id, String lang) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (TestInfo t : discoverTests(id, lang)) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"suite\":").append(json(t.suite()))
                    .append(",\"method\":").append(json(t.method()))
                    .append(",\"name\":").append(json(t.name()))
                    .append(",\"file\":").append(json(t.file()))
                    .append(",\"line\":").append(t.line()).append('}');
        }
        return sb.append(']').toString();
    }

    /** Parses ?tests=Suite#method,... into validated [suite, method] pairs; null = run everything. */
    static List<String[]> selection(String id, String lang, String raw) throws IOException {
        if (raw == null || raw.isBlank()) return null;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (TestInfo t : discoverTests(id, lang)) known.add(t.suite() + "#" + t.method());
        List<String[]> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (!known.contains(t)) throw new IllegalArgumentException("unknown test " + t);
            out.add(t.split("#", 2));
        }
        if (out.isEmpty()) return null;
        if (out.size() > 500) throw new IllegalArgumentException("too many tests selected");
        return out;
    }

    // ------------------------------------------------------------------ running

    static Suite runSuite(String id, String lang, Path src, Path out, boolean sandbox, List<String[]> only) throws Exception {
        deleteTree(out);
        Files.createDirectories(out);
        if (sandbox) openPermissions(out);
        if (!lang.equals("java") && tool(toolFor(lang)) == null) {
            return compileFailure("The " + lang + " toolchain is not installed here (" + toolFor(lang) + " not found).", 0);
        }
        return switch (lang) {
            case "python" -> runPython(id, src, out, sandbox, only);
            case "go" -> runGo(id, src, out, sandbox, only);
            case "cpp" -> runCpp(id, src, out, sandbox, only);
            default -> runJava(id, src, out, sandbox, only);
        };
    }

    static String toolFor(String lang) {
        return switch (lang) {
            case "python" -> "python";
            case "go" -> "go";
            case "cpp" -> "g++";
            default -> "java";
        };
    }

    static Suite compileFailure(String output, long t0) {
        return new Suite("{\"phase\":\"compile\",\"ok\":false,\"output\":" + json(output)
                + ",\"results\":[],\"passed\":0,\"failed\":0,\"total\":0,\"millis\":"
                + (t0 == 0 ? 0 : System.currentTimeMillis() - t0) + "}", false);
    }

    /**
     * Java: compiles into three output roots so the security policy can tell them apart:
     *   kit (trusted), tests (trusted), user (sandboxed). Kit and tests come first on the classpath.
     */
    static Suite runJava(String id, Path src, Path out, boolean sandbox, List<String[]> only) throws Exception {
        Path tests = langRoot(id, "java").resolve("tests/src");
        Path user = out.resolve("user");
        Path testOut = out.resolve("tests");
        Files.createDirectories(user);
        long t0 = System.currentTimeMillis();

        List<Path> userSources = listFilesAbs(src, "java");
        String err = userSources.isEmpty() ? "No .java files to compile." : compile(userSources, user, user.toString());
        if (err == null) err = compile(listFilesAbs(tests, "java"), testOut, kitDir() + File.pathSeparator + user);
        if (err != null) return compileFailure(clean(err, src, tests), t0);
        compiled();
        if (sandbox) openPermissions(out);

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
            openPermissions(out);
            cmd.add("-XX:ActiveProcessorCount=2");
            cmd.add("-Djava.security.manager=default");
            cmd.add("-Djava.security.policy==" + policy);
            cmd.add("-Djava.io.tmpdir=" + tmp);
            timeout = 90;
        }
        cmd.add("testkit.TestRunner");
        if (only != null) {
            Map<String, String> fqn = new LinkedHashMap<>();
            for (Path p : listFilesAbs(tests, "java")) {
                String rel = tests.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith("Test.java")) {
                    String name = rel.substring(0, rel.length() - 5).replace('/', '.');
                    fqn.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
            }
            for (String[] t : only) cmd.add(fqn.get(t[0]) + "#" + t[1]);
        } else {
            for (Path p : listFilesAbs(tests, "java")) {
                String rel = tests.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith("Test.java")) cmd.add(rel.substring(0, rel.length() - 5).replace('/', '.'));
            }
        }
        Proc run = exec(sandboxed(cmd, sandbox), timeout, out, sandboxEnv(out, sandbox));
        return parseRunnerOutput(run, timeout, t0, only != null, Map.of());
    }

    /** Python: our runner imports the tests (and through them the code), after installing the sandbox. */
    static Suite runPython(String id, Path src, Path out, boolean sandbox, List<String[]> only) throws Exception {
        Path tests = langRoot(id, "python").resolve("tests/src");
        long t0 = System.currentTimeMillis();
        if (listFilesAbs(src, "python").isEmpty()) return compileFailure("No .py files.", t0);
        List<String> cmd = new ArrayList<>(List.of(tool("python").toString(), "-I", "-B", "-X", "utf8",
                root.resolve("testkit/python/lab_runner.py").toString(),
                "--src", src.toString(), "--tests", tests.toString()));
        if (sandbox) cmd.add("--sandbox");
        if (only != null) for (String[] t : only) cmd.add(t[0] + "#" + t[1]);
        int timeout = sandbox ? 90 : 180;
        Map<String, String> env = sandboxEnv(out, sandbox);
        compiled(); // Python compiles on import, inside the run; syntax errors come back as COMPILE| lines
        Proc run = exec(sandboxed(cmd, sandbox), timeout, out, env);
        // the runner reports import/syntax errors as COMPILE| lines
        StringBuilder compile = new StringBuilder();
        for (String line : run.output.split("\\R")) {
            if (line.startsWith("COMPILE|")) compile.append(line.substring(8).replace("\\n", "\n")).append('\n');
        }
        if (compile.length() > 0) return compileFailure(clean(compile.toString(), src, tests), t0);
        return parseRunnerOutput(run, timeout, t0, only != null, Map.of());
    }

    /** Go: build a throwaway module with the code and the tests in one package, then `go test -c`. */
    static Suite runGo(String id, Path src, Path out, boolean sandbox, List<String[]> only) throws Exception {
        Path tests = langRoot(id, "go").resolve("tests/src");
        long t0 = System.currentTimeMillis();
        List<Path> userFiles = listFilesAbs(src, "go");
        if (userFiles.isEmpty()) return compileFailure("No .go files.", t0);
        Path mod = out.resolve("mod");
        String pkgDir = null;
        for (Path p : userFiles) {
            Path rel = src.relativize(p);
            if (rel.getNameCount() != 2) return compileFailure("Go files must live in one package folder: " + rel, t0);
            pkgDir = rel.getName(0).toString();
            Path dest = mod.resolve(rel.toString());
            Files.createDirectories(dest.getParent());
            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        for (Path p : listFilesAbs(tests, "go")) {
            Path dest = mod.resolve(tests.relativize(p).toString());
            Files.createDirectories(dest.getParent());
            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(mod.resolve("go.mod"), "module lab\n\ngo 1.22\n");
        if (sandbox) {
            Path sb = mod.resolve("labsandbox");
            Files.createDirectories(sb);
            for (Path p : listFilesAbs(root.resolve("testkit/go/labsandbox"), "go")) {
                Files.copy(p, sb.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
            String pkgName = goPackageName(mod.resolve(pkgDir));
            // importing the sandbox from inside the package under test makes it initialise first
            Files.writeString(mod.resolve(pkgDir).resolve("zz_labsandbox_test.go"),
                    "package " + pkgName + "\n\nimport _ \"lab/labsandbox\"\n");
        }
        if (sandbox) openPermissions(out);
        Path bin = out.resolve(win ? "t.test.exe" : "t.test");
        Map<String, String> env = sandboxEnv(out, sandbox);
        env.put("GOCACHE", goCache(out).toString());
        env.put("GOPATH", out.resolve("gopath").toString());
        env.put("GOFLAGS", "-mod=mod");
        env.put("GOPROXY", "off");
        env.put("GOTOOLCHAIN", "local");
        env.put("GOWORK", "off");
        env.put("GOTELEMETRY", "off");
        env.put("CGO_ENABLED", "0");
        Proc compile = exec(sandboxed(List.of(tool("go").toString(), "test", "-c", "-o", bin.toString(), "./" + pkgDir),
                false, sandbox), 240, mod, env);
        if (compile.code != 0 || !Files.exists(bin)) {
            return compileFailure(clean(compile.output.replace(mod.toString() + File.separator, ""), src, tests), t0);
        }
        compiled();
        List<String> cmd = new ArrayList<>(List.of(bin.toString(), "-test.v", "-test.timeout=60s", "-test.count=1"));
        if (only != null) {
            StringBuilder re = new StringBuilder("^(");
            for (int i = 0; i < only.size(); i++) re.append(i > 0 ? "|" : "").append(only.get(i)[1]);
            cmd.add("-test.run=" + re.append(")$"));
        }
        env.put("GOMEMLIMIT", "200MiB");
        Proc run = exec(sandboxed(cmd, sandbox), 90, mod.resolve(pkgDir), env);
        return parseGoOutput(run, t0, only != null, discoverTests(id, "go"));
    }

    static String goPackageName(Path dir) throws IOException {
        for (Path p : listFilesAbs(dir, "go")) {
            if (p.getFileName().toString().endsWith("_test.go")) continue;
            Matcher m = Pattern.compile("(?m)^package\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(Files.readString(p));
            if (m.find()) return m.group(1);
        }
        throw new IllegalArgumentException("no package clause found");
    }

    static Path goCache(Path out) throws IOException {
        String env = System.getenv("LAB_GOCACHE");
        if (env != null && !env.isBlank()) return Paths.get(env);
        Path shared = build.resolve("_gocache");
        Files.createDirectories(shared);
        return shared;
    }

    /** C++: one binary from the code, the tests and our header-only kit. */
    static Suite runCpp(String id, Path src, Path out, boolean sandbox, List<String[]> only) throws Exception {
        Path tests = langRoot(id, "cpp").resolve("tests/src");
        Path kit = root.resolve("testkit/cpp");
        long t0 = System.currentTimeMillis();
        List<String> cmd = new ArrayList<>(List.of(tool("g++").toString(), "-std=c++20", "-O1", "-pthread",
                "-fdiagnostics-color=never", "-I" + src, "-I" + kit));
        if (win) cmd.add("-static");
        int sources = 0;
        for (Path p : listFilesAbs(src, "cpp")) {
            if (p.toString().endsWith(".cpp")) {
                cmd.add(p.toString());
                sources++;
            }
        }
        for (Path p : listFilesAbs(tests, "cpp")) if (p.toString().endsWith(".cpp")) cmd.add(p.toString());
        cmd.add(kit.resolve("labtest_main.cpp").toString());
        cmd.add(kit.resolve("labsandbox.cpp").toString());
        Path bin = out.resolve(win ? "t.exe" : "t");
        cmd.add("-o");
        cmd.add(bin.toString());
        Map<String, String> env = sandboxEnv(out, sandbox);
        if (win) env.put("PATH", tool("g++").getParent() + File.pathSeparator + System.getenv("PATH"));
        Proc compile = exec(sandboxed(cmd, false, sandbox), 240, out, env);
        if (compile.code != 0 || !Files.exists(bin)) {
            return compileFailure(clean(compile.output, src, tests).replace(kit.toString() + File.separator, "[kit] "), t0);
        }
        compiled();
        List<String> run = new ArrayList<>(List.of(bin.toString()));
        if (only != null) for (String[] t : only) run.add(t[0] + "#" + t[1]);
        Proc p = exec(sandboxed(run, sandbox), sandbox ? 90 : 180, out, env);
        return parseRunnerOutput(p, sandbox ? 90 : 180, t0, only != null, Map.of());
    }

    /** Parses the PASS|Suite|method|name|ms[|msg] / RESULT lines printed by our Java, Python and C++ runners. */
    static Suite parseRunnerOutput(Proc run, int timeout, long t0, boolean partial, Map<String, String> unused) {
        StringBuilder results = new StringBuilder("[");
        StringBuilder other = new StringBuilder();
        int passed = 0, failed = 0, total = 0;
        for (String line : run.output.split("\\R")) {
            String[] f = line.split("\\|", 6);
            if ((f[0].equals("PASS") || f[0].equals("FAIL")) && f.length >= 5) {
                appendResult(results, f[0], f[1], f[2], f[3], f[4], f.length > 5 ? f[5] : "");
            } else if (f[0].equals("RESULT") && f.length >= 4) {
                passed = Integer.parseInt(f[1].trim());
                failed = Integer.parseInt(f[2].trim());
                total = Integer.parseInt(f[3].trim());
            } else if (!line.isBlank() && !line.startsWith("WARNING:")) { // JDK's security-manager notice
                other.append(line).append('\n');
            }
        }
        results.append(']');
        if (run.timedOut) other.append("Test process killed after ").append(timeout).append("s.\n");
        else if (total == 0) other.append("The test process ended before reporting results (did the code exit or crash?).\n");
        return finish(results, other, passed, failed, total, run.timedOut, t0, partial);
    }

    static final Pattern GO_RESULT = Pattern.compile("^--- (PASS|FAIL): (Test[A-Za-z0-9_]*) \\(([0-9.]+)s\\)");
    static final Pattern GO_RUN = Pattern.compile("^=== (RUN|CONT|PAUSE)\\s+(Test[A-Za-z0-9_]*)$");
    static final Pattern GO_LOCATION = Pattern.compile("^\\s+[A-Za-z0-9_./-]+\\.go:\\d+: ");

    /** Parses `go test -v` output. */
    static Suite parseGoOutput(Proc run, long t0, boolean partial, List<TestInfo> known) {
        Map<String, TestInfo> byMethod = new LinkedHashMap<>();
        for (TestInfo t : known) byMethod.put(t.method(), t);
        StringBuilder results = new StringBuilder("[");
        StringBuilder other = new StringBuilder();
        Map<String, StringBuilder> messages = new LinkedHashMap<>();
        java.util.Set<String> started = new java.util.LinkedHashSet<>();
        java.util.Set<String> finished = new java.util.HashSet<>();
        String current = null;
        int passed = 0, failed = 0;
        for (String line : run.output.split("\\R")) {
            Matcher r = GO_RUN.matcher(line);
            if (r.find()) {
                current = r.group(2);
                started.add(current);
                continue;
            }
            Matcher res = GO_RESULT.matcher(line);
            if (res.find()) {
                String m = res.group(2);
                long ms = Math.round(Double.parseDouble(res.group(3)) * 1000);
                TestInfo info = byMethod.get(m);
                String msg = messages.containsKey(m) ? messages.get(m).toString().trim() : "";
                if (res.group(1).equals("PASS")) passed++;
                else failed++;
                appendResult(results, res.group(1), info == null ? "go" : info.suite(), m,
                        info == null ? m : info.name(), String.valueOf(ms),
                        res.group(1).equals("FAIL") ? (msg.isEmpty() ? "failed" : msg.replace('\n', ' ')) : "");
                finished.add(m);
                current = null;
                continue;
            }
            if (current != null && line.startsWith("    ")) {
                String text = GO_LOCATION.matcher(line).replaceFirst("").trim();
                messages.computeIfAbsent(current, k -> new StringBuilder()).append(text).append('\n');
                continue;
            }
            if (line.equals("PASS") || line.equals("FAIL") || line.isBlank() || line.startsWith("ok ")) continue;
            other.append(line).append('\n');
        }
        // tests that started but never finished (timeout / crash)
        for (String m : started) {
            if (finished.contains(m)) continue;
            TestInfo info = byMethod.get(m);
            failed++;
            appendResult(results, "FAIL", info == null ? "go" : info.suite(), m, info == null ? m : info.name(), "-",
                    "did not finish (timed out or crashed)");
        }
        results.append(']');
        String otherText = other.length() > 4000 ? other.substring(0, 4000) + "\n... (truncated)" : other.toString();
        int total = passed + failed;
        if (run.timedOut) otherText += "Test process killed after 90s.\n";
        return finish(results, new StringBuilder(otherText), passed, failed, total, run.timedOut, t0, partial);
    }

    static void appendResult(StringBuilder results, String status, String suite, String method, String name,
                             String millis, String message) {
        if (results.length() > 1) results.append(',');
        results.append("{\"status\":").append(json(status))
                .append(",\"suite\":").append(json(suite))
                .append(",\"method\":").append(json(method))
                .append(",\"name\":").append(json(name))
                .append(",\"millis\":").append(json(millis))
                .append(",\"message\":").append(json(message))
                .append('}');
    }

    static Suite finish(StringBuilder results, StringBuilder other, int passed, int failed, int total,
                        boolean timedOut, long t0, boolean partial) {
        boolean ok = total > 0 && failed == 0 && !timedOut;
        return new Suite("{\"phase\":\"test\",\"ok\":" + ok + ",\"partial\":" + partial
                + ",\"output\":" + json(other.toString())
                + ",\"results\":" + results + ",\"passed\":" + passed + ",\"failed\":" + failed
                + ",\"total\":" + total + ",\"millis\":" + (System.currentTimeMillis() - t0) + "}", ok);
    }

    // ------------------------------------------------------------------ sandbox

    static Boolean sandboxReady;

    /** OS-level sandbox is available: Linux, running as root, with util-linux prlimit and setpriv. */
    static synchronized boolean sandboxReady() {
        if (sandboxReady == null) {
            sandboxReady = !win && "root".equals(System.getProperty("user.name"))
                    && Files.isExecutable(Paths.get("/usr/bin/prlimit")) && Files.isExecutable(Paths.get("/usr/bin/setpriv"));
        }
        return sandboxReady;
    }

    static List<String> sandboxed(List<String> cmd, boolean sandbox) {
        return sandboxed(cmd, sandbox, sandbox);
    }

    /**
     * Wraps a command so it runs as the unprivileged "runner" user with resource limits.
     * {@code limits} applies prlimit; {@code drop} switches user. Compilers get the user switch only
     * (they need to start helper processes, which the test-time limits/filters would block).
     */
    static List<String> sandboxed(List<String> cmd, boolean limits, boolean drop) {
        if (!hosted || !sandboxReady() || !(limits || drop)) return cmd;
        List<String> out = new ArrayList<>();
        if (limits) {
            out.addAll(List.of("/usr/bin/prlimit", "--nproc=1024", "--nofile=256", "--fsize=52428800",
                    "--cpu=120", "--core=0", "--"));
        }
        out.addAll(List.of("/usr/bin/setpriv", "--reuid=runner", "--regid=runner", "--clear-groups", "--no-new-privs",
                "--"));
        out.addAll(cmd);
        return out;
    }

    static Map<String, String> sandboxEnv(Path out, boolean sandbox) {
        Map<String, String> env = new LinkedHashMap<>();
        if (!hosted) {
            env.putAll(System.getenv());
        } else {
            // a minimal, secret-free environment for anything that runs submitted code
            env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
            env.put("LANG", "C.UTF-8");
            env.put("HOME", out.toString());
            env.put("TMPDIR", out.resolve("tmp").toString());
            if (win) { // trying hosted mode on Windows: compilers look for these instead
                env.put("TEMP", out.resolve("tmp").toString());
                env.put("TMP", out.resolve("tmp").toString());
                env.put("SystemRoot", System.getenv().getOrDefault("SystemRoot", "C:\\Windows"));
                env.put("LOCALAPPDATA", out.toString());
                env.put("APPDATA", out.toString());
            }
        }
        if (sandbox) env.put("LAB_SANDBOX", "1");
        try {
            Files.createDirectories(out.resolve("tmp"));
            if (sandbox) openPermissions(out);
        } catch (IOException ignored) {
        }
        return env;
    }

    /** Lets the unprivileged runner user write into a run folder (the folder itself is per run). */
    static void openPermissions(Path dir) throws IOException {
        if (win || !hosted || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                try {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                            Files.isDirectory(p) ? "rwxrwxrwx" : "rwxrwxrw-"));
                } catch (UnsupportedOperationException | IOException ignored) {
                }
            }
        }
    }

    /** Java: user code may touch only its temp folder, read properties, manage its own threads and use localhost. */
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

    /** The shared Java test kit, compiled once per server run. */
    static synchronized Path kitDir() throws IOException {
        if (kitPath != null) return kitPath;
        Path out = build.resolve("_kit");
        deleteTree(out);
        String err = compile(listFilesAbs(root.resolve("testkit/java/src"), "java"), out, null);
        if (err != null) throw new IllegalStateException("test kit failed to compile:\n" + err);
        openPermissions(out);
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

    // ------------------------------------------------------------------ fleet: API + Redis queue + workers
    //
    // api role:    validate, rate limit, XADD the job to the lab:jobs stream, then BLPOP lab:result:<runId>.
    // worker role: XREADGROUP jobs, run each one (LAB_RUNNER=docker: a fresh gVisor container per job;
    //              inprocess: in this JVM, for testing), RPUSH the result, XACK + XDEL the job.
    // Results are "<flat JSON meta>\n<suite JSON>". Workers heartbeat into the lab:workers sorted set.

    static final String JOBS = "lab:jobs", GROUP = "workers", WORKERS = "lab:workers";
    static final int JOB_WAIT_SECONDS = 400;   // longest compile (240 s) + run (90 s) + container start, with margin
    static final int MAX_QUEUE = Integer.parseInt(System.getenv().getOrDefault("LAB_MAX_QUEUE", "20"));
    static final java.util.concurrent.BlockingQueue<Redis> REDIS_POOL = new java.util.concurrent.LinkedBlockingQueue<>();

    static URI redisUri() {
        String url = System.getenv("LAB_REDIS_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("LAB_REDIS_URL is required for --role " + role);
        return URI.create(url);
    }

    /** A minimal Redis client (RESP2) over one TCP connection; not thread-safe, so connections are pooled. */
    static final class Redis implements AutoCloseable {
        final java.net.Socket socket;
        final InputStream in;
        final OutputStream out;

        Redis(URI uri) throws IOException {
            socket = new java.net.Socket();
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort()), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            in = new java.io.BufferedInputStream(socket.getInputStream());
            out = new java.io.BufferedOutputStream(socket.getOutputStream());
            String info = uri.getRawUserInfo();
            if (info != null && !info.isEmpty()) {
                int c = info.indexOf(':');
                String user = c >= 0 ? URLDecoder.decode(info.substring(0, c), StandardCharsets.UTF_8) : "";
                String pass = URLDecoder.decode(c >= 0 ? info.substring(c + 1) : info, StandardCharsets.UTF_8);
                if (user.isEmpty()) call("AUTH", pass);
                else call("AUTH", user, pass);
            }
        }

        Object call(String... args) throws IOException {
            out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                out.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b);
                out.write('\r');
                out.write('\n');
            }
            out.flush();
            return read();
        }

        /** For blocking commands: waits up to {@code seconds} plus a margin for the reply. */
        Object callBlocking(int seconds, String... args) throws IOException {
            socket.setSoTimeout((seconds + 15) * 1000);
            try {
                return call(args);
            } finally {
                socket.setSoTimeout(30_000);
            }
        }

        Object read() throws IOException {
            int type = in.read();
            if (type == -1) throw new java.io.EOFException("Redis closed the connection");
            String line = readLine();
            switch (type) {
                case '+':
                    return line;
                case '-':
                    throw new RedisError(line);
                case ':':
                    return Long.parseLong(line);
                case '$': {
                    int n = Integer.parseInt(line);
                    if (n < 0) return null;
                    byte[] b = in.readNBytes(n);
                    in.readNBytes(2);
                    return new String(b, StandardCharsets.UTF_8);
                }
                case '*': {
                    int n = Integer.parseInt(line);
                    if (n < 0) return null;
                    List<Object> items = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) items.add(read());
                    return items;
                }
                default:
                    throw new IOException("unexpected Redis reply type " + (char) type);
            }
        }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') {
                if (c == -1) throw new java.io.EOFException("Redis closed the connection");
                sb.append((char) c);
            }
            in.read(); // \n
            return sb.toString();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    static final class RedisError extends IOException {
        RedisError(String message) {
            super(message);
        }
    }

    static Redis borrowRedis() throws IOException {
        Redis r = REDIS_POOL.poll();
        return r != null ? r : new Redis(redisUri());
    }

    /** Returns a healthy connection to the pool; pass broken=true after an I/O error to drop it. */
    static void returnRedis(Redis r, boolean broken) {
        if (broken || REDIS_POOL.size() >= 32) r.close();
        else REDIS_POOL.offer(r);
    }

    static String flatJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(json(e.getKey())).append(':').append(json(e.getValue()));
        }
        return sb.append('}').toString();
    }

    static String testsParam(List<String[]> only) {
        if (only == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String[] t : only) sb.append(sb.length() > 0 ? "," : "").append(t[0]).append('#').append(t[1]);
        return sb.toString();
    }

    /** api role: queue the run and wait for a worker's result. */
    static String submitJob(String id, String lang, String mode, List<String[]> only, Map<String, String> files)
            throws Exception {
        Redis r = borrowRedis();
        boolean broken = true;
        try {
            long now = System.currentTimeMillis();
            long workers = (Long) r.call("ZCOUNT", WORKERS, String.valueOf(now - 30_000), "+inf");
            if (workers == 0) throw new HttpError(503, "No test workers are online right now. Try again in a minute.");
            long backlog = (Long) r.call("XLEN", JOBS);
            if (backlog >= MAX_QUEUE) throw new HttpError(503, "The server is busy, try again in a moment.");
            String runId = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
                    + Long.toHexString(System.nanoTime());
            r.call("XADD", JOBS, "MAXLEN", "~", "1000", "*", "run", runId, "project", id, "lang", lang, "mode", mode,
                    "tests", testsParam(only), "files", flatJson(files), "at", String.valueOf(now));
            Object reply = r.callBlocking(JOB_WAIT_SECONDS, "BLPOP", "lab:result:" + runId, String.valueOf(JOB_WAIT_SECONDS));
            broken = false;
            if (reply == null) throw new HttpError(504, "The test run did not finish in time. Try again.");
            String value = (String) ((List<?>) reply).get(1);
            int nl = value.indexOf('\n');
            Map<String, String> meta = parseFlatJson(value.substring(0, nl));
            if (!meta.getOrDefault("error", "").isEmpty()) {
                throw new HttpError(500, "The test worker could not run this job. Please try again.");
            }
            RunCtx ctx = RUN_CTX.get();
            if (ctx != null) {
                ctx.queueMs = Long.parseLong(meta.get("queueMs"));
                ctx.compileMs = Long.parseLong(meta.get("compileMs"));
                ctx.execMs = Long.parseLong(meta.get("execMs"));
                ctx.timedOut = Boolean.parseBoolean(meta.get("timedOut"));
            }
            return value.substring(nl + 1);
        } finally {
            returnRedis(r, broken);
        }
    }

    /** api role: queue depth and live workers for /metrics (-1 when Redis is unreachable). */
    static long[] fleetGauges() {
        Redis r = null;
        boolean broken = true;
        try {
            r = borrowRedis();
            long backlog = (Long) r.call("XLEN", JOBS);
            long workers = (Long) r.call("ZCOUNT", WORKERS, String.valueOf(System.currentTimeMillis() - 30_000), "+inf");
            broken = false;
            return new long[] {backlog, workers};
        } catch (Exception e) {
            return new long[] {-1, -1};
        } finally {
            if (r != null) returnRedis(r, broken);
        }
    }

    // ----- worker

    static final String RUNNER = System.getenv().getOrDefault("LAB_RUNNER", "docker");
    static final String DOCKER_IMAGE = System.getenv().getOrDefault("LAB_DOCKER_IMAGE", "build-lab");
    static final String DOCKER_RUNTIME = System.getenv().getOrDefault("LAB_DOCKER_RUNTIME", "runsc");
    static final Path JOB_ROOT = Paths.get(System.getenv().getOrDefault("LAB_JOB_DIR", "/var/lib/buildlab/jobs"));
    static final java.util.concurrent.atomic.LongAdder JOBS_LOST = new java.util.concurrent.atomic.LongAdder();

    static void startWorker() throws Exception {
        if (!RUNNER.matches("docker|inprocess")) throw new IllegalStateException("LAB_RUNNER must be docker or inprocess");
        String host = java.net.InetAddress.getLocalHost().getHostName();
        String name = host + "-" + ProcessHandle.current().pid();
        int slots = Integer.parseInt(System.getenv().getOrDefault("LAB_WORKER_SLOTS", String.valueOf(PARALLEL_RUNS)));
        try (Redis r = new Redis(redisUri())) {
            try {
                r.call("XGROUP", "CREATE", JOBS, GROUP, "$", "MKSTREAM");
            } catch (RedisError e) {
                if (!e.getMessage().startsWith("BUSYGROUP")) throw e; // the group already exists
            }
        }
        if (RUNNER.equals("docker")) Files.createDirectories(JOB_ROOT);
        for (int i = 0; i < slots; i++) {
            String consumer = name + "-" + i;
            Thread t = new Thread(() -> consume(consumer), "worker-" + i);
            t.start();
        }
        Thread housekeeping = new Thread(() -> housekeeping(name), "worker-housekeeping");
        housekeeping.setDaemon(true);
        housekeeping.start();
        System.out.println("Build Lab worker " + name + ": " + slots + " slots, runner " + RUNNER
                + (RUNNER.equals("docker") ? " (image " + DOCKER_IMAGE + ", runtime " + DOCKER_RUNTIME + ")" : ""));
    }

    /** Heartbeat every 10 s; every minute, drop jobs nobody finished (their worker died). */
    static void housekeeping(String name) {
        Redis r = null;
        long lastSweep = 0;
        while (true) {
            try {
                if (r == null) r = new Redis(redisUri());
                long now = System.currentTimeMillis();
                r.call("ZADD", WORKERS, String.valueOf(now), name);
                r.call("ZREMRANGEBYSCORE", WORKERS, "-inf", String.valueOf(now - 120_000));
                if (now - lastSweep > 60_000) {
                    lastSweep = now;
                    Object pending = r.call("XPENDING", JOBS, GROUP, "IDLE",
                            String.valueOf((JOB_WAIT_SECONDS + 60) * 1000L), "-", "+", "20");
                    for (Object p : (List<?>) pending) {
                        String entryId = (String) ((List<?>) p).get(0);
                        r.call("XACK", JOBS, GROUP, entryId);
                        r.call("XDEL", JOBS, entryId);
                        JOBS_LOST.increment();
                        System.err.println("Dropped job " + entryId + ": its worker stopped before finishing it");
                    }
                }
            } catch (Exception e) {
                System.err.println("Worker housekeeping: " + e);
                if (r != null) r.close();
                r = null;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static void consume(String consumer) {
        Redis r = null;
        while (true) {
            try {
                if (r == null) r = new Redis(redisUri());
                Object reply = r.callBlocking(5, "XREADGROUP", "GROUP", GROUP, consumer, "COUNT", "1", "BLOCK", "5000",
                        "STREAMS", JOBS, ">");
                if (reply == null) continue;
                List<?> entry = (List<?>) ((List<?>) ((List<?>) ((List<?>) reply).get(0)).get(1)).get(0);
                String entryId = (String) entry.get(0);
                List<?> kv = (List<?>) entry.get(1);
                Map<String, String> job = new LinkedHashMap<>();
                for (int i = 0; i + 1 < kv.size(); i += 2) job.put((String) kv.get(i), (String) kv.get(i + 1));
                String result = executeJob(job);
                String key = "lab:result:" + job.get("run");
                r.call("RPUSH", key, result);
                r.call("EXPIRE", key, "120");
                r.call("XACK", JOBS, GROUP, entryId);
                r.call("XDEL", JOBS, entryId);
            } catch (Exception e) {
                System.err.println("Worker " + consumer + ": " + e);
                if (r != null) r.close();
                r = null;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Runs one queued job and returns "<meta>\n<suite JSON>". Never throws: failures go into meta.error. */
    static String executeJob(Map<String, String> job) {
        long picked = System.currentTimeMillis();
        long queueMs = Math.max(0, picked - Long.parseLong(job.getOrDefault("at", String.valueOf(picked))));
        Map<String, String> meta = new LinkedHashMap<>();
        String suite = "";
        try {
            if (RUNNER.equals("docker")) {
                String[] out = runInContainer(job);
                meta.putAll(parseFlatJson(out[0]));
                suite = out[1];
            } else {
                RunCtx ctx = new RunCtx();
                RUN_CTX.set(ctx);
                try {
                    suite = runJobRequest(job);
                } finally {
                    RUN_CTX.remove();
                }
                long[] phases = ctx.phases(System.currentTimeMillis());
                meta.put("compileMs", String.valueOf(phases[0]));
                meta.put("execMs", String.valueOf(phases[1]));
                meta.put("timedOut", String.valueOf(ctx.timedOut));
            }
        } catch (Exception e) {
            System.err.println("Job " + job.get("run") + " failed: " + e);
            meta.put("error", String.valueOf(e));
        }
        meta.put("queueMs", String.valueOf(queueMs));
        meta.putIfAbsent("compileMs", "0");
        meta.putIfAbsent("execMs", "0");
        meta.putIfAbsent("timedOut", "false");
        return flatJson(meta) + "\n" + suite;
    }

    /** Validates a job's fields again (never trust the queue) and runs it in this process. */
    static String runJobRequest(Map<String, String> job) throws Exception {
        String id = id(job.get("project"));
        String lang = lang(job.get("lang"));
        String mode = job.getOrDefault("mode", "workspace");
        String tests = job.getOrDefault("tests", "");
        List<String[]> only = selection(id, lang, tests.isEmpty() ? null : tests);
        boolean solution = mode.equals("solution");
        Map<String, String> files = solution ? Map.of() : parseFlatJson(job.getOrDefault("files", "{}"));
        if (!solution) {
            if (files.isEmpty() || files.size() > 30) throw new IllegalArgumentException("send 1..30 files");
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (!validUploadName(lang, e.getKey())) throw new IllegalArgumentException("bad file name " + e.getKey());
                if (lang.equals("go")) checkGoSource(e.getKey(), e.getValue());
            }
        }
        return runUploaded(id, lang, solution, files, only);
    }

    /** docker runner: the job runs in a fresh, network-less gVisor container that is removed afterwards. */
    static String[] runInContainer(Map<String, String> job) throws Exception {
        String runId = job.get("run");
        if (runId == null || !runId.matches("[0-9a-f]{1,40}")) throw new IllegalArgumentException("bad run id");
        Path dir = JOB_ROOT.resolve(runId);
        Files.createDirectories(dir);
        try {
            Files.writeString(dir.resolve("request.json"), flatJson(job));
            String container = "lab-run-" + runId;
            List<String> cmd = List.of("docker", "run", "--rm", "--name", container,
                    "--runtime=" + DOCKER_RUNTIME, "--network=none",
                    "--memory=1g", "--memory-swap=1g", "--cpus=1", "--pids-limit=512",
                    "--security-opt=no-new-privileges",
                    "-v", dir + ":/job", DOCKER_IMAGE, "run-job", "/job");
            Proc p = exec(cmd, JOB_WAIT_SECONDS - 30, null, null);
            if (p.timedOut()) exec(List.of("docker", "rm", "-f", container), 30, null, null);
            Path result = dir.resolve("result.json");
            if (!Files.exists(result)) {
                String tail = p.output().length() > 2000 ? p.output().substring(p.output().length() - 2000) : p.output();
                throw new IOException("container produced no result (exit " + p.code() + "): " + tail.strip());
            }
            String text = Files.readString(result);
            int nl = text.indexOf('\n');
            return new String[] {text.substring(0, nl), text.substring(nl + 1)};
        } finally {
            deleteTree(dir);
        }
    }

    /** Entry point inside the container: /job/request.json in, /job/result.json out. */
    static void runJobInContainer(Path dir) throws Exception {
        Map<String, String> job = parseFlatJson(Files.readString(dir.resolve("request.json")));
        RunCtx ctx = new RunCtx();
        RUN_CTX.set(ctx);
        Map<String, String> meta = new LinkedHashMap<>();
        String suite = "";
        try {
            suite = runJobRequest(job);
        } catch (Exception e) {
            meta.put("error", String.valueOf(e));
        }
        long[] phases = ctx.phases(System.currentTimeMillis());
        meta.put("compileMs", String.valueOf(phases[0]));
        meta.put("execMs", String.valueOf(phases[1]));
        meta.put("timedOut", String.valueOf(ctx.timedOut));
        Files.writeString(dir.resolve("result.json"), flatJson(meta) + "\n" + suite);
    }

    // ------------------------------------------------------------------ telemetry
    //
    // One JSON log line per test run (stdout, plus Grafana Loki when LAB_LOKI_URL is set) and Prometheus metrics
    // on /metrics. Metric labels stay low-cardinality (language, outcome, phase); the project id only goes into
    // logs. Source code and client addresses are never logged.

    /** Timings for the run on this request thread; the runners fill it in. */
    static final class RunCtx {
        final long createdAt = System.currentTimeMillis();
        long startedAt = createdAt; // hosted: when a run slot was acquired
        long queueMs;
        long compileDoneAt;
        boolean timedOut;
        long compileMs = -1, execMs = -1; // set when another process (a worker) measured the phases

        /** {compileMs, execMs} for a run that ended at {@code end}. */
        long[] phases(long end) {
            if (compileMs >= 0) return new long[] {compileMs, Math.max(0, execMs)};
            long compile = (compileDoneAt > 0 ? compileDoneAt : end) - startedAt;
            return new long[] {compile, compileDoneAt > 0 ? end - compileDoneAt : 0};
        }
    }

    static final ThreadLocal<RunCtx> RUN_CTX = new ThreadLocal<>();
    static final long STARTED_AT = System.currentTimeMillis();
    static final double[] BUCKETS = {0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60, 120, 240};
    static final Map<String, java.util.concurrent.atomic.LongAdder> RUN_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Histogram> RUN_SECONDS = new java.util.concurrent.ConcurrentHashMap<>();
    static final Pattern SUITE_COUNTS = Pattern.compile("\"passed\":(\\d+),\"failed\":\\d+,\"total\":(\\d+),\"millis\":-?\\d+\\}$");

    /** Cumulative Prometheus-style histogram (bucket i counts observations <= BUCKETS[i]). */
    static final class Histogram {
        final long[] buckets = new long[BUCKETS.length];
        long count;
        double sum;

        synchronized void observe(double seconds) {
            for (int i = 0; i < BUCKETS.length; i++) if (seconds <= BUCKETS[i]) buckets[i]++;
            count++;
            sum += seconds;
        }
    }

    /** Called by a runner once the code has compiled and the tests are about to start. */
    static void compiled() {
        RunCtx ctx = RUN_CTX.get();
        if (ctx != null) ctx.compileDoneAt = System.currentTimeMillis();
    }

    /** Runs a test request, records its outcome and timings, and adds a "timing" object to the response. */
    static String observedRun(String id, String lang, String target, List<String[]> only,
                              java.util.concurrent.Callable<String> body) throws Exception {
        RunCtx ctx = new RunCtx();
        RUN_CTX.set(ctx);
        String json;
        try {
            json = body.call();
        } catch (Exception e) {
            String outcome = e instanceof HttpError h
                    ? (h.status == 429 ? "rate_limited" : h.status == 503 ? "busy" : h.status >= 500 ? "server_error" : "rejected")
                    : e instanceof IllegalArgumentException || e instanceof SecurityException ? "rejected" : "server_error";
            recordRun(id, lang, target, only, outcome, ctx, System.currentTimeMillis(), 0, 0);
            throw e;
        } finally {
            RUN_CTX.remove();
        }
        long end = System.currentTimeMillis();
        int passed = 0, total = 0;
        Matcher m = SUITE_COUNTS.matcher(json);
        if (m.find()) {
            passed = Integer.parseInt(m.group(1));
            total = Integer.parseInt(m.group(2));
        }
        String outcome = ctx.timedOut ? "timeout"
                : json.startsWith("{\"phase\":\"compile\"") ? "compile_error"
                : json.startsWith("{\"phase\":\"test\",\"ok\":true") ? "pass" : "fail";
        long[] t = recordRun(id, lang, target, only, outcome, ctx, end, passed, total);
        return json.substring(0, json.length() - 1) + ",\"timing\":{\"queueMs\":" + t[0] + ",\"compileMs\":" + t[1]
                + ",\"execMs\":" + t[2] + "}}";
    }

    /** Updates the metrics and writes the log line; returns {queueMs, compileMs, execMs}. */
    static long[] recordRun(String id, String lang, String target, List<String[]> only, String outcome, RunCtx ctx,
                            long end, int passed, int total) {
        boolean ran = switch (outcome) {
            case "pass", "fail", "compile_error", "timeout" -> true;
            default -> false;
        };
        long queueMs = ctx.queueMs;
        long[] phases = ctx.phases(end);
        long compileMs = ran ? phases[0] : 0;
        long execMs = ran ? phases[1] : 0;
        long totalMs = end - ctx.createdAt;

        RUN_COUNTS.computeIfAbsent(lang + "|" + outcome, k -> new java.util.concurrent.atomic.LongAdder()).increment();
        if (ran) {
            observe(lang, "queue", queueMs);
            observe(lang, "compile", compileMs);
            if (execMs > 0) observe(lang, "exec", execMs);
            observe(lang, "total", totalMs);
        }

        String line = "{\"event\":\"run\",\"ts\":" + json(java.time.Instant.ofEpochMilli(end).toString())
                + ",\"run_id\":" + json(Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()))
                + ",\"site\":" + json(hosted ? "hosted" : "local")
                + ",\"lang\":" + json(lang)
                + ",\"project\":" + json(Files.isDirectory(projects.resolve(id)) ? id : "unknown")
                + ",\"target\":" + json(target)
                + ",\"selected\":" + (only == null ? 0 : only.size()) + ",\"outcome\":" + json(outcome)
                + ",\"queue_ms\":" + queueMs + ",\"compile_ms\":" + compileMs + ",\"exec_ms\":" + execMs
                + ",\"total_ms\":" + totalMs + ",\"tests_passed\":" + passed + ",\"tests_total\":" + total + "}";
        System.out.println(line);
        shipLog(end, line);
        return new long[] {queueMs, compileMs, execMs};
    }

    static void observe(String lang, String phase, long millis) {
        RUN_SECONDS.computeIfAbsent(lang + "|" + phase, k -> new Histogram()).observe(millis / 1000.0);
    }

    /** GET /metrics in the Prometheus text format. Protected by LAB_METRICS_TOKEN when that is set. */
    static void metrics(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/metrics") || !ex.getRequestMethod().equals("GET")) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        String token = System.getenv("LAB_METRICS_TOKEN");
        if (token != null && !token.isBlank() && !metricsAuthorized(ex.getRequestHeaders().getFirst("Authorization"), token)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"metrics\"");
            send(ex, 401, "text/plain", "unauthorized");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP lab_runs_total Test runs by language and outcome.\n# TYPE lab_runs_total counter\n");
        for (Map.Entry<String, java.util.concurrent.atomic.LongAdder> e : new java.util.TreeMap<>(RUN_COUNTS).entrySet()) {
            String[] k = e.getKey().split("\\|");
            sb.append("lab_runs_total{lang=\"").append(k[0]).append("\",outcome=\"").append(k[1]).append("\"} ")
                    .append(e.getValue().sum()).append('\n');
        }
        sb.append("# HELP lab_run_seconds Time per run phase (queue, compile, exec, total).\n# TYPE lab_run_seconds histogram\n");
        for (Map.Entry<String, Histogram> e : new java.util.TreeMap<>(RUN_SECONDS).entrySet()) {
            String[] k = e.getKey().split("\\|");
            String labels = "lang=\"" + k[0] + "\",phase=\"" + k[1] + "\"";
            Histogram h = e.getValue();
            synchronized (h) {
                for (int i = 0; i < BUCKETS.length; i++) {
                    sb.append("lab_run_seconds_bucket{").append(labels).append(",le=\"").append(BUCKETS[i]).append("\"} ")
                            .append(h.buckets[i]).append('\n');
                }
                sb.append("lab_run_seconds_bucket{").append(labels).append(",le=\"+Inf\"} ").append(h.count).append('\n');
                sb.append("lab_run_seconds_sum{").append(labels).append("} ").append(h.sum).append('\n');
                sb.append("lab_run_seconds_count{").append(labels).append("} ").append(h.count).append('\n');
            }
        }
        Runtime rt = Runtime.getRuntime();
        gauge(sb, "lab_run_slots", "Test runs allowed at the same time.", PARALLEL_RUNS);
        gauge(sb, "lab_runs_active", "Test runs executing now.", PARALLEL_RUNS - RUN_SLOTS.availablePermits());
        gauge(sb, "lab_runs_waiting", "Test runs waiting for a slot.", RUN_SLOTS.getQueueLength());
        if (role.equals("api")) {
            long[] fleet = fleetGauges();
            gauge(sb, "lab_queue_depth", "Runs queued or running in Redis (-1: Redis unreachable).", fleet[0]);
            gauge(sb, "lab_workers_online", "Workers with a heartbeat in the last 30 s (-1: Redis unreachable).", fleet[1]);
        }
        gauge(sb, "lab_jvm_heap_used_bytes", "Server heap in use.", rt.totalMemory() - rt.freeMemory());
        gauge(sb, "lab_jvm_heap_max_bytes", "Server heap limit.", rt.maxMemory());
        gauge(sb, "lab_start_time_seconds", "Server start time (unix seconds).", STARTED_AT / 1000);
        sb.append("# HELP lab_log_lines_dropped_total Run log lines that could not be shipped to Loki.\n")
                .append("# TYPE lab_log_lines_dropped_total counter\nlab_log_lines_dropped_total ")
                .append(LOGS_DROPPED.sum()).append('\n');
        send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", sb.toString());
    }

    static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value).append('\n');
    }

    /** Accepts "Bearer <token>" or Basic auth with the token as the password (Grafana's scraper uses either). */
    static boolean metricsAuthorized(String header, String token) {
        if (header == null) return false;
        String given = null;
        if (header.startsWith("Bearer ")) {
            given = header.substring(7).trim();
        } else if (header.startsWith("Basic ")) {
            try {
                String pair = new String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = pair.indexOf(':');
                if (colon >= 0) given = pair.substring(colon + 1);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return given != null && java.security.MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    static final java.util.concurrent.BlockingQueue<String[]> LOG_QUEUE = new java.util.concurrent.ArrayBlockingQueue<>(5000);
    static final java.util.concurrent.atomic.LongAdder LOGS_DROPPED = new java.util.concurrent.atomic.LongAdder();
    static volatile boolean shipLogs;

    /**
     * Optional: pushes run log lines to Grafana Loki every 10 s.
     * LAB_LOKI_URL = https://<host>/loki/api/v1/push, LAB_LOKI_USER = numeric user id, LAB_LOKI_TOKEN = access token.
     */
    static void startLogShipper() {
        String url = System.getenv("LAB_LOKI_URL");
        if (url == null || url.isBlank()) return;
        String user = System.getenv("LAB_LOKI_USER");
        String token = System.getenv("LAB_LOKI_TOKEN");
        String auth = token == null || token.isBlank() ? null
                : user == null || user.isBlank() ? "Bearer " + token
                : "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        String stream = "{\"app\":\"build-lab\",\"site\":" + json(hosted ? "hosted" : "local") + "}";
        java.util.concurrent.ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-shipper");
            t.setDaemon(true);
            return t;
        });
        shipLogs = true;
        ses.scheduleWithFixedDelay(() -> {
            List<String[]> batch = new ArrayList<>();
            LOG_QUEUE.drainTo(batch, 1000);
            if (batch.isEmpty()) return;
            StringBuilder body = new StringBuilder("{\"streams\":[{\"stream\":").append(stream).append(",\"values\":[");
            for (int i = 0; i < batch.size(); i++) {
                body.append(i > 0 ? "," : "").append('[').append(json(batch.get(i)[0])).append(',')
                        .append(json(batch.get(i)[1])).append(']');
            }
            body.append("]}]}");
            try {
                java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()));
                if (auth != null) req.header("Authorization", auth);
                int status = client.send(req.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status >= 300) {
                    LOGS_DROPPED.add(batch.size());
                    System.err.println("Log shipping failed: HTTP " + status);
                }
            } catch (Exception e) {
                LOGS_DROPPED.add(batch.size());
                System.err.println("Log shipping failed: " + e);
            }
        }, 10, 10, TimeUnit.SECONDS);
        System.out.println("Shipping run logs to " + URI.create(url).getHost());
    }

    static void shipLog(long epochMillis, String line) {
        if (!shipLogs) return;
        if (!LOG_QUEUE.offer(new String[] {epochMillis + "000000", line})) LOGS_DROPPED.increment();
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

    static Proc exec(List<String> cmd, int timeoutSeconds, Path dir, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        if (env != null) {
            pb.environment().clear();
            pb.environment().putAll(env);
        }
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
        if (!done) {
            RunCtx ctx = RUN_CTX.get();
            if (ctx != null) ctx.timedOut = true;
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        pump.join(2000);
        String text = buf.toString(StandardCharsets.UTF_8);
        if (text.length() > 200_000) text = text.substring(0, 200_000) + "\n... output truncated ...";
        return new Proc(done ? p.exitValue() : -1, text, !done);
    }

    static String clean(String output, Path src, Path tests) {
        return output.replace(src.toString() + File.separator, "")
                .replace(tests.toString() + File.separator, "[tests] ");
    }

    static void ensureWorkspace(String id, String lang) throws IOException {
        Path target = workspace.resolve(id).resolve(lang).resolve("src");
        if (Files.isDirectory(target)) return;
        Path starter = langRoot(id, lang).resolve("starter/src");
        if (!Files.isDirectory(starter)) throw new HttpError(404, "not available in " + lang + " yet");
        Files.createDirectories(target);
        try (Stream<Path> s = Files.walk(starter)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = target.resolve(starter.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** One-time move from the single-language layout: workspace/<id>/src -> workspace/<id>/java/src. */
    static void migrateWorkspace() throws IOException {
        try (Stream<Path> s = Files.list(workspace)) {
            for (Path dir : (Iterable<Path>) s::iterator) {
                Path old = dir.resolve("src");
                if (Files.isDirectory(old) && !Files.exists(dir.resolve("java"))) {
                    Files.createDirectories(dir.resolve("java"));
                    Files.move(old, dir.resolve("java").resolve("src"));
                }
            }
        }
        Map<String, String> progress = readProgress();
        boolean changed = false;
        Map<String, String> migrated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : progress.entrySet()) {
            if (e.getKey().contains(":")) migrated.put(e.getKey(), e.getValue());
            else {
                migrated.put("java:" + e.getKey(), e.getValue());
                changed = true;
            }
        }
        if (changed) writeProgress(migrated);
    }

    static Path resolveFile(String id, String lang, String area, String rel) {
        if (rel == null || rel.isBlank()) throw new IllegalArgumentException("path required");
        Path base = switch (area) {
            case "workspace" -> workspace.resolve(id).resolve(lang).resolve("src");
            case "starter" -> langRoot(id, lang).resolve("starter/src");
            case "tests" -> langRoot(id, lang).resolve("tests/src");
            case "solution" -> langRoot(id, lang).resolve("solution/src");
            default -> throw new IllegalArgumentException("bad area");
        };
        Path file = base.resolve(rel).normalize();
        if (!file.startsWith(base) || !editable(lang, file.getFileName().toString())) {
            throw new SecurityException("path outside project");
        }
        return file;
    }

    static List<String> listFiles(Path base, String lang) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : listFilesAbs(base, lang)) out.add(base.relativize(p).toString().replace('\\', '/'));
        return out;
    }

    static List<Path> listFilesAbs(Path base, String lang) throws IOException {
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(p -> Files.isRegularFile(p) && editable(lang, p.getFileName().toString()))
                    .filter(p -> !p.toString().contains("__pycache__"))
                    .sorted().toList();
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
        Matcher m = Pattern.compile("\"([a-z0-9:-]+)\"\\s*:\\s*\"([a-z]+)\"").matcher(text);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    static synchronized void setProgress(String key, String status) throws IOException {
        if (!status.matches("todo|attempted|solved")) throw new IllegalArgumentException("bad status");
        if (!key.matches("(java|python|go|cpp):[a-z0-9-]{1,64}")) throw new IllegalArgumentException("bad key");
        Map<String, String> map = readProgress();
        if (status.equals("todo")) map.remove(key);
        else map.put(key, status);
        writeProgress(map);
    }

    static synchronized void writeProgress(Map<String, String> map) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append(json(k)).append(':').append(json(v));
        });
        sb.append('}');
        Files.writeString(progressFile(), sb);
    }

    static synchronized void setProgressIfEmpty(String key, String status) throws IOException {
        if (!readProgress().containsKey(key)) setProgress(key, status);
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
