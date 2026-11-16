package com.chandrika.dhaba;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A very small request/response helper and router on top of the JDK's built-in HttpServer. */
final class Http {
    private Http() {}

    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "frame-ancestors 'none'; form-action 'self'";

    interface Handler {
        void handle(Ctx c) throws Exception;
    }

    // ================================================================== request context
    static final class Ctx {
        final HttpExchange ex;
        final Map<String, String> params = new HashMap<>();
        final Map<String, String> query;
        private byte[] cachedBody;

        Ctx(HttpExchange ex) {
            this.ex = ex;
            this.query = parseQuery(ex.getRequestURI().getRawQuery());
        }

        String method() { return ex.getRequestMethod(); }

        String path() { return ex.getRequestURI().getPath(); }

        String ip() {
            return ex.getRemoteAddress().getAddress().getHostAddress();
        }

        String header(String name) { return ex.getRequestHeaders().getFirst(name); }

        String cookie(String name) {
            String h = header("Cookie");
            if (h == null) return null;
            for (String part : h.split(";")) {
                String p = part.strip();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
            }
            return null;
        }

        int intParam(String name) {
            try {
                return Integer.parseInt(params.get(name));
            } catch (NumberFormatException e) {
                throw ApiException.bad("Invalid " + name);
            }
        }

        byte[] body(int max) throws IOException {
            if (cachedBody == null) {
                byte[] b = ex.getRequestBody().readNBytes(max + 1);
                if (b.length > max) throw new ApiException(413, "Request is too large");
                cachedBody = b;
            }
            return cachedBody;
        }

        /** The JSON body as a map (empty map when there is no body). */
        Map<String, Object> json() throws IOException {
            byte[] b = body(64 * 1024);
            if (b.length == 0) return new java.util.LinkedHashMap<>();
            try {
                return Json.parseObject(new String(b, StandardCharsets.UTF_8));
            } catch (Json.JsonException e) {
                throw ApiException.bad("That request could not be read");
            }
        }

        void json(int status, Object o) throws IOException {
            send(status, "application/json; charset=utf-8", Json.stringify(o).getBytes(StandardCharsets.UTF_8), "no-store");
        }

        void send(int status, String contentType, byte[] data, String cacheControl) throws IOException {
            var h = ex.getResponseHeaders();
            h.set("Content-Type", contentType);
            h.set("Cache-Control", cacheControl);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "same-origin");
            h.set("X-Frame-Options", "DENY");
            if (contentType.startsWith("text/html")) h.set("Content-Security-Policy", CSP);
            boolean head = "HEAD".equals(method());
            ex.sendResponseHeaders(status, head || data.length == 0 ? -1 : data.length);
            if (!head && data.length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            } else {
                ex.close();
            }
        }

        void redirect(String location) throws IOException {
            ex.getResponseHeaders().set("Location", location);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        }

        void setCookie(String value) {
            ex.getResponseHeaders().add("Set-Cookie", value);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    // ================================================================== router
    static final class Router {
        record Route(String method, String[] segs, boolean auth, Handler handler) {}

        private final List<Route> routes = new ArrayList<>();

        void add(String method, String pattern, boolean auth, Handler h) {
            routes.add(new Route(method, split(pattern), auth, h));
        }

        void get(String p, boolean auth, Handler h) { add("GET", p, auth, h); }
        void post(String p, boolean auth, Handler h) { add("POST", p, auth, h); }
        void put(String p, boolean auth, Handler h) { add("PUT", p, auth, h); }
        void delete(String p, boolean auth, Handler h) { add("DELETE", p, auth, h); }

        private static String[] split(String path) {
            String p = path.startsWith("/") ? path.substring(1) : path;
            if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p.isEmpty() ? new String[0] : p.split("/");
        }

        /** @return the matched route (params filled into ctx), null if the path exists for no method, or sets pathMatched */
        Route match(Ctx c, boolean[] pathMatched) {
            String[] segs = split(c.path());
            String method = c.method().equals("HEAD") ? "GET" : c.method();
            for (Route r : routes) {
                if (r.segs.length != segs.length) continue;
                Map<String, String> params = new HashMap<>();
                boolean ok = true;
                for (int i = 0; i < segs.length && ok; i++) {
                    String rs = r.segs[i];
                    if (rs.startsWith("{") && rs.endsWith("}")) {
                        params.put(rs.substring(1, rs.length() - 1), URLDecoder.decode(segs[i], StandardCharsets.UTF_8));
                    } else if (!rs.equals(segs[i])) {
                        ok = false;
                    }
                }
                if (!ok) continue;
                pathMatched[0] = true;
                if (r.method.equals(method)) {
                    c.params.putAll(params);
                    return r;
                }
            }
            return null;
        }
    }
}
