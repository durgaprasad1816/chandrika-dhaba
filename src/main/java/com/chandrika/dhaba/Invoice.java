package com.chandrika.dhaba;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the invoice file saved to Google Drive: a one-page PDF written by hand (no PDF library needed).
 * It uses the built-in Courier font, so it looks like a till receipt and every column lines up.
 * The PDF standard fonts have no rupee sign, so amounts are written as "Rs." and letters outside the basic
 * Latin alphabet (for example Telugu names) are shown as "?" in this file; the bill inside the app is not affected.
 */
final class Invoice {
    private Invoice() {}

    private static final double PAGE_W = 283;     // about 100 mm, like a receipt
    private static final double MARGIN = 20;
    private static final double CHAR = 0.6;       // Courier: every character is 0.6 of the font size wide

    private record Row(String text, boolean bold, double size) {}

    static String money(long n) {
        boolean neg = n < 0;
        String s = String.valueOf(Math.abs(n));
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) sb.append(',');
                sb.append(head.charAt(i));
            }
            s = sb + "," + tail;
        }
        return (neg ? "-" : "") + "Rs." + s;
    }

    private static String ascii(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(c >= 32 && c < 127 ? c : (c == '\n' || c == '\t' ? ' ' : '?'));
        return sb.toString();
    }

    private static int cols(double size) { return (int) Math.floor((PAGE_W - 2 * MARGIN) / (CHAR * size)); }

    private static void center(List<Row> rows, String text, boolean bold, double size) {
        int w = cols(size);
        for (String part : wrap(ascii(text), w)) {
            int pad = Math.max(0, (w - part.length()) / 2);
            rows.add(new Row(" ".repeat(pad) + part, bold, size));
        }
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        String rest = s.strip();
        if (rest.isEmpty()) { out.add(""); return out; }
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut < width / 3) cut = width;
            out.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).strip();
        }
        out.add(rest);
        return out;
    }

    private static String twoCol(String left, String right, int width) {
        left = ascii(left); right = ascii(right);
        int gap = width - left.length() - right.length();
        if (gap < 1) { left = left.substring(0, Math.max(0, width - right.length() - 1)); gap = 1; }
        return left + " ".repeat(gap) + right;
    }

    @SuppressWarnings("unchecked")
    static byte[] pdf(Map<String, Object> bill, Map<String, Object> dhaba, ZoneId zone) {
        final double S = 9;
        final int W = cols(S);
        List<Row> rows = new ArrayList<>();
        String rule = "-".repeat(W);

        center(rows, Json.str(dhaba, "dhabaName", "Chandrika Dhaba & Restaurant"), true, 12);
        center(rows, Json.str(dhaba, "address", ""), false, S);
        String phone = Json.str(dhaba, "phone", "");
        if (!phone.isEmpty()) center(rows, "Ph: " + phone, false, S);
        rows.add(new Row(rule, false, S));

        long no = Json.lng(bill, "no", 0);
        rows.add(new Row(twoCol("INVOICE  Bill #" + no, "Table " + Json.lng(bill, "table", 0), W), true, S));
        long paidAt = Json.lng(bill, "paidAt", System.currentTimeMillis());
        String when = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(paidAt).atZone(zone));
        rows.add(new Row("Date: " + when, false, S));
        List<Object> orderNos = Json.list(bill, "orderNos");
        if (!orderNos.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object o : orderNos) { if (sb.length() > 0) sb.append(", "); sb.append(o instanceof Number n ? n.intValue() : o); }
            for (String l : wrap("Order No: " + sb, W)) rows.add(new Row(l, false, S));
        }
        String waiter = Json.str(bill, "waiterName", "");
        if (!waiter.isEmpty()) rows.add(new Row("Waiter: " + ascii(waiter), false, S));
        StringBuilder guests = new StringBuilder();
        for (Object o : Json.list(bill, "customers")) {
            String n = Json.str(Json.map(o), "name", "");
            if (!n.isEmpty()) { if (guests.length() > 0) guests.append(", "); guests.append(n); }
        }
        if (guests.length() > 0) for (String l : wrap("Guest: " + ascii(guests.toString()), W)) rows.add(new Row(l, false, S));
        rows.add(new Row(rule, false, S));

        rows.add(new Row(twoCol("QTY ITEM", "AMOUNT", W), true, S));
        int nameW = W - 4 - 11;
        for (Object o : Json.list(bill, "lines")) {
            Map<String, Object> l = Json.map(o);
            List<String> parts = wrap(ascii(Json.str(l, "name", "")), nameW);
            String qty = String.format("%3d ", Json.lng(l, "qty", 1));
            String amt = money(Json.lng(l, "total", 0));
            for (int i = 0; i < parts.size(); i++) {
                String left = (i == 0 ? qty : "    ") + parts.get(i);
                rows.add(new Row(i == 0 ? twoCol(left, amt, W) : left, false, S));
            }
        }
        rows.add(new Row(rule, false, S));
        rows.add(new Row(twoCol("Items total", money(Json.lng(bill, "subtotal", 0)), W), false, S));
        if (Json.lng(bill, "discount", 0) > 0) rows.add(new Row(twoCol("Discount", "-" + money(Json.lng(bill, "discount", 0)), W), false, S));
        if (Json.lng(bill, "gst", 0) > 0) rows.add(new Row(twoCol("GST " + Json.lng(bill, "gstPercent", 0) + "%", money(Json.lng(bill, "gst", 0)), W), false, S));
        rows.add(new Row(twoCol("TOTAL", money(Json.lng(bill, "total", 0)), cols(11)), true, 11));
        String method = Json.str(bill, "method", "cash").toUpperCase(Locale.ROOT);
        rows.add(new Row("Paid by " + method, false, S));
        if ("CASH".equals(method)) rows.add(new Row(twoCol("Received " + money(Json.lng(bill, "received", 0)), "Change " + money(Json.lng(bill, "change", 0)), W), false, S));
        String ref = Json.str(bill, "reference", "");
        if (!ref.isEmpty()) rows.add(new Row("Ref: " + ascii(ref), false, S));
        rows.add(new Row(rule, false, S));
        center(rows, "Thank you! Visit again.", false, S);

        // ---- page content: lines from the top
        StringBuilder cs = new StringBuilder();
        double top = 28;
        double total = top;
        for (Row r : rows) total += r.size * 1.35;
        double pageH = Math.max(220, total + 24);
        double y = pageH - top;
        for (Row r : rows) {
            cs.append("BT /").append(r.bold ? "F2" : "F1").append(' ').append(fmt(r.size)).append(" Tf ")
              .append(fmt(MARGIN)).append(' ').append(fmt(y)).append(" Td (").append(esc(r.text)).append(") Tj ET\n");
            y -= r.size * 1.35;
        }
        byte[] content = cs.toString().getBytes(StandardCharsets.ISO_8859_1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        obj(out, offsets, 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(PAGE_W) + " " + fmt(pageH) + "] /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>");
        offsets.add(out.size());
        write(out, "4 0 obj\n<< /Length " + content.length + " >>\nstream\n");
        out.writeBytes(content);
        write(out, "\nendstream\nendobj\n");
        obj(out, offsets, 5, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, 6, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>");
        int xref = out.size();
        write(out, "xref\n0 7\n0000000000 65535 f \n");
        for (int off : offsets) write(out, String.format("%010d 00000 n \n", off));
        write(out, "trailer\n<< /Size 7 /Root 1 0 R /Info << /Title (Invoice " + no + ") /Producer (Chandrika Dhaba) >> >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"); }

    private static void write(ByteArrayOutputStream o, String s) { o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }

    private static void obj(ByteArrayOutputStream o, List<Integer> offsets, int n, String body) {
        offsets.add(o.size());
        write(o, n + " 0 obj\n" + body + "\nendobj\n");
    }
}
