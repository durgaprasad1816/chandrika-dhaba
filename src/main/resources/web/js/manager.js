(function () {
  "use strict";

  // ================================================================= helpers
  var $ = function (id) { return document.getElementById(id); };

  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) {
      for (var k in attrs) {
        if (!Object.prototype.hasOwnProperty.call(attrs, k)) continue;
        var v = attrs[k];
        if (v === null || v === undefined || v === false) continue;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k === "value") el.value = v;
        else if (k.indexOf("on") === 0) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    (Array.isArray(kids) ? kids : kids == null ? [] : [kids]).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
    });
    return el;
  }

  function rs(n) { return "₹" + Number(n || 0).toLocaleString("en-IN"); }
  function timeOf(ms) { return new Date(ms).toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit" }); }
  function dateTimeOf(ms) {
    return new Date(ms).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
  }
  function ago(ms, now) {
    var m = Math.max(0, Math.floor((now - ms) / 60000));
    if (m < 1) return "just now";
    if (m < 60) return m + " min";
    return Math.floor(m / 60) + " h " + (m % 60) + " min";
  }
  function todayIst() {
    return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Kolkata" });
  }

  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } }

  var toastTimer;
  function toast(msg, isErr) {
    var t = $("toast");
    t.textContent = msg;
    t.className = "toast show" + (isErr ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = "toast"; }, 3400);
  }

  function api(method, url, body, raw) {
    var opts = { method: method, headers: { "X-Requested-With": "dhaba" }, cache: "no-store", credentials: "same-origin" };
    if (raw) { opts.body = raw; opts.headers["Content-Type"] = raw.type || "application/octet-stream"; }
    else if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    return fetch(url, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (r.status === 401 && url.indexOf("/login") < 0) { showLogin(); }
        if (!r.ok) { var e = new Error(data.error || "Something went wrong"); e.status = r.status; throw e; }
        return data;
      });
    }, function () { var e = new Error("Cannot reach the server"); e.network = true; throw e; });
  }

  // ================================================================= state
  var settings = {};
  var tablesData = null;
    var prevBills = null;
  var prevOngoing = {};
  var prevSale = {};
  var tab = "tables";
  var pollTimer = null;
  var soundOn = lsGet("dhaba_sound") !== "off";
  var openTable = 0;          // table number whose detail popup is open
  var detail = null;
  var menuCache = null;
  var notificationCursor = 0;
  var notificationsPrimed = false;
  var kitchenTimer = null;

  // ================================================================= sound
  var audioCtx;
  var audioReady = false;
  function beep() {
    if (!soundOn) return;
    try {
      audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
      [0, 0.22].forEach(function (d, i) {
        var o = audioCtx.createOscillator(), g = audioCtx.createGain();
        o.type = "sine"; o.frequency.value = i ? 988 : 784;
        g.gain.setValueAtTime(0.0001, audioCtx.currentTime + d);
        g.gain.exponentialRampToValueAtTime(0.35, audioCtx.currentTime + d + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + d + 0.2);
        o.connect(g); g.connect(audioCtx.destination);
        o.start(audioCtx.currentTime + d); o.stop(audioCtx.currentTime + d + 0.22);
      });
    } catch (e) { /* no audio available */ }
  }
  function paintSound() { $("soundBtn").textContent = "Sound: " + (soundOn ? "on" : "off"); }
  $("soundBtn").addEventListener("click", function () {
    if (!audioReady) {
      try { audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)(); audioCtx.resume(); audioReady = true; soundOn = true; }
      catch (e) {}
    } else soundOn = !soundOn;
    lsSet("dhaba_sound", soundOn ? "on" : "off"); paintSound(); if (soundOn) beep();
  });
  paintSound();

  // ================================================================= login / logout
  function showLogin() {
    $("shell").hidden = true;
    $("login").hidden = false;
    closeModal();
    clearInterval(pollTimer);
    pollTimer = null;
  }

  $("loginForm").addEventListener("submit", function (e) {
    e.preventDefault();
    try { audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)(); audioCtx.resume(); audioReady = true; } catch (audioError) {}
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(function () {});
    $("lErr").textContent = "";
    var btn = $("lBtn");
    btn.disabled = true;
    api("POST", "/api/manager/login", { username: $("lUser").value, password: $("lPass").value })
      .then(function () { $("lPass").value = ""; start(); })
      .catch(function (err) { $("lErr").textContent = err.message; })
      .then(function () { btn.disabled = false; });
  });

  $("logoutBtn").addEventListener("click", function () {
    api("POST", "/api/manager/logout", {}).catch(function () { }).then(showLogin);
  });

  function start() {
    api("GET", "/api/manager/me").then(function (me) {
      settings = me.settings;
      $("login").hidden = true;
      $("shell").hidden = false;
      prevBills = null;
      poll();
      pollNotifications();
      clearInterval(pollTimer);
      pollTimer = setInterval(function () { poll(); pollNotifications(); if (tab === "kitchen") loadKitchenQueue(); }, 3000);
      switchTab(tab);
    }).catch(function () { showLogin(); });
  }

  // ================================================================= tabs
  $("tabs").addEventListener("click", function (e) {
    var b = e.target.closest("button[data-tab]");
    if (b) switchTab(b.getAttribute("data-tab"));
  });

  function switchTab(t) {
    tab = t;
    ["tables", "waiters", "payments", "menu", "kitchen", "reports", "settings"].forEach(function (n) { $("tab-" + n).hidden = n !== t; });
    Array.prototype.forEach.call($("tabs").children, function (b) { b.classList.toggle("on", b.getAttribute("data-tab") === t); });
    if (t === "tables") renderTables();
    if (t === "waiters") loadWaiters();
    if (t === "payments") { if (!$("pDate").value) $("pDate").value = todayIst(); loadPayments(); }
    if (t === "menu") loadMenuAdmin();
    if (t === "kitchen") loadKitchenQueue();
    if (t === "reports") { if (!$("rFrom").value) setRange(0); loadReport(); }
    if (t === "settings") loadSettings();
  }

  // ================================================================= tables
  function poll() {
    api("GET", "/api/manager/tables").then(function (d) {
      $("connWarn").hidden = true;
      tablesData = d;
      settings = d.settings || settings;
      detectNew(d);
      var n = d.counts.toCollect;
      $("bPay").hidden = !(n > 0);
      $("bPay").textContent = rs(n);
      if (tab === "tables") renderTables();
      if (openTable && !$("modal").hidden && modalKind === "detail" && !busyEditing()) refreshDetail(true);
    }).catch(function (e) {
      if (e.network) $("connWarn").hidden = false;
    });
  }

  function pollNotifications() {
    api("GET", "/api/manager/notifications?since=" + notificationCursor).then(function (d) {
      if (!notificationsPrimed) { notificationCursor = d.latestId || 0; notificationsPrimed = true; return; }
      (d.notifications || []).forEach(function (n) {
        notificationCursor = Math.max(notificationCursor, n.id || 0);
        beep(); toast(n.message || "New restaurant notification");
        if ("Notification" in window && Notification.permission === "granted") new Notification("Chandrika Dhaba", { body: n.message || "New restaurant notification" });
      });
      notificationCursor = Math.max(notificationCursor, d.latestId || 0);
    }).catch(function () {});
  }

  function loadKitchenQueue() {
    api("GET", "/api/manager/kitchen").then(function (d) {
      var root = $("kitchenBody"); if (!root) return;
      root.textContent = "";
      root.appendChild(h("p", { class: "hint", text: "Queue total includes every unready dish, including main courses waiting for starters. Starters are released first; the estimate assumes one sequential kitchen station and updates as items are marked ready." }));
      root.appendChild(h("div", { class: "count" }, [h("b", { text: (d.estimatedRemainingMinutes || 0) + " min" }), h("span", { text: "Estimated queue workload" })]));
      if (!d.items.length) { root.appendChild(h("p", { class: "empty-note", text: "No pending kitchen items." })); return; }
      function buildKitchenTable(rows) {
      var table = h("table", { class: "rep" });
      table.appendChild(h("thead", null, h("tr", null, ["Order", "Course", "Table", "Dish", "Qty", "Prep time", "Queue estimate", "Action"].map(function (x) { return h("th", { text: x }); }))));
      var body = h("tbody");
      rows.forEach(function (x) {
        body.appendChild(h("tr", null, [
          h("td", { text: x.orderNo > 0 ? "#" + x.orderNo : "-" }),
          h("td", { text: x.course === "STARTER" ? "Starter" : "Main course" }),
          h("td", { class: "kitchen-table-number", text: "Table " + x.table }),
          h("td", { text: x.item + (x.preparation ? " · " + x.preparation : "") }),
          h("td", { text: x.qty }),
          h("td", { text: x.prepMinutes + " min" }),
          h("td", { text: x.estimatedQueueMinutes + " min" }),
          h("td", null, h("button", { class: "btn small gold", type: "button", text: "Mark ready", onclick: function () {
            api("POST", "/api/manager/kitchen/" + x.orderId + "/lines/" + x.lineIndex + "/ready", {})
              .then(function () { beep(); toast(x.item + " marked ready for Table " + x.table); loadKitchenQueue(); })
              .catch(function (e) { toast(e.message, true); });
          } }))
        ]));
      });
      table.appendChild(body); return table;
      }
      var oldRows = d.oldOrders || [], newRows = d.newOrders || d.items;
      root.appendChild(h("h3", { class: "section-title", text: "Old Orders · Priority" }));
      if (oldRows.length) root.appendChild(buildKitchenTable(oldRows));
      else root.appendChild(h("p", { class: "empty-note", text: "No older pending orders." }));
      root.appendChild(h("h3", { class: "section-title", text: "New Orders" }));
      if (newRows.length) root.appendChild(buildKitchenTable(newRows));
      else root.appendChild(h("p", { class: "empty-note", text: "No new pending orders." }));
    }).catch(function (e) { toast(e.message, true); });
  }

  function detectNew(d) {
    var bills = {}, ongoing = {}, sale = {};
    d.tables.forEach(function (t) {
      if (t.status === "BILL") bills[t.number] = true;
      if (t.status !== "FREE") ongoing[t.number] = true;
      sale[t.number] = t.todaySale || 0;
    });
    if (prevBills) {
      Object.keys(bills).forEach(function (n) {
        if (!prevBills[n]) { beep(); toast((d.tables.find(function(x){return String(x.number)===String(n);}) || {}).label || ("Table " + n) + ": waiter asked for payment"); }
      });
      Object.keys(prevOngoing).forEach(function (n) {
        if (!ongoing[n] && sale[n] > (prevSale[n] || 0)) { toast("Table " + n + ": payment received \u2713"); }
      });
    }
    prevBills = bills;
    prevOngoing = ongoing;
    prevSale = sale;
  }

  function renderTables() {
    if (!tablesData) return;
    var d = tablesData;
    var c = d.counts;
    var cbox = $("counts");
    cbox.textContent = "";
    [["Free tables", c.free, ""], ["Busy (ongoing)", c.ongoing, ""], ["Payment requested", c.bill, "bill"],
      ["Today's sale", rs(c.todaySales), ""], ["To collect from waiters", rs(c.toCollect), "pend"]].forEach(function (x) {
      cbox.appendChild(h("div", { class: "count " + x[2] }, [h("b", { text: x[1] }), h("span", { text: x[0] })]));
    });

    var grid = $("tableGrid");
    grid.textContent = "";
    d.tables.forEach(function (t) {
      var label = t.status === "FREE" ? "Free" : t.status === "BILL" ? "Payment requested" : "Busy";
      var kids = [h("div", { class: "no", text: t.label || ("Table " + t.number) }), h("div", { class: "st", text: label }),
        h("div", { class: "who", text: t.waiter ? "Waiter: " + t.waiter : "No waiter assigned" })];
      if (t.status !== "FREE") {
        kids.push(h("div", { class: "who", text: (t.customers && t.customers.length ? t.customers.join(", ") : "Counter order") }));
        kids.push(h("div", { class: "meta", text: t.items + (t.items === 1 ? " item" : " items") + " \u00B7 " + ago(t.since, d.now) }));
        kids.push(h("div", { class: "amt", text: rs(t.total) }));
      } else if (t.todaySale > 0) {
        kids.push(h("div", { class: "meta", text: "Today: " + rs(t.todaySale) }));
      }
      if (t.toCollect > 0) {
        kids.push(h("button", { class: "btn small gold collect", type: "button", text: "Collected " + rs(t.toCollect), onclick: function (ev) {
          ev.stopPropagation(); collectTable(t.number);
        } }));
      } else if (t.todaySale > 0) {
        kids.push(h("div", { class: "okmark", text: "\u2713 Collected" }));
      }
      grid.appendChild(h("div", { class: "tbl " + t.status, role: "button", tabindex: "0",
        onclick: function () { openDetail(t.number); },
        onkeydown: function (e) { if (e.key === "Enter") openDetail(t.number); } }, kids));
    });
  }

  // ================================================================= modal system
  var modalKind = "";
  function openModal(kind, title, bodyKids, footKids) {
    modalKind = kind;
    var box = $("modalBox");
    box.textContent = "";
    box.appendChild(h("div", { class: "m-head" }, [h("h2", { text: title }), h("button", { class: "x", type: "button", "aria-label": "Close", text: "×", onclick: closeModal })]));
    box.appendChild(h("div", { class: "m-body" }, bodyKids));
    if (footKids && footKids.length) box.appendChild(h("div", { class: "m-foot" }, footKids));
    $("modal").hidden = false;
    document.body.style.overflow = "hidden";
  }
  function closeModal() {
    $("modal").hidden = true;
    modalKind = "";
    openTable = 0;
    detail = null;
    document.body.style.overflow = "";
  }
  $("modal").addEventListener("mousedown", function (e) { if (e.target === $("modal") && modalKind !== "receipt") closeModal(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !$("modal").hidden) closeModal(); });

  function busyEditing() {
    var a = document.activeElement;
    return a && $("modalBox").contains(a) && (a.tagName === "INPUT" || a.tagName === "SELECT" || a.tagName === "TEXTAREA");
  }

  // ================================================================= table detail popup
  function openDetail(n) {
    openTable = n;
    detail = null;
    refreshDetail();
  }

  function refreshDetail(quiet) {
    var n = openTable;
    if (!n) return;
    Promise.all([api("GET", "/api/manager/tables/" + n), loadMenuOnce()]).then(function (res) {
      if (openTable !== n) return;
      detail = res[0];
      settings = detail.settings || settings;
      renderDetail();
    }).catch(function (e) { if (!quiet) toast(e.message, true); });
  }

  function loadMenuOnce() {
    if (menuCache) return Promise.resolve(menuCache);
    return api("GET", "/api/manager/menu").then(function (m) { menuCache = m; return m; });
  }

  function renderDetail() {
    var d = detail;
    var n = d.number;
    var body = [];
    body.push(h("p", null, [h("b", { text: "Waiter: " + (d.waiter || "not assigned") }),
      d.billRequested ? h("span", { class: "reqtag", style: "margin-left:8px", text: "Payment requested" }) : null]));

    if (!d.orders.length) body.push(h("div", { class: "empty-note", text: "No orders on this table yet. The waiter takes the order by scanning the table QR." }));

    d.orders.forEach(function (o) {
      var title = o.source === "manager" ? "Added by manager" : o.customerName;
      var head = h("div", { class: "cust-head" }, [
        h("span", null, [h("b", { text: title }), o.phone ? " · " + o.phone : ""]),
        h("span", { text: (o.seq > 0 ? "Order " + o.seq : o.source === "manager" ? "Manager additions" : "Order #" + o.id) + " · " + timeOf(o.createdAt) + (o.status === "SERVED" ? " · served" : " · preparing") })
      ]);
      var lines = o.lines.map(function (l, idx) {
        var qtyCtl;
        qtyCtl = h("span", { class: "qty" }, [
          h("button", { type: "button", "aria-label": "Decrease", text: "−", onclick: function () { setQty(o.id, idx, l.qty - 1, l.name); } }),
          h("b", { text: l.qty }),
          h("button", { type: "button", "aria-label": "Increase", text: "+", onclick: function () { setQty(o.id, idx, l.qty + 1, l.name); } })
        ]);
        return h("div", { class: "ln" }, [
          h("div", { class: "n" }, [l.name, h("small", { text: "  " + rs(l.price) + (l.custom ? " (custom)" : "") })]),
          qtyCtl,
          h("div", { class: "t", text: rs(l.total) })
        ]);
      });
      var card = h("div", { class: "cust" }, [head].concat(lines));
      if (o.note) card.appendChild(h("div", { class: "pend" }, [h("div", { class: "note", text: "Note: " + o.note })]));
      body.push(card);
    });

    body.push(addItemBox(n));

    var disc = h("input", { type: "number", min: "0", step: "1", value: String(d.discount || 0), "aria-label": "Discount in rupees" });
    function applyDiscount() {
      var v = parseInt(disc.value || "0", 10);
      if (isNaN(v) || v < 0) { toast("Enter a valid discount", true); return; }
      if (v === (d.discount || 0)) return;
      api("POST", "/api/manager/tables/" + n + "/discount", { discount: v })
        .then(function (r) { detail = r; renderDetail(); poll(); })
        .catch(function (e) { toast(e.message, true); disc.value = d.discount || 0; });
    }
    disc.addEventListener("change", applyDiscount);
    disc.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); applyDiscount(); } });

    var sum = [h("div", { class: "r" }, [h("span", { text: "Items total" }), h("b", { text: rs(d.subtotal) })])];
    sum.push(h("div", { class: "r" }, [h("span", { text: "Discount (₹)" }), disc]));
    if (d.gstPercent > 0) sum.push(h("div", { class: "r" }, [h("span", { text: "GST " + d.gstPercent + "%" }), h("b", { text: rs(d.gst) })]));
    sum.push(h("div", { class: "r big" }, [h("span", { text: "Total to pay" }), h("span", { text: rs(d.total) })]));
    body.push(h("div", { class: "sumbox" }, sum));
    body.push(h("p", { class: "hint", text: "The waiter takes the payment (cash, card or UPI) on his phone. Then it shows under Payments." }));
    var scrollBox = $("modal");
    var keep = scrollBox.scrollTop;
    openModal("detail", "Table " + n + " · details", body, [
      h("button", { class: "btn gold", type: "button", text: "Close", onclick: closeModal })
    ]);
    scrollBox.scrollTop = keep;
  }

  function setQty(orderId, idx, q, name) {
    if (q < 0) return;
    if (q === 0 && !window.confirm("Remove " + name + " from this table's bill?")) return;
    api("POST", "/api/manager/orders/" + orderId + "/lines/" + idx, { qty: q })
      .then(function () { poll(); return api("GET", "/api/manager/tables/" + openTable); })
      .then(function (d) { detail = d; renderDetail(); })
      .catch(function (e) { toast(e.message, true); refreshDetail(); });
  }

  function addItemBox(n) {
    var sel = h("select", { "aria-label": "Choose item" });
    sel.appendChild(h("option", { value: "", text: "Choose an item..." }));
    (menuCache ? menuCache.categories : []).forEach(function (c) {
      var og = h("optgroup", { label: c.name });
      c.items.forEach(function (i) {
        if (i.available) og.appendChild(h("option", { value: i.id, text: i.name + " – " + rs(i.price) }));
      });
      if (og.children.length) sel.appendChild(og);
    });
    sel.appendChild(h("option", { value: "__custom", text: "Other item (type name and cost)..." }));

    var qty = h("input", { class: "q", type: "number", min: "1", max: "99", value: "1", "aria-label": "Quantity" });
    var cname = h("input", { class: "t", type: "text", maxlength: "80", placeholder: "Item name", "aria-label": "Custom item name" });
    var cprice = h("input", { class: "p", type: "number", min: "0", step: "1", placeholder: "Cost ₹", "aria-label": "Custom item cost" });
    var custom = h("div", { class: "add-row", style: "margin-top:8px", hidden: true }, [cname, cprice]);
    sel.addEventListener("change", function () { custom.hidden = sel.value !== "__custom"; if (!custom.hidden) cname.focus(); });

    var btn = h("button", { class: "btn small gold", type: "button", text: "Add to bill", onclick: function () {
      var body = { qty: parseInt(qty.value, 10) || 1 };
      if (!sel.value) { toast("Choose an item first", true); return; }
      if (sel.value === "__custom") {
        if (!cname.value.trim()) { toast("Type the item name", true); cname.focus(); return; }
        var p = parseInt(cprice.value, 10);
        if (isNaN(p) || p < 0) { toast("Type the cost in rupees", true); cprice.focus(); return; }
        body.name = cname.value.trim(); body.price = p;
      } else {
        body.itemId = sel.value;
      }
      btn.disabled = true;
      api("POST", "/api/manager/tables/" + n + "/items", body)
        .then(function (r) { detail = r; renderDetail(); poll(); toast("Added to Table " + n); })
        .catch(function (e) { toast(e.message, true); btn.disabled = false; });
    } });
    return h("div", { class: "add-box" }, [
      h("h4", { text: "Add item to this table" }),
      h("div", { class: "add-row" }, [sel, h("span", { text: "Qty" }), qty, btn]),
      custom
    ]);
  }

  // ================================================================= receipt
  function showReceipt(b, justPaid) {
    var dh = settings;
    var rows = [];
    rows.push(h("h3", { text: dh.dhabaName || "Chandrika Dhaba & Restaurant" }));
    rows.push(h("div", { class: "c", text: dh.address || "" }));
    if (dh.phone) rows.push(h("div", { class: "c", text: "Ph: " + dh.phone }));
    rows.push(h("hr"));
    rows.push(h("div", { class: "rr" }, [h("span", { text: "Bill #" + b.no }), h("span", { text: "Table " + b.table })]));
    if (b.waiterName) rows.push(h("div", { text: "Waiter: " + b.waiterName }));
    rows.push(h("div", { text: dateTimeOf(b.paidAt) }));
    if (b.customers && b.customers.length) rows.push(h("div", { text: "Guest: " + b.customers.map(function (c) { return c.name; }).join(", ") }));
    rows.push(h("hr"));
    b.lines.forEach(function (l) {
      rows.push(h("div", { class: "rr" }, [h("span", { text: l.qty + " x " + l.name }), h("span", { text: rs(l.total) })]));
    });
    rows.push(h("hr"));
    rows.push(h("div", { class: "rr" }, [h("span", { text: "Items total" }), h("span", { text: rs(b.subtotal) })]));
    if (b.discount > 0) rows.push(h("div", { class: "rr" }, [h("span", { text: "Discount" }), h("span", { text: "-" + rs(b.discount) })]));
    if (b.gst > 0) rows.push(h("div", { class: "rr" }, [h("span", { text: "GST " + b.gstPercent + "%" }), h("span", { text: rs(b.gst) })]));
    rows.push(h("div", { class: "rr b" }, [h("span", { text: "TOTAL" }), h("span", { text: rs(b.total) })]));
    rows.push(h("div", { class: "rr" }, [h("span", { text: "Paid by " + b.method.toUpperCase() }), h("span", { text: b.method === "cash" ? "Recd " + rs(b.received) + " / Chg " + rs(b.change) : "" })]));
    rows.push(h("hr"));
    rows.push(h("div", { class: "c", text: "Thank you! Visit again." }));
    openModal("receipt", justPaid ? "Payment done" : "Bill #" + b.no, [h("div", { class: "receipt" }, rows)], [
      h("button", { class: "btn ghost", type: "button", text: "Print", onclick: function () { window.print(); } }),
      h("button", { class: "btn gold", type: "button", text: "Done", onclick: closeModal })
    ]);
  }

  // ================================================================= menu admin
  function loadMenuAdmin() {
    api("GET", "/api/manager/menu").then(function (m) { menuCache = m; renderMenuAdmin(); })
      .catch(function (e) { toast(e.message, true); });
  }

  function renderMenuAdmin() {
    var root = $("menuAdmin");
    root.textContent = "";
    menuCache.categories.forEach(function (c, ci) {
      var head = h("div", { class: "mcat-head" }, [
        h("b", { text: c.name + (c.nameTe ? "  ·  " + c.nameTe : "") + "  (" + c.items.length + ")" }),
        h("button", { class: "btn small", type: "button", "aria-label": "Move section up", text: "↑", disabled: ci === 0, onclick: function () { moveCat(c.id, -1); } }),
        h("button", { class: "btn small", type: "button", "aria-label": "Move section down", text: "↓", disabled: ci === menuCache.categories.length - 1, onclick: function () { moveCat(c.id, 1); } }),
        h("button", { class: "btn small gold", type: "button", text: "+ Item", onclick: function () { itemDialog(null, c.id); } }),
        h("button", { class: "btn small", type: "button", text: "Rename", onclick: function () { catDialog(c); } }),
        h("button", { class: "btn small", type: "button", text: "Delete", onclick: function () { delCat(c); } })
      ]);
      var list = c.items.map(function (i) {
        var sw = h("input", { type: "checkbox", "aria-label": "Available" });
        sw.checked = i.available;
        sw.addEventListener("change", function () {
          api("POST", "/api/manager/menu/items/" + i.id + "/available", { available: sw.checked })
            .then(function () { i.available = sw.checked && Number(i.stock || 0) > 0; if (sw.checked && !i.available) toast("Add stock before making " + i.name + " available", true); else toast(i.name + (sw.checked ? " is available" : " is sold out")); renderMenuAdmin(); })
            .catch(function (e) { toast(e.message, true); sw.checked = !sw.checked; });
        });
        return h("div", { class: "mi" + (i.available ? "" : " off") }, [
          i.image ? h("img", { src: i.image, alt: "", width: 40, height: 40 }) : null,
          h("span", { class: i.veg ? "veg-mark" : "nv-mark" }),
          h("div", { class: "nm" }, [h("b", { text: i.name }), i.nameTe ? h("small", { text: i.nameTe }) : null]),
          h("div", { class: "pr", text: rs(i.price) }),
          i.special ? h("span", { class: "special-admin-tag", text: "CHEF SPECIAL · customer price hidden" }) : null,
          h("label", { class: "sw" }, [sw, i.available ? "Available" : "Sold out"]),
          h("span", { class: "stock-label", text: "Stock: " + (i.stock == null ? "—" : i.stock) }),
          h("button", { class: "btn small ghost", type: "button", text: "+ Stock", onclick: function () {
            var qty = window.prompt("How many portions of " + i.name + " should be added?", "10");
            if (qty === null) return;
            var amount = parseInt(qty, 10);
            if (!Number.isFinite(amount) || amount < 1) { toast("Enter a positive stock quantity", true); return; }
            api("POST", "/api/manager/menu/items/" + i.id + "/stock", { add: amount })
              .then(function () { toast(i.name + " stock increased by " + amount); loadMenuAdmin(); })
              .catch(function (e) { toast(e.message, true); });
          } }),
          h("button", { class: "btn small ghost", type: "button", text: "Edit", onclick: function () { itemDialog(i, c.id); } }),
          h("button", { class: "btn small ghost", type: "button", text: "Delete", onclick: function () {
            if (!window.confirm("Delete " + i.name + " from the menu?")) return;
            api("DELETE", "/api/manager/menu/items/" + i.id).then(loadMenuAdmin).catch(function (e) { toast(e.message, true); });
          } })
        ]);
      });
      if (!list.length) list.push(h("div", { class: "empty-note", text: "No items in this section yet." }));
      root.appendChild(h("div", { class: "mcat" }, [head].concat(list)));
    });
  }

  function moveCat(id, dir) {
    api("POST", "/api/manager/menu/categories/" + id + "/move", { dir: dir }).then(loadMenuAdmin).catch(function (e) { toast(e.message, true); });
  }
  function delCat(c) {
    if (!window.confirm("Delete the section \"" + c.name + "\"?")) return;
    api("DELETE", "/api/manager/menu/categories/" + c.id).then(loadMenuAdmin).catch(function (e) { toast(e.message, true); });
  }

  function catDialog(c) {
    var name = h("input", { type: "text", maxlength: "50", value: c ? c.name : "", placeholder: "e.g. COOL DRINKS" });
    var te = h("input", { type: "text", maxlength: "50", value: c ? c.nameTe : "", placeholder: "Telugu name (optional)" });
    openModal("cat", c ? "Rename section" : "Add section", [
      h("label", { class: "field" }, [h("span", { text: "Section name" }), name]),
      h("label", { class: "field" }, [h("span", { text: "Telugu name (optional)" }), te])
    ], [
      h("button", { class: "btn ghost", type: "button", text: "Cancel", onclick: closeModal }),
      h("button", { class: "btn gold", type: "button", text: "Save", onclick: function () {
        var p = { name: name.value, nameTe: te.value };
        (c ? api("PUT", "/api/manager/menu/categories/" + c.id, p) : api("POST", "/api/manager/menu/categories", p))
          .then(function () { closeModal(); loadMenuAdmin(); toast("Saved"); })
          .catch(function (e) { toast(e.message, true); });
      } })
    ]);
    name.focus();
  }

  function itemDialog(item, catId) {
    var isNew = !item;
    var it = item || { name: "", nameTe: "", desc: "", ingredients: "", preparation: "", preparationEnabled: false, stock: 0, lowStockThreshold: 5, prepMinutes: 10, price: "", veg: true, available: false, special: false, image: "", categoryId: catId };
    var name = h("input", { type: "text", maxlength: "80", value: it.name, placeholder: "e.g. Mango Lassi" });
    var te = h("input", { type: "text", maxlength: "80", value: it.nameTe || "", placeholder: "Telugu name (optional)" });
    var price = h("input", { type: "number", min: "0", step: "1", value: String(it.price), placeholder: "Price in rupees" });
    var stock = h("input", { type: "number", min: "0", max: "100000", step: "1", value: String(it.stock == null ? 0 : it.stock) });
    var lowStock = h("input", { type: "number", min: "0", max: "100000", step: "1", value: String(it.lowStockThreshold == null ? 5 : it.lowStockThreshold) });
    var prepMinutes = h("input", { type: "number", min: "1", max: "240", step: "1", value: String(it.prepMinutes == null ? 10 : it.prepMinutes) });
    var desc = h("input", { type: "text", maxlength: "200", value: it.desc || "", placeholder: "Short description (optional)" });
    var ingredients = h("textarea", { maxlength: "2000", rows: "4", placeholder: "Ingredients in English and Telugu (editable)" }); ingredients.value = it.ingredients || "";
    var preparation = h("select");
    var assignedPrep = Array.isArray(it.preparationOptions) ? it.preparationOptions.slice() : (it.preparation ? [it.preparation] : []);
    var prepEnabled = h("input", { type: "checkbox" }); prepEnabled.checked = !!it.preparationEnabled;
    var prepControls = h("div", { class: "prep-assignment-controls" });
    var prepChoices = h("div", { class: "prep-item-options" });
    var newPreparation = h("input", { type: "text", maxlength: "30", placeholder: "Add option for this dish (e.g. Roast)" });
    function refreshPrepChoices() {
      prepChoices.textContent = "";
      (menuCache.preparationTypes || []).forEach(function (v) {
        var cb = h("input", { type: "checkbox" }); cb.checked = assignedPrep.some(function (x) { return x.toLowerCase() === v.toLowerCase(); });
        cb.addEventListener("change", function () {
          if (cb.checked) { if (!assignedPrep.includes(v)) assignedPrep.push(v); }
          else assignedPrep = assignedPrep.filter(function (x) { return x.toLowerCase() !== v.toLowerCase(); });
          refreshPreparationSelect();
        });
        prepChoices.appendChild(h("label", { class: "prep-option-row" }, [cb, h("span", { text: v })]));
      });
      refreshPreparationSelect();
    }
    function refreshPreparationSelect() {
      preparation.textContent = "";
      assignedPrep.forEach(function (v) { preparation.appendChild(h("option", { value: v, text: v })); });
      preparation.value = assignedPrep.includes(it.preparation) ? it.preparation : (assignedPrep[0] || "");
    }
    var addPreparation = h("button", { class: "btn small ghost", type: "button", text: "+ Add option", onclick: function () {
      var value = newPreparation.value.trim();
      if (!value) { toast("Enter a preparation type", true); newPreparation.focus(); return; }
      if (assignedPrep.some(function (v) { return v.toLowerCase() === value.toLowerCase(); })) { toast("Already assigned to this dish", true); return; }
      var known = (menuCache.preparationTypes || []).find(function (v) { return v.toLowerCase() === value.toLowerCase(); });
      var addKnown = known ? Promise.resolve(known) : api("POST", "/api/manager/menu/preparation-types", { name: value }).then(function () {
        menuCache.preparationTypes = (menuCache.preparationTypes || []).concat([value]); return value;
      });
      addKnown.then(function (v) { assignedPrep.push(v); newPreparation.value = ""; refreshPrepChoices(); toast("Option assigned to this dish"); })
        .catch(function (e) { toast(e.message, true); });
    }});
    var removePreparation = h("button", { class: "btn small ghost", type: "button", text: "Remove selected option from this dish", onclick: function () {
      var selected = preparation.value;
      if (!selected) { toast("Select an assigned option first", true); return; }
      assignedPrep = assignedPrep.filter(function (v) { return v !== selected; });
      refreshPrepChoices();
      toast("Removed from this dish only");
    }});
    prepControls.appendChild(prepChoices);
    prepControls.appendChild(h("div", { class: "inline-prep-add" }, [newPreparation, addPreparation]));
    prepControls.appendChild(preparation);
    prepControls.appendChild(removePreparation);
    prepControls.hidden = !prepEnabled.checked;
    prepEnabled.addEventListener("change", function () { prepControls.hidden = !prepEnabled.checked; });
    refreshPrepChoices();
    var special = h("input", { type: "checkbox" }); special.checked = !!it.special;
    var cat = h("select");
    menuCache.categories.forEach(function (c) { cat.appendChild(h("option", { value: c.id, text: c.name })); });
    cat.value = it.categoryId;
    var veg = h("select");
    veg.appendChild(h("option", { value: "v", text: "Vegetarian" }));
    veg.appendChild(h("option", { value: "n", text: "Non-vegetarian / egg" }));
    veg.value = it.veg ? "v" : "n";
    var image = it.image || "";
    var prev = h("div", { style: "margin:6px 0" });
    function paintPrev() {
      prev.textContent = "";
      if (image) prev.appendChild(h("img", { src: image, alt: "", style: "width:90px;height:90px;object-fit:cover;border-radius:14px;border:2px solid var(--gold)" }));
    }
    paintPrev();
    var file = h("input", { type: "file", accept: "image/jpeg,image/png,image/webp" });
    file.addEventListener("change", function () {
      var f = file.files[0];
      if (!f) return;
      if (f.size > 3 * 1024 * 1024) { toast("Photo is too big (max 3 MB)", true); file.value = ""; return; }
      api("POST", "/api/manager/upload", undefined, f)
        .then(function (r) { image = r.path; paintPrev(); if (r.warning) toast(r.warning, true); else toast(r.storage === "drive" ? "Photo saved to your Google Drive" : "Photo added"); })
        .catch(function (e) { toast(e.message, true); });
    });
    var rm = h("button", { class: "btn small ghost", type: "button", text: "Remove photo", onclick: function () { image = ""; paintPrev(); } });

    openModal("item", isNew ? "Add item" : "Edit item", [
      h("label", { class: "field" }, [h("span", { text: "Item name *" }), name]),
      h("label", { class: "field" }, [h("span", { text: "Telugu name" }), te]),
      h("label", { class: "field" }, [h("span", { text: "Section *" }), cat]),
      h("label", { class: "field" }, [h("span", { text: "Price (₹) *" }), price]),
      h("div", { class: "inventory-fields" }, [
        h("label", { class: "field" }, [h("span", { text: "Stock quantity (portions)" }), stock]),
        h("label", { class: "field" }, [h("span", { text: "Low-stock alert at" }), lowStock])
      ]),
      h("label", { class: "field" }, [h("span", { text: "Kitchen preparation time (minutes)" }), prepMinutes]),
      h("label", { class: "field" }, [h("span", { text: "Type" }), veg]),
      h("label", { class: "field" }, [h("span", { text: "Description" }), desc]),
      h("div", { class: "field item-preparation-field" }, [
        h("label", { class: "prep-enable-toggle" }, [prepEnabled, h("span", { text: "Enable preparation selection for this item" })]),
        prepControls,
        h("small", { class: "hint", text: "When enabled, this dish shows a preparation dropdown to the waiter. Add or remove preparation types here." })
      ]),
      h("label", { class: "field special-toggle" }, [special, h("span", { text: "★ Mark this item as Chef Special — appears at the top; customers see only the image and name" })]),
      h("label", { class: "field" }, [h("span", { text: "Ingredients / పదార్థాలు (English + Telugu; editable)" }), ingredients]),
      h("div", { class: "field" }, [h("span", { text: "Premium dish photo (upload this item's own photo)" }), file, prev, rm])
    ], [
      h("button", { class: "btn ghost", type: "button", text: "Cancel", onclick: closeModal }),
      h("button", { class: "btn gold", type: "button", text: "Save item", onclick: function (ev) {
        var p = parseInt(price.value, 10);
        if (!name.value.trim()) { toast("Enter the item name", true); name.focus(); return; }
        if (isNaN(p) || p < 0) { toast("Enter the price in rupees", true); price.focus(); return; }
        var body = { name: name.value, nameTe: te.value, categoryId: cat.value, price: p, veg: veg.value === "v", desc: desc.value, ingredients: ingredients.value, preparation: prepEnabled.checked ? preparation.value : "", preparationOptions: prepEnabled.checked ? assignedPrep : [], preparationEnabled: prepEnabled.checked, stock: parseInt(stock.value, 10) || 0, lowStockThreshold: parseInt(lowStock.value, 10) || 0, prepMinutes: parseInt(prepMinutes.value, 10) || 10, special: special.checked, image: image, available: it.available };
        ev.currentTarget.disabled = true;
        var btn = ev.currentTarget;
        (isNew ? api("POST", "/api/manager/menu/items", body) : api("PUT", "/api/manager/menu/items/" + it.id, body))
          .then(function () { closeModal(); loadMenuAdmin(); toast(isNew ? "Item added to the menu" : "Item updated"); })
          .catch(function (e) { toast(e.message, true); btn.disabled = false; });
      } })
    ]);
    name.focus();
  }

  function preparationTypesDialog() {
    loadMenuOnce().then(function () {
      var list = h("div", { class: "prep-type-list" });
      (menuCache.preparationTypes || []).forEach(function (v) { list.appendChild(h("span", { class: "prep-type-chip", text: v })); });
      var name = h("input", { type: "text", maxlength: "30", placeholder: "e.g. Tandoori, Gravy, Roast" });
      openModal("prep-types", "Manage preparation types", [
        h("p", { class: "hint", text: "Only the manager can add options. These options appear in the item editor dropdown and customer preparation filter." }),
        list,
        h("label", { class: "field" }, [h("span", { text: "Add a preparation type" }), name])
      ], [
        h("button", { class: "btn ghost", type: "button", text: "Close", onclick: closeModal }),
        h("button", { class: "btn gold", type: "button", text: "Add option", onclick: function () {
          if (!name.value.trim()) { toast("Enter a preparation type", true); name.focus(); return; }
          api("POST", "/api/manager/menu/preparation-types", { name: name.value.trim() })
            .then(function () { closeModal(); loadMenuAdmin(); toast("Preparation type added"); })
            .catch(function (e) { toast(e.message, true); });
        } })
      ]);
      name.focus();
    });
  }


  $("addItemBtn").addEventListener("click", function () {
    loadMenuOnce().then(function () { itemDialog(null, menuCache.categories.length ? menuCache.categories[0].id : ""); });
  });
  $("addCatBtn").addEventListener("click", function () { catDialog(null); });

  // ================================================================= reports
  function setRange(daysBack) {
    var to = todayIst();
    var from = daysBack ? new Date(Date.now() - daysBack * 86400000).toLocaleDateString("en-CA", { timeZone: "Asia/Kolkata" }) : to;
    $("rFrom").value = from; $("rTo").value = to;
  }
  $("rangeForm").addEventListener("submit", function (e) { e.preventDefault(); loadReport(); });
  $("rToday").addEventListener("click", function () { setRange(0); loadReport(); });
  $("rWeek").addEventListener("click", function () { setRange(6); loadReport(); });

  function loadReport() {
    api("GET", "/api/manager/reports?from=" + encodeURIComponent($("rFrom").value) + "&to=" + encodeURIComponent($("rTo").value))
      .then(renderReport).catch(function (e) { toast(e.message, true); });
  }

  function renderReport(r) {
    var box = $("reportBody");
    box.textContent = "";
    var s = r.summary;
    box.appendChild(h("div", { class: "counts" }, [
      ["Sales", rs(s.revenue)], ["Bills", s.bills], ["Average bill", rs(s.average)], ["Discounts given", rs(s.discount)], ["GST collected", rs(s.gst)]
    ].map(function (x) { return h("div", { class: "count" }, [h("b", { text: x[1] }), h("span", { text: x[0] })]); })));

    var cols = h("div", { class: "cols", style: "margin-top:16px" });
    cols.appendChild(h("div", null, [h("h3", { class: "h2", style: "font-size:19px", text: "By payment type" }), table(["Type", "Bills", "Amount"], r.methods.map(function (m) {
      return [m.method.toUpperCase(), m.bills, rs(m.amount)];
    }), [2])]));
    cols.appendChild(h("div", null, [h("h3", { class: "h2", style: "font-size:19px", text: "Best selling items" }), r.top.length ? table(["Item", "Qty", "Amount"], r.top.map(function (m) {
      return [m.name, m.qty, rs(m.amount)];
    }), [1, 2]) : h("div", { class: "empty-note", text: "No sales in this period." })]));
    box.appendChild(cols);

    if (r.days.length > 1) {
      var max = Math.max.apply(null, r.days.map(function (d) { return d.amount; }).concat([1]));
      box.appendChild(h("h3", { class: "h2", style: "font-size:19px", text: "Sales per day" }));
      box.appendChild(h("div", { class: "bars" }, r.days.map(function (d) {
        return h("div", { class: "bcol", title: d.date + ": " + rs(d.amount) }, [h("i", { style: "height:" + Math.round(d.amount / max * 90) + "%" }), h("span", { text: d.date.slice(8) })]);
      })));
    }

    box.appendChild(h("h3", { class: "h2", style: "font-size:19px", text: "Paid bills" }));
    if (!r.bills.length) { box.appendChild(h("div", { class: "empty-note", text: "No paid bills in this period." })); return; }
    var t = h("table", { class: "rep" });
    t.appendChild(h("thead", null, h("tr", null, ["Bill", "Time", "Table", "Waiter", "Guest", "Paid by", "Total"].map(function (x, i) { return h("th", { class: i === 6 ? "r" : "", text: x }); }))));
    var tb = h("tbody");
    r.bills.forEach(function (b) {
      tb.appendChild(h("tr", { class: "click", onclick: function () {
        api("GET", "/api/manager/bills/" + b.no).then(function (full) { showReceipt(full, false); }).catch(function (e) { toast(e.message, true); });
      } }, [h("td", { text: "#" + b.no }), h("td", { text: dateTimeOf(b.paidAt) }), h("td", { text: b.table }), h("td", { text: b.waiter || "-" }), h("td", { text: (b.customers || []).join(", ") || "-" }),
        h("td", { text: b.method.toUpperCase() }), h("td", { class: "r", text: rs(b.total) })]));
    });
    t.appendChild(tb);
    box.appendChild(t);
    box.appendChild(h("p", { class: "hint", text: "Click a bill to open it and print." }));
  }

  function table(head, rows, rightCols) {
    var t = h("table", { class: "rep" });
    t.appendChild(h("thead", null, h("tr", null, head.map(function (x, i) { return h("th", { class: rightCols.indexOf(i) >= 0 ? "r" : "", text: x }); }))));
    var tb = h("tbody");
    rows.forEach(function (row) { tb.appendChild(h("tr", null, row.map(function (cell, i) { return h("td", { class: rightCols.indexOf(i) >= 0 ? "r" : "", text: cell }); }))); });
    t.appendChild(tb);
    return t;
  }

  // ================================================================= settings
  function loadSettings() {
    api("GET", "/api/manager/settings").then(function (s) {
      settings = s;
      $("sName").value = s.dhabaName; $("sAddr").value = s.address; $("sPhone").value = s.phone;
      $("sTables").value = s.tableCount; $("sGst").value = s.gstPercent; $("sUpi").value = s.upiId || ""; $("sPayee").value = s.payeeName || "";
      $("sPerWaiter").value = s.tablesPerWaiter || 4;
      paintSplash();
    }).catch(function (e) { toast(e.message, true); });
    loadGoogle();
    api("GET", "/api/manager/server-info").then(function (info) {
      var host = location.hostname;
      var local = host === "localhost" || host === "127.0.0.1" || host === "[::1]";
      var base = location.origin;
      if (local && info.addresses.length) base = "http://" + info.addresses[0] + ":" + info.port;
      if (!$("qrBase").value) $("qrBase").value = lsGet("dhaba_qrbase") || base;
      $("qrHint").textContent = local
        ? "Phones must be on the same Wi-Fi as this computer for this address to work. If you put the app online, type your real website address here."
        : "Customers will open this address when they scan the QR.";
    }).catch(function () { });
  }

  $("setForm").addEventListener("submit", function (e) {
    e.preventDefault();
    api("PUT", "/api/manager/settings", {
      dhabaName: $("sName").value, address: $("sAddr").value, phone: $("sPhone").value,
      tableCount: parseInt($("sTables").value, 10), gstPercent: parseInt($("sGst").value, 10) || 0,
      upiId: $("sUpi").value.trim(), payeeName: $("sPayee").value, tablesPerWaiter: parseInt($("sPerWaiter").value, 10) || 4
    }).then(function (s) { settings = s; toast("Settings saved"); poll(); })
      .catch(function (err) { toast(err.message, true); });
  });


  // ================================================================= welcome picture + Google account / Drive (Settings)
  var gState = null;

  function paintSplash() {
    $("splashPrev").src = settings.splashImage || "/img/welcome-dish.jpg";
  }

  $("splashFile").addEventListener("change", function () {
    var f = $("splashFile").files && $("splashFile").files[0];
    if (!f) return;
    if (f.size > 3 * 1024 * 1024) { toast("Picture is larger than 3 MB. Please choose a smaller one.", true); return; }
    api("POST", "/api/manager/upload?for=welcome", undefined, f)
      .then(function (r) {
        if (r.warning) toast(r.warning, true);
        return api("PUT", "/api/manager/settings", { splashImage: r.path });
      })
      .then(function (s) { settings = s; paintSplash(); toast("Welcome picture changed"); })
      .catch(function (e) { toast(e.message, true); })
      .then(function () { $("splashFile").value = ""; });
  });
  $("splashReset").addEventListener("click", function () {
    api("PUT", "/api/manager/settings", { splashImage: "" })
      .then(function (s) { settings = s; paintSplash(); toast("Default welcome picture restored"); })
      .catch(function (e) { toast(e.message, true); });
  });

  function paintGoogle(g) {
    gState = g;
    var box = $("gStatus");
    box.textContent = "";
    var lines = [];
    if (g.connected) lines.push(h("div", { class: "g-ok" }, ["Connected as ", h("b", { text: g.email || "your Google account" })]));
    else if (g.needsReconnect) lines.push(h("div", { class: "g-bad", text: "Google sign-in expired. Please sign in again." }));
    else if (!g.configured) lines.push(h("div", { class: "g-off", text: "Not connected. First save your Google app keys (one-time setup below), then sign in." }));
    else lines.push(h("div", { class: "g-off", text: "Not connected. Press 'Sign in with Google'." }));
    if (g.lastError) lines.push(h("div", { class: "g-bad", text: g.lastError }));
    lines.forEach(function (l) { box.appendChild(l); });

    $("gRedirect").textContent = g.redirectUri || "";
    $("gClientId").value = g.clientId || "";
    $("gClientSecret").value = "";
    $("gSetup").open = !g.configured;
    $("gSignIn").hidden = !!g.connected;
    $("gSignIn").disabled = !g.configured;
    $("gSignOut").hidden = !(g.connected || g.needsReconnect);
    var warn = $("gRedirectWarn");
    warn.hidden = !!g.redirectOk;
    warn.textContent = g.redirectOk ? "" : "Google only allows signing in from http://localhost:8080/manager (on the computer running the app) or from an https:// website address. Open the manager page that way, then sign in.";
    $("gFolderBox").hidden = !g.connected;
    if (g.connected) {
      if (document.activeElement !== $("gFolder")) $("gFolder").value = g.invoiceFolderUrl || "";
      $("gDaily").checked = !!g.dailyFolders;
      $("gToday").textContent = g.todayFolderName || "";
      var open = $("gOpenFolder");
      open.hidden = !g.invoiceFolderUrl;
      if (g.invoiceFolderUrl) open.href = g.invoiceFolderUrl;
      var q = (g.uploadedInvoices || 0) + " invoice(s) saved to Drive · " + (g.pendingInvoices || 0) + " waiting · " + (g.driveImages || 0) + " picture(s) stored in Drive";
      if (g.invoiceFolderName) q = "Invoice folder: " + g.invoiceFolderName + " · " + q;
      $("gQueue").textContent = q;
      if (!$("gSyncDate").value) $("gSyncDate").value = todayIst();
<<<<<<< HEAD
      var bs = g.backupError ? "⚠ " + g.backupError : (g.lastBackup ? "Last backup: " + new Date(g.lastBackup).toLocaleString() : "No backup yet; it runs a few seconds after your next change.");
      $("gBackupStatus").textContent = bs;
=======
>>>>>>> origin/main
    }
  }

  function loadGoogle() {
    api("GET", "/api/manager/google/status").then(paintGoogle).catch(function (e) { toast(e.message, true); });
  }

  function gCall(method, url, body, okMsg) {
    return api(method, url, body).then(function (g) { paintGoogle(g); if (okMsg) toast(typeof okMsg === "function" ? okMsg(g) : okMsg); return g; })
      .catch(function (e) { toast(e.message, true); });
  }

  $("gSaveKeys").addEventListener("click", function () {
    gCall("PUT", "/api/manager/google/credentials", { clientId: $("gClientId").value, clientSecret: $("gClientSecret").value }, "Google keys saved. Now press 'Sign in with Google'.");
  });
  $("gSignIn").addEventListener("click", function () {
    $("gSignIn").disabled = true;
    api("POST", "/api/manager/google/start", {}).then(function (r) { location.href = r.url; })
      .catch(function (e) { toast(e.message, true); $("gSignIn").disabled = false; });
  });
  $("gSignOut").addEventListener("click", function () {
    if (!confirm("Disconnect this Google account? Pictures already in Drive stay there, but they cannot be shown in the app until you sign in again.")) return;
    gCall("POST", "/api/manager/google/disconnect", {}, "Google account disconnected");
  });
  $("gSaveFolder").addEventListener("click", function () {
    gCall("PUT", "/api/manager/google/folder", { folder: $("gFolder").value, daily: $("gDaily").checked }, "Invoice folder saved");
  });
  $("gDaily").addEventListener("change", function () {
    gCall("PUT", "/api/manager/google/folder", { daily: $("gDaily").checked }, $("gDaily").checked ? "A dated folder will be used every day" : "Invoices go straight into the folder");
  });
  $("gTodayFolder").addEventListener("click", function () {
    api("POST", "/api/manager/google/today-folder", {}).then(function (g) {
      paintGoogle(g); toast("Today's folder is ready");
      if (g.todayFolderUrl) window.open(g.todayFolderUrl, "_blank", "noopener");
    }).catch(function (e) { toast(e.message, true); });
  });
  $("gSync").addEventListener("click", function () {
    gCall("POST", "/api/manager/google/sync-invoices", { date: $("gSyncDate").value }, function (g) { return g.queued ? g.queued + " invoice(s) are being uploaded" : "Nothing new to upload for that day"; });
  });
