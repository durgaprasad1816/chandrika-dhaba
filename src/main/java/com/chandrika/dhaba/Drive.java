package com.chandrika.dhaba;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google sign-in and Google Drive storage, using only the JDK (java.net.http) - no Google libraries.
 *
 * What goes to Drive:
 *   - every paid invoice (PDF + the payment photo) into the manager's invoice folder, in a dated sub-folder per day
 *   - dish photos, waiter photos / Aadhaar pictures and the welcome-screen picture (see {@link Media})
 *
 * The manager signs in with Google once in Settings. We keep the refresh token in data/google.json.
 * If Drive is slow or offline a sale is never blocked: invoices wait in a queue and are retried every 30 seconds.
 */
final class Drive {
    // Endpoints can be redirected to a test server with -Ddhaba.google.* (used by the automated tests).
    private static String prop(String k, String def) { String v = System.getProperty(k); return v == null || v.isBlank() ? def : v; }
    static final String AUTH_URL = prop("dhaba.google.auth", "https://accounts.google.com/o/oauth2/v2/auth");
    static final String TOKEN_URL = prop("dhaba.google.token", "https://oauth2.googleapis.com/token");
    static final String API = prop("dhaba.google.api", "https://www.googleapis.com/drive/v3");
    static final String UPLOAD = prop("dhaba.google.upload", "https://www.googleapis.com/upload/drive/v3");
    static final String USERINFO = prop("dhaba.google.userinfo", "https://openidconnect.googleapis.com/v1/userinfo");
    // "drive" (not "drive.file") so the manager can paste ANY folder of their own Drive as the invoice folder.
    static final String SCOPE = "openid email https://www.googleapis.com/auth/drive";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    static final String KIND_PUBLIC = "public";     // dish photos, welcome picture
    static final String KIND_PRIVATE = "private";   // waiter photo, Aadhaar picture

    private static final Pattern FOLDER_ID = Pattern.compile("^[A-Za-z0-9_-]{10,100}$");
    private static final Pattern FOLDER_URL = Pattern.compile("/folders/([A-Za-z0-9_-]{10,100})");
    private static final Pattern ID_PARAM = Pattern.compile("[?&]id=([A-Za-z0-9_-]{10,100})");

    private final Path configFile;
    private volatile Store store;
    private final ZoneId zone;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    // ---- persisted state (guarded by "this")
    private String clientId = "";
    private String clientSecret = "";
    private String refreshToken = "";
    private String email = "";
    private String invoiceFolderId = "";
    private String invoiceFolderName = "";
    private boolean dailyFolders = true;
    private String dataFolderId = "";
    private final Map<String, String> subFolders = new LinkedHashMap<>();           // "dishes" / "waiters" / "welcome" -> folder id
    private final Map<String, Map<String, Object>> files = new LinkedHashMap<>();    // stored image name -> {id, kind}
    private final Map<String, Map<String, Object>> invoices = new LinkedHashMap<>(); // bill no -> {id, folder, name, at}
    private final List<Long> pending = new ArrayList<>();                            // bill numbers waiting to be uploaded
    private String lastError = "";
    private boolean needsReconnect = false;

    // ---- memory only
    private String accessToken = "";
    private long accessExpiry = 0;
    private final Map<String, String> folderCache = new ConcurrentHashMap<>();
    private final Map<String, Object[]> oauthStates = new ConcurrentHashMap<>();     // state -> {redirectUri, expiresAt}
    private final Object workerLock = new Object();
    private volatile boolean workerStarted = false;

    private final Path dir;

    void attach(Store s) { this.store = s; }

    Drive(Path dir, Store store, ZoneId zone) {
        this.dir = dir;
        this.configFile = dir.resolve("google.json");
        this.store = store;
        this.zone = zone;
        load();
        // Order of precedence: what was typed in Settings (google.json) > host environment variables > google-defaults.properties
        Properties baked = bakedDefaults();
        if (clientId.isEmpty()) clientId = firstOf(System.getenv("DHABA_GOOGLE_CLIENT_ID"), baked.getProperty("client.id"));
        if (clientSecret.isEmpty()) clientSecret = firstOf(System.getenv("DHABA_GOOGLE_CLIENT_SECRET"), baked.getProperty("client.secret"));
        // On hosts that wipe files (Render free plan) the sign-in comes from here instead of google.json.
        if (refreshToken.isEmpty()) {
            String t = firstOf(System.getenv("DHABA_GOOGLE_REFRESH_TOKEN"), baked.getProperty("refresh.token"));
            if (!t.isEmpty()) { refreshToken = t; needsReconnect = false; }
        }
        if (email.isEmpty()) email = firstOf(System.getenv("DHABA_OWNER_EMAIL"), baked.getProperty("owner.email"));
    }

