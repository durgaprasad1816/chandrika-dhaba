package com.chandrika.dhaba;

import com.chandrika.dhaba.Models.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * All the dhaba's data and rules. Everything is kept in memory and saved to
 * data/store.json after each change (paid bills go to data/bills.jsonl).
 * Every public method is synchronized, so two people can never corrupt a bill.
 */
final class Store {
    static final String DEFAULT_PASSWORD = "chandrika123";
    private static final Pattern IMAGE_PATH = Pattern.compile("^/uploads/[a-f0-9]{16,40}\\.(jpg|png|webp)$");
    private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PRIVATE_FILE = Pattern.compile("^[a-f0-9]{16,40}\\.(jpg|png|webp)$");
    private static final Pattern AADHAAR = Pattern.compile("^[2-9]\\d{11}$");

    private final Path dir;
    private final Path storeFile;
    private final Path billsFile;
    private final Path auditFile;
    private final Path backupDir;
    private final Path collectFile;
    final Path privateDir;
    final Path uploadsDir;
    final Media media;
    final Drive drive;
    final ZoneId zone = ZoneId.of("Asia/Kolkata");

    private Settings settings = new Settings();
    private final List<Category> categories = new ArrayList<>();
    private final List<MenuItem> items = new ArrayList<>();
    private final List<String> preparationTypes = new ArrayList<>(List.of("Dry", "Wet", "Spicy", "Light Dry", "Light Wet", "Dry & Wet", "Not applicable"));
    private final List<Map<String, Object>> notifications = new ArrayList<>();
    private long nextNotification = 1;
    private final Map<Integer, TableState> tables = new TreeMap<>();
    private final List<Bill> bills = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final Map<Integer, String> allocation = new TreeMap<>();   // table number -> waiter id
    private long nextWaiter = 1;
    private long nextOrder = 1;
    private String daySeqDate = "";   // the day the order counter below belongs to (yyyy-MM-dd, India time)
    private int daySeq = 0;           // last order number given on that day
    private long nextBill = 1;
    private long nextItem = 1;
    private long nextCat = 1;
    private long lastBackup = 0;

