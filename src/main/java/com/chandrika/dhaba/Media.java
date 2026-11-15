package com.chandrika.dhaba;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where uploaded pictures live.
 *
 * When the manager has signed in with Google, every new picture (dish photo, waiter photo, Aadhaar picture, welcome picture)
 * is stored in Google Drive. The pictures keep their file names, so nothing else in the app changes: pages still ask for
 * /uploads/abc.jpg and the server fetches it from Drive (and keeps a disposable copy in data/cache so the menu stays fast).
 *
 * If Google is not connected (or is unreachable) the picture is saved in data/uploads or data/private exactly as before,
 * and the "Move existing pictures to Drive" button in Settings can move it later.
 */
final class Media {
    private static final Pattern NAME = Pattern.compile("^[a-f0-9]{16,40}\\.(jpg|png|webp)$");

    private final Path uploads;      // public pictures kept locally
    private final Path privateDir;   // private pictures kept locally
    private final Path cachePublic;
    private final Path cachePrivate;
    private final Drive drive;

    Media(Path dataDir, Path uploads, Path privateDir, Drive drive) {
        this.uploads = uploads;
        this.privateDir = privateDir;
        this.cachePublic = dataDir.resolve("cache").resolve("public");
        this.cachePrivate = dataDir.resolve("cache").resolve("private");
        this.drive = drive;
        try {
            Files.createDirectories(cachePublic);
            Files.createDirectories(cachePrivate);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    private Path local(String kind, String name) { return (Drive.KIND_PRIVATE.equals(kind) ? privateDir : uploads).resolve(name); }
    private Path cache(String kind, String name) { return (Drive.KIND_PRIVATE.equals(kind) ? cachePrivate : cachePublic).resolve(name); }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") ? "image/png" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }

    /** Result of saving a picture: where it went and, if Drive was tried and failed, why. */
    record Saved(String storage, String warning) {}

    /** @param sub dishes | waiters | welcome (the Drive sub-folder) */
    Saved save(String kind, String sub, String name, byte[] data) throws IOException {
        if (!validName(name)) throw new IOException("bad name");
        if (drive.connected()) {
            try {
                drive.putImage(sub, kind, name, mimeOf(name), data);
                Files.write(cache(kind, name), data);
                return new Saved("drive", "");
            } catch (Drive.DriveException e) {
                drive.noteError("Picture saved on this computer only: " + e.getMessage());
                Files.write(local(kind, name), data);
                return new Saved("local", "Could not reach Google Drive (" + e.getMessage() + "). The picture was kept on this computer; use 'Move existing pictures to Drive' in Settings later.");
            }
        }
        Files.write(local(kind, name), data);
        return new Saved("local", "");
    }

    boolean exists(String kind, String name) {
        if (!validName(name)) return false;
        return Files.isRegularFile(local(kind, name)) || Files.isRegularFile(cache(kind, name)) || drive.hasImage(name, kind);
    }

    /** @return the bytes, or null when the picture does not exist */
    byte[] read(String kind, String name) throws IOException {
        if (!validName(name)) return null;
        Path l = local(kind, name);
        if (Files.isRegularFile(l)) return Files.readAllBytes(l);
        Path c = cache(kind, name);
        if (Files.isRegularFile(c)) return Files.readAllBytes(c);
        byte[] d = drive.getImage(name, kind);
        if (d != null) {
            try { Files.write(c, d); } catch (IOException ignored) { /* the cache is optional */ }
        }
        return d;
    }

    void delete(String kind, String name) {
        if (!validName(name)) return;
        try {
            Files.deleteIfExists(local(kind, name));
            Files.deleteIfExists(cache(kind, name));
        } catch (IOException ignored) { /* leaving a file behind is harmless */ }
        drive.trashImage(name);
    }

    /** Uploads every picture that is only on this computer to Drive. Returns {moved, failed}. */
    Map<String, Object> migrateToDrive() throws IOException {
        int moved = 0, failed = 0;
        String firstError = "";
        for (String kind : List.of(Drive.KIND_PUBLIC, Drive.KIND_PRIVATE)) {
            Path dir = Drive.KIND_PRIVATE.equals(kind) ? privateDir : uploads;
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String name = p.getFileName().toString();
                    if (!validName(name) || !Files.isRegularFile(p)) continue;
                    try {
                        // dish and welcome pictures share /uploads; waiter pictures are the private kind
                        String sub = Drive.KIND_PRIVATE.equals(kind) ? "waiters" : "dishes";
                        byte[] data = Files.readAllBytes(p);
                        drive.putImage(sub, kind, name, mimeOf(name), data);
                        Files.write(cache(kind, name), data);
                        Files.deleteIfExists(p);
                        moved++;
                    } catch (Drive.DriveException e) {
                        failed++;
                        if (firstError.isEmpty()) firstError = e.getMessage();
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        out.put("failed", failed);
        out.put("error", firstError);
        return out;
    }
}
