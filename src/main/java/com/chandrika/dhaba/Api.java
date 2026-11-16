package com.chandrika.dhaba;

import com.chandrika.dhaba.Http.Ctx;
import com.chandrika.dhaba.Http.Router;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All the web addresses (routes) the browser pages talk to. */
final class Api {
    static final String SESSION_COOKIE = "dhaba_session";

    private final Store store;
    private final Auth auth;
    private final int port;

    Api(Store store, Auth auth, int port) {
        this.store = store;
        this.auth = auth;
        this.port = port;
    }

    private static long parseSince(Ctx c) {
        try { return Math.max(0, Long.parseLong(c.query.getOrDefault("since", "0"))); }
        catch (NumberFormatException e) { return 0; }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    void register(Router r) {
        // ------------------------------------------------------------ customers: menu only (the waiter takes the order)
        r.get("/api/public/kitchen-estimate", false, c -> c.json(200, store.publicKitchenEstimate()));
        r.get("/api/public/menu", false, c -> {
            int table = 0;
            try { table = Integer.parseInt(c.query.getOrDefault("table", "0")); } catch (NumberFormatException ignored) { /* no table */ }
            boolean waiterMode = auth.waiterIdFor(c.header("X-Waiter-Token")) != null;
            c.json(200, store.publicMenu(table, waiterMode));
        });

        // ------------------------------------------------------------ waiter (opens a table with his registered mobile number)
        r.post("/api/waiter/verify", false, c -> {
            if (!auth.allow("wv:" + c.ip(), 15, 10 * 60_000L)) {
                throw new ApiException(429, "Too many tries. Please wait a few minutes.");
            }
            Map<String, Object> b = c.json();
            Map<String, Object> w = store.verifyWaiter(Json.str(b, "phone", ""), (int) Json.lng(b, "table", 0));
            Map<String, Object> out = new LinkedHashMap<>(w);
            out.put("token", auth.startWaiterSession((String) w.get("id")));
            c.json(200, out);
        });
        r.get("/api/waiter/notifications", false, c -> {
            waiterId(c);
            c.json(200, store.notificationsSince(parseSince(c)));
        });
        r.get("/api/waiter/next-order", false, c -> {
            waiterId(c);
            c.json(200, store.nextOrderInfo());
        });
        r.get("/api/waiter/tables/{n}", false, c ->
                c.json(200, store.waiterTable(waiterId(c), c.intParam("n"))));
        r.post("/api/waiter/orders", false, c ->
                c.json(200, store.waiterPlaceOrder(waiterId(c), c.json())));
        r.post("/api/waiter/orders/{id}/served", false, c ->
                c.json(200, store.waiterServe(waiterId(c), c.params.get("id"))));
        r.post("/api/waiter/orders/{id}/lines/{idx}/served", false, c ->
                c.json(200, store.waiterServeLine(waiterId(c), c.params.get("id"), c.intParam("idx"))));
        r.post("/api/waiter/tables/{n}/request-payment", false, c ->
                c.json(200, store.waiterRequestPayment(waiterId(c), c.intParam("n"))));
        r.post("/api/waiter/tables/{n}/pay", false, c ->
                c.json(200, store.waiterPay(waiterId(c), c.intParam("n"), c.json())));

        // ------------------------------------------------------------ manager login
        r.post("/api/manager/login", false, c -> {
            String ip = c.ip();
            long wait = auth.lockedFor(ip);
            if (wait > 0) throw new ApiException(429, "Too many wrong attempts. Try again in " + ((wait + 59) / 60) + " minute(s).");
            Map<String, Object> b = c.json();
            if (!store.checkLogin(Json.str(b, "username", ""), Json.str(b, "password", ""))) {
                auth.loginFailed(ip);
                throw new ApiException(401, "Wrong username or password");
            }
            auth.loginOk(ip);
            String token = auth.startSession();
            c.setCookie(SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + Auth.sessionSeconds());
            c.json(200, ok());
        });

        r.post("/api/manager/logout", false, c -> {
            auth.endSession(c.cookie(SESSION_COOKIE));
            c.setCookie(SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            c.json(200, ok());
        });

        r.get("/api/manager/me", true, c -> {
            Map<String, Object> out = ok();
            out.put("settings", store.managerSettings());
            c.json(200, out);
        });

        // ------------------------------------------------------------ manager: tables and orders
        r.get("/api/manager/notifications", true, c -> c.json(200, store.notificationsSince(parseSince(c))));
        r.get("/api/manager/kitchen", true, c -> c.json(200, store.kitchenQueue()));
        r.post("/api/manager/kitchen/{id}/lines/{idx}/ready", true, c ->
                c.json(200, store.setKitchenLineReady(c.params.get("id"), c.intParam("idx"))));
        r.get("/api/manager/tables", true, c -> c.json(200, store.managerTables()));
        r.get("/api/manager/tables/{n}", true, c -> c.json(200, store.tableDetail(c.intParam("n"))));

        r.post("/api/manager/orders/{id}/lines/{idx}", true, c -> {
            store.setLineQty(c.params.get("id"), c.intParam("idx"), Json.lng(c.json(), "qty", -1));
            c.json(200, ok());
        });

        r.post("/api/manager/tables/{n}/items", true, c ->
                c.json(200, store.addManagerItem(c.intParam("n"), c.json())));
        r.post("/api/manager/tables/{n}/discount", true, c ->
                c.json(200, store.setDiscount(c.intParam("n"), c.json().get("discount"))));

        // ------------------------------------------------------------ manager: waiters, table allocation, payments from waiters
        r.get("/api/manager/waiters", true, c -> c.json(200, store.waiters()));
        r.post("/api/manager/waiters", true, c -> c.json(200, store.saveWaiter(null, c.json())));
        r.put("/api/manager/waiters/{id}", true, c -> c.json(200, store.saveWaiter(c.params.get("id"), c.json())));
        r.delete("/api/manager/waiters/{id}", true, c -> {
            store.deleteWaiter(c.params.get("id"));
            c.json(200, ok());
        });
        r.post("/api/manager/waiters/auto-assign", true, c -> c.json(200, store.autoAssign()));
        r.post("/api/manager/tables/{n}/waiter", true, c -> {
            store.setTableWaiter(c.intParam("n"), Json.str(c.json(), "waiterId", ""));
            c.json(200, store.waiters());
        });
        r.get("/api/manager/payments", true, c -> c.json(200, store.payments(c.query.get("date"))));
        r.post("/api/manager/payments/collect", true, c -> {
            Map<String, Object> b = c.json();
            c.json(200, store.collect(Json.str(b, "date", ""), Json.list(b, "items")));
        });

        // ------------------------------------------------------------ manager: menu
        r.get("/api/manager/menu", true, c -> c.json(200, store.menu()));
        r.post("/api/manager/menu/preparation-types", true, c -> c.json(200, store.addPreparationType(c.json())));
        r.delete("/api/manager/menu/preparation-types/{name}", true, c -> c.json(200, store.removePreparationType(c.params.get("name"))));
        r.post("/api/manager/menu/items", true, c -> c.json(200, store.saveItem(null, c.json())));
        r.put("/api/manager/menu/items/{id}", true, c -> c.json(200, store.saveItem(c.params.get("id"), c.json())));
        r.post("/api/manager/menu/items/{id}/stock", true, c ->
                c.json(200, store.updateStock(c.params.get("id"), c.json())));
        r.post("/api/manager/menu/items/{id}/available", true, c -> {
            store.setAvailable(c.params.get("id"), Json.bool(c.json(), "available", true));
            c.json(200, ok());
        });
        r.delete("/api/manager/menu/items/{id}", true, c -> {
            store.deleteItem(c.params.get("id"));
            c.json(200, ok());
        });
        r.post("/api/manager/menu/categories", true, c -> c.json(200, store.saveCategory(null, c.json())));
        r.put("/api/manager/menu/categories/{id}", true, c -> c.json(200, store.saveCategory(c.params.get("id"), c.json())));
        r.delete("/api/manager/menu/categories/{id}", true, c -> {
            store.deleteCategory(c.params.get("id"));
            c.json(200, ok());
        });
        r.post("/api/manager/menu/categories/{id}/move", true, c -> {
            store.moveCategory(c.params.get("id"), (int) Json.lng(c.json(), "dir", 1));
            c.json(200, ok());
        });

        r.post("/api/manager/upload", true, c -> {
            byte[] data = c.body(3 * 1024 * 1024);
            String ext = imageExtension(data);
            if (ext == null) throw ApiException.bad("Please choose a JPG, PNG or WebP photo");
            String name = Auth.randomToken(10) + "." + ext;
            // dish photos and the welcome-screen picture go to Google Drive when the manager has signed in with Google
            Media.Saved saved = store.media.save(Drive.KIND_PUBLIC, "welcome".equals(c.query.get("for")) ? "welcome" : "dishes", name, data);
            Map<String, Object> out = ok();
            out.put("path", "/uploads/" + name);
            out.put("storage", saved.storage());
            if (!saved.warning().isEmpty()) out.put("warning", saved.warning());
            c.json(200, out);
        });

        // waiter photo and Aadhaar card picture: kept in a private folder, only the logged-in manager can open them
        r.post("/api/manager/upload-private", true, c -> {
            byte[] data = c.body(4 * 1024 * 1024);
            String ext = imageExtension(data);
            if (ext == null) throw ApiException.bad("Please choose a JPG, PNG or WebP photo");
            String name = Auth.randomToken(10) + "." + ext;
            Media.Saved saved = store.media.save(Drive.KIND_PRIVATE, "waiters", name, data);
            Map<String, Object> out = ok();
            out.put("name", name);
            out.put("storage", saved.storage());
            if (!saved.warning().isEmpty()) out.put("warning", saved.warning());
            c.json(200, out);
        });

        // ------------------------------------------------------------ manager: reports and settings
        r.get("/api/manager/reports", true, c ->
                c.json(200, store.reports(c.query.get("from"), c.query.get("to"))));
        r.get("/api/manager/bills/{no}", true, c -> {
            try {
                c.json(200, store.bill(Long.parseLong(c.params.get("no"))));
            } catch (NumberFormatException e) {
                throw ApiException.bad("Invalid bill number");
            }
        });


        // ------------------------------------------------------------ manager: Google account + Drive (Settings -> Google account)
        r.get("/api/manager/google/status", true, c -> c.json(200, googleStatus(c)));
        r.put("/api/manager/google/credentials", true, c -> {
            Map<String, Object> b = c.json();
            store.drive.saveCredentials(Json.str(b, "clientId", ""), Json.str(b, "clientSecret", ""));
            c.json(200, googleStatus(c));
        });
        r.post("/api/manager/google/start", true, c -> {
            Map<String, Object> out = ok();
            out.put("url", store.drive.startSignIn(redirectUri(c)));
            c.json(200, out);
        });
        // Google sends the browser back here. The one-time "state" (made by the logged-in manager) is the proof, because the
        // session cookie is not sent on a redirect that comes from another website.
        r.get("/api/manager/google/callback", false, c -> {
            String err = c.query.get("error");
            try {
                if (err != null) throw ApiException.bad("Google sign-in was cancelled (" + err + ")");
                String mail = store.drive.finishSignIn(c.query.get("state"), c.query.get("code"));
                c.redirect("/manager?google=ok&email=" + java.net.URLEncoder.encode(mail, java.nio.charset.StandardCharsets.UTF_8));
            } catch (ApiException e) {
                c.redirect("/manager?google=error&msg=" + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        r.post("/api/manager/google/disconnect", true, c -> { store.drive.disconnect(); c.json(200, googleStatus(c)); });
        r.put("/api/manager/google/folder", true, c -> {
            Map<String, Object> b = c.json();
            if (b.containsKey("daily")) store.drive.setDailyFolders(Json.bool(b, "daily", true));
            if (b.containsKey("folder")) store.drive.setInvoiceFolder(Json.str(b, "folder", ""));
            c.json(200, googleStatus(c));
        });
        r.post("/api/manager/google/today-folder", true, c -> {
            Map<String, Object> out = store.drive.createTodayFolder();
            out.putAll(googleExtras(c));
            c.json(200, out);
        });
        r.post("/api/manager/google/sync-invoices", true, c -> {
            String d = Json.str(c.json(), "date", "");
            java.time.LocalDate day;
            try { day = d.isBlank() ? java.time.LocalDate.now(store.zone) : java.time.LocalDate.parse(d); }
            catch (java.time.format.DateTimeParseException e) { throw ApiException.bad("Choose a valid date"); }
            int n = store.drive.enqueueDay(day);
            Map<String, Object> out = googleStatus(c);
            out.put("queued", n);
            c.json(200, out);
        });
<<<<<<< HEAD
        r.post("/api/manager/google/backup-now", true, c -> c.json(200, store.drive.backupNow().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new))));
        r.post("/api/manager/google/host-key", true, c -> {
            Map<String, Object> out = ok();
            out.put("refreshToken", store.drive.refreshTokenForHost());
            c.json(200, out);
        });
=======
>>>>>>> origin/main
        r.post("/api/manager/google/retry", true, c -> { store.drive.startWorker(); store.drive.wake(); c.json(200, googleStatus(c)); });
        r.post("/api/manager/google/migrate", true, c -> {
            if (!store.drive.connected()) throw ApiException.conflict("Sign in with Google first.");
            Map<String, Object> out = googleStatus(c);
            out.putAll(store.media.migrateToDrive());
            c.json(200, out);
        });

        r.get("/api/manager/settings", true, c -> c.json(200, store.managerSettings()));
        r.put("/api/manager/settings", true, c -> c.json(200, store.updateSettings(c.json())));
        r.get("/api/manager/server-info", true, c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("port", port);
            out.put("addresses", lanAddresses());
            out.put("host", c.header("Host"));
            c.json(200, out);
        });
    }

    /** The address Google must send the manager back to (it has to be added in the Google Cloud console). */
    private String redirectUri(Ctx c) {
        String host = c.header("Host");
        if (host == null || !host.matches("^[A-Za-z0-9.\\-:\\[\\]]{1,100}$")) host = "localhost:" + port;
        String proto = "https".equalsIgnoreCase(c.header("X-Forwarded-Proto")) ? "https" : "http";
        return proto + "://" + host + "/api/manager/google/callback";
    }

    private Map<String, Object> googleExtras(Ctx c) {
        Map<String, Object> m = new LinkedHashMap<>();
        String uri = redirectUri(c);
        m.put("redirectUri", uri);
        m.put("redirectOk", uri.startsWith("https://") || uri.startsWith("http://localhost") || uri.startsWith("http://127.0.0.1"));
        return m;
    }

    private Map<String, Object> googleStatus(Ctx c) {
        Map<String, Object> m = store.drive.status();
        m.putAll(googleExtras(c));
        return m;
    }

    private String waiterId(Ctx c) {
        String id = auth.waiterIdFor(c.header("X-Waiter-Token"));
        if (id == null) throw new ApiException(401, "Please open the table again with your mobile number");
        if (!c.method().equals("GET") && !"dhaba".equals(c.header("X-Requested-With"))) throw new ApiException(403, "Request blocked");
        return id;
    }

    /** Looks at the first bytes of a file to decide if it really is a JPG, PNG or WebP picture. */
    static String imageExtension(byte[] d) {
        if (d.length > 12 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "jpg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        return null;
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // no network information available
        }
        return out;
    }

    /** Needed by Main to serve uploaded photos. */
    Path uploads() { return store.uploadsDir; }
}
