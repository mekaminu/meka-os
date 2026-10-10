// MEKA's family page (build plan "Family sharing with Jeanette", slice 2).
// First open: the invite's token (in the link's #fragment, never sent in a URL) is exchanged for this browser's own
// key, a non-extractable P-256 key made here with WebCrypto and kept in IndexedDB. Every later request is signed with
// it exactly as MEKA's apps sign theirs: "MEKA1\nPOST\n<path>\n<time>\n<nonce>\n<sha256 of body>". So the link alone is
// no use once opened, and Meka can turn it off at any time. Only the shopping list is shared.
"use strict";
(function () {
  const DB = "meka-family", STORE = "keys", ME = "me";
  const $ = (id) => document.getElementById(id);
  const reduced = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const haptic = (ms) => { try { if (navigator.vibrate) navigator.vibrate(ms); } catch (_) {} };
  let me = null;          // { id, name, keys: CryptoKeyPair }
  let busy = false;

  // ---- IndexedDB: one record holding the key pair (the private key can't be read out, only used) ----
  function idb() {
    return new Promise((ok, fail) => {
      const r = indexedDB.open(DB, 1);
      r.onupgradeneeded = () => r.result.createObjectStore(STORE);
      r.onsuccess = () => ok(r.result);
      r.onerror = () => fail(r.error);
    });
  }
  async function load() {
    const db = await idb();
    return new Promise((ok, fail) => {
      const r = db.transaction(STORE).objectStore(STORE).get(ME);
      r.onsuccess = () => ok(r.result || null);
      r.onerror = () => fail(r.error);
    });
  }
  async function save(v) {
    const db = await idb();
    return new Promise((ok, fail) => {
      const t = db.transaction(STORE, "readwrite");
      t.objectStore(STORE).put(v, ME);
      t.oncomplete = () => ok();
      t.onerror = () => fail(t.error);
    });
  }

  // ---- Signing ----
  const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");
  const b64 = (buf) => btoa(String.fromCharCode.apply(null, new Uint8Array(buf)));
  async function sha256Hex(s) { return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))); }
  function nonce() { const b = new Uint8Array(16); crypto.getRandomValues(b); return hex(b); }

  async function post(path, payload, keys, guestId) {
    const body = JSON.stringify(payload || {});
    const time = String(Date.now()), n = nonce();
    const message = ["MEKA1", "POST", path, time, n, await sha256Hex(body)].join("\n");
    const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, keys.privateKey, new TextEncoder().encode(message));
    const headers = { "Content-Type": "application/json", "X-Meka-Time": time, "X-Meka-Nonce": n, "X-Meka-Signature": b64(sig) };
    if (guestId) headers["Authorization"] = "Guest " + guestId;
    const r = await fetch(path, { method: "POST", headers, body, credentials: "omit", cache: "no-store" });
    let data = null;
    try { data = await r.json(); } catch (_) {}
    return { status: r.status, data };
  }

  // ---- Screens ----
  function notice(text, critical) {
    $("loading").hidden = true;
    $("list").hidden = true;
    $("notice").hidden = false;
    $("notice").classList.toggle("critical", !!critical);
    $("notice-text").textContent = text;
    setLine("");
  }

  function setLine(text) {
    const el = $("line");
    if (el.textContent === text) return;
    if (reduced() || !el.textContent) { el.textContent = text; return; }
    el.classList.add("fading");
    setTimeout(() => { el.textContent = text; el.classList.remove("fading"); }, 160);
  }

  const SVG = "http://www.w3.org/2000/svg";
  function ring(done) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ring press" + (done ? " done" : "");
    b.setAttribute("aria-label", done ? "Put back" : "Got it");
    const svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", "0 0 30 30");
    const c = (cls) => { const e = document.createElementNS(SVG, "circle"); e.setAttribute("class", cls); e.setAttribute("cx", "15"); e.setAttribute("cy", "15"); e.setAttribute("r", "12"); return e; };
    svg.append(c("track"), c("fill"), c("sweep"));
    const tick = document.createElementNS(SVG, "path");
    tick.setAttribute("class", "tick");
    tick.setAttribute("d", "M10 15.5 L13.6 19 L20.5 11.5"); // short leg first
    svg.append(tick);
    b.append(svg);
    return b;
  }

  function row(item, got) {
    const li = document.createElement("li");
    li.className = "row";
    li.dataset.id = item.id;
    li.dataset.sig = item.title + "|" + (item.meta || "");
    const r = ring(got);
    r.addEventListener("click", () => tap(item.id, got, li, r));
    const text = document.createElement("div");
    text.className = "text";
    const t = document.createElement("div");
    t.className = "title";
    t.textContent = item.title;
    text.append(t);
    if (item.meta) {
      const m = document.createElement("div");
      m.className = "meta";
      m.textContent = item.meta;
      text.append(m);
    }
    li.append(r, text);
    return li;
  }

  // Keeps rows that didn't change (no flicker on refresh); new rows rise in, gone rows leave.
  function fill(ul, items, got) {
    const old = new Map(Array.from(ul.children).map((li) => [li.dataset.id, li]));
    const keep = new Set();
    const frag = [];
    items.forEach((item, i) => {
      const was = old.get(item.id);
      if (was && was.dataset.sig === item.title + "|" + (item.meta || "") && !was.classList.contains("leaving")) {
        keep.add(item.id);
        frag.push(was);
      } else {
        const li = row(item, got);
        if (!reduced()) { li.classList.add("entering"); li.style.transitionDelay = Math.min(i, 8) * 40 + "ms"; }
        frag.push(li);
      }
    });
    old.forEach((li, id) => { if (!keep.has(id)) li.remove(); });
    frag.forEach((li) => ul.append(li));
    requestAnimationFrame(() => requestAnimationFrame(() => {
      ul.querySelectorAll(".entering").forEach((li) => {
        li.classList.remove("entering");
        setTimeout(() => { li.style.transitionDelay = ""; }, 700);
      });
    }));
  }

  function show(v) {
    $("loading").hidden = true;
    $("notice").hidden = true;
    $("list").hidden = false;
    if (v.name) $("who").textContent = "MEKA · shared with " + v.name;
    setLine(v.line || "");
    fill($("to-buy"), v.toBuy || [], false);
    fill($("got"), v.got || [], true);
    $("empty").hidden = (v.toBuy || []).length > 0;
    $("got-title").hidden = (v.got || []).length === 0;
  }

  function refused(status, data) {
    const why = data && data.error;
    if (status === 401 && me) {
      notice("This page isn't open to this phone any more. Ask Meka for a new link.", true);
      return true;
    }
    if (why === "revoked") { notice("Meka has turned this link off. Ask him for a new one.", true); return true; }
    if (why === "used") { notice("This link has already been opened on another phone. Ask Meka for a new one.", true); return true; }
    if (why === "full") { setLine("The list is full. Tick a few things off first."); return true; }
    return false;
  }

  async function call(path, payload) {
    const r = await post(path, payload, me.keys, me.id);
    if (r.status === 200 && r.data) { show(r.data); return true; }
    if (!refused(r.status, r.data)) setLine("Couldn't reach MEKA. Try again in a moment.");
    return false;
  }

  async function refresh() {
    if (!me || busy || document.hidden) return;
    try { await call("/family/v1/shopping", {}); } catch (_) { setLine("Offline. The list will update when you're back."); }
  }

  async function tap(id, got, li, r) {
    if (busy) return;
    busy = true;
    haptic(got ? 6 : 12); // a tick when putting back, a light tap when got
    if (!got) {
      r.classList.add("drawing");
      requestAnimationFrame(() => r.classList.add("done"));
      await new Promise((ok) => setTimeout(ok, reduced() ? 0 : 420));
    }
    if (!reduced()) li.classList.add("leaving");
    try {
      await call(got ? "/family/v1/shopping/putback" : "/family/v1/shopping/got", { id });
    } catch (_) {
      li.classList.remove("leaving");
      r.classList.remove("done", "drawing");
      setLine("Couldn't reach MEKA. Try again in a moment.");
    } finally { busy = false; }
  }

  async function add(e) {
    e.preventDefault();
    const input = $("add-text");
    const text = input.value.trim();
    if (!text || busy || !me) return;
    busy = true;
    $("add-go").disabled = true;
    try {
      if (await call("/family/v1/shopping/add", { text })) { input.value = ""; haptic(12); }
    } catch (_) {
      setLine("Couldn't reach MEKA. Your words are still in the box.");
    } finally { busy = false; $("add-go").disabled = false; }
  }

  // ---- Start ----
  async function claim(token) {
    const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
    const publicKey = b64(await crypto.subtle.exportKey("spki", keys.publicKey));
    const r = await post("/family/v1/claim", { token, publicKey }, keys, null);
    if (r.status !== 200 || !r.data) {
      if (!refused(r.status, r.data)) notice("This link isn't valid. Ask Meka for a new one.", true);
      return null;
    }
    const v = { id: r.data.id, name: r.data.name, keys };
    await save(v);
    return v;
  }

  async function start() {
    if (!window.crypto || !crypto.subtle || !window.indexedDB) {
      notice("This browser can't keep the page private. Open the link in Safari or Chrome.", true);
      return;
    }
    const token = (location.hash || "").replace(/^#/, "");
    // Drop the token from the address bar and history at once, whatever happens next.
    if (token) history.replaceState(null, "", location.pathname);
    try {
      me = await load();
      if (!me) {
        if (!/^[0-9a-f]{64}$/.test(token)) { notice("Open the link Meka sent you to see the shopping list."); return; }
        me = await claim(token);
        if (!me) return;
        try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch (_) {}
      }
      await refresh();
    } catch (_) {
      notice("Couldn't reach MEKA. Check your connection and reload.", true);
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    $("add").addEventListener("submit", add);
    $("add-go").classList.add("press");
    document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
    setInterval(refresh, 20000);
    start();
  });
})();