<<<<<<< HEAD
  $("gBackup").addEventListener("click", function () { gCall("POST", "/api/manager/google/backup-now", {}, "Backup finished"); });
  $("gHostKey").addEventListener("click", function () {
    api("POST", "/api/manager/google/host-key", {}).then(function (r) {
      var b = $("gHostKeyBox");
      b.hidden = false;
      b.textContent = "Add this in Render > Environment (keep it secret):\nDHABA_GOOGLE_REFRESH_TOKEN=" + r.refreshToken + "\n(also add DHABA_GOOGLE_CLIENT_ID and DHABA_GOOGLE_CLIENT_SECRET with the keys from Google Cloud)";
    }).catch(function (e) { toast(e.message, true); });
  });
=======
>>>>>>> origin/main
  $("gRetry").addEventListener("click", function () { gCall("POST", "/api/manager/google/retry", {}, "Retrying waiting uploads"); });
  $("gMigrate").addEventListener("click", function () {
    $("gMigrate").disabled = true;
    api("POST", "/api/manager/google/migrate", {}).then(function (g) {
      paintGoogle(g);
      toast(g.moved + " picture(s) moved to Drive" + (g.failed ? ", " + g.failed + " failed: " + g.error : ""), g.failed > 0);
    }).catch(function (e) { toast(e.message, true); }).then(function () { $("gMigrate").disabled = false; });
  });

  // Back from Google's sign-in screen: /manager?google=ok&email=... or ?google=error&msg=...
  (function () {
    var m = /[?&]google=(ok|error)/.exec(location.search);
    if (!m) return;
    var q = {};
    location.search.replace(/^\?/, "").split("&").forEach(function (p) { var i = p.indexOf("="); if (i > 0) q[p.slice(0, i)] = decodeURIComponent(p.slice(i + 1).replace(/\+/g, " ")); });
    history.replaceState(null, "", "/manager");
    tab = "settings";
    setTimeout(function () {
      if (q.google === "ok") toast("Google connected" + (q.email ? ": " + q.email : "") + ". Now choose your invoice folder.");
      else toast(q.msg || "Google sign-in did not finish", true);
    }, 600);
  })();

  $("qrOpen").addEventListener("click", function () {
    var base = $("qrBase").value.trim().replace(/\/+$/, "");
    if (!/^https?:\/\/[^\s]+$/i.test(base)) { toast("Type the website address, e.g. http://192.168.1.10:8080", true); return; }
    lsSet("dhaba_qrbase", base);
    window.open("/qr.html?base=" + encodeURIComponent(base), "_blank");
  });

  // ================================================================= waiters
  var waitersData = null;

  function loadWaiters() {
    api("GET", "/api/manager/waiters").then(function (d) { waitersData = d; renderWaiters(); })
      .catch(function (e) { toast(e.message, true); });
  }

  function maskAadhaar(a) { return a && a.length === 12 ? "XXXX XXXX " + a.slice(8) : a; }
  function fmtAadhaar(a) { return a && a.length === 12 ? a.slice(0, 4) + " " + a.slice(4, 8) + " " + a.slice(8) : a; }

  function renderWaiters() {
    var d = waitersData;
    $("waiterHint").textContent = "Each waiter looks after up to " + d.tablesPerWaiter + " tables (change in Settings). " + d.tableCount + " tables in total.";
    var box = $("waiterList");
    box.textContent = "";
    if (!d.waiters.length) box.appendChild(h("div", { class: "empty-note", text: "No waiters yet. Press + Add waiter and enter name, phone number, Aadhaar and address." }));
    d.waiters.forEach(function (w) {
      var shown = false;
      var aad = h("b", { text: maskAadhaar(w.aadhaar) });
      var eye = h("button", { class: "linkbtn", type: "button", text: "Show", onclick: function (ev) {
        shown = !shown; aad.textContent = shown ? fmtAadhaar(w.aadhaar) : maskAadhaar(w.aadhaar); ev.currentTarget.textContent = shown ? "Hide" : "Show";
      } });
      var photo = w.photo ? h("img", { class: "wph", src: "/private/" + w.photo, alt: w.name, width: 72, height: 72 }) : h("div", { class: "wph none", text: w.name.charAt(0).toUpperCase() });
      var tl = w.tables.length ? w.tables.map(function (n) { return h("span", { class: "chip", text: "Table " + n }); }) : [h("span", { class: "hint", text: "No tables assigned" })];
      var kids = [
        h("div", { class: "whead" }, [photo, h("div", null, [h("b", { class: "wn", text: w.name }), h("div", { text: w.phone }),
          w.active ? null : h("span", { class: "reqtag", text: "Not working" })])]),
        h("div", { class: "wrow" }, [h("span", { text: "Aadhaar " }), aad, eye]),
        h("div", { class: "wrow", text: "Address: " + w.address }),
        h("div", { class: "chips" }, tl)
      ];
      if (w.aadhaarImage) kids.push(h("button", { class: "linkbtn", type: "button", text: "View Aadhaar card picture", onclick: function () { viewImage("/private/" + w.aadhaarImage, "Aadhaar card · " + w.name); } }));
      kids.push(h("div", { class: "wact" }, [
        h("button", { class: "btn small gold", type: "button", text: "Details", onclick: function () { waiterDetails(w); } }),
        h("button", { class: "btn small ghost", type: "button", text: "Edit", onclick: function () { waiterDialog(w); } }),
        h("button", { class: "btn small ghost", type: "button", text: "Remove", onclick: function () {
          if (!window.confirm("Remove " + w.name + "? Their tables become unassigned and their photos are deleted.")) return;
          api("DELETE", "/api/manager/waiters/" + w.id).then(function () { toast("Waiter removed"); loadWaiters(); }).catch(function (e) { toast(e.message, true); });
        } })
      ]));
      box.appendChild(h("div", { class: "wcard" }, kids));
    });

    var grid = $("allocGrid");
    grid.textContent = "";
    var busy = {};
    d.busyTables.forEach(function (n) { busy[n] = true; });
    for (var n = 1; n <= d.tableCount; n++) {
      (function (n) {
        var sel = h("select", { "aria-label": "Waiter for table " + n, disabled: !!busy[n] });
        sel.appendChild(h("option", { value: "", text: "— no waiter —" }));
        d.waiters.forEach(function (w) { if (w.active) sel.appendChild(h("option", { value: w.id, text: w.name })); });
        sel.value = d.allocation[String(n)] || "";
        sel.addEventListener("change", function () {
          api("POST", "/api/manager/tables/" + n + "/waiter", { waiterId: sel.value })
            .then(function (r) { waitersData = r; renderWaiters(); toast("Table " + n + " updated"); poll(); })
            .catch(function (e) { toast(e.message, true); loadWaiters(); });
        });
        grid.appendChild(h("label", { class: "acell" + (busy[n] ? " busy" : "") }, [h("b", { text: "Table " + n }), sel, busy[n] ? h("small", { text: "Busy now" }) : null]));
      })(n);
    }
  }

  function viewImage(src, title) {
    openModal("image", title, [h("img", { src: src, alt: title, style: "max-width:100%;border-radius:12px;display:block;margin:0 auto" })],
      [h("button", { class: "btn gold", type: "button", text: "Close", onclick: closeModal })]);
  }

  $("autoAssignBtn").addEventListener("click", function () {
    if (!window.confirm("Give every working waiter the next free tables in order (up to the limit each)? Tables that are busy keep their waiter.")) return;
    api("POST", "/api/manager/waiters/auto-assign", {}).then(function (r) {
      toast(r.assigned + " tables assigned" + (r.unassigned ? ", " + r.unassigned + " still without a waiter (add more waiters)" : ""));
      loadWaiters(); poll();
    }).catch(function (e) { toast(e.message, true); });
  });
  $("addWaiterBtn").addEventListener("click", function () { waiterDialog(null); });

  function waiterDetails(w) {
    var passport = w.photo
      ? h("img", { class: "staff-doc-image passport", src: "/private/" + w.photo, alt: "Passport-size photo of " + w.name })
      : h("div", { class: "staff-doc-placeholder", text: "Passport-size photo not uploaded" });
    var aadhaar = w.aadhaarImage
      ? h("img", { class: "staff-doc-image aadhaar", src: "/private/" + w.aadhaarImage, alt: "Aadhaar image for " + w.name })
      : h("div", { class: "staff-doc-placeholder", text: "Aadhaar image not uploaded" });
    var kids = [
      h("div", { class: "waiter-detail-grid" }, [
        h("div", { class: "staff-doc-card" }, [h("h4", { text: "Passport-size photo" }), passport]),
        h("div", { class: "staff-info-card" }, [
          h("h3", { text: w.name }),
          h("p", { text: "Phone: " + (w.phone || "—") }),
          h("p", { text: "Aadhaar: " + fmtAadhaar(w.aadhaar || "") }),
          h("p", { text: "Address: " + (w.address || "—") }),
          h("p", { text: "Status: " + (w.active ? "Working" : "Not working") }),
          h("p", { text: "Assigned tables: " + ((w.tables || []).join(", ") || "None") })
        ])
      ]),
      h("div", { class: "staff-doc-card staff-aadhaar-card" }, [h("h4", { text: "Aadhaar card image · private" }), aadhaar]),
      h("p", { class: "hint", text: "Confidential personal information. Images are visible only to an authorised manager. Upload the real staff documents in Edit waiter; no sample identity documents are generated." })
    ];
    openModal("waiter-details", "Waiter details", kids, [h("button", { class: "btn gold", type: "button", text: "Close", onclick: closeModal })]);
  }

  function waiterDialog(w) {
    var isNew = !w;
    var cur = w || { name: "", phone: "", aadhaar: "", address: "", photo: "", aadhaarImage: "", active: true };
    var name = h("input", { type: "text", maxlength: "60", value: cur.name, placeholder: "Waiter name" });
    var phone = h("input", { type: "tel", inputmode: "numeric", maxlength: "14", value: cur.phone, placeholder: "10-digit mobile number" });
    var aad = h("input", { type: "text", inputmode: "numeric", maxlength: "14", value: fmtAadhaar(cur.aadhaar), placeholder: "12-digit Aadhaar number" });
    var addr = h("input", { type: "text", maxlength: "200", value: cur.address, placeholder: "Home address" });
    var photo = cur.photo, aadImg = cur.aadhaarImage;
    var pPrev = h("div"), aPrev = h("div");
    function paint() {
      pPrev.textContent = ""; aPrev.textContent = "";
      if (photo) pPrev.appendChild(h("img", { src: "/private/" + photo, alt: "", style: "width:80px;height:80px;object-fit:cover;border-radius:14px;border:2px solid var(--gold);margin:6px 0" }));
      if (aadImg) aPrev.appendChild(h("img", { src: "/private/" + aadImg, alt: "", style: "width:150px;max-height:100px;object-fit:cover;border-radius:10px;border:2px solid var(--gold);margin:6px 0" }));
    }
    paint();
    function picker(onDone) {
      var camera = h("input", { type: "file", accept: "image/*", capture: "user", class: "staff-photo-input" });
      var choose = h("input", { type: "file", accept: "image/*", class: "staff-photo-input" });
      function upload(file) {
        if (!file) return;
        if (file.size > 4 * 1024 * 1024) { toast("Picture is too big (max 4 MB)", true); return; }
        if (!file.type || file.type.indexOf("image/") !== 0) { toast("Please select an image", true); return; }
        api("POST", "/api/manager/upload-private", undefined, file).then(function (r) { onDone(r.name); paint(); if (r.warning) toast(r.warning, true); else toast(r.storage === "drive" ? "Picture saved to your Google Drive" : "Picture added"); })
          .catch(function (e) { toast(e.message, true); });
      }
      camera.addEventListener("change", function () { upload(camera.files && camera.files[0]); });
      choose.addEventListener("change", function () { upload(choose.files && choose.files[0]); });
      return h("div", { class: "photo-action-row" }, [
        h("label", { class: "btn gold photo-action" }, [h("span", { text: "Take Photo" }), camera]),
        h("label", { class: "btn ghost photo-action" }, [h("span", { text: "Choose Photo" }), choose])
      ]);
    }
    var active = h("input", { type: "checkbox" });
    active.checked = cur.active;
    openModal("waiter", isNew ? "Add waiter" : "Edit waiter", [
      h("label", { class: "field" }, [h("span", { text: "Name *" }), name]),
      h("label", { class: "field" }, [h("span", { text: "Phone number * (the waiter uses this to open his tables)" }), phone]),
      h("label", { class: "field" }, [h("span", { text: "Aadhaar number *" }), aad]),
      h("label", { class: "field" }, [h("span", { text: "Address *" }), addr]),
      h("div", { class: "field" }, [h("span", { text: "Passport-size photo" }), picker(function (n) { photo = n; }), pPrev]),
      h("div", { class: "field" }, [h("span", { text: "Aadhaar card image (private)" }), picker(function (n) { aadImg = n; }), aPrev]),
      isNew ? null : h("label", { class: "check" }, [active, " Working (untick if the waiter has left)"]),
      h("p", { class: "hint", text: "Aadhaar details and pictures are private: only the logged-in manager can see them." })
    ], [
      h("button", { class: "btn ghost", type: "button", text: "Cancel", onclick: closeModal }),
      h("button", { class: "btn gold", type: "button", text: "Save waiter", onclick: function (ev) {
        var btn = ev.currentTarget;
        btn.disabled = true;
        var body = { name: name.value, phone: phone.value, aadhaar: aad.value, address: addr.value, photo: photo, aadhaarImage: aadImg, active: isNew ? true : active.checked };
        (isNew ? api("POST", "/api/manager/waiters", body) : api("PUT", "/api/manager/waiters/" + w.id, body))
          .then(function () { closeModal(); toast(isNew ? "Waiter added" : "Waiter updated"); loadWaiters(); })
          .catch(function (e) { toast(e.message, true); btn.disabled = false; });
      } })
    ]);
    name.focus();
  }

  // ================================================================= payments handed over by waiters
  var payData = null;

  $("payForm").addEventListener("submit", function (e) { e.preventDefault(); loadPayments(); });
  $("pToday").addEventListener("click", function () { $("pDate").value = todayIst(); loadPayments(); });

  function loadPayments() {
    return api("GET", "/api/manager/payments?date=" + encodeURIComponent($("pDate").value || todayIst()))
      .then(function (d) { payData = d; renderPayments(); return d; })
      .catch(function (e) { toast(e.message, true); });
  }

  function statusBadge(st) {
    if (st === "COLLECTED") return h("span", { class: "pstat ok", text: "✓ Collected" });
    if (st === "PENDING") return h("span", { class: "pstat wait", text: "To collect" });
    return h("span", { class: "pstat none", text: "No sale" });
  }

  function renderPayments() {
    var d = payData;
    var box = $("payBody");
    box.textContent = "";
    var s = d.summary;
    box.appendChild(h("div", { class: "counts" }, [
      ["Total sale", rs(s.total), ""], ["Cash", rs(s.cash), ""], ["UPI", rs(s.upi), ""], ["Card", rs(s.card), ""],
      ["Collected", rs(s.collected), ""], ["Still to collect", rs(s.pending), "pend"]
    ].map(function (x) { return h("div", { class: "count " + x[2] }, [h("b", { text: x[1] }), h("span", { text: x[0] })]); })));

    var byNo = {};
    d.tables.forEach(function (t) { byNo[t.number] = t; });
    d.groups.forEach(function (g) {
      var tabs = g.tables.map(function (n) { return byNo[n]; }).filter(Boolean);
      var head = h("div", { class: "ghead" }, [
        h("div", null, [h("b", { text: g.name }), g.phone ? h("small", { text: "  " + g.phone }) : null]),
        h("div", { class: "gsum" }, [h("span", { text: "Sale " + rs(g.total) }), h("b", { text: g.pending > 0 ? "To collect " + rs(g.pending) : "✓ All collected" })]),
        g.pending > 0 ? h("button", { class: "btn small gold", type: "button", text: "Collected all " + rs(g.pending), onclick: function () {
          collectMany(tabs.filter(function (t) { return t.pending > 0; }), g.name);
        } }) : null
      ]);
      var rows = tabs.map(function (t) {
        return h("button", { class: "prow " + t.status, type: "button", onclick: function () { payDetail(t); } }, [
          h("b", { text: t.label || ("Table " + t.number) }),
          h("span", { class: "pw", text: t.waiter || "-" }),
          h("span", { class: "pm", text: t.total > 0 ? "Cash " + rs(t.cash) + " · UPI " + rs(t.upi) + " · Card " + rs(t.card) : "No payments" }),
          h("b", { class: "pa", text: rs(t.total) }),
          statusBadge(t.status)
        ]);
      });
      box.appendChild(h("div", { class: "pgroup" }, [head].concat(rows)));
    });
    if (!d.groups.length) box.appendChild(h("div", { class: "empty-note", text: "No tables yet." }));
  }

  function payDetail(t) {
    var kids = [
      h("p", null, [h("b", { text: "Waiter: " + (t.waiter || "-") }), " ", statusBadge(t.status)]),
      h("div", { class: "sumbox" }, [
        h("div", { class: "r" }, [h("span", { text: "Cash" }), h("b", { text: rs(t.cash) })]),
        h("div", { class: "r" }, [h("span", { text: "UPI" }), h("b", { text: rs(t.upi) })]),
        h("div", { class: "r" }, [h("span", { text: "Card" }), h("b", { text: rs(t.card) })]),
        h("div", { class: "r big" }, [h("span", { text: "Table total" }), h("span", { text: rs(t.total) })]),
        h("div", { class: "r" }, [h("span", { text: "Already collected" }), h("b", { text: rs(t.collected) })]),
        h("div", { class: "r big" }, [h("span", { text: "Waiter must hand over" }), h("span", { text: rs(t.pending) })])
      ])
    ];
    if (t.bills.length) {
      var bt = h("table", { class: "rep" });
      bt.appendChild(h("thead", null, h("tr", null, ["Bill", "Time", "Guest", "Paid by", "Total", ""].map(function (x, i) { return h("th", { class: i === 4 ? "r" : "", text: x }); }))));
      var tb = h("tbody");
      t.bills.forEach(function (b) {
        tb.appendChild(h("tr", null, [h("td", { text: "#" + b.no }), h("td", { text: timeOf(b.paidAt) }), h("td", { text: (b.customers || []).join(", ") || "-" }),
          h("td", { text: b.method.toUpperCase() }), h("td", { class: "r", text: rs(b.total) }), h("td", { text: b.collected ? "✓" : "" })]));
      });
      bt.appendChild(tb);
      kids.push(bt);
    }
    var foot = [h("button", { class: "btn ghost", type: "button", text: "Close", onclick: closeModal })];
    if (t.pending > 0) foot.push(h("button", { class: "btn gold", type: "button", text: "Mark as collected " + rs(t.pending), onclick: function (ev) {
      ev.currentTarget.disabled = true;
      collectMany([t], t.waiter, true);
    } }));
    openModal("paydetail", "Table " + t.number + " · " + $("pDate").value, kids, foot);
  }

  function collectMany(list, who, closeAfter) {
    if (!list.length) return;
    var total = list.reduce(function (a, t) { return a + t.pending; }, 0);
    if (!closeAfter && !window.confirm("Did you receive " + rs(total) + " from " + (who || "the waiter") + " for " + list.length + " table(s)?")) return;
    api("POST", "/api/manager/payments/collect", { date: $("pDate").value || todayIst(),
      items: list.map(function (t) { return { table: t.number, amount: t.pending }; }) })
      .then(function (d) { payData = d; if (closeAfter) closeModal(); toast(rs(total) + " marked as collected ✓"); renderPayments(); poll(); })
      .catch(function (e) { toast(e.message, true); loadPayments(); if (closeAfter) closeModal(); });
  }

  /** The "Collected" button on a table card: opens that table's cash / UPI / card amounts for today. */
  function collectTable(n) {
    api("GET", "/api/manager/payments?date=" + encodeURIComponent(todayIst())).then(function (d) {
      payData = d;
      var t = d.tables.filter(function (x) { return x.number === n; })[0];
      if (!t) return;
      $("pDate").value = todayIst();
      payDetail(t);
    }).catch(function (e) { toast(e.message, true); });
  }

  // ================================================================= go
  start();
})();