    private static String firstOf(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank() && !v.strip().startsWith("PASTE_")) return v.strip();
        return "";
    }

    /**
     * google-defaults.properties holds the Google app keys and the saved Google sign-in of the dhaba owner, so a fresh,
     * empty server (Render free plan) can reconnect to Drive and restore the data by itself. It is looked for next to
     * the app (working folder, then the project folder) and finally inside the jar.
     */
    static Properties bakedDefaults() {
        Properties p = new Properties();
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get("google-defaults.properties"));
        try {
            Path jar = Paths.get(Drive.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path folder = Files.isDirectory(jar) ? jar : jar.getParent();
            if (folder != null) {
                candidates.add(folder.resolve("google-defaults.properties"));
                if (folder.getParent() != null) candidates.add(folder.getParent().resolve("google-defaults.properties"));
            }
        } catch (Exception ignored) { /* running from an unusual place; the other locations still work */ }
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                try (var in = Files.newBufferedReader(c, StandardCharsets.UTF_8)) { p.load(in); return p; }
                catch (IOException e) { System.err.println("Could not read " + c + ": " + e.getMessage()); }
            }
        }
        try (var in = Drive.class.getResourceAsStream("/google-defaults.properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) { /* no defaults file */ }
        return p;
    }

    // ================================================================= persistence

    private void load() {
        if (!Files.exists(configFile)) return;
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(configFile, StandardCharsets.UTF_8));
            clientId = Json.str(m, "clientId", "");
            clientSecret = Json.str(m, "clientSecret", "");
            refreshToken = Json.str(m, "refreshToken", "");
            email = Json.str(m, "email", "");
            invoiceFolderId = Json.str(m, "invoiceFolderId", "");
            invoiceFolderName = Json.str(m, "invoiceFolderName", "");
            dailyFolders = Json.bool(m, "dailyFolders", true);
            dataFolderId = Json.str(m, "dataFolderId", "");
            needsReconnect = Json.bool(m, "needsReconnect", false);
            for (Map.Entry<String, Object> e : Json.map(m.get("subFolders")).entrySet()) subFolders.put(e.getKey(), String.valueOf(e.getValue()));
            for (Map.Entry<String, Object> e : Json.map(m.get("files")).entrySet()) files.put(e.getKey(), new LinkedHashMap<>(Json.map(e.getValue())));
            for (Map.Entry<String, Object> e : Json.map(m.get("invoices")).entrySet()) invoices.put(e.getKey(), new LinkedHashMap<>(Json.map(e.getValue())));
            for (Object o : Json.list(m, "pending")) if (o instanceof Number n) pending.add(n.longValue());
        } catch (Exception e) {
            System.err.println("Could not read google.json: " + e.getMessage());
        }
    }

    private synchronized void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", clientId);
        m.put("clientSecret", clientSecret);
        m.put("refreshToken", refreshToken);
        m.put("email", email);
        m.put("invoiceFolderId", invoiceFolderId);
        m.put("invoiceFolderName", invoiceFolderName);
        m.put("dailyFolders", dailyFolders);
        m.put("dataFolderId", dataFolderId);
        m.put("needsReconnect", needsReconnect);
        m.put("subFolders", new LinkedHashMap<>(subFolders));
        m.put("files", new LinkedHashMap<>(files));
        m.put("invoices", new LinkedHashMap<>(invoices));
        m.put("pending", new ArrayList<>(pending));
        try {
            Path tmp = configFile.resolveSibling("google.json.tmp");
            Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) { /* not POSIX (Windows) */ }
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not save google.json: " + e.getMessage());
        }
    }

    // ================================================================= status / settings (for the manager page)

    synchronized boolean connected() { return !refreshToken.isEmpty() && !needsReconnect; }
    synchronized boolean configured() { return !clientId.isEmpty() && !clientSecret.isEmpty(); }

    static String folderUrl(String id) { return id == null || id.isEmpty() ? "" : "https://drive.google.com/drive/folders/" + id; }

    synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("clientId", clientId);
        m.put("clientSecretSet", !clientSecret.isEmpty());
        m.put("connected", connected());
        m.put("needsReconnect", needsReconnect);
        m.put("email", email);
        m.put("invoiceFolderId", invoiceFolderId);
        m.put("invoiceFolderName", invoiceFolderName);
        m.put("invoiceFolderUrl", folderUrl(invoiceFolderId));
        m.put("dailyFolders", dailyFolders);
        m.put("dataFolderUrl", folderUrl(dataFolderId));
        m.put("pendingInvoices", pending.size());
        m.put("uploadedInvoices", invoices.size());
        m.put("driveImages", files.size());
        m.put("lastError", lastError);
        m.put("lastBackup", lastBackupAt);
        m.put("backupError", backupError);
        m.put("todayFolderName", LocalDate.now(zone).toString());
        return m;
    }

    synchronized Map<String, Object> saveCredentials(String id, String secret) {
        id = id == null ? "" : id.strip();
        secret = secret == null ? "" : secret.strip();
        if (id.isEmpty()) throw ApiException.bad("Paste the Google Client ID");
        if (id.length() > 300 || secret.length() > 300) throw ApiException.bad("That value is too long");
        boolean changed = !id.equals(clientId) || (!secret.isEmpty() && !secret.equals(clientSecret));
        if (secret.isEmpty() && clientSecret.isEmpty()) throw ApiException.bad("Paste the Google Client Secret");
        clientId = id;
        if (!secret.isEmpty()) clientSecret = secret;
        if (changed) { refreshToken = ""; email = ""; needsReconnect = false; accessToken = ""; accessExpiry = 0; }
        save();
        return status();
    }

    synchronized Map<String, Object> disconnect() {
        refreshToken = ""; email = ""; accessToken = ""; accessExpiry = 0; needsReconnect = false; lastError = "";
        folderCache.clear();
        save();
        return status();
    }

    synchronized Map<String, Object> setDailyFolders(boolean on) {
        dailyFolders = on;
        save();
        return status();
    }

    /** Accepts a Drive folder link or a bare folder id. */
    static String parseFolderId(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) return "";
        Matcher m = FOLDER_URL.matcher(s);
        if (m.find()) return m.group(1);
        m = ID_PARAM.matcher(s);
        if (m.find()) return m.group(1);
        if (FOLDER_ID.matcher(s).matches()) return s;
        throw ApiException.bad("That does not look like a Google Drive folder link. Open the folder in Drive, copy the address from the browser and paste it here.");
    }

    Map<String, Object> setInvoiceFolder(String raw) {
        String id = parseFolderId(raw);
        if (id.isEmpty()) {
            synchronized (this) { invoiceFolderId = ""; invoiceFolderName = ""; save(); return status(); }
        }
        requireConnected();
        try {
            Map<String, Object> f = apiJson("GET", API + "/files/" + enc(id) + "?supportsAllDrives=true&fields=" + enc("id,name,mimeType,trashed,capabilities/canAddChildren"), null);
            if (!FOLDER_MIME.equals(Json.str(f, "mimeType", ""))) throw ApiException.bad("That link is a file, not a folder.");
            if (Json.bool(f, "trashed", false)) throw ApiException.bad("That folder is in the Drive bin.");
            Map<String, Object> caps = Json.map(f.get("capabilities"));
            if (caps.containsKey("canAddChildren") && !Json.bool(caps, "canAddChildren", true)) {
                throw ApiException.bad("This Google account can only view that folder. Choose a folder you can edit.");
            }
            synchronized (this) { invoiceFolderId = id; invoiceFolderName = Json.str(f, "name", ""); save(); }
        } catch (ApiException e) {
            throw e;
        } catch (DriveException e) {
            if (e.status == 404) throw ApiException.bad("Folder not found. Check the link and that you opened it with the same Google account.");
            throw ApiException.bad("Google Drive said: " + e.getMessage());
        }
        return status();
    }

    /** Creates (or finds) today's dated folder so the manager can see it straight away. */
    Map<String, Object> createTodayFolder() {
        requireConnected();
        try {
            String parent = invoiceRoot();
            String name = LocalDate.now(zone).toString();
            String id = ensureFolder(parent, name);
            Map<String, Object> out = status();
            out.put("todayFolderUrl", folderUrl(id));
            return out;
        } catch (DriveException e) {
            throw new ApiException(502, "Google Drive said: " + e.getMessage());
        }
    }

    private void requireConnected() {
        if (!connected()) throw ApiException.conflict("Sign in with Google first (Settings -> Google account).");
    }

    // ================================================================= OAuth sign-in

    String startSignIn(String redirectUri) {
        synchronized (this) {
            if (!configured()) throw ApiException.bad("Save the Google Client ID and Client Secret first.");
        }
        long now = System.currentTimeMillis();
        oauthStates.values().removeIf(v -> (Long) v[1] < now);
        String state = Auth.randomToken(16);
        oauthStates.put(state, new Object[]{redirectUri, now + 10 * 60_000L});
        return AUTH_URL + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPE)
                + "&access_type=offline&prompt=consent&include_granted_scopes=true"
                + "&state=" + state;
    }

    /** Called when Google sends the manager's browser back. Returns the connected e-mail address. */
    String finishSignIn(String state, String code) {
        Object[] st = state == null ? null : oauthStates.remove(state);
        if (st == null || (Long) st[1] < System.currentTimeMillis()) throw ApiException.bad("The sign-in link expired. Start again from Settings.");
        String redirectUri = (String) st[0];
        if (code == null || code.isBlank()) throw ApiException.bad("Google did not send a sign-in code.");
        try {
            Map<String, Object> t = tokenRequest("grant_type=authorization_code&code=" + enc(code)
                    + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret) + "&redirect_uri=" + enc(redirectUri));
            String access = Json.str(t, "access_token", "");
            String refresh = Json.str(t, "refresh_token", "");
            if (access.isEmpty()) throw ApiException.bad("Google did not give access. Try again.");
            String scopeGot = Json.str(t, "scope", "");
            if (!scopeGot.isEmpty() && !scopeGot.contains("auth/drive")) {
                throw ApiException.bad("Please tick the Google Drive permission on the Google screen, otherwise invoices cannot be saved.");
            }
            String mail = "";
            try {
                Map<String, Object> u = apiJson("GET", USERINFO, null, access);
                mail = Json.str(u, "email", "");
            } catch (Exception ignored) { /* the e-mail is only shown in Settings */ }
            synchronized (this) {
                if (!refresh.isEmpty()) refreshToken = refresh;
                if (refreshToken.isEmpty()) throw ApiException.bad("Google did not return long-term access. Remove this app at myaccount.google.com/permissions and sign in again.");
                email = mail;
                needsReconnect = false;
                lastError = "";
                accessToken = access;
                accessExpiry = System.currentTimeMillis() + Math.max(60, Json.lng(t, "expires_in", 3600)) * 1000L - 60_000L;
                save();
            }
            startWorker();
            wake();
            startBackupWorker();
            markDirty();
            return mail;
        } catch (DriveException e) {
            throw ApiException.bad("Google sign-in failed: " + e.getMessage());
        }
    }

    // ================================================================= access token + HTTP

    static final class DriveException extends Exception {
        final int status;
        DriveException(int status, String msg) { super(msg); this.status = status; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private Map<String, Object> tokenRequest(String form) throws DriveException {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<byte[]> r = http.send(rq, HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(r.body(), StandardCharsets.UTF_8);
            if (r.statusCode() / 100 != 2) {
                String msg = body;
                try { Map<String, Object> e = Json.parseObject(body); msg = Json.str(e, "error_description", Json.str(e, "error", body)); } catch (Exception ignored) { /* raw body */ }
                throw new DriveException(r.statusCode(), msg);
            }
            return Json.parseObject(body);
        } catch (IOException e) {
            throw new DriveException(0, "cannot reach Google (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriveException(0, "interrupted");
        }
    }

    private String accessToken() throws DriveException {
        String rt;
        synchronized (this) {
            if (refreshToken.isEmpty()) throw new DriveException(401, "Google account is not connected");
            if (needsReconnect) throw new DriveException(401, "Google sign-in expired - sign in again in Settings");
            if (!accessToken.isEmpty() && System.currentTimeMillis() < accessExpiry) return accessToken;
            rt = refreshToken;
        }
        try {
            Map<String, Object> t = tokenRequest("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret));
            synchronized (this) {
                accessToken = Json.str(t, "access_token", "");
                accessExpiry = System.currentTimeMillis() + Math.max(120, Json.lng(t, "expires_in", 3600)) * 1000L - 60_000L;
                return accessToken;
            }
        } catch (DriveException e) {
            if (e.status == 400 || e.status == 401) {   // invalid_grant: the manager removed access or the token expired
                synchronized (this) { needsReconnect = true; lastError = "Google sign-in expired. Sign in again in Settings."; save(); }
            }
            throw e;
        }
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder b, String token) throws DriveException {
        try {
            return http.send(b.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DriveException(0, "cannot reach Google Drive (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriveException(0, "interrupted");
        }
    }

    private Map<String, Object> apiJson(String method, String url, Map<String, Object> body) throws DriveException {
        return apiJson(method, url, body, null);
    }

    private Map<String, Object> apiJson(String method, String url, Map<String, Object> body, String fixedToken) throws DriveException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = fixedToken != null ? fixedToken : accessToken();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
            if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
            else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
            HttpResponse<byte[]> r = send(b, token);
            if (r.statusCode() == 401 && fixedToken == null && attempt == 0) { synchronized (this) { accessToken = ""; } continue; }
            if (r.statusCode() / 100 != 2) throw new DriveException(r.statusCode(), errorText(r));
            String s = new String(r.body(), StandardCharsets.UTF_8);
            return s.isBlank() ? new LinkedHashMap<>() : Json.parseObject(s);
        }
        throw new DriveException(401, "Google rejected the sign-in");
    }

    private static String errorText(HttpResponse<byte[]> r) {
        String s = new String(r.body(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> e = Json.map(Json.parseObject(s).get("error"));
            String m = Json.str(e, "message", "");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) { /* not JSON */ }
        return "HTTP " + r.statusCode();
    }

    // ================================================================= Drive operations

    private static String q(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

    private String findFolder(String parent, String name) throws DriveException {
        String qq = "mimeType='" + FOLDER_MIME + "' and name='" + q(name) + "' and trashed=false"
                + (parent == null || parent.isEmpty() ? " and 'root' in parents" : " and '" + parent + "' in parents");
        Map<String, Object> r = apiJson("GET", API + "/files?supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=1&fields=" + enc("files(id)") + "&q=" + enc(qq), null);
        List<Object> l = Json.list(r, "files");
        return l.isEmpty() ? "" : Json.str(Json.map(l.get(0)), "id", "");
    }

    private String createFolder(String parent, String name) throws DriveException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("mimeType", FOLDER_MIME);
        if (parent != null && !parent.isEmpty()) b.put("parents", List.of(parent));
        return Json.str(apiJson("POST", API + "/files?supportsAllDrives=true&fields=id", b), "id", "");
    }

    /** Finds a folder by name inside parent (or My Drive when parent is empty), creating it when missing. */
    String ensureFolder(String parent, String name) throws DriveException {
        String key = (parent == null ? "" : parent) + "/" + name;
        String cached = folderCache.get(key);
        if (cached != null) return cached;
        String id = findFolder(parent, name);
        if (id.isEmpty()) id = createFolder(parent, name);
        if (id.isEmpty()) throw new DriveException(502, "Google Drive did not create the folder");
        folderCache.put(key, id);
        return id;
    }

    private String uploadFile(String parent, String name, String mime, byte[] data) throws DriveException {
        String boundary = "dhaba" + Auth.randomToken(8);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        if (parent != null && !parent.isEmpty()) meta.put("parents", List.of(parent));
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 512);
        try {
            out.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + Json.stringify(meta) + "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        byte[] body = out.toByteArray();
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(UPLOAD + "/files?uploadType=multipart&supportsAllDrives=true&fields=id"))
                    .header("Content-Type", "multipart/related; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = send(b, token);
            if (r.statusCode() == 401 && attempt == 0) { synchronized (this) { accessToken = ""; } continue; }
            if (r.statusCode() / 100 != 2) throw new DriveException(r.statusCode(), errorText(r));
            return Json.str(Json.parseObject(new String(r.body(), StandardCharsets.UTF_8)), "id", "");
        }
        throw new DriveException(401, "Google rejected the sign-in");
    }

    private byte[] downloadFile(String id) throws DriveException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + "/files/" + enc(id) + "?alt=media&supportsAllDrives=true")).GET();
            HttpResponse<byte[]> r = send(b, token);
            if (r.statusCode() == 401 && attempt == 0) { synchronized (this) { accessToken = ""; } continue; }
            if (r.statusCode() / 100 != 2) throw new DriveException(r.statusCode(), errorText(r));
            return r.body();
        }
        throw new DriveException(401, "Google rejected the sign-in");
    }

    private String findFileByName(String parent, String name) throws DriveException {
        String qq = "name='" + q(name) + "' and '" + parent + "' in parents and trashed=false";
        Map<String, Object> r = apiJson("GET", API + "/files?supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=1&fields=" + enc("files(id)") + "&q=" + enc(qq), null);
        List<Object> l = Json.list(r, "files");
        return l.isEmpty() ? "" : Json.str(Json.map(l.get(0)), "id", "");
    }

    // ================================================================= images (dish photos, waiter photos, welcome picture)

    private static final Map<String, String> SUBFOLDER_NAMES = Map.of(
            "dishes", "Dish Images",
            "waiters", "Waiter Photos and Documents",
            "welcome", "Welcome Screen");

    /** The "Chandrika Dhaba - Data" folder in My Drive and its sub-folders (created the first time they are needed). */
    private String dataSubFolder(String sub) throws DriveException {
        String root;
        synchronized (this) { root = dataFolderId; }
        if (root.isEmpty()) {
            String name = "Chandrika Dhaba - Data";
            root = ensureFolder("", name);
            synchronized (this) { dataFolderId = root; save(); }
        }
        String have;
        synchronized (this) { have = subFolders.getOrDefault(sub, ""); }
        if (!have.isEmpty()) return have;
        String id = ensureFolder(root, SUBFOLDER_NAMES.getOrDefault(sub, sub));
        synchronized (this) { subFolders.put(sub, id); save(); }
        return id;
    }

    /** Uploads an image. sub = dishes | waiters | welcome. Returns the Drive file id. */
    String putImage(String sub, String kind, String name, String mime, byte[] data) throws DriveException {
        String id = uploadFile(dataSubFolder(sub), name, mime, data);
        if (id.isEmpty()) throw new DriveException(502, "Google Drive did not return a file id");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("kind", kind);
        e.put("sub", sub);
        synchronized (this) { files.put(name, e); save(); }
        return id;
    }

    /** @return the picture bytes, or null when this name is not stored in Drive (or Drive cannot be reached). */
    byte[] getImage(String name, String kind) {
        String id;
        synchronized (this) {
            Map<String, Object> e = files.get(name);
            if (e == null || !kind.equals(Json.str(e, "kind", ""))) return null;
            id = Json.str(e, "id", "");
        }
        if (id.isEmpty() || !connected()) return null;
        try {
            return downloadFile(id);
        } catch (DriveException e) {
            noteError("Could not read a picture from Google Drive: " + e.getMessage());
            return null;
        }
    }

    synchronized boolean hasImage(String name, String kind) {
        Map<String, Object> e = files.get(name);
        return e != null && kind.equals(Json.str(e, "kind", ""));
    }

    /** Moves a picture to the Drive bin (recoverable for 30 days). */
    void trashImage(String name) {
        String id;
        synchronized (this) {
            Map<String, Object> e = files.remove(name);
            if (e == null) return;
            id = Json.str(e, "id", "");
            save();
        }
        if (id.isEmpty() || !connected()) return;
        try {
            apiJson("PATCH", API + "/files/" + enc(id) + "?supportsAllDrives=true", Map.of("trashed", true));
        } catch (DriveException ignored) { /* a leftover file in Drive is harmless */ }
    }


    // ================================================================= backup + restore of ALL data (for hosts that erase files)
    // store.json (dishes, waiters, settings, open tables), bills.jsonl, collections.jsonl and a copy of the Drive picture index
    // are copied to Drive folder "Chandrika Dhaba - Data / Backup" a few seconds after every change. When the app starts on
    // an empty/reset disk it copies them back before loading, so nothing saved is lost when Render sleeps or restarts.

    private static final String[] BACKUP_FILES = {"store.json", "bills.jsonl", "collections.jsonl"};
    private static final String STATE_FILE = "drive-state.json";
    private String lastBackupAt = "";
    private String backupError = "";
    private final Map<String, String> uploadedSig = new ConcurrentHashMap<>();
    private final Map<String, String> backupIds = new ConcurrentHashMap<>();
    private volatile boolean backupStarted = false;
    private volatile boolean restoreFailed = false;
    private volatile boolean dirty = false;
    private final Object backupLock = new Object();
    private volatile String backupFolderId = "";

    private Path stampFile() { return dir.resolve(".backup-stamp"); }

    private String readStamp() {
        try { return Files.exists(stampFile()) ? Files.readString(stampFile()).strip() : ""; } catch (IOException e) { return ""; }
    }

    private String backupFolder(boolean create) throws DriveException {
        if (!backupFolderId.isEmpty()) return backupFolderId;
        String root = create ? ensureFolder("", "Chandrika Dhaba - Data") : findFolder("", "Chandrika Dhaba - Data");
        if (root.isEmpty()) return "";
        String b = create ? ensureFolder(root, "Backup") : findFolder(root, "Backup");
        if (!b.isEmpty()) backupFolderId = b;
        return b;
    }

    private Map<String, Object> fileMeta(String parent, String name) throws DriveException {
        String qq = "name='" + q(name) + "' and '" + parent + "' in parents and trashed=false";
        Map<String, Object> r = apiJson("GET", API + "/files?supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=1&fields=" + enc("files(id,modifiedTime)") + "&q=" + enc(qq), null);
        List<Object> l = Json.list(r, "files");
        return l.isEmpty() ? null : Json.map(l.get(0));
    }

    /** Call before the Store is created. Copies the Drive backup over the local files when the backup is newer than what this disk has. */
    void restoreIfNewer() {
        try {
            if (!connected() || !configured()) return;
            String folder = backupFolder(false);
            if (folder.isEmpty()) return;
            Map<String, Object> meta = fileMeta(folder, "store.json");
            if (meta == null) return;
            String remote = Json.str(meta, "modifiedTime", "");
            String stamp = readStamp();
            if (!stamp.isEmpty() && stamp.compareTo(remote) >= 0 && Files.exists(dir.resolve("store.json"))) return;   // this disk is already up to date
            System.out.println("Restoring your saved data from Google Drive (backup of " + remote + ") ...");
            for (String n : BACKUP_FILES) {
                Map<String, Object> m = n.equals("store.json") ? meta : fileMeta(folder, n);
                if (m == null) continue;
                byte[] data = downloadFile(Json.str(m, "id", ""));
                Path tmp = dir.resolve(n + ".restore");
                Files.write(tmp, data);
                Files.move(tmp, dir.resolve(n), StandardCopyOption.REPLACE_EXISTING);
                uploadedSig.put(n, sig(dir.resolve(n)));
            }
            Map<String, Object> sm = fileMeta(folder, STATE_FILE);
            if (sm != null) {
                Map<String, Object> st = Json.parseObject(new String(downloadFile(Json.str(sm, "id", "")), StandardCharsets.UTF_8));
                synchronized (this) {
                    for (Map.Entry<String, Object> e : Json.map(st.get("subFolders")).entrySet()) subFolders.put(e.getKey(), String.valueOf(e.getValue()));
                    for (Map.Entry<String, Object> e : Json.map(st.get("files")).entrySet()) files.put(e.getKey(), new LinkedHashMap<>(Json.map(e.getValue())));
                    for (Map.Entry<String, Object> e : Json.map(st.get("invoices")).entrySet()) invoices.put(e.getKey(), new LinkedHashMap<>(Json.map(e.getValue())));
                    for (Object o : Json.list(st, "pending")) if (o instanceof Number n && !pending.contains(n.longValue())) pending.add(n.longValue());
                    if (invoiceFolderId.isEmpty()) { invoiceFolderId = Json.str(st, "invoiceFolderId", ""); invoiceFolderName = Json.str(st, "invoiceFolderName", ""); }
                    if (dataFolderId.isEmpty()) dataFolderId = Json.str(st, "dataFolderId", "");
                    dailyFolders = Json.bool(st, "dailyFolders", dailyFolders);
                    save();
                }
            }
            Files.writeString(stampFile(), remote);
            System.out.println("Restore finished.");
        } catch (Exception e) {
            restoreFailed = true;
            System.err.println("Could not restore from Google Drive; backups are paused so the saved copy is not overwritten: " + e.getMessage());
        }
    }

    private static String sig(Path p) {
        try { return Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return ""; }
    }

    void markDirty() { dirty = true; synchronized (backupLock) { backupLock.notifyAll(); } }

    void startBackupWorker() {
        if (backupStarted) return;
        synchronized (backupLock) { if (backupStarted) return; backupStarted = true; }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    synchronized (backupLock) { backupLock.wait(dirty ? 6_000L : 20_000L); }
                    dirty = false;
                    if (connected()) backupChanged();
                } catch (InterruptedException e) { return; }
                catch (Throwable e) { synchronized (this) { backupError = "Backup problem: " + e.getMessage(); } }
            }
        }, "drive-backup-worker");
        t.setDaemon(true);
        t.start();
    }

    /** Uploads the data files that changed since the last upload. */
    int backupChanged() {
        int n = 0;
        try {
            if (restoreFailed) {
                synchronized (this) { backupError = "Backup paused: your saved data could not be restored from Drive at start-up, so the app will not overwrite it. Restart the app."; }
                return 0;
            }
            String folder = backupFolder(true);
            // Never replace a newer Drive backup with data that was not restored from it (for example a fresh, empty Render disk).
            Map<String, Object> remoteMeta = fileMeta(folder, "store.json");
            if (remoteMeta != null) {
                String remote = Json.str(remoteMeta, "modifiedTime", "");
                String stamp = readStamp();
                if (stamp.isEmpty() || stamp.compareTo(remote) < 0) {
                    synchronized (this) { backupError = "Backup paused: Google Drive already holds saved data that this app has not loaded. Set DHABA_GOOGLE_CLIENT_ID, DHABA_GOOGLE_CLIENT_SECRET and DHABA_GOOGLE_REFRESH_TOKEN on the host and restart, so it is restored first."; }
                    return 0;
                }
            }
            String lastModified = "";
            for (String name : BACKUP_FILES) {
                Path p = dir.resolve(name);
                if (!Files.isRegularFile(p)) continue;
                String sg = sig(p);
                if (sg.equals(uploadedSig.get(name))) continue;
                byte[] data = Files.readAllBytes(p);
                String mt = putBackup(folder, name, "application/json", data);
                uploadedSig.put(name, sg);
                if (name.equals("store.json")) lastModified = mt;
                n++;
            }
            Map<String, Object> st = new LinkedHashMap<>();
            synchronized (this) {
                st.put("subFolders", new LinkedHashMap<>(subFolders));
                st.put("files", new LinkedHashMap<>(files));
                st.put("invoices", new LinkedHashMap<>(invoices));
                st.put("pending", new ArrayList<>(pending));
                st.put("invoiceFolderId", invoiceFolderId);
                st.put("invoiceFolderName", invoiceFolderName);
                st.put("dataFolderId", dataFolderId);
                st.put("dailyFolders", dailyFolders);
            }
            String stateText = Json.stringify(st);
            if (!stateText.equals(uploadedSig.get(STATE_FILE))) {
                putBackup(folder, STATE_FILE, "application/json", stateText.getBytes(StandardCharsets.UTF_8));
                uploadedSig.put(STATE_FILE, stateText);
                n++;
            }
            if (!lastModified.isEmpty()) {
                try { Files.writeString(stampFile(), lastModified); } catch (IOException ignored) { /* only used to skip needless restores */ }
            }
            synchronized (this) {
                if (n > 0) lastBackupAt = java.time.Instant.now().toString();
                backupError = "";
            }
        } catch (DriveException e) {
            synchronized (this) { backupError = "Backup to Google Drive failed (will retry): " + e.getMessage(); }
        } catch (IOException e) {
            synchronized (this) { backupError = "Backup could not read a data file: " + e.getMessage(); }
        }
        return n;
    }

    /** Creates or overwrites a file in the Backup folder. Returns its modifiedTime. */
    private String putBackup(String folder, String name, String mime, byte[] data) throws DriveException {
        String id = backupIds.get(name);
        if (id == null) {
            Map<String, Object> m = fileMeta(folder, name);
            if (m != null) id = Json.str(m, "id", "");
        }
        if (id == null || id.isEmpty()) {
            String nid = uploadFile(folder, name, mime, data);
            backupIds.put(name, nid);
            Map<String, Object> m = apiJson("GET", API + "/files/" + enc(nid) + "?supportsAllDrives=true&fields=modifiedTime", null);
            return Json.str(m, "modifiedTime", "");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(UPLOAD + "/files/" + enc(id) + "?uploadType=media&supportsAllDrives=true&fields=id,modifiedTime"))
                    .header("Content-Type", mime).method("PATCH", HttpRequest.BodyPublishers.ofByteArray(data));
            HttpResponse<byte[]> r = send(b, token);
            if (r.statusCode() == 401 && attempt == 0) { synchronized (this) { accessToken = ""; } continue; }
            if (r.statusCode() == 404) { backupIds.remove(name); throw new DriveException(404, "backup file moved; will recreate"); }
            if (r.statusCode() / 100 != 2) throw new DriveException(r.statusCode(), errorText(r));
            backupIds.put(name, id);
            return Json.str(Json.parseObject(new String(r.body(), StandardCharsets.UTF_8)), "modifiedTime", "");
        }
        throw new DriveException(401, "Google rejected the sign-in");
    }

    /** Last chance before the app is stopped (Render sends a stop signal when it puts the app to sleep). */
    void flushBackup() {
        try { if (connected()) backupChanged(); } catch (Throwable ignored) { /* best effort */ }
    }

    /** The value to put in Render's DHABA_GOOGLE_REFRESH_TOKEN setting. Only handed to the logged-in manager. */
    synchronized String refreshTokenForHost() { return refreshToken; }

    Map<String, Object> backupNow() {
        requireConnected();
        startBackupWorker();
        backupChanged();
        return status();
    }

    // ================================================================= invoices

    private String invoiceRoot() throws DriveException {
        String root;
        synchronized (this) { root = invoiceFolderId; }
        if (!root.isEmpty()) return root;
        // The manager has not chosen a folder yet: use one we create so no invoice is ever lost.
        root = ensureFolder("", "Chandrika Dhaba - Invoices");
        synchronized (this) { invoiceFolderId = root; invoiceFolderName = "Chandrika Dhaba - Invoices"; save(); }
        return root;
    }

    /** Puts a paid bill in the queue; the background worker uploads it (and retries until it works). */
    void enqueueInvoice(long billNo) {
        synchronized (this) {
            if (!connected() || invoices.containsKey(String.valueOf(billNo)) || pending.contains(billNo)) return;
            pending.add(billNo);
            save();
        }
        startWorker();
        wake();
    }

    /** Queues every bill paid on that day which is not in Drive yet. Returns how many were queued. */
    int enqueueDay(LocalDate day) {
        requireConnected();
        int n = 0;
        for (long no : store.billNosOn(day)) {
            synchronized (this) { if (invoices.containsKey(String.valueOf(no)) || pending.contains(no)) continue; }
            enqueueInvoice(no);
            n++;
        }
        return n;
    }

    void wake() { synchronized (workerLock) { workerLock.notifyAll(); } }

    synchronized void noteError(String msg) { lastError = msg; }

    void startWorker() {
        if (workerStarted) return;
        synchronized (workerLock) {
            if (workerStarted) return;
            workerStarted = true;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try { drainQueue(); } catch (Throwable e) { noteError("Invoice upload problem: " + e.getMessage()); }
                synchronized (workerLock) {
                    try { workerLock.wait(30_000L); } catch (InterruptedException e) { return; }
                }
            }
        }, "drive-invoice-worker");
        t.setDaemon(true);
        t.start();
    }

    /** Uploads queued invoices one by one. Stops at the first network/auth failure and tries again later. */
    void drainQueue() {
        while (true) {
            long no;
            synchronized (this) {
                if (!connected() || pending.isEmpty()) return;
                no = pending.get(0);
            }
            try {
                uploadInvoice(no);
                synchronized (this) { pending.remove(Long.valueOf(no)); lastError = ""; save(); }
            } catch (DriveException e) {
                noteError("Invoice " + no + " not saved to Google Drive yet: " + e.getMessage() + " (will retry)");
                if (e.status == 404 || e.status == 403) {
                    // the chosen folder disappeared or is not writable: fall back to a folder we own on the next try
                    synchronized (this) { if (!invoiceFolderId.isEmpty()) { folderCache.clear(); } }
                }
                return;
            } catch (RuntimeException e) {
                // a bill that cannot be built must not block the others
                synchronized (this) { pending.remove(Long.valueOf(no)); save(); }
                noteError("Invoice " + no + " skipped: " + e.getMessage());
            }
        }
    }

    private void uploadInvoice(long no) throws DriveException {
        Map<String, Object> bill = store.billForInvoice(no);
        if (bill == null) throw new IllegalStateException("bill not found");
        long paidAt = Json.lng(bill, "paidAt", System.currentTimeMillis());
        LocalDate day = java.time.Instant.ofEpochMilli(paidAt).atZone(zone).toLocalDate();
        String parent = invoiceRoot();
        boolean daily;
        synchronized (this) { daily = dailyFolders; }
        String folder = daily ? ensureFolder(parent, day.toString()) : parent;
        String base = "Invoice-" + String.format("%04d", no) + "_Table-" + Json.lng(bill, "table", 0) + "_" + day;
        byte[] pdf = Invoice.pdf(bill, store.dhabaInfo(), zone);
        String id = uploadFile(folder, base + ".pdf", "application/pdf", pdf);
        String photo = Json.str(bill, "receiptPhoto", "");
        int comma = photo.indexOf(',');
        if (photo.startsWith("data:image/") && comma > 0) {
            String mime = photo.substring(5, photo.indexOf(';') > 0 ? photo.indexOf(';') : comma);
            String ext = mime.contains("png") ? "png" : mime.contains("webp") ? "webp" : "jpg";
            try {
                byte[] img = java.util.Base64.getDecoder().decode(photo.substring(comma + 1));
                uploadFile(folder, "Invoice-" + String.format("%04d", no) + "_payment-proof." + ext, mime, img);
            } catch (IllegalArgumentException ignored) { /* damaged photo: the invoice itself is already saved */ }
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("folder", folder);
        rec.put("name", base + ".pdf");
        rec.put("at", System.currentTimeMillis());
        synchronized (this) { invoices.put(String.valueOf(no), rec); save(); }
    }

    synchronized Map<String, Object> invoiceRecord(long no) {
        Map<String, Object> r = invoices.get(String.valueOf(no));
        return r == null ? null : new LinkedHashMap<>(r);
    }

    synchronized boolean invoicePending(long no) { return pending.contains(no); }

    /** Called at start-up: resume anything that was waiting when the app was last stopped. */
    void resume() {
        startBackupWorker();
        boolean any;
        synchronized (this) { any = connected() && !pending.isEmpty(); }
        if (any) { startWorker(); wake(); }
    }
}
