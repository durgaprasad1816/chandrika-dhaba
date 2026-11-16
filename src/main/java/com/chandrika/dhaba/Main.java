package com.chandrika.dhaba;

import com.chandrika.dhaba.Http.Ctx;
import com.chandrika.dhaba.Http.Router;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Chandrika Dhaba & Restaurant - QR menu, waiter orders, billing and cash collection.
 * Uses only the JDK (com.sun.net.httpserver) - no Spring, no servlets, no libraries.
 *
 * Run:  java -jar target/chandrika-dhaba.jar [--port 8080] [--data ./data] [--password ...]
 */
public final class Main {
    private static final Pattern UPLOAD_NAME = Pattern.compile("^[a-f0-9]{16,40}\\.(jpg|png|webp)$");

    private Main() {}

    private static java.nio.channels.FileChannel lockChannel;
    private static java.nio.channels.FileLock lock;

    /**
     * Where data is kept when --data / DHABA_DATA are not given: the "data" folder of the project (the folder that
     * holds dist/ or target/), NOT whatever folder the terminal happened to be in. Starting the jar from a different
     * folder used to create a second, empty data folder, which looked like saved changes had disappeared.
     */
    static Path defaultDataDir() {
        Path cwdData = Paths.get("data").toAbsolutePath().normalize();
        try {
            Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path folder = jar.getParent();
            String n = folder.getFileName() == null ? "" : folder.getFileName().toString();
            if (n.equals("dist") || n.equals("target")) folder = folder.getParent();
            Path anchored = folder.resolve("data").toAbsolutePath().normalize();
            if (!Files.exists(anchored.resolve("store.json")) && Files.exists(cwdData.resolve("store.json"))) return cwdData;  // older setup
            return anchored;
        } catch (Exception e) {
            return cwdData;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String password = System.getenv("DHABA_PASSWORD");
        String dataDir = System.getenv("DHABA_DATA");   // null = use the data folder next to the app (see defaultDataDir)
        if (System.getenv("PORT") != null) port = Integer.parseInt(System.getenv("PORT").trim());
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--data") && i + 1 < args.length) dataDir = args[++i];
            else if (args[i].equals("--password") && i + 1 < args.length) password = args[++i];
        }

        Path dir = dataDir != null ? Paths.get(dataDir).toAbsolutePath().normalize() : defaultDataDir();
        Files.createDirectories(dir);
        // Two copies of the app on the same data folder overwrite each other's saves (old data "comes back"): allow only one.
        try {
            lockChannel = java.nio.channels.FileChannel.open(dir.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
        } catch (Exception e) { lock = null; }
        if (lock == null) {
            System.err.println("Another Chandrika Dhaba window is already running with this data folder:\n  " + dir
                    + "\nClose that other window first (or this one will overwrite its saved data).");
            return;
        }
        System.out.println("Data folder       : " + dir + "   <- every dish, waiter and bill is saved here");
        // On Render's free plan the disk is empty after every sleep/restart: bring the last backup back from Google Drive first.
        Drive drive = new Drive(dir, null, java.time.ZoneId.of("Asia/Kolkata"));
        drive.restoreIfNewer();
        Store store = new Store(dir, drive);
        Runtime.getRuntime().addShutdownHook(new Thread(drive::flushBackup, "flush-backup"));
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) { System.err.println("Password must be at least 6 characters."); return; }
            store.setStartupPassword(password);
        }
        Auth auth = new Auth();
        Api api = new Api(store, auth, port);
        Router router = new Router();
        api.register(router);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. Close the other program or start with --port 9090");
            return;
        }
        server.createContext("/", ex -> {
            Ctx c = new Ctx(ex);
            try {
                String path = c.path();
                if (path.startsWith("/api/")) {
                    handleApi(c, router, auth);
                } else if (path.startsWith("/private/")) {
                    servePrivate(c, store, auth);
                } else if (path.startsWith("/uploads/")) {
                    serveUpload(c, store);
                } else {
                    serveStatic(c, store);
                }
            } catch (ApiException e) {
                safeJson(c, e.status, e.getMessage());
            } catch (Exception e) {
                System.err.println("Error on " + c.method() + " " + c.path() + ": " + e);
                e.printStackTrace();
                safeJson(c, 500, "Something went wrong on the server");
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(24));
        server.start();

        System.out.println();
        System.out.println("  Chandrika Dhaba & Restaurant is running.  (press Ctrl+C to stop)");
        System.out.println("  Data folder       : " + dir);
        System.out.println("  Manager dashboard : http://localhost:" + port + "/manager");
        if (Boolean.TRUE.equals(store.managerSettings().get("defaultPassword"))) {
            System.out.println("  First login       : username 'manager', password '" + Store.DEFAULT_PASSWORD + "'  (then change it in Settings > Manager login)");
        }
        List<String> ips = Api.lanAddresses();
        if (ips.isEmpty()) {
            System.out.println("  Customer menu     : http://localhost:" + port + "/?table=1");
        } else {
            for (String ip : ips) System.out.println("  Customer menu     : http://" + ip + ":" + port + "/?table=1   (phones on the same Wi-Fi)");
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ API dispatch
    private static void handleApi(Ctx c, Router router, Auth auth) throws Exception {
        boolean[] pathMatched = new boolean[1];
        Router.Route route = router.match(c, pathMatched);
        if (route == null) {
            if (pathMatched[0]) throw new ApiException(405, "Method not allowed");
            throw ApiException.notFound("Not found");
        }
        if (route.auth()) {
            if (!auth.isValid(c.cookie(Api.SESSION_COOKIE))) throw new ApiException(401, "Please log in again");
            String m = c.method();
            if (!m.equals("GET") && !"dhaba".equals(c.header("X-Requested-With"))) {
                throw new ApiException(403, "Request blocked");
            }
        }
        route.handler().handle(c);
    }

    private static void safeJson(Ctx c, int status, String message) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", message);
            c.json(status, m);
        } catch (Exception ignored) {
            // response was already started; nothing more to do
        }
    }

    // ------------------------------------------------------------------ files
    private static void serveStatic(Ctx c, Store store) throws IOException {
        String m = c.method();
        if (!m.equals("GET") && !m.equals("HEAD")) throw new ApiException(405, "Method not allowed");
        String path = c.path();
        if (path.equals("/")) path = "/index.html";
        if (path.equals("/manager") || path.equals("/manager/")) path = "/manager.html";
        if (path.contains("..") || path.contains("\\") || path.contains("\0") || path.endsWith("/")) {
            throw ApiException.notFound("Not found");
        }
        try (InputStream in = Main.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                c.send(404, "text/plain; charset=utf-8", "Page not found".getBytes(), "no-store");
                return;
            }
            byte[] data = in.readAllBytes();
            if (path.equals("/index.html")) data = fillWelcome(new String(data, java.nio.charset.StandardCharsets.UTF_8), store).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String type = contentType(path);
            boolean code = path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css");
            c.send(200, type, data, code ? "no-cache" : "public, max-age=604800");
        }
    }

    /** Waiter photos and Aadhaar pictures: manager session required, never cached. */
    private static void servePrivate(Ctx c, Store store, Auth auth) throws IOException {
        if (!auth.isValid(c.cookie(Api.SESSION_COOKIE))) throw new ApiException(401, "Please log in again");
        String name = c.path().substring("/private/".length());
        c.send(200, contentType(name), store.privateBytes(name), "private, no-store");
    }

    /** Dish photos and the welcome picture: from this computer, the local cache, or Google Drive. */
    private static void serveUpload(Ctx c, Store store) throws IOException {
        String name = c.path().substring("/uploads/".length());
        if (!UPLOAD_NAME.matcher(name).matches()) throw ApiException.notFound("Not found");
        byte[] data = store.media.read(Drive.KIND_PUBLIC, name);
        if (data == null) throw ApiException.notFound("Not found");
        c.send(200, contentType(name), data, "public, max-age=2592000, immutable");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The welcome screen picture and restaurant name come from Settings, so there is no flash of the old picture. */
    private static String fillWelcome(String html, Store store) {
        String img = store.splashImage();
        if (img.isEmpty()) img = "/img/welcome-dish.jpg";
        return html.replace("__SPLASH_IMAGE__", esc(img)).replace("__DHABA_NAME__", esc(store.dhabaInfo().get("dhabaName").toString()));
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
