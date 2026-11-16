package com.chandrika.dhaba;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password hashing, manager sessions, and simple rate limits. */
final class Auth {
    private static final SecureRandom RND = new SecureRandom();
    private static final long SESSION_MS = 12L * 60 * 60 * 1000;

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Map<String, String[]> waiterSessions = new ConcurrentHashMap<>(); // token -> {waiterId, expiryMillis}

    static String newSalt() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String hash(String password, String saltHex) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(saltHex), 120_000, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ sessions
    String startSession() {
        String t = randomToken(24);
        sessions.put(t, System.currentTimeMillis() + SESSION_MS);
        // forget expired sessions now and then
        if (sessions.size() > 50) {
            long now = System.currentTimeMillis();
            sessions.values().removeIf(exp -> exp < now);
        }
        return t;
    }

    boolean isValid(String token) {
        if (token == null) return false;
        Long exp = sessions.get(token);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    void endSession(String token) {
        if (token != null) sessions.remove(token);
    }

    void endAllSessions() {
        sessions.clear();
    }

    static long sessionSeconds() { return SESSION_MS / 1000; }

    // ------------------------------------------------------------ waiter sessions (no password: the waiter's registered mobile number opens the table)
    String startWaiterSession(String waiterId) {
        String t = randomToken(24);
        waiterSessions.put(t, new String[] {waiterId, String.valueOf(System.currentTimeMillis() + SESSION_MS)});
        if (waiterSessions.size() > 200) {
            long now = System.currentTimeMillis();
            waiterSessions.values().removeIf(v -> Long.parseLong(v[1]) < now);
        }
        return t;
    }

    /** @return the waiter id for a valid token, otherwise null */
    String waiterIdFor(String token) {
        if (token == null) return null;
        String[] v = waiterSessions.get(token);
        if (v == null) return null;
        if (Long.parseLong(v[1]) < System.currentTimeMillis()) {
            waiterSessions.remove(token);
            return null;
        }
        return v[0];
    }

    // ------------------------------------------------------------ login throttling
    /** @return seconds the caller must wait, or 0 when login attempts are allowed */
    long lockedFor(String ip) {
        Deque<Long> q = failures.get(ip);
        if (q == null) return 0;
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 10 * 60 * 1000L) q.pollFirst();
            if (q.size() >= 5) {
                long wait = (q.peekFirst() + 10 * 60 * 1000L - now) / 1000;
                return Math.max(wait, 1);
            }
        }
        return 0;
    }

    void loginFailed(String ip) {
        Deque<Long> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }

    void loginOk(String ip) {
        failures.remove(ip);
    }

    // ------------------------------------------------------------ generic rate limit
    /** Allows at most {@code max} events per {@code windowMs} for the key. */
    boolean allow(String key, int max, long windowMs) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }
}
