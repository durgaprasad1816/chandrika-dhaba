package com.chandrika.dhaba;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data classes. Each one can turn itself into a Map (for JSON) and back. */
final class Models {
    private Models() {}

    static final String PENDING = "PENDING";      // only used to read data saved by version 1
    static final String CONFIRMED = "CONFIRMED";  // waiter has taken and confirmed the order
    static final String SERVED = "SERVED";        // waiter has served the food
    static final String REJECTED = "REJECTED";    // only used to read data saved by version 1

    // ------------------------------------------------------------ settings
    static final class Settings {
        String dhabaName = "Chandrika Dhaba & Restaurant";
        String address = "Samisragudem";
        String phone = "733 755 3223";
        String upiId = "";
        String payeeName = "Chandrika Dhaba & Restaurant";
        int tableCount = 12;
        int gstPercent = 0;
        int tablesPerWaiter = 4;
        String splashImage = "";      // "" = built-in picture, otherwise /uploads/<name> chosen by the manager
        String managerUser = "manager";
        String passSalt = "";
        String passHash = "";
        boolean defaultPassword = true;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dhabaName", dhabaName);
            m.put("address", address);
            m.put("phone", phone);
            m.put("upiId", upiId);
            m.put("payeeName", payeeName);
            m.put("tableCount", tableCount);
            m.put("gstPercent", gstPercent);
            m.put("tablesPerWaiter", tablesPerWaiter);
            m.put("splashImage", splashImage);
            m.put("managerUser", managerUser);
            m.put("passSalt", passSalt);
            m.put("passHash", passHash);
            m.put("defaultPassword", defaultPassword);
            return m;
        }

