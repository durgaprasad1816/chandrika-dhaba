(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";

  function qrSvg(text) {
    var qr = qrcode(0, "M");
    qr.addData(text);
    qr.make();
    var n = qr.getModuleCount(), m = 2;
    var svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 " + (n + 2 * m) + " " + (n + 2 * m));
    svg.setAttribute("shape-rendering", "crispEdges");
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

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  var params = new URLSearchParams(location.search);
  var base = (params.get("base") || location.origin).replace(/\/+$/, "");
  if (!/^https?:\/\/[^\s]+$/i.test(base)) base = location.origin;

  document.getElementById("printBtn").addEventListener("click", function () { window.print(); });

  fetch("/api/public/menu", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (m) {
    var count = (m.dhaba && m.dhaba.tableCount) || 1;
    var name = (m.dhaba && m.dhaba.dhabaName) || "Chandrika Dhaba & Restaurant";
    document.getElementById("info").textContent = "QR codes open: " + base + "/?table=NUMBER  —  " + count + " tables. Print this page and stick one QR on each table.";
    var sheet = document.getElementById("sheet");
    for (var i = 1; i <= count; i++) {
      var url = base + "/?table=" + i;
      var card = el("div", "qcard");
      card.appendChild(el("div", "nm", name));
      card.appendChild(el("div", "tag", "Scan to see our menu - your waiter will take the order"));
      var box = el("div", "qr");
      box.appendChild(qrSvg(url));
      card.appendChild(box);
      card.appendChild(el("div", "tno", "Table " + i));
      card.appendChild(el("div", "url", url));
      sheet.appendChild(card);
    }
  }).catch(function () {
    document.getElementById("info").textContent = "Could not load the table list. Is the server running?";
  });
})();
