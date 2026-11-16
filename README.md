# Chandrika Dhaba & Restaurant - QR menu, waiter orders, billing and cash collection

Every table has a QR code. Customers scan it to see the menu. The waiter allocated to that table takes the order, confirms it
with one customer's name and phone number, serves it and takes payment (cash, card or UPI QR). The manager monitors the tables,
manages waiters, and collects each table's money from the waiter.

Built with plain **Java 17+** (the JDK's built-in web server), **Maven**, and plain **HTML / CSS / JavaScript**.
No Spring, no servlets, no libraries to download.

---------------------------------------------------------------------

## 1. Run it

You need Java 17 or newer (check with `java -version`) and Maven (check with `mvn -version`).

    mvn clean package
    java -jar target/chandrika-dhaba.jar

(or double-click `run.bat` on Windows / run `./run.sh` on Linux or Mac, which do both steps.)

The first time Maven runs it needs the internet to download its own build plugins. After that it works offline.
The finished app itself needs no internet.

**No Maven yet?** A ready-made copy is included: `java -jar dist/chandrika-dhaba.jar`
(`run.bat` / `run.sh` use it automatically when Maven is not installed).

When it starts you will see:

    Manager dashboard : http://localhost:8080/manager
    Customer menu     : http://192.168.1.10:8080/?table=1

Options: `--port 9090` to change the port, `--data D:\dhaba-data` to change where data is kept, `--password ...` to set the manager password.

## 2. Manager login (the only login)

* Address: `http://localhost:8080/manager`
* Username: `manager`
* Password: `chandrika123` the first time.

There is no change-password screen. To set your own password, start the app with it:

    java -jar dist/chandrika-dhaba.jar --password MyNewPassword

(or set the `DHABA_PASSWORD` environment variable). It is remembered, so you only need to give it once.

Waiters have **no password**. A waiter opens his tables with the mobile number the manager registered (see below).

## 3. Set up the restaurant (10 minutes)

1. **Settings tab**: check the name, address and phone, set the **number of tables**, **tables for each waiter** (4 by default),
   GST % (0 if none) and your **UPI ID** (for example `chandrika@okaxis`) so the UPI QR works when a waiter takes payment.
2. **Settings -> Table QR codes**: press **Open printable QR sheet**, print it and stick one QR on each table.
3. **Waiters tab -> + Add waiter**: name, mobile number, Aadhaar number, address, photo and Aadhaar card picture.
   Then press **Auto-assign tables** (4 tables per waiter in order) or choose the waiter for each table in **Table allocation**.
4. **Menu tab**: all dishes are already loaded. Add cool drinks, desserts and anything else with **+ Add item**.

## Manager menu additions (October 30 update)

- **Preparation types**: Manager → Menu → **Manage preparation types** lets the manager add options such as Dry, Wet, Spicy, Light Dry, Light Wet, Dry & Wet, Tandoori or any restaurant-specific preparation. These choices appear in the Add/Edit item dropdown and the customer preparation filter. The add-options API is manager-session protected.
- **Per-item preparation dropdown**: In Manager → Menu → Add/Edit item, choose the preparation type for that individual dish. The dropdown is populated from manager-managed preparation types; adding a new type makes it available for future item assignments.
- **Chef Special**: Manager → Menu → Add/Edit item → tick **Mark as Chef Special**. The item appears in the Chef's Special section near the top. For customers, Chef Special dishes show only their photo and name (including Telugu name where supplied): no price, description, preparation details, ingredients or detail popup. The public API also omits those fields. Managers and logged-in waiters retain the actual price and full details for ordering and billing.
- **Larger food photos**: Increased regular menu-card photos, Chef Special photos and the main Mixed Biryani hero photo for clearer food presentation.
- **Visual style**: Original layout and workflow retained; black and deep-red palette with very thin gold outlines around cards, panels, dialogs and menu boxes.

## 4. How it works day to day

Customer: scans the table QR, sees the welcome animation and the menu, and tells the waiter what they want.
The page shows "Your waiter for this table is ...". Customers do not place orders themselves.

Waiter (his own phone):
1. Scans the table QR, taps **Waiter login**, types his registered mobile number. Only the waiter given that table can open it.
2. Taps **ADD** on the dishes, then **Review order**, asks one customer for **name and mobile number** (both required)
   and taps **Confirm order**.
3. When the food is brought, taps **Mark served** in the table panel.
4. Taps **Request payment**, chooses **Cash** (shows change), **Card**, or **UPI QR** (QR with the exact amount), then
   **Payment received**. A big animated tick appears and the table becomes free.

Manager:
* **Tables tab**: every table is **Free**, **Busy** or **Payment requested**, with the waiter's name, today's sale and what is still
  to collect. Click a table to see every item eaten, add or fix an item, or give a discount.
  The **Collected** button on a table marks the money as received from the waiter.
* **Waiters tab**: waiter list with photo, phone, Aadhaar (hidden until you press Show), address, Aadhaar picture and tables.
* **Payments tab**: every table with its sale for the day grouped by waiter. Open a table to see **cash, UPI and card** amounts,
  and when the waiter hands over the money press **Mark as collected** (or **Collected all** for a waiter).
  The amount you confirm must match what the app expects, so a stale screen cannot mark the wrong money.
* **Reports tab**: sales, bills, payment types, best sellers, bills with the waiter's name.

## 5. Where your data is

Everything is saved in the `data` folder next to where you start the app:

* `store.json`        - menu, tables, waiters, table allocation, open bills, settings
* `bills.jsonl`       - every paid bill (never edited, one line per bill)
* `collections.jsonl` - every "collected from waiter" action (never edited)
* `private/`          - waiter photos and Aadhaar pictures (only the logged-in manager can open them)
* `uploads/`          - menu photos
* `audit.log`         - who changed what
* `backups/`          - a copy of the data every day

Back this folder up and keep it private: it contains Aadhaar details.

## 6. Notes and limits

* **Wi-Fi**: phones must reach the computer running the app (same Wi-Fi), or host the app on an internet server and put its
  web address in the QR box.
* **UPI / card tick**: a plain web app cannot see your bank. The waiter confirms the payment on his phone (UPI app or card machine)
  and presses **Payment received**; that shows the tick and records it. Truly automatic detection needs a payment gateway
  (Razorpay, PhonePe, Paytm...) with a merchant account, which can be added later.
* **Waiter identification**: a registered mobile number is the only check. If you need more safety, add a PIN per waiter later.
* **Aadhaar**: storing Aadhaar numbers and pictures is sensitive. Keep the data folder private and protect the computer.
  Use HTTPS if the app is on the internet.
* **Security**: the manager area needs the password, sessions expire after 12 hours, 5 wrong passwords lock login for 10 minutes.
* **Upgrading from version 1**: your old `data` folder works. Old waiting orders are treated as confirmed.

## 7. Project layout

    pom.xml
    src/main/java/com/chandrika/dhaba/
        Main.java      web server, file serving
        Api.java       all the web addresses
        Store.java     tables, waiters, orders, bills, collections, reports (all the rules)
        Models.java    data classes
        Auth.java      password hashing, sessions, rate limits
        Json.java      small JSON reader / writer
        Http.java      request helpers and router
    src/main/resources/seed/menu.txt   the starting menu
    src/main/resources/web/            all HTML, CSS, JS, fonts, images

## Update: dish details and waiter profile details
- Customer and waiter QR views share the same menu and now have a `Details / వివరాలు` action for each dish.
- Dish details show English/Telugu names, price, preparation type, description, and editable ingredient text. If no dish photo exists yet, the manager can upload that dish's own JPG/PNG/WebP photo from Manager → Menu → Edit item.
- Manager → Menu → Add/Edit item now includes `Preparation type` (Dry, Light Dry, Wet, Dry & Wet, Not applicable) and an Ingredients field. The menu has a preparation filter; existing items with no saved preparation type receive a best-effort default based on category/name and can be corrected by the manager.
- Manager → Waiters has a `Details` popup with profile photo, phone, address, status, assigned tables, Aadhaar number, and Aadhaar image. The Aadhaar/profile images remain behind the manager session check. Use only for authorised staff records and restrict manager access.
- The existing waiter login remains required before the order-add controls appear; customers can browse the same menu and see dish details without placing orders themselves.

**Important:** Existing `data/store.json` menu data is preserved on startup so current restaurant data is not overwritten. Please review the current menu against the supplied October 18 PDF in Manager → Menu and edit/add items and prices there. Ingredient lists and item photos are intentionally manager-editable; no unverified ingredient claims are prefilled. Back up the `data` folder before replacing any existing installation.


## UI corrections (2026-10-30)
- Customer-facing Mixed Biryani hero no longer displays a price, description, or details action. Waiters retain operational price/details.
- The manager-wide preparation-type dialog/button has been removed from the Menu toolbar.
- Preparation assignment is handled inside each individual Add/Edit Item dialog. Managers can choose an existing type, add a new type, or clear the assignment for that item.
- Every item retains its own Chef Special toggle. Customer-facing Chef Special cards show only the image and dish names; operational details remain available to authenticated waiters/managers.
- Cart/review total text is forced to white for contrast on the dark theme.


## Waiter preparation selection update
- Waiter menu cards now show a preparation dropdown for each dish. The default is the preparation assigned by the manager; the waiter can ask the customer and select the requested preparation before adding the dish.
- The review-order sheet shows the selected preparation per dish and allows the waiter to adjust it before confirming.
- Confirmed order lines persist the selected preparation and display it as a kitchen instruction in the waiter's table/order panel.
- Backend validates the selected preparation against manager-configured preparation types and preserves separate order lines if the same dish is requested with different preparations.
- Improved dark-theme selection highlight and input/select contrast so selected text is white.


## Preparation assignment and Chef Special visibility update
- Each menu item has a manager-only checkbox: "Enable preparation selection for this item".
- Waiter menu cards and review-order dropdowns appear only for items where the manager enabled preparation selection.
- When disabled, the dish displays no waiter preparation dropdown and no kitchen preparation instruction.
- Manager can add preparation types or remove the selected preparation type from the shared list; removing a type clears that assignment from affected items.
- Chef Special dishes no longer show a price or Details button on the menu cards, including waiter mode. Their menu card also suppresses the description/preparation badge. Chef Special cards in the featured section show image and names only (waiter retains ADD).
- Customer-facing Mixed Biryani hero hides price/details whenever Mixed Biryani is marked Chef Special.


## Client-requested feature update
- **Special dishes:** the featured Chef Special area and hero never display prices or a Details action. A TRY NOW button scrolls to the regular menu list, where normal menu prices are displayed. Chef Special menu cards retain image and dish names without the Details button.
- **Admin inventory:** each menu item has stock quantity and low-stock threshold fields. Admin can add stock using the + Stock action, set stock directly in Edit Item, and turn availability on/off. Unconfigured/legacy and new menu items start at 0 stock and unavailable until the admin enters actual quantities; low-stock alert defaults to 5 and preparation time to 10 minutes.
- **Live stock notifications:** waiter orders reserve/decrement stock atomically. Low-stock, out-of-stock, restock, availability changes and new orders create shared staff notifications. Zero stock automatically disables the item. Waiter pages refresh availability when stock notifications arrive.
- **Sound alerts:** manager and waiter sessions use a two-tone browser audio alert; manager login and waiter login unlock browser audio. Desktop notifications are also requested where supported. Sound controls remain available in the UI.
- **Kitchen timing:** admin assigns preparation minutes per menu item. Waiter review displays an estimated wait with starters first, then main course. Manager has a Kitchen Queue tab. A main-course line is held until the starters for that order are marked ready. Marking an item ready notifies staff; waiters can mark an order served only after all its lines are ready.
- **Kitchen workflow:** the manager marks individual kitchen lines ready from the Kitchen Queue. Queue times are indicative estimates, not guaranteed service times; actual timing depends on kitchen capacity and workload.