        /** The part that is safe to show to anyone (customers) or to the manager's settings page. */
        Map<String, Object> publicView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dhabaName", dhabaName);
            m.put("address", address);
            m.put("phone", phone);
            m.put("tableCount", tableCount);
            m.put("gstPercent", gstPercent);
            return m;
        }

        Map<String, Object> managerView() {
            Map<String, Object> m = publicView();
            m.put("upiId", upiId);
            m.put("payeeName", payeeName);
            m.put("tablesPerWaiter", tablesPerWaiter);
            m.put("splashImage", splashImage);
            m.put("managerUser", managerUser);
            m.put("defaultPassword", defaultPassword);
            return m;
        }

        static Settings fromMap(Map<String, Object> m) {
            Settings s = new Settings();
            s.dhabaName = Json.str(m, "dhabaName", s.dhabaName);
            s.address = Json.str(m, "address", s.address);
            s.phone = Json.str(m, "phone", s.phone);
            s.upiId = Json.str(m, "upiId", "");
            s.payeeName = Json.str(m, "payeeName", s.payeeName);
            s.tableCount = (int) Json.lng(m, "tableCount", 12);
            s.gstPercent = (int) Json.lng(m, "gstPercent", 0);
            s.tablesPerWaiter = (int) Json.lng(m, "tablesPerWaiter", 4);
            s.splashImage = Json.str(m, "splashImage", "");
            s.managerUser = Json.str(m, "managerUser", "manager");
            s.passSalt = Json.str(m, "passSalt", "");
            s.passHash = Json.str(m, "passHash", "");
            s.defaultPassword = Json.bool(m, "defaultPassword", true);
            return s;
        }
    }

    // ------------------------------------------------------------ menu
    static final class Category {
        String id;
        String name;
        String nameTe = "";
        List<String> images = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("nameTe", nameTe);
            m.put("images", images);
            return m;
        }

        static Category fromMap(Map<String, Object> m) {
            Category c = new Category();
            c.id = Json.str(m, "id", "");
            c.name = Json.str(m, "name", "");
            c.nameTe = Json.str(m, "nameTe", "");
            for (Object o : Json.list(m, "images")) c.images.add(String.valueOf(o));
            return c;
        }
    }

    static final class MenuItem {
        String id;
        String categoryId;
        String name;
        String nameTe = "";
        String desc = "";
        String ingredients = "";
        String preparation = ""; // legacy/default preparation
        List<String> preparationOptions = new ArrayList<>();
        boolean preparationEnabled = false; // manager must explicitly enable per item
        int stock = 0;                   // admin must enter actual stock before orders are accepted
        int lowStockThreshold = 5;
        int prepMinutes = 10;
        String image = "";
        long price;
        boolean veg = true;
        boolean available = false;
        boolean special = false;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("categoryId", categoryId);
            m.put("name", name);
            m.put("nameTe", nameTe);
            m.put("desc", desc);
            m.put("ingredients", ingredients);
            m.put("preparation", preparation);
            m.put("preparationOptions", new ArrayList<>(preparationOptions));
            m.put("preparationEnabled", preparationEnabled);
            m.put("stock", stock);
            m.put("lowStockThreshold", lowStockThreshold);
            m.put("prepMinutes", prepMinutes);
            m.put("image", image);
            m.put("price", price);
            m.put("veg", veg);
            m.put("available", available);
            m.put("special", special);
            return m;
        }

        static MenuItem fromMap(Map<String, Object> m) {
            MenuItem i = new MenuItem();
            i.id = Json.str(m, "id", "");
            i.categoryId = Json.str(m, "categoryId", "");
            i.name = Json.str(m, "name", "");
            i.nameTe = Json.str(m, "nameTe", "");
            i.desc = Json.str(m, "desc", "");
            i.ingredients = Json.str(m, "ingredients", "");
            i.preparation = Json.str(m, "preparation", "");
            for (Object p : Json.list(m, "preparationOptions")) { String v = String.valueOf(p).trim(); if (!v.isEmpty() && !i.preparationOptions.contains(v)) i.preparationOptions.add(v); }
            if (i.preparationOptions.isEmpty() && !i.preparation.isBlank()) i.preparationOptions.add(i.preparation);
            i.preparationEnabled = Json.bool(m, "preparationEnabled", false);
            i.stock = (int) Json.lng(m, "stock", 0);
            i.lowStockThreshold = (int) Json.lng(m, "lowStockThreshold", 5);
            i.prepMinutes = (int) Json.lng(m, "prepMinutes", 10);
            if (i.stock <= 0) i.available = false;
            if (i.preparation.isBlank()) {
                String n = i.name.toLowerCase(java.util.Locale.ROOT);
                if (i.categoryId.contains("curr") || n.contains("curry") || n.contains("masala curry") || n.contains("biryani")) i.preparation = "Wet";
                else if (n.contains("manchuria") || n.contains("chilli") || n.contains("schezwan") || n.contains("apollo")) i.preparation = "Dry & Wet";
                else if (n.contains("pepper") || n.contains("salt") || n.contains("roast") || n.contains("fry")) i.preparation = "Dry";
                else i.preparation = "Dry";
            }
            i.image = Json.str(m, "image", "");
            i.price = Json.lng(m, "price", 0);
            i.veg = Json.bool(m, "veg", true);
            i.available = Json.bool(m, "available", true) && i.stock > 0;
            i.special = Json.bool(m, "special", false);
            return i;
        }
    }

    // ------------------------------------------------------------ orders and tables
    static final class Line {
        String itemId = "";
        String name;
        long price;
        int qty;
        boolean custom;
        String preparation = "";
        int prepMinutes = 10;
        String course = "MAIN";
        boolean ready = false;
        boolean served = false;

        long total() { return price * qty; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", itemId);
            m.put("name", name);
            m.put("price", price);
            m.put("qty", qty);
            m.put("custom", custom);
            m.put("preparation", preparation);
            m.put("prepMinutes", prepMinutes);
            m.put("course", course);
            m.put("ready", ready);
            m.put("served", served);
            m.put("total", total());
            return m;
        }

        static Line fromMap(Map<String, Object> m) {
            Line l = new Line();
            l.itemId = Json.str(m, "itemId", "");
            l.name = Json.str(m, "name", "");
            l.price = Json.lng(m, "price", 0);
            l.qty = (int) Json.lng(m, "qty", 1);
            l.custom = Json.bool(m, "custom", false);
            l.preparation = Json.str(m, "preparation", "");
            l.prepMinutes = (int) Json.lng(m, "prepMinutes", 10);
            l.course = Json.str(m, "course", "MAIN");
            l.ready = Json.bool(m, "ready", true);
            l.served = Json.bool(m, "served", false);
            return l;
        }
    }

    static final class Order {
        String id;
        int table;
        String customerName = "";
        String phone = "";
        String note = "";
        String status = PENDING;
        String rejectReason = "";
        String source = "waiter"; // waiter | manager
        String waiterName = "";
        String token = "";
        int seq;                 // order number of the day (1, 2, 3 ...), restarts every day
        String seqDate = "";     // the day (yyyy-MM-dd, India time) that number belongs to
        long createdAt;
        long confirmedAt;
        List<Line> lines = new ArrayList<>();

        long subtotal() {
            long t = 0;
            for (Line l : lines) t += l.total();
            return t;
        }

        int itemCount() {
            int n = 0;
            for (Line l : lines) n += l.qty;
            return n;
        }

        Map<String, Object> toMap(boolean withToken) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("seq", seq);
            m.put("seqDate", seqDate);
            m.put("table", table);
            m.put("customerName", customerName);
            m.put("phone", phone);
            m.put("note", note);
            m.put("status", status);
            m.put("rejectReason", rejectReason);
            m.put("source", source);
            m.put("waiterName", waiterName);
            if (withToken) m.put("token", token);
            m.put("createdAt", createdAt);
            m.put("confirmedAt", confirmedAt);
            List<Object> ls = new ArrayList<>();
            for (Line l : lines) ls.add(l.toMap());
            m.put("lines", ls);
            m.put("subtotal", subtotal());
            return m;
        }

        static Order fromMap(Map<String, Object> m) {
            Order o = new Order();
            o.id = Json.str(m, "id", "");
            o.seq = (int) Json.lng(m, "seq", 0);
            o.seqDate = Json.str(m, "seqDate", "");
            o.table = (int) Json.lng(m, "table", 0);
            o.customerName = Json.str(m, "customerName", "");
            o.phone = Json.str(m, "phone", "");
            o.note = Json.str(m, "note", "");
            o.status = Json.str(m, "status", PENDING);
            o.rejectReason = Json.str(m, "rejectReason", "");
            o.source = Json.str(m, "source", "waiter");
            o.waiterName = Json.str(m, "waiterName", "");
            o.token = Json.str(m, "token", "");
            o.createdAt = Json.lng(m, "createdAt", 0);
            o.confirmedAt = Json.lng(m, "confirmedAt", 0);
            for (Object x : Json.list(m, "lines")) o.lines.add(Line.fromMap(Json.map(x)));
            return o;
        }
    }

    /** One table's open bill: every order since the last payment. */
    static final class TableState {
        int number;
        boolean billRequested;
        long billRequestedAt;
        long discount;
        List<Order> orders = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", number);
            m.put("billRequested", billRequested);
            m.put("billRequestedAt", billRequestedAt);
            m.put("discount", discount);
            List<Object> os = new ArrayList<>();
            for (Order o : orders) os.add(o.toMap(true));
            m.put("orders", os);
            return m;
        }

        static TableState fromMap(Map<String, Object> m) {
            TableState t = new TableState();
            t.number = (int) Json.lng(m, "number", 0);
            t.billRequested = Json.bool(m, "billRequested", false);
            t.billRequestedAt = Json.lng(m, "billRequestedAt", 0);
            t.discount = Json.lng(m, "discount", 0);
            for (Object x : Json.list(m, "orders")) t.orders.add(Order.fromMap(Json.map(x)));
            return t;
        }
    }

    // ------------------------------------------------------------ paid bills
    static final class Bill {
        long no;
        int table;
        long paidAt;
        String method;
        long subtotal;
        long discount;
        int gstPercent;
        long gst;
        long total;
        long received;
        long change;
        String reference = "";
        String receiptPhoto = ""; // compressed payment proof image (data URL)
        String waiterId = "";
        String waiterName = "";
        boolean collected;
        long collectedAt;
        List<Map<String, Object>> customers = new ArrayList<>();
        List<Integer> orderNos = new ArrayList<>();   // day order numbers that made up this bill
        List<Line> lines = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", no);
            m.put("table", table);
            m.put("paidAt", paidAt);
            m.put("method", method);
            m.put("subtotal", subtotal);
            m.put("discount", discount);
            m.put("gstPercent", gstPercent);
            m.put("gst", gst);
            m.put("total", total);
            m.put("received", received);
            m.put("change", change);
            m.put("reference", reference);
            m.put("receiptPhoto", receiptPhoto);
            m.put("waiterId", waiterId);
            m.put("waiterName", waiterName);
            m.put("collected", collected);
            m.put("collectedAt", collectedAt);
            m.put("customers", customers);
            m.put("orderNos", orderNos);
            List<Object> ls = new ArrayList<>();
            for (Line l : lines) ls.add(l.toMap());
            m.put("lines", ls);
            return m;
        }

        static Bill fromMap(Map<String, Object> m) {
            Bill b = new Bill();
            b.no = Json.lng(m, "no", 0);
            b.table = (int) Json.lng(m, "table", 0);
            b.paidAt = Json.lng(m, "paidAt", 0);
            b.method = Json.str(m, "method", "cash");
            b.subtotal = Json.lng(m, "subtotal", 0);
            b.discount = Json.lng(m, "discount", 0);
            b.gstPercent = (int) Json.lng(m, "gstPercent", 0);
            b.gst = Json.lng(m, "gst", 0);
            b.total = Json.lng(m, "total", 0);
            b.received = Json.lng(m, "received", 0);
            b.change = Json.lng(m, "change", 0);
            b.reference = Json.str(m, "reference", "");
            b.receiptPhoto = Json.str(m, "receiptPhoto", "");
            b.waiterId = Json.str(m, "waiterId", "");
            b.waiterName = Json.str(m, "waiterName", "");
            b.collected = Json.bool(m, "collected", false);
            b.collectedAt = Json.lng(m, "collectedAt", 0);
            for (Object x : Json.list(m, "customers")) b.customers.add(Json.map(x));
            for (Object x : Json.list(m, "orderNos")) if (x instanceof Number n) b.orderNos.add(n.intValue());
            for (Object x : Json.list(m, "lines")) b.lines.add(Line.fromMap(Json.map(x)));
            return b;
        }
    }

    // ------------------------------------------------------------ waiters
    static final class Waiter {
        String id;
        String name;
        String phone;
        String aadhaar = "";
        String address = "";
        String photo = "";          // file name inside data/private
        String aadhaarImage = "";   // file name inside data/private
        boolean active = true;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("phone", phone);
            m.put("aadhaar", aadhaar);
            m.put("address", address);
            m.put("photo", photo);
            m.put("aadhaarImage", aadhaarImage);
            m.put("active", active);
            return m;
        }

        static Waiter fromMap(Map<String, Object> m) {
            Waiter w = new Waiter();
            w.id = Json.str(m, "id", "");
            w.name = Json.str(m, "name", "");
            w.phone = Json.str(m, "phone", "");
            w.aadhaar = Json.str(m, "aadhaar", "");
            w.address = Json.str(m, "address", "");
            w.photo = Json.str(m, "photo", "");
            w.aadhaarImage = Json.str(m, "aadhaarImage", "");
            w.active = Json.bool(m, "active", true);
            return w;
        }
    }
}
