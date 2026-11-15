(function () {
  "use strict";

  // ---------------------------------------------------------------- tiny helpers
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

  function store(key, val) {
    try {
      if (val === undefined) { var s = localStorage.getItem(key); return s ? JSON.parse(s) : null; }
      if (val === null) localStorage.removeItem(key); else localStorage.setItem(key, JSON.stringify(val));
    } catch (e) { /* private mode: just continue without saving */ }
    return null;
  }

  function api(method, url, body) {
    var opts = { method: method, headers: {}, cache: "no-store" };
    if (waiter && waiter.token) opts.headers["X-Waiter-Token"] = waiter.token;
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    return fetch(url, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) { var e = new Error(data.error || "Something went wrong"); e.status = r.status; throw e; }
        return data;
      });
    }, function () { throw new Error("No internet connection. Please try again."); });
  }

  var toastTimer;
  function toast(msg, isErr) {
    var t = $("toast");
    t.textContent = msg;
    t.className = "toast show" + (isErr ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = "toast"; }, 3200);
  }

  function rs(n) { return "₹" + Number(n).toLocaleString("en-IN"); }

  // ---------------------------------------------------------------- state
  var menu = null;           // { dhaba, categories:[{..., items:[]}] }
  var kitchenEstimate = { queueMinutes: 0, pendingItems: 0, updatedAt: 0 };
  var kitchenEstimateTimer = null;
  var itemsById = {};
  var cart = {};             // itemId -> qty
  var cartPreparation = {};  // itemId -> preparation selected by waiter for kitchen
  var table = 0;             // detected from QR (0 = unknown)
  var filterVeg = "all";
  var query = "";
  var prepFilter = "all";
  var rowActions = {};       // itemId -> container element for add/stepper
  var waiter = store("dhaba_waiter");          // {token, id, name, tables} once a waiter has opened a table
  var tableCarts = store("dhaba_table_carts") || {};
  function cartKey(t) { return (waiter ? waiter.id : "guest") + ":" + String(t); }
  function saveCurrentCart() { if (table > 0 && waiter) { tableCarts[cartKey(table)] = { cart: cart, preparation: cartPreparation }; store("dhaba_table_carts", tableCarts); } }
  function restoreTableCart(t) {
    var saved = tableCarts[cartKey(t)] || {};
    cart = saved.cart || {}; cartPreparation = saved.preparation || {};
  }

  var tableData = null;                         // the waiter's view of the table (orders, totals)
  var nextOrder = { no: 0, date: "" };          // the number the NEXT confirmed order will get (same for every waiter)
  var trackOpen = false;
  var waiterSoundOn = store("dhaba_waiter_sound") !== false;
  var waiterNotificationCursor = 0;
  var waiterNotificationsPrimed = false;
  var waiterNotificationTimer = null;
  var waiterAudio = null;
  var waiterSoundReady = false;

  // ---------------------------------------------------------------- order numbers (1, 2, 3 ... restart every day)
  var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  function prettyDate(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
    return m ? parseInt(m[3], 10) + " " + MONTHS[parseInt(m[2], 10) - 1] + " " + m[1] : "";
  }
  function orderLabel(o) {
    if (o.seq > 0) return "Order " + o.seq + (o.seqDate ? " · " + prettyDate(o.seqDate) : "");
    if (o.source === "manager") return "Added by manager";
    return "Order #" + o.id;
  }
  function paintNextOrder() {
    var has = waiter && nextOrder.no > 0;
    var chip = $("orderChip");
    if (chip) { chip.hidden = !has; if (has) chip.textContent = "Next order: " + nextOrder.no; }
    var badge = $("orderNoBadge");
    if (badge) {
      badge.hidden = !has;
      if (has) { badge.querySelector("b").textContent = nextOrder.no; badge.querySelector("small").textContent = prettyDate(nextOrder.date); }
    }
  }
  // Called with any server reply that carries the next number, so every waiter's panel stays in step.
  function applyNextOrder(d) {
    if (!d || d.nextOrderNo === undefined) return;
    var changed = nextOrder.no !== d.nextOrderNo || nextOrder.date !== d.orderDate;
    nextOrder = { no: d.nextOrderNo, date: d.orderDate || "" };
    if (changed) paintNextOrder();
  }

  function waiterBeep() {
    if (!waiterSoundOn) return;
    try {
      waiterAudio = waiterAudio || new (window.AudioContext || window.webkitAudioContext)();
      if (waiterAudio.state === "suspended") waiterAudio.resume();
      [0, 0.18].forEach(function (delay, idx) {
        var osc = waiterAudio.createOscillator(), gain = waiterAudio.createGain();
        osc.type = "sine"; osc.frequency.value = idx ? 1046 : 880;
        gain.gain.setValueAtTime(0.0001, waiterAudio.currentTime + delay);
        gain.gain.exponentialRampToValueAtTime(0.28, waiterAudio.currentTime + delay + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, waiterAudio.currentTime + delay + 0.22);
        osc.connect(gain); gain.connect(waiterAudio.destination);
        osc.start(waiterAudio.currentTime + delay); osc.stop(waiterAudio.currentTime + delay + 0.24);
      });
    } catch (e) {}
  }
  function pollWaiterNotifications() {
    if (!waiter) return;
    api("GET", "/api/waiter/notifications?since=" + waiterNotificationCursor).then(function (d) {
      applyNextOrder(d);
      if (!waiterNotificationsPrimed) { waiterNotificationCursor = d.latestId || 0; waiterNotificationsPrimed = true; return; }
      (d.notifications || []).forEach(function (n) {
        waiterNotificationCursor = Math.max(waiterNotificationCursor, n.id || 0);
        waiterBeep(); toast(n.message || "Restaurant notification", n.type === "out-of-stock");
        if ("Notification" in window && Notification.permission === "granted") new Notification("Chandrika Dhaba", { body: n.message || "Restaurant notification" });
        if (n.type === "low-stock" || n.type === "out-of-stock" || n.type === "stock-restored") {
          loadMenu().then(function () { Object.keys(rowActions).forEach(paintAction); });
        }
        if (n.type === "kitchen-ready") refreshTable().then(function () { if (trackOpen) renderPanel(); });
      });
      waiterNotificationCursor = Math.max(waiterNotificationCursor, d.latestId || 0);
    }).catch(function () {});
  }
  function startWaiterNotifications() {
    if (!waiter) return;
    var soundBtn = $("waiterSoundBtn");
    if (soundBtn) { soundBtn.hidden = false; soundBtn.textContent = waiterSoundReady ? (waiterSoundOn ? "Sound: on" : "Sound: off") : "Enable sound"; }
    if (waiterNotificationTimer) clearInterval(waiterNotificationTimer);
    pollWaiterNotifications();
    waiterNotificationTimer = setInterval(pollWaiterNotifications, 1500);
  }
  if ($("waiterSoundBtn")) $("waiterSoundBtn").addEventListener("click", function () {
    if (!waiterSoundReady) {
      try { waiterAudio = waiterAudio || new (window.AudioContext || window.webkitAudioContext)(); waiterAudio.resume(); waiterSoundReady = true; waiterSoundOn = true; } catch (e) {}
      if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(function () {});
    } else waiterSoundOn = !waiterSoundOn;
    store("dhaba_waiter_sound", waiterSoundOn);
    $("waiterSoundBtn").textContent = waiterSoundReady ? (waiterSoundOn ? "Sound: on" : "Sound: off") : "Enable sound";
    if (waiterSoundOn) waiterBeep();
  });

  // table number from the QR link: /?table=5
  (function () {
    var m = /[?&]table=(\d{1,3})\b/.exec(location.search);
    if (m) table = parseInt(m[1], 10);
  })();

  // ---------------------------------------------------------------- welcome animation
  var entered = false;
  function enter() {
    if (entered) return;
    entered = true;
    var w = $("welcome");
    w.classList.add("leave");
    $("app").hidden = false;
    document.body.classList.remove("locked");
    setTimeout(function () { w.hidden = true; }, 750);
    window.scrollTo(0, 0);
  }

  document.body.classList.add("locked");
  if (table > 0) { var tp = $("welcomeTable"); tp.textContent = table >= 101 && table <= 103 ? "Bed " + (table - 100) : "Table " + table; tp.hidden = false; }
  $("enterBtn").addEventListener("click", enter);
  setTimeout(enter, 5600);

  // Aggregate workload only; customer pages never receive other tables' names or orders.
  function refreshKitchenEstimate() {
    if (!waiter) {
      document.querySelectorAll(".item-kitchen-time").forEach(function (node) { node.hidden = true; });
      return Promise.resolve(kitchenEstimate);
    }
    return api("GET", "/api/public/kitchen-estimate").then(function (data) {
      kitchenEstimate = data || kitchenEstimate;
      document.querySelectorAll(".item-kitchen-time").forEach(function (node) {
        var id = node.getAttribute("data-item-id"), item = itemsById[id];
        if (!item) return;
        var prep = Math.max(1, Number(item.prepMinutes || 10));
        var queue = Math.max(0, Number(kitchenEstimate.queueMinutes || 0));
        node.textContent = "Kitchen prep: " + prep + " min · Estimated wait: ~" + (queue + prep) + " min";
      });
      return kitchenEstimate;
    }).catch(function () { return kitchenEstimate; });
  }

  // ---------------------------------------------------------------- load menu
  function loadMenu() {
    return api("GET", "/api/public/menu?table=" + table).then(function (data) {
      menu = data;
      updatePreparationFilterOptions(data.preparationTypes || ["Dry", "Wet", "Spicy", "Light Dry", "Light Wet", "Dry & Wet", "Not applicable"]);
      itemsById = {};
      data.categories.forEach(function (c) { c.items.forEach(function (i) { itemsById[i.id] = i; }); });
      var mixed = null;
      data.categories.forEach(function (c) { c.items.forEach(function (i) { if (/mixed biryani/i.test(i.name)) mixed = i; }); });
      var heroImg = document.querySelector("#specialPhoto img");
      var heroPrice = $("specialHeroPrice"), heroDetails = $("specialDetails");
      var heroDescription = $("specialHeroDescription"), heroActions = $("specialHeroActions");
      var customerSpecialHero = !!(mixed && mixed.special);
      if (mixed && heroImg) { heroImg.src = mixed.image || "/img/dishes/mixed-biryani.jpg"; heroImg.alt = mixed.name; }
      // The featured/specially assigned dish follows the same two-view rule as menu cards:
      // public QR view = details only, waiter view = price and add-to-cart.
      if (mixed && heroPrice && mixed.price !== undefined) heroPrice.textContent = rs(mixed.price);
      if (heroPrice) heroPrice.hidden = !waiter;
      if (heroDetails) heroDetails.hidden = !!waiter;
      if (heroDescription) heroDescription.hidden = false;
      if (heroActions) heroActions.hidden = false;
      if ( $("specialTryNow") ) $("specialTryNow").hidden = true;
      if (heroDetails) heroDetails.textContent = "View Details / వివరాలు";
      function openMixed() {
        if (!mixed) { toast("Mixed Biryani is not available in the menu yet.", true); return; }
        showItemDetail(mixed);
      }
      if (heroDetails) heroDetails.onclick = openMixed;
      if (waiter && mixed) {
        var heroAdd = $("specialTryNow");
        if (heroAdd) {
          heroAdd.hidden = false;
          heroAdd.textContent = "ADD";
          heroAdd.onclick = function () {
            if (!mixed.available) { toast("This dish is currently unavailable.", true); return; }
            setQty(mixed.id, (cart[mixed.id] || 0) + 1);
          };
        }
      }
      var heroPhoto = $("specialPhoto");
      if (heroPhoto) { heroPhoto.onclick = openMixed; heroPhoto.setAttribute("aria-label", customerSpecialHero ? "Chef special Mixed Biryani" : "View Mixed Biryani details"); }
      var d = data.dhaba || {};
      if (table > d.tableCount || table < 1) table = 0;   // unknown table: customer will choose
      $("brandSub").textContent = d.address || "";
      $("footAddr").textContent = d.address || "";
      $("footPhone").textContent = d.phone || "";
      cart = {};
      cartPreparation = {};
      if (waiter && (!table || (waiter.tables || []).indexOf(table) < 0)) { waiter = null; store("dhaba_waiter", null); }
      renderTableChip();
      renderChefSpecials();
      return refreshKitchenEstimate().then(function () {
        renderMenu();
        updateCartBar();
      });
    }).catch(function (e) {
      $("menu").textContent = "";
      $("menu").appendChild(h("div", { class: "empty" }, [
        "The menu could not be loaded. ", h("br"),
        h("button", { class: "btn small", type: "button", onclick: function () { loadMenu(); }, text: "Try again" })
      ]));
      toast(e.message, true);
    });
  }

  function renderTableChip() {
    var chip = $("tableChip");
    if (table > 0) { chip.textContent = table >= 101 && table <= 103 ? "Bed " + (table - 100) : "Table " + table; chip.hidden = false; } else { chip.hidden = true; }
    var wb = $("waiterBtn");
    wb.textContent = waiter ? "Table panel" : "Waiter login";
    if ($("waiterSoundBtn")) { $("waiterSoundBtn").hidden = !waiter; if (waiter) $("waiterSoundBtn").textContent = waiterSoundOn ? "Sound: on" : "Sound: off"; }
    var note = $("custNote");
    if (waiter) {
      note.hidden = false;
      note.textContent = "";
      note.appendChild(h("span", { text: "Waiter " + waiter.name + ": tap ADD on the dishes the customers ask for, then review the order." }));
      note.appendChild(h("button", { type: "button", class: "linkbtn", text: "Log out", onclick: waiterLogout }));
    } else {
      var wn = menu && menu.waiter;
      note.hidden = false;
      note.textContent = wn ? "Your waiter for this table is " + wn + ". Tell him what you would like and he will take your order." :
        "Please call the waiter to take your order.";
    }
    $("cbMid").textContent = "Review order";
    paintNextOrder();
  }

  function goToMenuList() {
    var target = $("menu");
    if (target) target.scrollIntoView({ behavior: "smooth", block: "start" });
  }
  ["specialTryNow", "chefTryNow"].forEach(function (id) {
    var btn = $(id); if (btn) btn.addEventListener("click", goToMenuList);
  });

  // ---------------------------------------------------------------- manager-configured preparation filters and chef specials
  function updatePreparationFilterOptions(types) {
    var select = $("prepFilter"), selected = prepFilter;
    select.textContent = "";
    select.appendChild(h("option", { value: "all", text: "All preparations" }));
    types.forEach(function (v) { select.appendChild(h("option", { value: v, text: v })); });
    select.value = types.indexOf(selected) >= 0 ? selected : "all";
    prepFilter = select.value;
  }

  function renderChefSpecials() {
    var root = $("chefSpecialList"), section = $("chefSpecials");
    root.textContent = "";
    var featured = [];
    menu.categories.forEach(function (c) { c.items.forEach(function (it) { if (it.special && (!waiter || it.available)) featured.push(it); }); });
    section.hidden = featured.length === 0;
    featured.forEach(function (it) {
      var card = h("article", { class: "chef-special-card" }, [
        h("img", { src: fallbackImage(it), alt: it.name, loading: "lazy", onerror: function () { this.onerror = null; this.src = "/img/dishes/mixed-biryani.jpg"; } }),
        h("div", { class: "chef-special-info" }, [
          h("span", { class: "chef-special-label", text: "CHEF SPECIAL" }),
          h("b", { text: it.name }),
          it.nameTe ? h("small", { text: it.nameTe }) : null,
          !waiter ? h("button", { class: "detail-btn", type: "button", text: "View Details / వివరాలు", onclick: function () { showItemDetail(it); } }) :
            (it.available ? h("button", { class: "add", type: "button", text: "ADD", onclick: function () { setQty(it.id, (cart[it.id] || 0) + 1); } }) : h("span", { class: "soldtag", text: "Unavailable" }))
        ])
      ]);
      root.appendChild(card);
    });
    var prev = $("chefSpecialPrev"), next = $("chefSpecialNext");
    function slideSpecial(dir) {
      var step = Math.max(220, Math.floor(root.clientWidth * 0.8));
      root.scrollBy({ left: dir * step, behavior: "smooth" });
    }
    if (prev) prev.onclick = function () { slideSpecial(-1); };
    if (next) next.onclick = function () { slideSpecial(1); };
    if (root._autoSlide) clearInterval(root._autoSlide);
    if (featured.length > 1) {
      root._autoSlide = setInterval(function () {
        if (document.hidden || !section.isConnected) return;
        var atEnd = root.scrollLeft + root.clientWidth >= root.scrollWidth - 8;
        if (atEnd) root.scrollTo({ left: 0, behavior: "smooth" });
        else slideSpecial(1);
      }, 3000);
    }
  }

  // ---------------------------------------------------------------- render menu
  function matches(it) {
    if (filterVeg === "veg" && !it.veg) return false;
    if (filterVeg === "nonveg" && it.veg) return false;
    if (prepFilter !== "all" && (it.preparation || "").toLowerCase() !== prepFilter.toLowerCase()) return false;
    if (query) {
      var q = query.toLowerCase();
      if (it.name.toLowerCase().indexOf(q) < 0 && (it.nameTe || "").indexOf(query) < 0) return false;
    }
    return true;
  }

  function renderMenu() {
    var root = $("menu");
    var nav = $("cats");
    root.textContent = "";
    nav.textContent = "";
    rowActions = {};
    var shown = 0;
    menu.categories.forEach(function (c) {
      var list = c.items.filter(matches);
      if (!list.length) return;
      shown += list.length;

      nav.appendChild(h("button", { type: "button", "data-sec": "sec-" + c.id, text: c.name.replace(/\b\w+/g, function (w) {
        return w.charAt(0) + w.slice(1).toLowerCase();
      }), onclick: function () {
        var s = $("sec-" + c.id);
        if (s) s.scrollIntoView({ behavior: "smooth", block: "start" });
      } }));

      // Keep section headers clean; each dish has its own image on its card.
      var head = h("div", { class: "sec-head" }, [
        h("div", { class: "sec-title" }, [
          h("span", { class: "pill", text: c.name }),
          c.nameTe ? h("div", { class: "sec-te", text: c.nameTe }) : null
        ])
      ]);
      var body = h("div", { class: "sec-list" }, list.map(itemRow));
      var left = h("button", { class: "section-scroll-arrow left", type: "button", text: "‹", "aria-label": "Scroll " + c.name + " left", onclick: function () { body.scrollBy({ left: -Math.max(240, body.clientWidth * .75), behavior: "smooth" }); } });
      var right = h("button", { class: "section-scroll-arrow right", type: "button", text: "›", "aria-label": "Scroll " + c.name + " right", onclick: function () { body.scrollBy({ left: Math.max(240, body.clientWidth * .75), behavior: "smooth" }); } });
      var scroller = h("div", { class: "sec-scroller" }, [left, body, right]);
      root.appendChild(h("section", { class: "sec", id: "sec-" + c.id }, [head, scroller]));
    });
    $("empty").hidden = shown > 0;
    setupSpy();
  }

  function fallbackImage(it) {
    if (it.image) return it.image;
    var n = ((it.category || "") + " " + (it.name || "")).toLowerCase();
    var photoMap = [
      [/mixed biryani/, "mixed-biryani"], [/chicken 65/, "chicken-65"], [/chilli chicken|chili chicken/, "chilli-chicken"],
      [/chicken pakoda|chicken pakora/, "chicken-pakoda"], [/pepper chicken/, "pepper-chicken"], [/dragon chicken/, "dragon-chicken"],
      [/chilli paneer|chili paneer/, "chilli-paneer"], [/paneer 65/, "paneer-65"], [/paneer pakoda|paneer pakora/, "paneer-pakoda"],
      [/crispy corn|corn pepper/, "crispy-corn"], [/veg fingers/, "veg-fingers"], [/chicken curry/, "chicken-curry"],
      [/mutton curry/, "mutton-curry"], [/prawns curry|prawn curry/, "prawns-curry"], [/chicken masala/, "chicken-masala"],
      [/mutton masala/, "mutton-masala"], [/chicken dum biryani|chicken biryani/, "chicken-dum-biryani"],
      [/mutton biryani/, "mutton-biryani"], [/prawns biryani|prawn biryani/, "prawns-biryani"],
      [/veg biryani/, "veg-biryani"], [/jeera rice/, "jeera-rice"]
    ];
    for (var i = 0; i < photoMap.length; i++) {
      if (photoMap[i][0].test(n)) return "/img/dishes/" + photoMap[i][1] + ".jpg";
    }
    if (/biryani|rice|noodle/.test(n)) return "/img/dishes/mixed-biryani.jpg";
    if (/tandoori|tikka|kabab|kebab/.test(n)) return "/img/dishes/chicken-65.jpg";
    if (/curry|masala|gravy|dal/.test(n)) return /veg|paneer|mushroom/.test(n) ? "/img/dishes/chilli-paneer.jpg" : "/img/dishes/chicken-curry.jpg";
    if (/paneer|mushroom|corn|veg|gobi|fry/.test(n) && !/chicken|mutton|prawn|fish/.test(n)) return "/img/dishes/paneer-pakoda.jpg";
    if (/chicken|mutton|prawn|fish|egg/.test(n)) return "/img/dishes/chilli-chicken.jpg";
    return "/img/dishes/mixed-biryani.jpg";
  }

  function itemRow(it) {
    var action = h("div", { class: "act" });
    rowActions[it.id] = action;
    paintAction(it.id);
    var customerSpecial = false; // special presentation is reserved for the top featured section
    var specialDish = !!it.special;
    var info = [
      h("div", { class: "nm-line" }, [
        h("span", { class: "mk " + (it.veg ? "veg-mark" : "nv-mark"), title: it.veg ? "Vegetarian" : "Non-vegetarian", "aria-label": it.veg ? "Vegetarian" : "Non-vegetarian" }),
        h("div", { class: "nm", text: it.name })
      ]),
      it.nameTe ? h("div", { class: "te", text: it.nameTe }) : null
    ];
    var itemPrepMinutes = Math.max(1, Number(it.prepMinutes || 10));
    var queueAheadMinutes = Math.max(0, Number(kitchenEstimate.queueMinutes || 0));
    info.push(h("div", { class: "item-kitchen-time", "data-item-id": it.id, hidden: !waiter,
      text: "Kitchen prep: " + itemPrepMinutes + " min · Estimated wait: ~" + (queueAheadMinutes + itemPrepMinutes) + " min" }));
    if (!customerSpecial) {
      if (!specialDish && it.desc) info.push(h("div", { class: "ds", text: it.desc }));
      if (!specialDish && it.preparation) info.push(h("span", { class: "prep-tag", text: it.preparation }));
      if (waiter && it.preparationEnabled) {
        var itemPrep = h("select", { class: "waiter-item-preparation", "aria-label": "Ask customer for preparation of " + it.name });
        var itemPrepTypes = (it.preparationOptions && it.preparationOptions.length ? it.preparationOptions : [it.preparation || "Dry"]);
        itemPrepTypes.forEach(function (type) { itemPrep.appendChild(h("option", { value: type, text: type })); });
        var chosenPrep = cartPreparation[it.id] || it.preparation || itemPrepTypes[0];
        itemPrep.value = itemPrepTypes.indexOf(chosenPrep) >= 0 ? chosenPrep : itemPrepTypes[0];
        cartPreparation[it.id] = itemPrep.value;
        itemPrep.addEventListener("change", function () { cartPreparation[it.id] = itemPrep.value; });
        info.push(h("label", { class: "waiter-item-prep-field" }, [
          h("small", { text: "Ask customer · Preparation" }), itemPrep
        ]));
      }
      if (!specialDish || waiter) info.push(h("div", { class: "pr", text: rs(it.price) }));
      if (!waiter || !specialDish) info.push(h("button", { class: "detail-btn", type: "button", text: "View Details / వివరాలు", onclick: function () { showItemDetail(it); } }));
      if (specialDish && waiter) info.push(h("span", { class: "chef-special-label", text: "CHEF SPECIAL" }));
    }
    var photoAttrs = { class: "ph" + (customerSpecial ? " special-menu-photo" : ""), src: fallbackImage(it), alt: it.name, title: customerSpecial ? it.name : "Click to view details", loading: "lazy", width: 230, height: 190,
      onerror: function () { this.onerror = null; this.src = "/img/dishes/mixed-biryani.jpg"; } };
    if (!customerSpecial && !specialDish) {
      photoAttrs.role = "button"; photoAttrs.tabindex = "0";
      photoAttrs.onclick = function () { showItemDetail(it); };
      photoAttrs.onkeydown = function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); showItemDetail(it); } };
    }
    return h("div", { class: "item" + (waiter && !it.available ? " sold" : "") + (customerSpecial ? " customer-chef-special" : "") }, [
      h("div", { class: "info" }, info),
      h("img", photoAttrs),
      action
    ]);
  }

  function paintAction(id) {
    var box = rowActions[id];
    if (!box) return;
    var it = itemsById[id];
    box.textContent = "";
    if (!waiter) return;   // customers only look at the menu; the waiter adds dishes
    if (!it.available) { box.appendChild(h("span", { class: "soldtag", text: "Sold out" })); return; }
    var q = cart[id] || 0;
    if (q === 0) {
      box.appendChild(h("button", { class: "add", type: "button", "aria-label": "Add " + it.name + " to cart", text: "ADD",
        onclick: function () { setQty(id, 1); } }));
    } else {
      box.appendChild(stepper(id, q));
    }
  }

  function stepper(id, q) {
    return h("div", { class: "stepper" }, [
      h("button", { type: "button", "aria-label": "Remove one", text: "−", onclick: function () { setQty(id, (cart[id] || 0) - 1); } }),
      h("b", { text: q }),
      h("button", { type: "button", "aria-label": "Add one", text: "+", onclick: function () { setQty(id, (cart[id] || 0) + 1); } }),
      h("button", { type: "button", class: "stepper-remove", "aria-label": "Remove item", text: "×", title: "Remove item", onclick: function () { setQty(id, 0); } })
    ]);
  }

  function setQty(id, q) {
    q = Math.max(0, Math.min(20, q));
    if (q === 0) { delete cart[id]; delete cartPreparation[id]; }
    else {
      cart[id] = q;
      if (!cartPreparation[id] && itemsById[id] && itemsById[id].preparationEnabled) cartPreparation[id] = itemsById[id].preparation || "";
    }
    paintAction(id);
    saveCurrentCart();
    updateCartBar(true);
    if (!$("sheet").hidden) renderCart();
  }

  function cartCount() { var n = 0; for (var k in cart) n += cart[k]; return n; }
  function cartTotal() { var t = 0; for (var k in cart) { if (itemsById[k]) t += itemsById[k].price * cart[k]; } return t; }

  function updateCartBar(bump) {
    var n = cartCount();
    var bar = $("cartBar");
    bar.hidden = !$("sheet").hidden;
    $("cbCount").textContent = n;
    $("cbLabel").textContent = n === 1 ? "item" : "items";
    var hint = $("cartHint");
    if (hint) hint.textContent = waiter ? (n ? "Review table order" : "Add dishes") : "Call waiter to order";
    $("cbTotal").textContent = String(cartTotal().toLocaleString("en-IN"));
    if (bump && n > 0) { bar.classList.remove("bump"); void bar.offsetWidth; bar.classList.add("bump"); }
  }

  // filters + search
  $("search").addEventListener("input", function (e) { query = e.target.value.trim(); renderMenu(); });
  // Voice search uses the browser's speech recognition and fills the normal search box.
  (function setupVoiceSearch() {
    var button = $("voiceSearch"), search = $("search");
    if (!button || !search) return;
    var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      button.title = "Voice search is not supported in this browser";
      button.addEventListener("click", function () {
        toast("Voice search is not supported here. Please use Chrome or Edge and allow microphone access.", true);
      });
      return;
    }
    var recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = navigator.language || "en-IN";
    recognition.onstart = function () {
      button.classList.add("listening");
      button.setAttribute("aria-label", "Listening for dish name");
      toast("Listening... Say the dish name");
    };
    recognition.onresult = function (event) {
      var transcript = "";
      for (var i = event.resultIndex; i < event.results.length; i++) transcript += event.results[i][0].transcript;
      if (transcript) {
        search.value = transcript.trim();
        query = search.value;
        renderMenu();
      }
    };
    recognition.onerror = function (event) {
      var message = event.error === "not-allowed" ? "Allow microphone access to use voice search." :
        event.error === "no-speech" ? "No speech detected. Please try again." : "Voice search failed. Please try again.";
      toast(message, true);
    };
    recognition.onend = function () {
      button.classList.remove("listening");
      button.setAttribute("aria-label", "Search by voice");
    };
    button.addEventListener("click", function () {
      try { recognition.start(); } catch (e) { toast("Voice search is already listening.", true); }
    });
  })();
  $("prepFilter").addEventListener("change", function (e) { prepFilter = e.target.value; renderMenu(); });
  Array.prototype.forEach.call(document.querySelectorAll(".vegtog button"), function (b) {
    b.addEventListener("click", function () {
      filterVeg = b.getAttribute("data-f");
      Array.prototype.forEach.call(document.querySelectorAll(".vegtog button"), function (x) { x.classList.toggle("on", x === b); });
      renderMenu();
    });
  });

  // highlight the category tab for the section on screen
  var spy;
  function setupSpy() {
    if (spy) spy.disconnect();
    if (!("IntersectionObserver" in window)) return;
    spy = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var tabs = $("cats").children;
        for (var i = 0; i < tabs.length; i++) {
          var on = tabs[i].getAttribute("data-sec") === en.target.id;
          tabs[i].classList.toggle("on", on);
          if (on && tabs[i].scrollIntoView) {
            var nav = $("cats");
            nav.scrollLeft = tabs[i].offsetLeft - nav.clientWidth / 2 + tabs[i].clientWidth / 2;
          }
        }
      });
    }, { rootMargin: "-80px 0px -65% 0px" });
    Array.prototype.forEach.call(document.querySelectorAll(".sec"), function (s) { spy.observe(s); });
  }

  // ---------------------------------------------------------------- waiter login
  function wapi(method, url, body) {
    var opts = { method: method, headers: { "X-Waiter-Token": waiter ? waiter.token : "", "X-Requested-With": "dhaba" }, cache: "no-store" };
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    return fetch(url, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) {
          if (r.status === 401) { waiter = null; store("dhaba_waiter", null); cart = {}; renderTableChip(); Object.keys(rowActions).forEach(paintAction); updateCartBar(); }
          var e = new Error(data.error || "Something went wrong"); e.status = r.status; throw e;
        }
        applyNextOrder(data);
        return data;
      });
    }, function () { throw new Error("No internet connection. Please try again."); });
  }

  function openLogin() {
    if (!table) { toast("Scan the QR code on a table first", true); return; }
    $("wlTable").textContent = "Table " + table;
    $("fWl").classList.remove("bad");
    $("wlErr").textContent = "Enter a valid 10-digit mobile number";
    $("wlogin").hidden = false;
    $("inWl").focus();
  }
  function closeLogin() { $("wlogin").hidden = true; }
  $("waiterBtn").addEventListener("click", function () { if (waiter) openPanel(); else openLogin(); });
  $("wlClose").addEventListener("click", closeLogin);
  $("wlogin").addEventListener("click", function (e) { if (e.target === $("wlogin")) closeLogin(); });
  $("inWl").addEventListener("input", function () { $("fWl").classList.remove("bad"); });

  var verifying = false;
  $("wlForm").addEventListener("submit", function (e) {
    e.preventDefault();
    try { waiterAudio = waiterAudio || new (window.AudioContext || window.webkitAudioContext)(); waiterAudio.resume(); waiterSoundReady = true; } catch (audioError) {}
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(function () {});
    if (verifying) return;
    var phone = cleanPhone($("inWl").value);
    if (!phone) { $("fWl").classList.add("bad"); return; }
    verifying = true;
    $("wlBtn").disabled = true;
    api("POST", "/api/waiter/verify", { phone: phone, table: table })
      .then(function (w) {
        waiter = { token: w.token, id: w.id, name: w.name, tables: w.tables };
        store("dhaba_waiter", waiter);
        restoreTableCart(table);
        $("inWl").value = "";
        closeLogin();
        renderTableChip();
        startWaiterNotifications();
        Object.keys(rowActions).forEach(paintAction);
        toast("Welcome, " + w.name + ". Table " + table + " is open.");
        return loadMenu().then(function () { return refreshTable(); }).then(function () { if (tableData && tableData.orders.length) openPanel(); });
      })
      .catch(function (err) { $("wlErr").textContent = err.message; $("fWl").classList.add("bad"); })
      .then(function () { verifying = false; $("wlBtn").disabled = false; });
  });

  function waiterLogout() {
    waiter = null; tableData = null; cart = {}; nextOrder = { no: 0, date: "" };
    store("dhaba_waiter", null);
    renderTableChip();
    Object.keys(rowActions).forEach(paintAction);
    updateCartBar();
    loadMenu();
    toast("Logged out");
  }

  // ---------------------------------------------------------------- review + confirm order (waiter)
  function openSheet() {
    renderCart();
    $("tableFixedNo").textContent = "Table " + table;
    $("waiterOf").textContent = "Waiter: " + (waiter ? waiter.name : "");
    $("sheet").hidden = false;
    $("cartBar").hidden = true;
    paintNextOrder();
    wapi("GET", "/api/waiter/next-order").catch(function () {});   // exact number right now
    $("inName").focus();
  }
  function closeSheet() { $("sheet").hidden = true; updateCartBar(); }

  function renderCart() {
    var box = $("cartLines");
    box.textContent = "";
    var ids = Object.keys(cart).filter(function (id) { return itemsById[id]; });
    if (!ids.length) { closeSheet(); return; }
    ids.forEach(function (id) {
      var it = itemsById[id];
      var lineParts = [
        h("span", { class: it.veg ? "veg-mark" : "nv-mark" }),
        h("div", { class: "n" }, [it.name, !it.special ? h("small", { text: rs(it.price) + " each" }) : null])
      ];
      if (waiter && it.preparationEnabled) {
        var prepSelect = h("select", { class: "order-preparation-select", "aria-label": "Preparation for " + it.name });
        var prepTypes = (menu && menu.preparationTypes) || ["Dry", "Wet", "Spicy", "Light Dry", "Light Wet", "Dry & Wet", "Not applicable"];
        prepTypes.forEach(function (type) { prepSelect.appendChild(h("option", { value: type, text: type })); });
        prepSelect.value = prepTypes.indexOf(cartPreparation[id]) >= 0 ? cartPreparation[id] : (prepTypes.indexOf(it.preparation) >= 0 ? it.preparation : prepTypes[0]);
        cartPreparation[id] = prepSelect.value;
        prepSelect.addEventListener("change", function () { cartPreparation[id] = prepSelect.value; });
        lineParts.push(h("label", { class: "order-preparation-field" }, [
          h("small", { text: "Ask customer · Preparation for chef" }), prepSelect
        ]));
      }
      lineParts.push(stepper(id, cart[id]));
      if (!it.special) lineParts.push(h("div", { class: "t", text: rs(it.price * cart[id]) }));
      box.appendChild(h("div", { class: "cl order-cart-line" }, lineParts));
    });
    $("cartTotal").textContent = String(cartTotal().toLocaleString("en-IN"));
    var starter = 0, main = 0;
    ids.forEach(function (id) {
      var it = itemsById[id] || {};
      var category = (menu.categories.find(function (c) { return c.items.some(function (x) { return x.id === id; }); }) || {}).name || "";
      var mins = Math.max(1, Number(it.prepMinutes || 10));
      if (/starter/i.test(category)) starter = Math.max(starter, mins); else main = Math.max(main, mins);
    });
    var queueAhead = Math.max(0, Number(kitchenEstimate.queueMinutes || 0));
    var newOrderPrep = starter + main;
    var estimate = $("cartEstimate");
    if (estimate) {
      estimate.hidden = false;
      estimate.textContent = "Estimated wait: ~" + (queueAhead + newOrderPrep) + " min · Current kitchen queue: " + queueAhead + " min + your order preparation: " + newOrderPrep + " min. Starters are prepared before main course. Actual time may vary with kitchen workload.";
    }
  }

  $("goCart").addEventListener("click", function () {
    if (!waiter) { toast("Please call the waiter to place your order."); return; }
    if (!cartCount()) { toast("Add dishes to this table first."); return; }
    openSheet();
  });
  $("sheetClose").addEventListener("click", closeSheet);
  $("sheet").addEventListener("click", function (e) { if (e.target === $("sheet")) closeSheet(); });

  function setBad(fieldId, bad) { $(fieldId).classList.toggle("bad", bad); }

  function cleanPhone(raw) {
    var s = String(raw).replace(/[\s\-()]/g, "");
    if (s.indexOf("+91") === 0) s = s.slice(3);
    else if (s.length === 12 && s.indexOf("91") === 0) s = s.slice(2);
    else if (s.length === 11 && s.charAt(0) === "0") s = s.slice(1);
    return /^[6-9]\d{9}$/.test(s) ? s : "";
  }
  function nameOk(s) { return s.length >= 2 && /\p{L}/u.test(s); }

  $("inName").addEventListener("input", function () { setBad("fName", false); });
  $("inPhone").addEventListener("input", function () { setBad("fPhone", false); });

  var placing = false;
  $("orderForm").addEventListener("submit", function (e) {
    e.preventDefault();
    if (placing || !waiter) return;
    var name = $("inName").value.trim().replace(/\s+/g, " ");
    var phone = cleanPhone($("inPhone").value);
    var ok = true;
    if (!nameOk(name)) { setBad("fName", true); ok = false; }
    if (!phone) { setBad("fPhone", true); ok = false; }
    if (!ok) {
      toast("Please ask the customer for name and mobile number", true);
      var firstBad = document.querySelector("#orderForm .field.bad input");
      if (firstBad) firstBad.focus();
      return;
    }
    var items = Object.keys(cart).map(function (id) { return { itemId: id, qty: cart[id], preparation: (itemsById[id] && itemsById[id].preparationEnabled) ? (cartPreparation[id] || itemsById[id].preparation || "") : "" }; });
    if (!items.length) { closeSheet(); return; }

    placing = true;
    var btn = $("placeBtn");
    btn.disabled = true;
    btn.textContent = "Confirming...";
    wapi("POST", "/api/waiter/orders", { table: table, name: name, phone: phone, note: $("inNote").value.trim(), items: items })
      .then(function (res) {
        tableData = res;
        cart = {};
        cartPreparation = {};
        Object.keys(rowActions).forEach(paintAction);
        $("inNote").value = "";
        closeSheet();
        toast("Order " + res.orderNo + " confirmed · Queue " + (res.kitchenQueueAheadMinutes || 0) + " min + preparation " + Math.max(0, (res.estimatedWaitMinutes || 0) - (res.kitchenQueueAheadMinutes || 0)) + " min = about " + (res.estimatedWaitMinutes || 0) + " min for " + name);
        openPanel();
      })
      .catch(function (err) { toast(err.message, true); })
      .then(function () { placing = false; btn.disabled = false; btn.textContent = "Confirm order"; });
  });

  // ---------------------------------------------------------------- table panel: serve -> request payment -> pay
  var payMethod = "cash";
  var paidBill = null;

  function refreshTable() {
    if (!waiter || !table) return Promise.resolve();
    return wapi("GET", "/api/waiter/tables/" + table).then(function (d) { tableData = d; }).catch(function (e) {
      if (e.status !== 401) toast(e.message, true);
    });
  }

  function openPanel() {
    if (!waiter) { openLogin(); return; }
    trackOpen = true;
    paidBill = null;
    $("track").hidden = false;
    $("cartBar").hidden = true;
    renderPanel();
    refreshTable().then(renderPanel);
  }
  function closePanel() { trackOpen = false; $("track").hidden = true; updateCartBar(); }
  $("trackClose").addEventListener("click", closePanel);
  $("track").addEventListener("click", function (e) { if (e.target === $("track")) closePanel(); });

  function lineList(lines) {
    return h("ul", null, lines.map(function (l) {
      return h("li", null, [
        h("span", null, [l.qty + " × " + l.name, l.preparation ? h("small", { class: "kitchen-prep", text: "Chef: " + l.preparation }) : null]),
        h("span", { text: rs(l.total) })
      ]);
    }));
  }

  function upiLink(total) {
    return "upi://pay?pa=" + encodeURIComponent(tableData.upiId) + "&pn=" + encodeURIComponent(tableData.payeeName || "Chandrika Dhaba & Restaurant")
      + "&am=" + total + ".00&cu=INR&tn=" + encodeURIComponent("Table " + table + " bill");
  }

  function qrSvg(text) {
    var qr = qrcode(0, "M");
    qr.addData(text);
    qr.make();
    var n = qr.getModuleCount(), m = 2, NS = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 " + (n + 2 * m) + " " + (n + 2 * m));
    svg.setAttribute("shape-rendering", "crispEdges");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "UPI QR code");
    var bg = document.createElementNS(NS, "rect");
    bg.setAttribute("width", n + 2 * m); bg.setAttribute("height", n + 2 * m); bg.setAttribute("fill", "#fff");
    svg.appendChild(bg);
    var d = "";
    for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (qr.isDark(r, c)) d += "M" + (c + m) + " " + (r + m) + "h1v1h-1z";
    var p = document.createElementNS(NS, "path");
    p.setAttribute("d", d); p.setAttribute("fill", "#1c2308");
    svg.appendChild(p);
    return svg;
  }

  function switchWaiterTable(nextTable) {
    nextTable = Number(nextTable);
    if (!waiter || !nextTable || (waiter.tables || []).map(Number).indexOf(nextTable) < 0) return;
    if (nextTable === table) return;
    saveCurrentCart();
    table = nextTable;
    history.replaceState(null, "", "/?table=" + table);
    restoreTableCart(table);
    tableData = null; paidBill = null;
    renderTableChip();
    Object.keys(rowActions).forEach(paintAction);
    updateCartBar();
    refreshTable().then(function () { if (trackOpen) renderPanel(); });
  }

  function renderPanel() {
    var body = $("trackBody");
    body.textContent = "";
    $("trackTitle").textContent = (table >= 101 && table <= 103 ? "Bed " + (table - 100) : "Table " + table);
    if (waiter && (waiter.tables || []).length > 1) {
      var switcher = h("label", { class: "waiter-table-switch" }, [
        h("span", { text: "Switch assigned table" }),
        h("select", { "aria-label": "Switch assigned table", onchange: function (ev) { switchWaiterTable(ev.target.value); } },
          (waiter.tables || []).map(function (n) { return h("option", { value: n, text: n >= 101 && n <= 103 ? "Bed " + (n - 100) : "Table " + n, selected: Number(n) === table }); }))
      ]);
      body.appendChild(switcher);
    }
    if (paidBill) { renderPaid(body); return; }
    var d = tableData;
    if (!d || !d.orders.length) {
      body.appendChild(h("p", { class: "hint center", text: "No order on this table yet. Close this, tap ADD on the dishes, then review the order." }));
      body.appendChild(h("button", { class: "btn gold block", type: "button", text: "Add dishes", onclick: closePanel }));
      return;
    }
    d.orders.forEach(function (o) {
      var counter = o.source === "manager";
      var who = counter ? "Added by manager" : o.customerName + " · " + o.phone;
      var card = h("div", { class: "ord" }, [
        h("div", { class: "ord-top" }, [
          h("b", { text: orderLabel(o) }),
          h("span", { class: "status " + o.status, text: o.status === "SERVED" ? "Served" : "Preparing" })
        ]),
        h("div", { class: "hint", text: who })
      ]);
      card.appendChild(lineList(o.lines));
      if (o.status === "CONFIRMED") {
        card.appendChild(h("button", { class: "btn small gold", type: "button", text: "Mark served", onclick: function (ev) {
          var btn = ev.currentTarget; btn.disabled = true;
          wapi("POST", "/api/waiter/orders/" + encodeURIComponent(o.id) + "/served", {})
            .then(function (res) { tableData = res; renderPanel(); })
            .catch(function (e) { toast(e.message, true); btn.disabled = false; });
        } }));
      }
      body.appendChild(card);
    });

    var rows = [h("div", { class: "row" }, [h("span", { text: "Bill so far" }), h("span", { text: rs(d.subtotal) })])];
    if (d.discount > 0) rows.push(h("div", { class: "row" }, [h("span", { text: "Discount" }), h("span", { text: "−" + rs(d.discount) })]));
    if (d.gst > 0) rows.push(h("div", { class: "row" }, [h("span", { text: "GST " + d.gstPercent + "%" }), h("span", { text: rs(d.gst) })]));
    rows.push(h("div", { class: "row big" }, [h("span", { text: "To pay" }), h("span", { text: rs(d.total) })]));
    var box = h("div", { class: "bill-box" }, rows);
    body.appendChild(box);

    if (!d.billRequested) {
      body.appendChild(h("button", { class: "btn block", type: "button", text: "+ Add more dishes", style: "margin-bottom:10px", onclick: closePanel }));
      if (!d.allServed) {
        body.appendChild(h("div", { class: "wait-note", text: "Serve all the orders, then request payment." }));
      } else {
        body.appendChild(h("button", { class: "btn gold block", type: "button", text: "Request payment", onclick: function (ev) {
          var btn = ev.currentTarget; btn.disabled = true;
          wapi("POST", "/api/waiter/tables/" + table + "/request-payment", {})
            .then(function (res) { tableData = res; payMethod = "cash"; renderPanel(); })
            .catch(function (e) { toast(e.message, true); btn.disabled = false; });
        } }));
      }
      return;
    }
    renderPay(body, d);
  }

  function renderPay(body, d) {
    var total = d.total;
    var tabs = h("div", { class: "paytabs" }, [["cash", "Cash"], ["card", "Card"], ["upi", "UPI QR"]].map(function (m) {
      return h("button", { type: "button", class: payMethod === m[0] ? "on" : "", text: m[1], onclick: function () { payMethod = m[0]; renderPanel(); } });
    }));
    body.appendChild(h("h3", { text: "Payment" }));
    body.appendChild(tabs);
    var confirmBtn = h("button", { class: "btn gold block", type: "button", text: "Payment completed", disabled: true });
    var received = h("input", { type: "number", min: "0", step: "1", value: String(total), inputmode: "numeric" });
    var changeEl = h("b", { text: rs(0) });
    var ref = h("input", { type: "text", maxlength: "40", placeholder: "Reference / last 4 digits (optional)" });
    var receiptPhotoData = "";
    var receiptCamera = h("input", { type: "file", accept: "image/*", capture: "environment", class: "payment-photo-input" });
    var receiptChoose = h("input", { type: "file", accept: "image/*", class: "payment-photo-input" });
    var receiptPreview = h("img", { class: "payment-receipt-preview", alt: "Payment receipt preview", hidden: true });
    var photoHint = h("p", { class: "hint", text: "Attach a payment receipt, UPI confirmation or cash received photo. One photo is required." });
    function processReceipt(file) {
      if (!file) return;
      if (!file.type || file.type.indexOf("image/") !== 0) { toast("Please choose an image", true); return; }
      if (file.size > 8 * 1024 * 1024) { toast("Photo is too large. Choose an image under 8 MB.", true); return; }
      var reader = new FileReader();
      reader.onload = function () {
        var img = new Image();
        img.onload = function () {
          var scale = Math.min(1, 1280 / Math.max(img.width, img.height));
          var canvas = document.createElement("canvas");
          canvas.width = Math.max(1, Math.round(img.width * scale));
          canvas.height = Math.max(1, Math.round(img.height * scale));
          canvas.getContext("2d").drawImage(img, 0, 0, canvas.width, canvas.height);
          receiptPhotoData = canvas.toDataURL("image/jpeg", 0.72);
          receiptPreview.src = receiptPhotoData; receiptPreview.hidden = false;
          photoHint.textContent = "Payment photo attached. You can now complete payment.";
          confirmBtn.disabled = false;
        };
        img.onerror = function () { toast("Could not read that photo", true); };
        img.src = reader.result;
      };
      reader.readAsDataURL(file);
    }
    receiptCamera.addEventListener("change", function () { processReceipt(receiptCamera.files && receiptCamera.files[0]); });
    receiptChoose.addEventListener("change", function () { processReceipt(receiptChoose.files && receiptChoose.files[0]); });
    body.appendChild(h("div", { class: "paybox payment-photo-box" }, [
      h("b", { text: "Payment proof photo *" }), photoHint,
      h("div", { class: "photo-action-row" }, [
        h("label", { class: "btn gold photo-action" }, [h("span", { text: "Take Photo" }), receiptCamera]),
        h("label", { class: "btn ghost photo-action" }, [h("span", { text: "Choose Photo" }), receiptChoose])
      ]),
      receiptPreview
    ]));

    if (payMethod === "cash") {
      var upd = function () {
        var r = parseInt(received.value, 10);
        var ch = isNaN(r) ? 0 : r - total;
        changeEl.textContent = ch >= 0 ? rs(ch) : "Short by " + rs(-ch);
        changeEl.style.color = ch >= 0 ? "" : "var(--danger)";
      };
      received.addEventListener("input", upd);
      body.appendChild(h("div", { class: "paybox" }, [
        h("div", { class: "r" }, [h("span", { text: "Cash received" }), received]),
        h("div", { class: "r" }, [h("span", { text: "Change to return" }), changeEl])
      ]));
      upd();
    } else if (payMethod === "card") {
      body.appendChild(h("p", { class: "hint", text: "Take " + rs(total) + " on the card machine, then press the button." }));
      body.appendChild(h("div", { class: "paybox" }, [ref]));
    } else if (!d.upiId) {
      body.appendChild(h("div", { class: "wait-note", text: "The manager has not set the UPI ID yet (Settings). Use cash or card." }));
      confirmBtn.disabled = true;
    } else {
      body.appendChild(h("div", { class: "upi-qr" }, [
        h("div", { class: "qrbox" }, [qrSvg(upiLink(total))]),
        h("p", { class: "hint center", text: "Customer scans with GPay, PhonePe or Paytm. Amount " + rs(total) + ". Pay to: " + (d.payeeName || d.upiId) })
      ]));
      body.appendChild(h("div", { class: "paybox" }, [ref]));
      body.appendChild(h("p", { class: "hint center", text: "Press the button after your phone shows the money received." }));
    }

    confirmBtn.addEventListener("click", function () {
      if (!receiptPhotoData) { toast("Take or upload the payment photo first", true); return; }
      var req = { method: payMethod, total: total, receiptPhoto: receiptPhotoData };
      if (payMethod === "cash") {
        var r = parseInt(received.value, 10);
        if (isNaN(r) || r < total) { toast("Cash received is less than the bill", true); return; }
        req.received = r;
      } else { req.reference = ref.value; }
      confirmBtn.disabled = true;
      wapi("POST", "/api/waiter/tables/" + table + "/pay", req)
        .then(function (bill) { paidBill = bill; tableData = null; renderPanel(); })
        .catch(function (e) { toast(e.message, true); confirmBtn.disabled = false; refreshTable().then(renderPanel); });
    });
    body.appendChild(confirmBtn);
  }

  function renderPaid(body) {
    var b = paidBill;
    var how = b.method === "cash" ? "Cash" : b.method === "card" ? "Card" : "UPI";
    var kids = [
      h("div", { class: "tick", text: "✓" }),
      h("h3", { text: "Payment received" }),
      h("p", { class: "amt", text: rs(b.total) + " · " + how })
    ];
    if (b.method === "cash" && b.change > 0) kids.push(h("p", { class: "hint", text: "Return change " + rs(b.change) }));
    kids.push(h("p", { class: "hint", text: "Bill #" + b.no + " · Table " + table + ". Please hand this money to the manager at the end of your shift." }));
    kids.push(h("button", { class: "btn gold", type: "button", text: "Done", onclick: function () { paidBill = null; closePanel(); refreshTable(); } }));
    body.appendChild(h("div", { class: "paid-banner" }, kids));
  }

  setInterval(function () {
    if (!waiter || document.hidden || !trackOpen || paidBill) return;
    refreshTable().then(function () { if (trackOpen && !paidBill) renderPanel(); });
  }, 8000);

  // Dish details are visible to customers and waiters.
  function showItemDetail(it) {
    // Customers may open special-dish details, but special prices remain hidden.
    var body = $("itemDetailBody"); body.textContent = "";
    body.appendChild(h("img", { class: "detail-hero", src: fallbackImage(it), alt: it.name, onerror: function () { this.onerror = null; this.src = "/img/dishes/mixed-biryani.jpg"; } }));
    body.appendChild(h("h2", { id: "itemDetailTitle", text: it.name }));
    if (it.nameTe) body.appendChild(h("div", { class: "detail-te", text: it.nameTe }));
    if (!it.special || waiter) body.appendChild(h("div", { class: "detail-price", text: rs(it.price) }));
    else body.appendChild(h("div", { class: "detail-price chef-price-hidden", text: "Chef Special · Ask our staff for the price" }));
    if (it.preparation) body.appendChild(h("p", null, [h("b", { text: "Preparation / తయారీ విధానం: " }), it.preparation]));
    if (it.desc) body.appendChild(h("p", null, it.desc));
    body.appendChild(h("h3", { text: "Ingredients / కావలసిన పదార్థాలు" }));
    body.appendChild(h("p", { class: "ingredients", text: it.ingredients || "Ingredients not entered yet. Please ask the staff. / పదార్థాల వివరాలు ఇంకా నమోదు చేయలేదు." }));
    $("itemDetail").hidden = false; document.body.style.overflow = "hidden";
  }
  function closeItemDetail() { $("itemDetail").hidden = true; document.body.style.overflow = ""; }
  $("itemDetailClose").addEventListener("click", closeItemDetail);
  $("itemDetail").addEventListener("click", function (e) { if (e.target === $("itemDetail")) closeItemDetail(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeItemDetail(); });

  // ---------------------------------------------------------------- start
  if (kitchenEstimateTimer) clearInterval(kitchenEstimateTimer);
  kitchenEstimateTimer = setInterval(function () {
    if (document.hidden) return;
    refreshKitchenEstimate();
  }, 15000);

  loadMenu().then(function () { if (waiter) { startWaiterNotifications(); return refreshTable(); } });
})();