    Store(Path dir) {
        this.dir = dir;
        this.storeFile = dir.resolve("store.json");
        this.billsFile = dir.resolve("bills.jsonl");
        this.auditFile = dir.resolve("audit.log");
        this.backupDir = dir.resolve("backups");
        this.uploadsDir = dir.resolve("uploads");
        this.privateDir = dir.resolve("private");
        this.collectFile = dir.resolve("collections.jsonl");
        try {
            Files.createDirectories(uploadsDir);
            Files.createDirectories(privateDir);
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (Files.exists(storeFile)) {
            load();
        } else {
            seed();
            save();
        }
        loadBills();
        loadCollections();
        this.drive = new Drive(dir, this, zone);
        this.media = new Media(dir, uploadsDir, privateDir, drive);
        drive.resume();
    }

    // ---- small read-only helpers used by the Google Drive invoice uploader
    synchronized List<Long> billNosOn(LocalDate d) {
        List<Long> out = new ArrayList<>();
        for (Bill b : billsOn(d)) out.add(b.no);
        return out;
    }

    synchronized Map<String, Object> billForInvoice(long no) {
        for (Bill b : bills) if (b.no == no) return b.toMap();
        return null;
    }

    synchronized String splashImage() { return settings.splashImage == null ? "" : settings.splashImage; }

    synchronized Map<String, Object> dhabaInfo() { return settings.publicView(); }

    // ================================================================= persistence

    private void seed() {
        settings = new Settings();
        settings.passSalt = Auth.newSalt();
        settings.passHash = Auth.hash(DEFAULT_PASSWORD, settings.passSalt);
        settings.defaultPassword = true;
        try (InputStream in = Store.class.getResourceAsStream("/seed/menu.txt")) {
            if (in == null) throw new IllegalStateException("seed/menu.txt is missing from the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            Category cur = null;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("#|")) {
                    String[] p = line.split("\\|", -1);
                    cur = new Category();
                    cur.id = p[1];
                    cur.name = p[2];
                    cur.nameTe = p.length > 3 ? p[3] : "";
                    if (p.length > 4 && !p[4].isBlank()) {
                        for (String img : p[4].split(",")) cur.images.add("/img/cat/" + img.strip() + ".webp");
                    }
                    categories.add(cur);
                    nextCat++;
                } else if (line.startsWith("#")) {
                    continue;
                } else if (cur != null) {
                    String[] p = line.split("\\|", -1);
                    MenuItem it = new MenuItem();
                    it.id = "i" + (nextItem++);
                    it.categoryId = cur.id;
                    it.name = p[0];
                    it.nameTe = p[1];
                    it.price = Long.parseLong(p[2].strip());
                    it.veg = p[3].strip().equals("v");
                    it.stock = 0;
                    it.available = false;
                    items.add(it);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void load() {
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(storeFile, StandardCharsets.UTF_8));
            settings = Settings.fromMap(Json.map(m.get("settings")));
            for (Object o : Json.list(m, "categories")) categories.add(Category.fromMap(Json.map(o)));
            for (Object o : Json.list(m, "items")) items.add(MenuItem.fromMap(Json.map(o)));
            for (Object no : Json.list(m, "notifications")) notifications.add(new LinkedHashMap<>(Json.map(no)));
            nextNotification = Json.lng(m, "nextNotification", notifications.size() + 1L);
            List<Object> savedPrepTypes = Json.list(m, "preparationTypes");
            if (!savedPrepTypes.isEmpty()) { preparationTypes.clear(); for (Object o : savedPrepTypes) { String v = String.valueOf(o).trim(); if (!v.isEmpty() && !preparationTypes.contains(v)) preparationTypes.add(v); } }
            for (Object o : Json.list(m, "tables")) {
                TableState t = TableState.fromMap(Json.map(o));
                tables.put(t.number, t);
            }
            Map<String, Object> c = Json.map(m.get("counters"));
            nextOrder = Json.lng(c, "order", 1);
            daySeqDate = Json.str(c, "daySeqDate", "");
            daySeq = (int) Json.lng(c, "daySeq", 0);
            nextBill = Json.lng(c, "bill", 1);
            nextItem = Json.lng(c, "item", 1);
            nextCat = Json.lng(c, "cat", 1);
            nextWaiter = Json.lng(c, "waiter", 1);
            for (Object o : Json.list(m, "waiters")) waiters.add(Waiter.fromMap(Json.map(o)));
            Map<String, Object> al = Json.map(m.get("allocation"));
            for (Map.Entry<String, Object> e : al.entrySet()) {
                try { allocation.put(Integer.parseInt(e.getKey()), String.valueOf(e.getValue())); }
                catch (NumberFormatException ignored) { /* skip bad key */ }
            }
            // data saved by version 1: orders were "waiting for confirmation" or rejected
            for (TableState t : tables.values()) {
                t.orders.removeIf(o -> Models.REJECTED.equals(o.status));
                for (Order o : t.orders) if (Models.PENDING.equals(o.status)) o.status = Models.CONFIRMED;
            }
            tables.values().removeIf(t -> t.orders.isEmpty());
            if ("Chandrika Dhaba".equals(settings.dhabaName)) settings.dhabaName = "Chandrika Dhaba & Restaurant";
            if ("Chandrika Dhaba".equals(settings.payeeName)) settings.payeeName = "Chandrika Dhaba & Restaurant";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Re-applies "collected" marks. collections.jsonl is append-only, so the bill file itself is never rewritten. */
    private void loadCollections() {
        if (!Files.exists(collectFile)) return;
        try {
            for (String line : Files.readAllLines(collectFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> ev = Json.parseObject(line);
                    long at = Json.lng(ev, "at", 0);
                    Set<Long> nos = new LinkedHashSet<>();
                    for (Object o : Json.list(ev, "bills")) nos.add(((Number) o).longValue());
                    for (Bill b : bills) if (nos.contains(b.no)) { b.collected = true; b.collectedAt = at; }
                } catch (RuntimeException ex) {
                    System.err.println("Skipping unreadable collection line: " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadBills() {
        if (!Files.exists(billsFile)) return;
        try {
            for (String line : Files.readAllLines(billsFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    bills.add(Bill.fromMap(Json.parseObject(line)));
                } catch (RuntimeException ex) {
                    System.err.println("Skipping unreadable bill line: " + ex.getMessage());
                }
            }
            long max = 0;
            for (Bill b : bills) max = Math.max(max, b.no);
            nextBill = Math.max(nextBill, max + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings.toMap());
        List<Object> cs = new ArrayList<>();
        for (Category c : categories) cs.add(c.toMap());
        m.put("categories", cs);
        List<Object> is = new ArrayList<>();
        for (MenuItem i : items) is.add(i.toMap());
        m.put("items", is);
        m.put("preparationTypes", new ArrayList<>(preparationTypes));
        m.put("notifications", new ArrayList<>(notifications));
        m.put("nextNotification", nextNotification);
        List<Object> ts = new ArrayList<>();
        for (TableState t : tables.values()) ts.add(t.toMap());
        m.put("tables", ts);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("order", nextOrder);
        c.put("daySeqDate", daySeqDate);
        c.put("daySeq", daySeq);
        c.put("bill", nextBill);
        c.put("item", nextItem);
        c.put("cat", nextCat);
        c.put("waiter", nextWaiter);
        m.put("counters", c);
        List<Object> ws = new ArrayList<>();
        for (Waiter w : waiters) ws.add(w.toMap());
        m.put("waiters", ws);
        Map<String, Object> al = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : allocation.entrySet()) al.put(String.valueOf(e.getKey()), e.getValue());
        m.put("allocation", al);
        try {
            Path tmp = dir.resolve("store.json.tmp");
            Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            backupIfDue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Keeps one copy of the data file per day (last 30 days) in data/backups. */
    private void backupIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastBackup < 6L * 60 * 60 * 1000) return;
        lastBackup = now;
        try {
            String day = DateTimeFormatter.ofPattern("yyyyMMdd").format(ZonedDateTime.now(zone));
            Files.copy(storeFile, backupDir.resolve("store-" + day + ".json"), StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(billsFile)) {
                Files.copy(billsFile, backupDir.resolve("bills-" + day + ".jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(collectFile)) {
                Files.copy(collectFile, backupDir.resolve("collections-" + day + ".jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
            try (var s = Files.list(backupDir)) {
                List<Path> old = s.sorted(Comparator.comparing(Path::getFileName).reversed()).skip(60).toList();
                for (Path p : old) Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // backups are a bonus, never block a sale because of them
        }
    }

    private void audit(String msg) {
        try {
            String line = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ZonedDateTime.now(zone)) + "  " + msg + "\n";
            Files.writeString(auditFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ================================================================= settings and login

    synchronized Settings settingsRef() { return settings; }

    boolean checkLogin(String user, String pass) {
        if (user == null || pass == null) return false;
        String salt, hash, manager;
        synchronized (this) {
            salt = settings.passSalt;
            hash = settings.passHash;
            manager = settings.managerUser;
        }
        // the slow password hash runs outside the lock so billing is never held up by a login
        String h = Auth.hash(pass, salt);
        boolean okUser = Auth.constantEquals(user.strip().toLowerCase(), manager.toLowerCase());
        boolean okPass = Auth.constantEquals(h, hash);
        return okUser && okPass;
    }

    synchronized Map<String, Object> managerSettings() { return settings.managerView(); }

    synchronized Map<String, Object> updateSettings(Map<String, Object> b) {
        if (b.containsKey("dhabaName")) settings.dhabaName = text(b.get("dhabaName"), 60, true, "Dhaba name");
        if (b.containsKey("address")) settings.address = text(b.get("address"), 120, false, "Address");
        if (b.containsKey("phone")) settings.phone = text(b.get("phone"), 30, false, "Phone");
        if (b.containsKey("payeeName")) settings.payeeName = text(b.get("payeeName"), 60, false, "Payee name");
        if (b.containsKey("upiId")) {
            String u = text(b.get("upiId"), 60, false, "UPI ID");
            if (!u.isEmpty() && !u.matches("^[A-Za-z0-9._-]{2,64}@[A-Za-z0-9._-]{2,64}$")) {
                throw ApiException.bad("UPI ID looks wrong. It should look like name@bank");
            }
            settings.upiId = u;
        }
        if (b.containsKey("gstPercent")) {
            long g = wholeNumber(b.get("gstPercent"), "GST %");
            if (g < 0 || g > 28) throw ApiException.bad("GST % must be between 0 and 28");
            settings.gstPercent = (int) g;
        }
        if (b.containsKey("splashImage")) {
            String img = text(b.get("splashImage"), 120, false, "Welcome image");
            if (!img.isEmpty() && !IMAGE_PATH.matcher(img).matches()) throw ApiException.bad("Please upload the welcome picture again");
            settings.splashImage = img;
        }
        if (b.containsKey("tablesPerWaiter")) {
            long k = wholeNumber(b.get("tablesPerWaiter"), "Tables per waiter");
            if (k < 1 || k > 20) throw ApiException.bad("Tables per waiter must be between 1 and 20");
            settings.tablesPerWaiter = (int) k;
        }
        if (b.containsKey("tableCount")) {
            long n = wholeNumber(b.get("tableCount"), "Number of tables");
            if (n < 1 || n > 100) throw ApiException.bad("Number of tables must be between 1 and 100");
            for (TableState t : tables.values()) {
                if (t.number > n && !t.orders.isEmpty()) {
                    throw ApiException.conflict("Table " + t.number + " still has an open bill. Collect payment first.");
                }
            }
            settings.tableCount = (int) n;
            allocation.keySet().removeIf(k -> k > settings.tableCount);
        }
        save();
        audit("settings updated");
        return settings.managerView();
    }

    /** The manager password is chosen when the app starts (--password or DHABA_PASSWORD); there is no change-password screen. */
    synchronized void setStartupPassword(String password) {
        if (password == null || password.isEmpty()) return;
        if (Auth.constantEquals(Auth.hash(password, settings.passSalt), settings.passHash)) return;
        settings.passSalt = Auth.newSalt();
        settings.passHash = Auth.hash(password, settings.passSalt);
        settings.defaultPassword = Auth.constantEquals(password, DEFAULT_PASSWORD);
        save();
        audit("manager password set at startup");
    }

    // ================================================================= validation helpers

    private static String text(Object o, int max, boolean required, String field) {
        String s = o == null ? "" : String.valueOf(o);
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
        if (required && s.isEmpty()) throw ApiException.bad(field + " is required");
        if (s.length() > max) throw ApiException.bad(field + " is too long (max " + max + " characters)");
        return s;
    }

    private static long wholeNumber(Object o, String field) {
        if (o == null || !Json.isWhole(o)) throw ApiException.bad(field + " must be a whole number");
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(String.valueOf(o).trim());
    }

    private static String cleanName(Object o) {
        String s = text(o, 60, false, "Name").replace('\n', ' ').strip();
        if (s.length() < 2) throw ApiException.bad("Please enter your name");
        boolean letter = false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) { letter = true; break; }
        if (!letter) throw ApiException.bad("Please enter a valid name");
        return s;
    }

    static String cleanPhone(Object o) {
        String s = o == null ? "" : String.valueOf(o).replaceAll("[\\s\\-()]", "");
        if (s.isEmpty()) throw ApiException.bad("Phone number is required");
        if (s.startsWith("+91")) s = s.substring(3);
        else if (s.length() == 12 && s.startsWith("91")) s = s.substring(2);
        else if (s.length() == 11 && s.startsWith("0")) s = s.substring(1);
        if (!PHONE.matcher(s).matches()) throw ApiException.bad("Enter a valid 10-digit mobile number");
        return s;
    }

    // ================================================================= menu: public + manager

    private Category category(String id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    private MenuItem item(String id) {
        for (MenuItem i : items) if (i.id.equals(id)) return i;
        return null;
    }

    /** The whole menu, grouped by category (what both the customer page and the menu editor use). */
    synchronized Map<String, Object> menu() { return menu(false); }

    private Map<String, Object> menu(boolean hideSpecialPrices) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> cats = new ArrayList<>();
        for (Category c : categories) {
            Map<String, Object> cm = c.toMap();
            List<Object> its = new ArrayList<>();
            for (MenuItem i : items) if (i.categoryId.equals(c.id)) {
                Map<String, Object> itemView = i.toMap();
                if (hideSpecialPrices && i.special) {
                    // Customer API exposes only the special dish name, category and image.
                    // Price, description, preparation and ingredients remain available to staff only.
                    itemView.remove("price");
                    itemView.remove("desc");
                    itemView.remove("ingredients");
                    itemView.remove("preparation");
                }
                its.add(itemView);
            }
            cm.put("items", its);
            cats.add(cm);
        }
        out.put("categories", cats);
        out.put("preparationTypes", new ArrayList<>(preparationTypes));
        return out;
    }

    synchronized Map<String, Object> publicMenu(int table, boolean waiterMode) {
        Map<String, Object> out = menu(!waiterMode);
        // Inventory counts and alert thresholds are private to the manager.
        for (Object categoryObject : Json.list(out, "categories")) {
            Map<String, Object> categoryMap = Json.map(categoryObject);
            for (Object itemObject : Json.list(categoryMap, "items")) {
                Map<String, Object> itemMap = Json.map(itemObject);
                itemMap.remove("stock");
                itemMap.remove("lowStockThreshold");
                if (!waiterMode) {
                    itemMap.remove("preparationOptions");
                    itemMap.remove("preparationEnabled");
                }
            }
        }
        out.put("dhaba", settings.publicView());
        String wn = table >= 1 && table <= settings.tableCount ? waiterNameOf(table) : "";
        out.put("table", table);
        out.put("waiter", wn);
        return out;
    }

    synchronized Map<String, Object> addPreparationType(Map<String, Object> body) {
        String name = text(body.get("name"), 30, true, "Preparation type");
        for (String existing : preparationTypes) if (existing.equalsIgnoreCase(name)) throw ApiException.conflict("That preparation type already exists");
        preparationTypes.add(name);
        save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preparationTypes", new ArrayList<>(preparationTypes));
        return result;
    }

    synchronized Map<String, Object> removePreparationType(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) throw ApiException.bad("Preparation type is required");
        String found = preparationTypes.stream().filter(v -> v.equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> ApiException.notFound("Preparation type not found"));
        preparationTypes.remove(found);
        for (MenuItem it : items) {
            it.preparationOptions.removeIf(v -> v.equalsIgnoreCase(found));
            if (it.preparation != null && it.preparation.equalsIgnoreCase(found))
                it.preparation = it.preparationOptions.isEmpty() ? "" : it.preparationOptions.get(0);
            if (it.preparationOptions.isEmpty()) it.preparationEnabled = false;
        }
        save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preparationTypes", new ArrayList<>(preparationTypes));
        return result;
    }

    synchronized Map<String, Object> saveItem(String id, Map<String, Object> b) {
        MenuItem it;
        boolean isNew = id == null;
        if (isNew) {
            it = new MenuItem();
        } else {
            it = item(id);
            if (it == null) throw ApiException.notFound("Item not found");
        }
        String name = text(b.get("name"), 80, true, "Item name");
        String cat = text(b.get("categoryId"), 40, true, "Category");
        if (category(cat) == null) throw ApiException.bad("Choose a valid category");
        long price = wholeNumber(b.get("price"), "Price");
        if (price < 0 || price > 100000) throw ApiException.bad("Price must be between 0 and 100000");
        String image = text(b.get("image"), 80, false, "Image");
        if (!image.isEmpty()) {
            if (!IMAGE_PATH.matcher(image).matches() || !media.exists(Drive.KIND_PUBLIC, image.substring("/uploads/".length()))) {
                throw ApiException.bad("Image not found. Upload the photo again.");
            }
        }
        long oldPrice = it.price;
        int oldStock = isNew ? 0 : it.stock;
        it.name = name;
        it.nameTe = text(b.get("nameTe"), 80, false, "Telugu name");
        it.desc = text(b.get("desc"), 200, false, "Description");
        it.ingredients = text(b.get("ingredients"), 2000, false, "Ingredients");
        it.preparation = text(b.get("preparation"), 30, false, "Preparation type");
        it.preparationEnabled = Json.bool(b, "preparationEnabled", false);
        it.preparationOptions.clear();
        for (Object raw : Json.list(b, "preparationOptions")) {
            String option = String.valueOf(raw).trim();
            if (option.isEmpty()) continue;
            if (preparationTypes.stream().noneMatch(v -> v.equalsIgnoreCase(option)))
                throw ApiException.bad("Choose preparation options from the manager list");
            if (it.preparationOptions.stream().noneMatch(v -> v.equalsIgnoreCase(option))) it.preparationOptions.add(option);
        }
        if (it.preparationOptions.isEmpty() && !it.preparation.isBlank()) it.preparationOptions.add(it.preparation);
        if (!it.preparationEnabled) { it.preparation = ""; it.preparationOptions.clear(); }
        else if (it.preparationOptions.isEmpty()) throw ApiException.bad("Add at least one preparation option for " + it.name);
        else if (it.preparationOptions.stream().noneMatch(v -> v.equalsIgnoreCase(it.preparation)))
            it.preparation = it.preparationOptions.get(0);
        it.stock = (int) Math.max(0, Math.min(100000, Json.lng(b, "stock", it.stock)));
        it.lowStockThreshold = (int) Math.max(0, Math.min(100000, Json.lng(b, "lowStockThreshold", it.lowStockThreshold)));
        if (it.stock <= 0) it.available = false;
        else if (it.stock > oldStock) it.available = true;
        if (it.stock > oldStock) notifyAllStaff("stock-restored", it.name + " stock updated: " + it.stock + " portions available.");
        if (it.stock == 0 && oldStock > 0) notifyAllStaff("out-of-stock", it.name + " is OUT OF STOCK.");
        else if (it.stock > 0 && it.stock <= it.lowStockThreshold && oldStock > it.lowStockThreshold) notifyAllStaff("low-stock", it.name + " is low: only " + it.stock + " portions left.");
        it.prepMinutes = (int) Math.max(1, Math.min(240, Json.lng(b, "prepMinutes", it.prepMinutes)));
        it.special = Json.bool(b, "special", it.special);
        it.categoryId = cat;
        it.price = price;
        it.image = image;
        it.veg = Boolean.TRUE.equals(b.get("veg")) || (b.get("veg") == null && it.veg);
        it.available = Json.bool(b, "available", it.available) && it.stock > 0;
        if (isNew) {
            it.id = "i" + (nextItem++);
            items.add(it);
            audit("menu: added " + it.name + " Rs." + it.price);
        } else if (oldPrice != price) {
            audit("menu: price of " + it.name + " changed Rs." + oldPrice + " -> Rs." + price);
        }
        save();
        return it.toMap();
    }

    private void notifyAllStaff(String type, String message) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", nextNotification++);
        n.put("type", type);
        n.put("message", message);
        n.put("createdAt", System.currentTimeMillis());
        notifications.add(n);
        while (notifications.size() > 250) notifications.remove(0);
    }

    // ================================================================= order numbers (restart at 1 every day)

    private String todayKey() { return java.time.LocalDate.now(zone).toString(); }

    /** The number the next confirmed order will get. Same for every waiter, so every panel shows the same "next" number. */
    private int nextOrderNumber() {
        return todayKey().equals(daySeqDate) ? daySeq + 1 : 1;
    }

    /** Takes the next order number of the day (call only while holding the store lock, when the order is confirmed). */
    private int takeOrderNumber() {
        String today = todayKey();
        if (!today.equals(daySeqDate)) { daySeqDate = today; daySeq = 0; }
        return ++daySeq;
    }

    private void putNextOrder(Map<String, Object> out) {
        out.put("nextOrderNo", nextOrderNumber());
        out.put("orderDate", todayKey());
    }

    /** What every waiter's "new order" panel shows. */
    synchronized Map<String, Object> nextOrderInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        putNextOrder(out);
        return out;
    }

    synchronized Map<String, Object> notificationsSince(long since) {
        List<Object> fresh = new ArrayList<>();
        long latest = 0;
        for (Map<String, Object> n : notifications) {
            long id = Json.lng(n, "id", 0);
            latest = Math.max(latest, id);
            if (id > since) fresh.add(new LinkedHashMap<>(n));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notifications", fresh);
        out.put("latestId", latest);
        putNextOrder(out);
        return out;
    }

    synchronized Map<String, Object> updateStock(String id, Map<String, Object> body) {
        MenuItem it = item(id);
        if (it == null) throw ApiException.notFound("Menu item not found");
        int old = it.stock;
        int add = (int) Json.lng(body, "add", 0);
        int set = (int) Json.lng(body, "stock", -1);
        if (add < 0 || set < -1) throw ApiException.bad("Stock quantity cannot be negative");
        it.stock = Math.max(0, Math.min(100000, set >= 0 ? set : old + add));
        int threshold = (int) Json.lng(body, "lowStockThreshold", it.lowStockThreshold);
        it.lowStockThreshold = Math.max(0, Math.min(100000, threshold));
        boolean wasAvailable = it.available;
        if (it.stock > 0) it.available = true;
        else it.available = false;
        if (it.stock > old) notifyAllStaff("stock-restored", it.name + " stock updated: " + it.stock + " portions available.");
        if (it.stock == 0 && old > 0) notifyAllStaff("out-of-stock", it.name + " is OUT OF STOCK. Stop taking orders.");
        else if (it.stock > 0 && it.stock <= it.lowStockThreshold && old > it.lowStockThreshold)
            notifyAllStaff("low-stock", it.name + " is low: only " + it.stock + " portions left.");
        save();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", it.toMap());
        out.put("availabilityChanged", wasAvailable != it.available);
        return out;
    }

    private int kitchenWorkloadMinutes() {
        int total = 0;
        for (TableState t : tables.values()) for (Order o : t.orders) {
            if (!Models.CONFIRMED.equals(o.status)) continue;
            for (Line line : o.lines) if (!line.ready) total += Math.max(1, line.prepMinutes);
        }
        return total;
    }

    synchronized Map<String, Object> publicKitchenEstimate() {
        int pendingLines = 0;
        for (TableState t : tables.values()) for (Order o : t.orders) {
            if (!Models.CONFIRMED.equals(o.status)) continue;
            for (Line line : o.lines) if (!line.ready) pendingLines++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queueMinutes", kitchenWorkloadMinutes());
        out.put("pendingItems", pendingLines);
        out.put("updatedAt", System.currentTimeMillis());
        out.put("estimateType", "sequential-kitchen-estimate");
        return out;
    }

    synchronized Map<String, Object> kitchenQueue() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // A table's main course is eligible only after all of that table's starters are marked ready.
        for (TableState t : tables.values()) for (Order o : t.orders) {
            if (!Models.CONFIRMED.equals(o.status)) continue;
            boolean startersPending = o.lines.stream().anyMatch(l -> "STARTER".equals(l.course) && !l.ready);
            for (int idx = 0; idx < o.lines.size(); idx++) {
                Line l = o.lines.get(idx);
                if (l.ready) continue;
                if ("MAIN".equals(l.course) && startersPending) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", o.id); row.put("orderNo", o.seq); row.put("orderDate", o.seqDate); row.put("lineIndex", idx); row.put("table", o.table); row.put("customer", o.customerName);
                row.put("item", l.name); row.put("qty", l.qty); row.put("course", l.course);
                row.put("prepMinutes", l.prepMinutes); row.put("preparation", l.preparation);
                row.put("createdAt", o.createdAt); rows.add(row);
            }
        }
        rows.sort((a,b) -> Long.compare((Long)a.get("createdAt"), (Long)b.get("createdAt")));
        int elapsed = 0;
        for (Map<String, Object> row : rows) {
            row.put("estimatedQueueMinutes", elapsed + ((Number)row.get("prepMinutes")).intValue());
            elapsed += ((Number)row.get("prepMinutes")).intValue();
        }
        List<Map<String, Object>> oldOrders = new ArrayList<>();
        List<Map<String, Object>> newOrders = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 10L * 60 * 1000;
        for (Map<String, Object> row : rows) {
            if (((Number) row.get("createdAt")).longValue() <= cutoff) oldOrders.add(row);
            else newOrders.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("oldOrders", oldOrders);
        out.put("newOrders", newOrders);
        out.put("estimatedRemainingMinutes", kitchenWorkloadMinutes());
        out.put("now", System.currentTimeMillis());
        return out;
    }

    synchronized Map<String, Object> setKitchenLineReady(String orderId, int idx) {
        Order o = findOrder(orderId);
        if (o == null) throw ApiException.notFound("Order not found");
        if (idx < 0 || idx >= o.lines.size()) throw ApiException.notFound("Order line not found");
        Line line = o.lines.get(idx);
        if (!line.ready) {
            line.ready = true;
            notifyAllStaff("kitchen-ready", line.name + " for Table " + o.table + " is ready to serve.");
            save();
        }
        return kitchenQueue();
    }

    synchronized void setAvailable(String id, boolean available) {
        MenuItem it = item(id);
        if (it == null) throw ApiException.notFound("Item not found");
        it.available = available && it.stock > 0;
        notifyAllStaff(it.available ? "stock-restored" : "out-of-stock", it.name + (it.available ? " is available again." : " has been marked unavailable by admin."));
        save();
    }

    synchronized void deleteItem(String id) {
        MenuItem it = item(id);
        if (it == null) throw ApiException.notFound("Item not found");
        items.remove(it);
        save();
        audit("menu: deleted " + it.name);
    }

    synchronized Map<String, Object> saveCategory(String id, Map<String, Object> b) {
        Category c;
        boolean isNew = id == null;
        if (isNew) {
            c = new Category();
        } else {
            c = category(id);
            if (c == null) throw ApiException.notFound("Category not found");
        }
        c.name = text(b.get("name"), 50, true, "Category name");
        c.nameTe = text(b.get("nameTe"), 50, false, "Telugu name");
        if (isNew) {
            c.id = "c" + (nextCat++);
            categories.add(c);
        }
        save();
        return c.toMap();
    }

    synchronized void deleteCategory(String id) {
        Category c = category(id);
        if (c == null) throw ApiException.notFound("Category not found");
        for (MenuItem i : items) {
            if (i.categoryId.equals(id)) throw ApiException.conflict("This category still has items. Move or delete them first.");
        }
        categories.remove(c);
        save();
    }

    synchronized void moveCategory(String id, int dir) {
        int idx = -1;
        for (int k = 0; k < categories.size(); k++) if (categories.get(k).id.equals(id)) idx = k;
        if (idx < 0) throw ApiException.notFound("Category not found");
        int to = idx + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= categories.size()) return;
        Category c = categories.remove(idx);
        categories.add(to, c);
        save();
    }

    // ================================================================= table helpers

    private int checkTable(int n) {
        if (!((n >= 1 && n <= settings.tableCount) || (n >= 101 && n <= 103))) throw ApiException.bad("Table or bed " + n + " does not exist");
        return n;
    }

    private TableState tableFor(int n) {
        checkTable(n);
        return tables.computeIfAbsent(n, k -> {
            TableState t = new TableState();
            t.number = k;
            return t;
        });
    }

    private Order findOrder(String id) {
        for (TableState t : tables.values()) for (Order o : t.orders) if (o.id.equals(id)) return o;
        return null;
    }

    private TableState tableOfOrder(Order o) { return tables.get(o.table); }

    /** Total of all orders on the table (every order here is already confirmed by a waiter). */
    private static long confirmedSubtotal(TableState t) {
        long s = 0;
        for (Order o : t.orders) for (Line line : o.lines) if (line.served) s += line.total();
        return s;
    }

    private boolean isActive(TableState t) {
        return !t.orders.isEmpty();
    }

    /** Totals for a table: subtotal, discount, gst, grand total (all whole rupees). */
    private Map<String, Object> totals(TableState t) {
        long sub = confirmedSubtotal(t);
        long disc = Math.min(Math.max(t.discount, 0), sub);
        long taxable = sub - disc;
        long gst = Math.round(taxable * settings.gstPercent / 100.0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subtotal", sub);
        m.put("discount", disc);
        m.put("gstPercent", settings.gstPercent);
        m.put("gst", gst);
        m.put("total", taxable + gst);
        return m;
    }

    private void dropIfEmpty(TableState t) {
        if (t.orders.isEmpty()) tables.remove(t.number);
    }

    // ================================================================= waiters: people and table allocation

    private Waiter waiter(String id) {
        for (Waiter w : waiters) if (w.id.equals(id)) return w;
        return null;
    }

    private List<Integer> tablesOf(String waiterId) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : allocation.entrySet()) if (e.getValue().equals(waiterId)) out.add(e.getKey());
        return out;
    }

    private boolean hasOpenBill(int table) {
        TableState t = tables.get(table);
        return t != null && !t.orders.isEmpty();
    }

    private String privateName(Object o) {
        String s = o == null ? "" : String.valueOf(o).strip();
        if (s.isEmpty()) return "";
        if (!PRIVATE_FILE.matcher(s).matches() || !media.exists(Drive.KIND_PRIVATE, s)) {
            throw ApiException.bad("Photo not found. Please upload it again.");
        }
        return s;
    }

    private void deletePrivate(String name) {
        if (name == null || name.isEmpty() || !PRIVATE_FILE.matcher(name).matches()) return;
        media.delete(Drive.KIND_PRIVATE, name);
    }

    /** Path of a private photo (waiter picture or Aadhaar card picture). Only the manager's page may ask for it. */
    byte[] privateBytes(String name) throws IOException {
        if (name == null || !PRIVATE_FILE.matcher(name).matches()) throw ApiException.notFound("Not found");
        byte[] d = media.read(Drive.KIND_PRIVATE, name);
        if (d == null) throw ApiException.notFound("Not found");
        return d;
    }

    private Map<String, Object> waiterRow(Waiter w) {
        Map<String, Object> m = w.toMap();
        List<Integer> mine = tablesOf(w.id);
        int open = 0;
        for (int n : mine) if (hasOpenBill(n)) open++;
        m.put("tables", mine);
        m.put("openTables", open);
        return m;
    }

    synchronized Map<String, Object> waiters() {
        List<Object> list = new ArrayList<>();
        for (Waiter w : waiters) list.add(waiterRow(w));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waiters", list);
        Map<String, Object> alloc = new LinkedHashMap<>();
        for (int n = 1; n <= settings.tableCount; n++) alloc.put(String.valueOf(n), allocation.getOrDefault(n, ""));
        out.put("allocation", alloc);
        List<Object> busy = new ArrayList<>();
        for (int n = 1; n <= settings.tableCount; n++) if (hasOpenBill(n)) busy.add(n);
        out.put("busyTables", busy);
        out.put("tableCount", settings.tableCount);
        out.put("tablesPerWaiter", settings.tablesPerWaiter);
        return out;
    }

    synchronized Map<String, Object> saveWaiter(String id, Map<String, Object> b) {
        boolean isNew = id == null;
        Waiter w;
        if (isNew) {
            w = new Waiter();
        } else {
            w = waiter(id);
            if (w == null) throw ApiException.notFound("Waiter not found");
        }
        String name = text(b.get("name"), 60, true, "Waiter name");
        String phone = cleanPhone(b.get("phone"));
        for (Waiter x : waiters) {
            if (x != w && x.phone.equals(phone)) throw ApiException.conflict("Another waiter already uses this mobile number");
        }
        String aad = String.valueOf(b.get("aadhaar") == null ? "" : b.get("aadhaar")).replaceAll("[\\s-]", "");
        if (!AADHAAR.matcher(aad).matches()) throw ApiException.bad("Aadhaar number must be 12 digits");
        String address = text(b.get("address"), 200, true, "Address");
        String photo = privateName(b.get("photo"));
        String aadhaarImage = privateName(b.get("aadhaarImage"));
        boolean active = !b.containsKey("active") || Boolean.TRUE.equals(b.get("active"));
        if (!isNew && !active && w.active) {
            for (int n : tablesOf(w.id)) {
                if (hasOpenBill(n)) throw ApiException.conflict("Table " + n + " still has an open bill. Switch this waiter off after payment.");
            }
            for (int n : tablesOf(w.id)) allocation.remove(n);
        }
        if (!isNew) {
            if (!w.photo.isEmpty() && !w.photo.equals(photo)) deletePrivate(w.photo);
            if (!w.aadhaarImage.isEmpty() && !w.aadhaarImage.equals(aadhaarImage)) deletePrivate(w.aadhaarImage);
        }
        w.name = name;
        w.phone = phone;
        w.aadhaar = aad;
        w.address = address;
        w.photo = photo;
        w.aadhaarImage = aadhaarImage;
        w.active = active;
        if (isNew) {
            w.id = "w" + (nextWaiter++);
            waiters.add(w);
            audit("waiter added: " + w.name + " (" + w.phone + ")");
        } else {
            audit("waiter updated: " + w.name);
        }
        save();
        return waiterRow(w);
    }

    synchronized void deleteWaiter(String id) {
        Waiter w = waiter(id);
        if (w == null) throw ApiException.notFound("Waiter not found");
        for (int n : tablesOf(id)) {
            if (hasOpenBill(n)) throw ApiException.conflict("Table " + n + " still has an open bill. Remove the waiter after payment.");
        }
        for (int n : tablesOf(id)) allocation.remove(n);
        waiters.remove(w);
        deletePrivate(w.photo);
        deletePrivate(w.aadhaarImage);
        save();
        audit("waiter removed: " + w.name);
    }

    synchronized void setTableWaiter(int table, String waiterId) {
        checkTable(table);
        String current = allocation.get(table);
        String wanted = waiterId == null ? "" : waiterId;
        if (wanted.equals(current == null ? "" : current)) return;
        if (hasOpenBill(table)) throw ApiException.conflict("Table " + table + " has an open bill. Change its waiter after payment.");
        if (wanted.isEmpty()) {
            allocation.remove(table);
        } else {
            Waiter w = waiter(wanted);
            if (w == null || !w.active) throw ApiException.notFound("Waiter not found");
            int already = 0;
            for (int n : tablesOf(w.id)) if (n != table) already++;
            if (already >= settings.tablesPerWaiter) {
                throw ApiException.conflict(w.name + " already has " + already + " tables (limit " + settings.tablesPerWaiter + "). Change the limit in Settings if needed.");
            }
            allocation.put(table, w.id);
        }
        save();
        audit("table " + table + " assigned to " + (wanted.isEmpty() ? "nobody" : waiter(wanted).name));
    }

    /** Gives each active waiter the next few free tables, in order (4 tables each by default). Tables with open bills keep their waiter. */
    synchronized Map<String, Object> autoAssign() {
        List<Waiter> active = new ArrayList<>();
        for (Waiter w : waiters) if (w.active) active.add(w);
        Map<String, Integer> load = new LinkedHashMap<>();
        for (Waiter w : active) load.put(w.id, 0);
        for (int n = 1; n <= settings.tableCount; n++) {
            if (hasOpenBill(n)) {
                String id = allocation.get(n);
                if (id != null && load.containsKey(id)) load.merge(id, 1, Integer::sum);
            } else {
                allocation.remove(n);
            }
        }
        int assigned = 0, left = 0, wi = 0;
        for (int n = 1; n <= settings.tableCount; n++) {
            if (hasOpenBill(n)) continue;
            while (wi < active.size() && load.get(active.get(wi).id) >= settings.tablesPerWaiter) wi++;
            if (wi >= active.size()) { left++; continue; }
            Waiter w = active.get(wi);
            allocation.put(n, w.id);
            load.merge(w.id, 1, Integer::sum);
            assigned++;
        }
        save();
        audit("tables auto-assigned: " + assigned + " assigned, " + left + " without a waiter");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", assigned);
        out.put("unassigned", left);
        return out;
    }

    // ================================================================= waiter's phone: open a table, take orders, serve, take payment

    /** A waiter opens a table by typing the mobile number the manager registered. Returns the waiter's id, name and tables. */
    synchronized Map<String, Object> verifyWaiter(String phoneRaw, int table) {
        String phone = cleanPhone(phoneRaw);
        Waiter w = null;
        for (Waiter x : waiters) if (x.active && x.phone.equals(phone)) w = x;
        if (w == null) throw new ApiException(403, "This mobile number is not registered as a waiter. Please ask the manager.");
        List<Integer> mine = tablesOf(w.id);
        if (table > 0) {
            checkTable(table);
            if (!mine.contains(table)) throw new ApiException(403, "Table " + table + " is not assigned to you.");
        }
        if (mine.isEmpty()) throw new ApiException(403, "No tables are assigned to you yet. Please ask the manager.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", w.id);
        out.put("name", w.name);
        out.put("tables", mine);
        return out;
    }

    private Waiter requireWaiter(String waiterId, int table) {
        checkTable(table);
        Waiter w = waiterId == null ? null : waiter(waiterId);
        if (w == null || !w.active) throw new ApiException(401, "Please open the table again with your mobile number");
        if (!w.id.equals(allocation.get(table))) throw new ApiException(403, "Table " + table + " is not assigned to you");
        return w;
    }

    private Map<String, Object> waiterView(Waiter w, int n) {
        TableState t = tables.get(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", n);
        out.put("waiter", w.name);
        List<Object> os = new ArrayList<>();
        String lastName = "", lastPhone = "";
        boolean allServed = t != null && !t.orders.isEmpty();
        if (t != null) {
            for (Order o : t.orders) {
                os.add(o.toMap(false));
                if (!Models.SERVED.equals(o.status)) allServed = false;
                if (!o.source.equals("manager")) { lastName = o.customerName; lastPhone = o.phone; }
            }
        }
        out.put("orders", os);
        out.put("allServed", t != null && confirmedSubtotal(t) > 0);
        out.put("allOrdersServed", allServed);
        out.put("billRequested", t != null && t.billRequested);
        out.putAll(totals(t == null ? new TableState() : t));
        out.put("lastCustomerName", lastName);
        out.put("lastCustomerPhone", lastPhone);
        out.put("upiId", settings.upiId);
        out.put("payeeName", settings.payeeName);
        putNextOrder(out);
        return out;
    }

    synchronized Map<String, Object> waiterTable(String waiterId, int n) {
        return waiterView(requireWaiter(waiterId, n), n);
    }

    /** The waiter takes an order at the table, asks one customer for name and number, and confirms it. */
    synchronized Map<String, Object> waiterPlaceOrder(String waiterId, Map<String, Object> b) {
        int table = (int) wholeNumber(b.get("table"), "Table number");
        Waiter w = requireWaiter(waiterId, table);
        String name = cleanName(b.get("name"));
        String phone = cleanPhone(b.get("phone"));
        String note = text(b.get("note"), 200, false, "Note");
        List<Object> req = Json.list(b, "items");
        if (req.isEmpty()) throw ApiException.bad("The order is empty");
        if (req.size() > 40) throw ApiException.bad("Too many different items in one order");

        List<Line> lines = new ArrayList<>();
        for (Object o : req) {
            Map<String, Object> r = Json.map(o);
            MenuItem mi = item(Json.str(r, "itemId", ""));
            if (mi == null) throw ApiException.bad("An item is no longer on the menu");
            if (!mi.available || mi.stock <= 0) throw ApiException.bad(mi.name + " is sold out right now");
            long qty = wholeNumber(r.get("qty"), "Quantity");
            if (qty < 1 || qty > 20) throw ApiException.bad("Quantity must be between 1 and 20");
            if (mi.stock < qty) throw ApiException.conflict(mi.name + " has only " + mi.stock + " portions left.");
            String requestedPreparation = mi.preparationEnabled
                ? Json.str(r, "preparation", mi.preparation == null ? "" : mi.preparation).trim()
                : "";
            if (mi.preparationEnabled && requestedPreparation.isEmpty()) throw ApiException.bad("Select a preparation type for " + mi.name);
            if (mi.preparationEnabled && mi.preparationOptions.stream().noneMatch(v -> v.equalsIgnoreCase(requestedPreparation)))
                throw ApiException.bad("Invalid preparation type for " + mi.name);
            final String selectedPreparation = requestedPreparation;
            Line existing = null;
            for (Line l : lines) if (l.itemId.equals(mi.id) && l.preparation.equalsIgnoreCase(selectedPreparation)) existing = l;
            if (existing != null) {
                existing.qty += (int) qty;
                if (existing.qty > 40) throw ApiException.bad("Quantity of " + mi.name + " is too high");
            } else {
                Line l = new Line();
                l.itemId = mi.id;
                l.name = mi.name;
                l.price = mi.price;
                l.qty = (int) qty;
                l.preparation = selectedPreparation;
                l.prepMinutes = mi.prepMinutes;
                Category cat = category(mi.categoryId);
                String catName = cat == null ? "" : cat.name.toLowerCase(java.util.Locale.ROOT);
                l.course = catName.contains("starter") ? "STARTER" : "MAIN";
                lines.add(l);
            }
        }

        int queueAheadMinutes = kitchenWorkloadMinutes();
        // Aggregate same menu item across distinct preparation requests before reserving stock.
        Map<String, Integer> requestedStock = new LinkedHashMap<>();
        for (Line line : lines) requestedStock.merge(line.itemId, line.qty, Integer::sum);
        for (Map.Entry<String, Integer> entry : requestedStock.entrySet()) {
            MenuItem stockItem = item(entry.getKey());
            if (stockItem == null || !stockItem.available || stockItem.stock < entry.getValue())
                throw ApiException.conflict(stockItem == null ? "Menu item unavailable" : stockItem.name + " has only " + stockItem.stock + " portions left.");
        }
        // Reserve stock atomically only after every requested line has passed validation.
        for (Map.Entry<String, Integer> entry : requestedStock.entrySet()) {
            MenuItem mi = item(entry.getKey());
            int usedQty = entry.getValue();
            mi.stock -= usedQty;
            if (mi.stock == 0) {
                mi.available = false;
                notifyAllStaff("out-of-stock", mi.name + " is OUT OF STOCK. Stop taking orders.");
            } else if (mi.stock <= mi.lowStockThreshold && mi.stock + usedQty > mi.lowStockThreshold) {
                notifyAllStaff("low-stock", mi.name + " is low: only " + mi.stock + " portions left.");
            }
        }
        TableState t = tableFor(table);
        Order ord = new Order();
        ord.id = String.valueOf(nextOrder++);
        ord.seq = takeOrderNumber();
        ord.seqDate = todayKey();
        ord.table = table;
        ord.customerName = name;
        ord.phone = phone;
        ord.note = note;
        ord.source = "waiter";
        ord.waiterName = w.name;
        ord.token = Auth.randomToken(16);
        ord.createdAt = System.currentTimeMillis();
        ord.confirmedAt = ord.createdAt;
        ord.status = Models.CONFIRMED;
        ord.lines = lines;
        t.orders.add(ord);
        t.billRequested = false;   // new food was ordered, so the payment request starts again
        save();
        notifyAllStaff("new-order", "Order " + ord.seq + " confirmed: Table " + table + " · " + w.name + ". Next order is " + (ord.seq + 1) + ".");
        audit("table " + table + ": " + w.name + " confirmed order " + ord.seq + " (" + ord.seqDate + ", id " + ord.id + ") for " + name);
        Map<String, Object> out = waiterView(w, table);
        int starterMins = 0, mainMins = 0;
        for (Line line : lines) {
            if ("STARTER".equals(line.course)) starterMins = Math.max(starterMins, line.prepMinutes);
            else mainMins = Math.max(mainMins, line.prepMinutes);
        }
        out.put("orderId", ord.id);
        out.put("orderNo", ord.seq);
        out.put("orderDate", ord.seqDate);
        out.put("kitchenQueueAheadMinutes", queueAheadMinutes);
        out.put("estimatedWaitMinutes", queueAheadMinutes + starterMins + mainMins);
        out.put("starterWaitMinutes", starterMins);
        out.put("mainCourseWaitMinutes", mainMins);
        return out;
    }

    synchronized Map<String, Object> waiterServeLine(String waiterId, String orderId, int idx) {
        Order o = findOrder(orderId);
        if (o == null) throw ApiException.notFound("Order not found");
        Waiter w = requireWaiter(waiterId, o.table);
        if (idx < 0 || idx >= o.lines.size()) throw ApiException.notFound("Order line not found");
        Line line = o.lines.get(idx);
        if (!line.ready) throw ApiException.conflict("Kitchen must mark this item Ready before serving.");
        line.served = true;
        if (o.lines.stream().allMatch(x -> x.served)) o.status = Models.SERVED;
        save();
        return waiterView(w, o.table);
    }

    synchronized Map<String, Object> waiterServe(String waiterId, String orderId) {
        Order o = findOrder(orderId);
        if (o == null) throw ApiException.notFound("Order not found");
        Waiter w = requireWaiter(waiterId, o.table);
        if (Models.CONFIRMED.equals(o.status)) {
            if (o.lines.stream().anyMatch(line -> !line.ready))
                throw ApiException.conflict("Kitchen has not marked every item ready yet.");
            for (Line line : o.lines) line.served = true;
            o.status = Models.SERVED;
            save();
        }
        return waiterView(w, o.table);
    }

    synchronized Map<String, Object> waiterRequestPayment(String waiterId, int table) {
        Waiter w = requireWaiter(waiterId, table);
        TableState t = tables.get(table);
        if (t == null || confirmedSubtotal(t) <= 0) throw ApiException.conflict("There are no served items to bill on this table");
        if (!t.billRequested) {
            t.billRequested = true;
            t.billRequestedAt = System.currentTimeMillis();
            save();
            audit("table " + table + ": " + w.name + " requested payment");
        }
        return waiterView(w, table);
    }

    /** The waiter records the payment (cash, card or UPI). The money is then owed to the manager until it is marked collected. */
    synchronized Map<String, Object> waiterPay(String waiterId, int table, Map<String, Object> b) {
        Waiter w = requireWaiter(waiterId, table);
        TableState t = tables.get(table);
        if (t == null || confirmedSubtotal(t) <= 0) throw ApiException.conflict("There is nothing to bill on this table");
        if (!t.billRequested) throw ApiException.conflict("Request payment first");
        String method = Json.str(b, "method", "");
        if (!method.equals("cash") && !method.equals("card") && !method.equals("upi")) throw ApiException.bad("Choose cash, card or UPI");
        Map<String, Object> tot = totals(t);
        long total = (Long) tot.get("total");
        if (b.get("total") != null && Json.lng(b, "total", -1) != total) {
            throw ApiException.conflict("The bill changed to Rs. " + total + ". Please check the amount and try again.");
        }
        long received = total;
        long change = 0;
        if (method.equals("cash")) {
            received = wholeNumber(b.get("received") == null ? total : b.get("received"), "Cash received");
            if (received < total) throw ApiException.bad("Cash received (Rs. " + received + ") is less than the bill (Rs. " + total + ")");
            change = received - total;
        }
        Bill bill = new Bill();
        bill.no = nextBill++;
        bill.table = table;
        bill.paidAt = System.currentTimeMillis();
        bill.method = method;
        bill.subtotal = (Long) tot.get("subtotal");
        bill.discount = (Long) tot.get("discount");
        bill.gstPercent = settings.gstPercent;
        bill.gst = (Long) tot.get("gst");
        bill.total = total;
        bill.received = received;
        bill.change = change;
        bill.reference = text(b.get("reference"), 40, false, "Reference");
        bill.receiptPhoto = text(b.get("receiptPhoto"), 2_500_000, true, "Payment photo");
        if (bill.receiptPhoto.isBlank() || !bill.receiptPhoto.startsWith("data:image/")) {
            throw ApiException.bad("Take or upload a payment photo before completing payment");
        }
        bill.waiterId = w.id;
        bill.waiterName = w.name;

        Set<String> seen = new LinkedHashSet<>();
        List<Line> merged = new ArrayList<>();
        for (Order o : t.orders) {
            if (o.seq > 0) bill.orderNos.add(o.seq);
            if (!o.source.equals("manager") && seen.add(o.customerName + "|" + o.phone)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", o.customerName);
                c.put("phone", o.phone);
                bill.customers.add(c);
            }
            for (Line l : o.lines) {
                if (!l.served) continue;
                Line found = null;
                for (Line m : merged) if (m.name.equals(l.name) && m.price == l.price) found = m;
                if (found != null) found.qty += l.qty;
                else {
                    Line c = new Line();
                    c.itemId = l.itemId; c.name = l.name; c.price = l.price; c.qty = l.qty; c.custom = l.custom;
                    merged.add(c);
                }
            }
        }
        bill.lines = merged;

        try {
            Files.writeString(billsFile, Json.stringify(bill.toMap()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        bills.add(bill);
        tables.remove(table);
        save();
        drive.enqueueInvoice(bill.no);   // saved to the manager's Google Drive folder in the background
        audit("table " + table + ": bill #" + bill.no + " Rs." + total + " paid by " + method + " (waiter " + w.name + ")");
        return bill.toMap();
    }

    // ================================================================= manager: tables

    private long dayStart(LocalDate d) { return d.atStartOfDay(zone).toInstant().toEpochMilli(); }

    private List<Bill> billsOn(LocalDate d) {
        long from = dayStart(d), to = dayStart(d.plusDays(1));
        List<Bill> out = new ArrayList<>();
        for (Bill b : bills) if (b.paidAt >= from && b.paidAt < to) out.add(b);
        return out;
    }

    private String waiterNameOf(int table) {
        Waiter w = allocation.containsKey(table) ? waiter(allocation.get(table)) : null;
        return w == null ? "" : w.name;
    }

    synchronized Map<String, Object> managerTables() {
        List<Object> list = new ArrayList<>();
        int ongoing = 0, bill = 0, free = 0;
        long toCollect = 0, todaySales = 0;
        Map<Integer, long[]> sale = new LinkedHashMap<>();   // table -> {sale today, still to collect}
        for (Bill b : billsOn(LocalDate.now(zone))) {
            long[] a = sale.computeIfAbsent(b.table, k -> new long[2]);
            a[0] += b.total;
            if (!b.collected) a[1] += b.total;
        }
        for (long[] a : sale.values()) { todaySales += a[0]; toCollect += a[1]; }
        for (int n = 1; n <= settings.tableCount; n++) {
            TableState t = tables.get(n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", n);
            m.put("label", "Table " + n);
            m.put("waiter", waiterNameOf(n));
            long[] a = sale.getOrDefault(n, new long[2]);
            m.put("todaySale", a[0]);
            m.put("toCollect", a[1]);
            if (t == null || !isActive(t)) {
                m.put("status", "FREE");
                free++;
            } else {
                m.put("status", t.billRequested ? "BILL" : "ONGOING");
                if (t.billRequested) bill++; else ongoing++;
                Set<String> names = new LinkedHashSet<>();
                long since = Long.MAX_VALUE;
                int itemCount = 0;
                for (Order o : t.orders) {
                    if (!o.source.equals("manager")) names.add(o.customerName);
                    since = Math.min(since, o.createdAt);
                    itemCount += o.itemCount();
                }
                m.put("customers", new ArrayList<>(names));
                m.put("items", itemCount);
                m.put("since", since == Long.MAX_VALUE ? 0 : since);
                m.put("billRequested", t.billRequested);
                m.put("billRequestedAt", t.billRequestedAt);
                m.putAll(totals(t));
            }
            list.add(m);
        }
        for (int bed = 1; bed <= 3; bed++) {
            int n = 100 + bed;
            TableState t = tables.get(n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", n); m.put("label", "Bed " + bed); m.put("waiter", waiterNameOf(n));
            long[] a = sale.getOrDefault(n, new long[2]); m.put("todaySale", a[0]); m.put("toCollect", a[1]);
            if (t == null || !isActive(t)) { m.put("status", "FREE"); free++; }
            else {
                m.put("status", t.billRequested ? "BILL" : "ONGOING");
                if (t.billRequested) bill++; else ongoing++;
                Set<String> names = new LinkedHashSet<>(); long since = Long.MAX_VALUE; int itemCount = 0;
                for (Order o : t.orders) { if (!o.source.equals("manager")) names.add(o.customerName); since = Math.min(since, o.createdAt); itemCount += o.itemCount(); }
                m.put("customers", new ArrayList<>(names)); m.put("items", itemCount); m.put("since", since == Long.MAX_VALUE ? 0 : since);
                m.put("billRequested", t.billRequested); m.put("billRequestedAt", t.billRequestedAt); m.putAll(totals(t));
            }
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", list);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("free", free);
        counts.put("ongoing", ongoing);
        counts.put("bill", bill);
        counts.put("toCollect", toCollect);
        counts.put("todaySales", todaySales);
        out.put("counts", counts);
        out.put("now", System.currentTimeMillis());
        out.put("settings", settings.managerView());
        return out;
    }

    synchronized Map<String, Object> tableDetail(int n) {
        checkTable(n);
        TableState t = tables.get(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", n);
        out.put("waiter", waiterNameOf(n));
        List<Object> os = new ArrayList<>();
        if (t != null) for (Order o : t.orders) os.add(o.toMap(false));
        out.put("orders", os);
        out.put("billRequested", t != null && t.billRequested);
        out.put("discountInput", t == null ? 0 : t.discount);
        out.putAll(totals(t == null ? new TableState() : t));
        out.put("settings", settings.managerView());
        return out;
    }

    synchronized void setLineQty(String orderId, int idx, long qty) {
        Order o = findOrder(orderId);
        if (o == null) throw ApiException.notFound("Order not found");
        if (idx < 0 || idx >= o.lines.size()) throw ApiException.notFound("Line not found");
        if (qty < 0 || qty > 99) throw ApiException.bad("Quantity must be between 0 and 99");
        Line l = o.lines.get(idx);
        TableState t = tableOfOrder(o);
        if (qty > l.qty && !l.custom) {
            MenuItem stockItem = item(l.itemId);
            int extra = (int)qty - l.qty;
            if (stockItem == null || !stockItem.available || stockItem.stock < extra)
                throw ApiException.conflict(stockItem == null ? "Menu item unavailable" : stockItem.name + " has only " + stockItem.stock + " portions left.");
            stockItem.stock -= extra;
            if (stockItem.stock == 0) { stockItem.available = false; notifyAllStaff("out-of-stock", stockItem.name + " is OUT OF STOCK."); }
            else if (stockItem.stock <= stockItem.lowStockThreshold && stockItem.stock + extra > stockItem.lowStockThreshold)
                notifyAllStaff("low-stock", stockItem.name + " is low: only " + stockItem.stock + " portions left.");
        } else if (qty < l.qty && !l.custom) {
            MenuItem stockItem = item(l.itemId);
            if (stockItem != null) { stockItem.stock += (int)(l.qty - qty); stockItem.available = true; notifyAllStaff("stock-restored", stockItem.name + " stock updated after order adjustment: " + stockItem.stock + "."); }
        }
        if (qty == 0) {
            o.lines.remove(idx);
            audit("table " + o.table + ": removed " + l.name + " x" + l.qty + " from order #" + o.id);
            if (o.lines.isEmpty()) {
                t.orders.remove(o);
                dropIfEmpty(t);
            }
        } else {
            audit("table " + o.table + ": " + l.name + " qty " + l.qty + " -> " + qty + " (order #" + o.id + ")");
            l.qty = (int) qty;
        }
        if (t != null) t.billRequested = false;
        save();
    }

    synchronized Map<String, Object> addManagerItem(int table, Map<String, Object> b) {
        checkTable(table);
        TableState t = tables.get(table);
        if (t == null || t.orders.isEmpty()) throw ApiException.conflict("Table " + table + " has no open order. The waiter takes the order first.");
        long qty = wholeNumber(b.get("qty") == null ? 1L : b.get("qty"), "Quantity");
        if (qty < 1 || qty > 99) throw ApiException.bad("Quantity must be between 1 and 99");
        Line add = new Line();
        add.qty = (int) qty;
        String itemId = Json.str(b, "itemId", "");
        if (!itemId.isEmpty()) {
            MenuItem mi = item(itemId);
            if (mi == null) throw ApiException.notFound("Item not found");
            if (!mi.available || mi.stock < qty) throw ApiException.conflict(mi.name + " is unavailable or has only " + mi.stock + " portions left.");
            add.itemId = mi.id;
            add.name = mi.name;
            add.price = mi.price;
            mi.stock -= (int)qty;
            if (mi.stock == 0) { mi.available = false; notifyAllStaff("out-of-stock", mi.name + " is OUT OF STOCK."); }
            else if (mi.stock <= mi.lowStockThreshold && mi.stock + qty > mi.lowStockThreshold) notifyAllStaff("low-stock", mi.name + " is low: only " + mi.stock + " portions left.");
        } else {
            add.name = text(b.get("name"), 80, true, "Item name");
            long price = wholeNumber(b.get("price"), "Price");
            if (price < 0 || price > 100000) throw ApiException.bad("Price must be between 0 and 100000");
            add.price = price;
            add.custom = true;
        }
        Order counter = null;
        for (Order o : t.orders) if (o.source.equals("manager")) counter = o;
        if (counter == null) {
            counter = new Order();
            counter.id = String.valueOf(nextOrder++);
            counter.table = table;
            counter.customerName = "Added by manager";
            counter.source = "manager";
            counter.status = Models.SERVED;
            counter.createdAt = System.currentTimeMillis();
            counter.confirmedAt = counter.createdAt;
            counter.token = Auth.randomToken(16);
            t.orders.add(counter);
        }
        Line same = null;
        for (Line l : counter.lines) {
            if (l.name.equals(add.name) && l.price == add.price && l.itemId.equals(add.itemId)) same = l;
        }
        if (same != null) same.qty += add.qty; else counter.lines.add(add);
        t.billRequested = false;
        save();
        audit("table " + table + ": manager added " + add.name + " x" + add.qty + " @Rs." + add.price);
        return tableDetail(table);
    }

    synchronized Map<String, Object> setDiscount(int table, Object amount) {
        TableState t = tables.get(checkTable(table));
        if (t == null) throw ApiException.conflict("Table has no open bill");
        long d = wholeNumber(amount == null ? 0L : amount, "Discount");
        long sub = confirmedSubtotal(t);
        if (d < 0) throw ApiException.bad("Discount cannot be negative");
        if (d > sub) throw ApiException.bad("Discount cannot be more than the bill (Rs. " + sub + ")");
        t.discount = d;
        save();
        audit("table " + table + ": discount set to Rs." + d);
        return tableDetail(table);
    }

    // ================================================================= manager: payments handed over by waiters

    private static String methodKey(String m) { return m == null ? "cash" : m; }

    /** Every table's sale for one day, split by cash / UPI / card, and how much the manager has already collected from the waiter. */
    synchronized Map<String, Object> payments(String dateStr) {
        LocalDate d = parseDate(dateStr, LocalDate.now(zone));
        List<Bill> day = billsOn(d);
        Set<Integer> numbers = new java.util.TreeSet<>();
        for (int n = 1; n <= settings.tableCount; n++) numbers.add(n);
        for (Bill b : day) numbers.add(b.table);

        long totalAll = 0, collectedAll = 0, cashAll = 0, upiAll = 0, cardAll = 0;
        List<Object> rows = new ArrayList<>();
        Map<String, List<Integer>> byWaiter = new LinkedHashMap<>();
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Waiter w : waiters) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("waiterId", w.id); g.put("name", w.name); g.put("phone", w.phone);
            g.put("total", 0L); g.put("pending", 0L);
            groups.put(w.id, g);
            byWaiter.put(w.id, new ArrayList<>());
        }
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("waiterId", ""); none.put("name", "No waiter assigned"); none.put("phone", "");
        none.put("total", 0L); none.put("pending", 0L);
        groups.put("", none);
        byWaiter.put("", new ArrayList<>());

        for (int n : numbers) {
            long cash = 0, upi = 0, card = 0, total = 0, collected = 0;
            List<Object> bl = new ArrayList<>();
            String lastWaiter = "";
            for (Bill b : day) {
                if (b.table != n) continue;
                total += b.total;
                if (b.collected) collected += b.total;
                String mk = methodKey(b.method);
                if (mk.equals("cash")) cash += b.total; else if (mk.equals("upi")) upi += b.total; else card += b.total;
                if (!b.waiterName.isEmpty()) lastWaiter = b.waiterName;
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("no", b.no);
                bm.put("paidAt", b.paidAt);
                bm.put("method", b.method);
                bm.put("total", b.total);
                bm.put("collected", b.collected);
                bm.put("waiter", b.waiterName);
                List<Object> cn = new ArrayList<>();
                for (Map<String, Object> c : b.customers) cn.add(c.get("name"));
                bm.put("customers", cn);
                bl.add(bm);
            }
            String wid = allocation.getOrDefault(n, "");
            if (!wid.isEmpty() && waiter(wid) == null) wid = "";
            String wname = wid.isEmpty() ? lastWaiter : waiter(wid).name;
            long pending = total - collected;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", n);
            row.put("waiterId", wid);
            row.put("waiter", wname);
            row.put("cash", cash); row.put("upi", upi); row.put("card", card);
            row.put("total", total); row.put("collected", collected); row.put("pending", pending);
            row.put("bills", bl);
            row.put("status", total == 0 ? "NONE" : pending == 0 ? "COLLECTED" : "PENDING");
            rows.add(row);
            Map<String, Object> g = groups.get(wid);
            g.put("total", (Long) g.get("total") + total);
            g.put("pending", (Long) g.get("pending") + pending);
            byWaiter.get(wid).add(n);
            totalAll += total; collectedAll += collected; cashAll += cash; upiAll += upi; cardAll += card;
        }
        List<Object> glist = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : groups.entrySet()) {
            Map<String, Object> g = e.getValue();
            List<Integer> tl = byWaiter.get(e.getKey());
            if (e.getKey().isEmpty() && tl.isEmpty()) continue;
            g.put("tables", tl);
            glist.add(g);
        }
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("total", totalAll); sum.put("collected", collectedAll); sum.put("pending", totalAll - collectedAll);
        sum.put("cash", cashAll); sum.put("upi", upiAll); sum.put("card", cardAll);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", d.toString());
        out.put("summary", sum);
        out.put("tables", rows);
        out.put("groups", glist);
        return out;
    }

    /** The manager has received the money from the waiter for these tables. Each item is {table, amount}; the amount must match what is still pending. */
    synchronized Map<String, Object> collect(String dateStr, List<Object> items) {
        LocalDate d = parseDate(dateStr, LocalDate.now(zone));
        if (items.isEmpty()) throw ApiException.bad("Choose a table");
        List<Bill> day = billsOn(d);
        List<Bill> toMark = new ArrayList<>();
        List<Object> tableNos = new ArrayList<>();
        long sum = 0;
        for (Object o : items) {
            Map<String, Object> r = Json.map(o);
            int n = (int) Json.lng(r, "table", 0);
            long expected = Json.lng(r, "amount", -1);
            long pending = 0;
            for (Bill b : day) {
                if (b.table == n && !b.collected) { pending += b.total; toMark.add(b); }
            }
            if (pending == 0) throw ApiException.conflict("Table " + n + " has nothing left to collect");
            if (pending != expected) throw ApiException.conflict("Table " + n + " now shows Rs. " + pending + " to collect. Please check and try again.");
            tableNos.add(n);
            sum += pending;
        }
        long now = System.currentTimeMillis();
        List<Object> nos = new ArrayList<>();
        for (Bill b : toMark) nos.add(b.no);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("at", now);
        ev.put("date", d.toString());
        ev.put("tables", tableNos);
        ev.put("bills", nos);
        try {
            Files.writeString(collectFile, Json.stringify(ev) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Bill b : toMark) { b.collected = true; b.collectedAt = now; }
        audit("collected Rs." + sum + " from waiter(s) for table(s) " + tableNos + " on " + d);
        return payments(d.toString());
    }

    // ================================================================= reports

    private LocalDate parseDate(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try {
            return LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.bad("Date must look like 2026-10-13");
        }
    }

    synchronized Map<String, Object> reports(String from, String to) {
        LocalDate today = LocalDate.now(zone);
        LocalDate f = parseDate(from, today);
        LocalDate t = parseDate(to, today);
        if (t.isBefore(f)) { LocalDate x = f; f = t; t = x; }
        if (f.plusDays(366).isBefore(t)) throw ApiException.bad("Please choose a range of at most one year");
        long start = f.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = t.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

        long revenue = 0, discount = 0, gst = 0;
        int count = 0;
        Map<String, long[]> byMethod = new LinkedHashMap<>();
        byMethod.put("cash", new long[2]);
        byMethod.put("card", new long[2]);
        byMethod.put("upi", new long[2]);
        Map<String, long[]> byItem = new LinkedHashMap<>();
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) byDay.put(d, new long[2]);
        List<Bill> inRange = new ArrayList<>();
        for (Bill b : bills) {
            if (b.paidAt < start || b.paidAt >= end) continue;
            inRange.add(b);
            count++;
            revenue += b.total;
            discount += b.discount;
            gst += b.gst;
            long[] mm = byMethod.computeIfAbsent(b.method, k -> new long[2]);
            mm[0]++; mm[1] += b.total;
            LocalDate day = Instant.ofEpochMilli(b.paidAt).atZone(zone).toLocalDate();
            long[] dd = byDay.get(day);
            if (dd != null) { dd[0]++; dd[1] += b.total; }
            for (Line l : b.lines) {
                long[] a = byItem.computeIfAbsent(l.name, k -> new long[2]);
                a[0] += l.qty; a[1] += l.total();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("bills", count);
        sum.put("revenue", revenue);
        sum.put("discount", discount);
        sum.put("gst", gst);
        sum.put("average", count == 0 ? 0 : Math.round((double) revenue / count));
        out.put("summary", sum);
        List<Object> methods = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byMethod.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", e.getKey());
            m.put("bills", e.getValue()[0]);
            m.put("amount", e.getValue()[1]);
            methods.add(m);
        }
        out.put("methods", methods);
        List<Object> days = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey().toString());
            m.put("bills", e.getValue()[0]);
            m.put("amount", e.getValue()[1]);
            days.add(m);
        }
        out.put("days", days);
        List<Map.Entry<String, long[]>> top = new ArrayList<>(byItem.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<Object> topList = new ArrayList<>();
        for (int k = 0; k < Math.min(10, top.size()); k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", top.get(k).getKey());
            m.put("qty", top.get(k).getValue()[0]);
            m.put("amount", top.get(k).getValue()[1]);
            topList.add(m);
        }
        out.put("top", topList);
        List<Object> list = new ArrayList<>();
        for (int k = inRange.size() - 1; k >= 0 && list.size() < 500; k--) {
            Bill b = inRange.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", b.no);
            m.put("table", b.table);
            m.put("paidAt", b.paidAt);
            m.put("method", b.method);
            m.put("total", b.total);
            m.put("waiter", b.waiterName);
            List<Object> names = new ArrayList<>();
            for (Map<String, Object> c : b.customers) names.add(c.get("name"));
            m.put("customers", names);
            list.add(m);
        }
        out.put("bills", list);
        return out;
    }

    synchronized Map<String, Object> bill(long no) {
        for (Bill b : bills) if (b.no == no) {
            Map<String, Object> m = b.toMap();
            m.put("dhaba", settings.publicView());
            return m;
        }
        throw ApiException.notFound("Bill not found");
    }
}
