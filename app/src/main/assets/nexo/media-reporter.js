/*
 * Medios con sonido de la página para Nexo (Chromium/Cefrium): notificación
 * multimedia, controles del sistema (pantalla de bloqueo, panel rápido, isla
 * dinámica / Now Bar) y reproducción en segundo plano. Chromium solo lo
 * informa con su propia notificación (que duplicaría la de Nexo), así que lo
 * informa la página: elementos <audio>/<video> audibles y
 * `navigator.mediaSession` (cubre el audio que no está en el documento). Un
 * video silenciado (portadas en bucle) no cuenta.
 *
 * Además de reproducir/pausar (que hace Chromium), atiende desde Nexo: saltar
 * a una posición, ±N segundos y pista siguiente/anterior. Usa los manejadores
 * que la propia página registró en `navigator.mediaSession` cuando existen, y
 * si no actúa sobre el elemento.
 *
 * Solo envía cambios (la posición no cuenta: Android la extrapola). Un marco
 * interno sin medios calla, para no borrar los de otro marco. Privacidad: solo
 * los metadatos que la página da a su sesión multimedia y una carátula https;
 * nunca la URL de la página.
 *
 * El mini video y los subtítulos los hace el puente de Nexo
 * (bridge/page-bridge.js).
 */
(() => {
  "use strict";
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== "function") return;
  const isTop = window === window.top;
  const send = payload => { try { port.postMessage(JSON.stringify({ id: 0, kind: "bridge", payload })); } catch (_) {} };
  let last = "", hadMedia = false, scheduled = 0;

  // Manejadores que registra la página (para siguiente/anterior y búsquedas propias).
  const handlers = new Map();
  let sitePosition = null;
  try {
    const session = navigator.mediaSession;
    if (session && typeof session.setActionHandler === "function") {
      const original = session.setActionHandler.bind(session);
      session.setActionHandler = function (action, handler) {
        try {
          if (typeof handler === "function") handlers.set(String(action), handler);
          else handlers.delete(String(action));
        } catch (_) {}
        soon();
        return original(action, handler);
      };
    }
    if (session && typeof session.setPositionState === "function") {
      const originalPos = session.setPositionState.bind(session);
      session.setPositionState = function (state) {
        try {
          sitePosition = state && typeof state === "object"
            ? { duration: Number(state.duration), position: Number(state.position), rate: Number(state.playbackRate || 1), at: Date.now() }
            : null;
        } catch (_) {}
        soon();
        return originalPos(state);
      };
    }
  } catch (_) {}

  function audible(m) {
    return !(m.muted || m.volume === 0 || !(m.currentSrc || m.src));
  }

  /** El elemento que suena (o el último audible si está en pausa). */
  function primary() {
    let paused = null;
    for (const m of document.querySelectorAll("video,audio")) {
      if (!audible(m)) continue;
      if (!m.paused && !m.ended) return m;
      if (!paused) paused = m;
    }
    return paused;
  }

  function artworkUrl(metadata) {
    try {
      let best = "", bestScore = -1;
      for (const art of Array.from(metadata.artwork || [])) {
        if (!art || !art.src) continue;
        let url;
        try { url = new URL(String(art.src), location.href); } catch (_) { continue; }
        if (url.protocol !== "https:" || url.username || url.password) continue;
        let size = 0;
        for (const part of String(art.sizes || "").split(/\s+/)) {
          const m = /^(\d+)x(\d+)$/i.exec(part);
          if (m) size = Math.max(size, Number(m[1]) * Number(m[2]));
        }
        // La mayor hasta 1024×1024; por encima, la más pequeña (no mejora la notificación).
        const cap = 1048576;
        const score = size > cap ? cap - size / cap : size;
        if (score > bestScore) { best = url.href; bestScore = score; }
      }
      return best.slice(0, 2048);
    } catch (_) {
      return "";
    }
  }

  const clip = (value, max) => String(value || "").slice(0, max);
  const finite = (value, fallback) => (Number.isFinite(value) ? value : fallback);

  function mediaState() {
    let present = false, playing = false;
    for (const m of document.querySelectorAll("video,audio")) {
      if (!audible(m)) continue;
      present = true;
      if (!m.paused && !m.ended) playing = true;
    }
    let title = "", artist = "", album = "", artwork = "";
    try {
      const session = navigator.mediaSession;
      if (session) {
        if (session.playbackState === "playing") { present = true; playing = true; }
        else if (session.playbackState === "paused") present = true;
        if (present && session.metadata) {
          title = clip(session.metadata.title, 200);
          artist = clip(session.metadata.artist, 200);
          album = clip(session.metadata.album, 200);
          artwork = artworkUrl(session.metadata);
        }
      }
    } catch (_) {}
    const el = present ? primary() : null;
    let duration = 0, position = 0, rate = 1;
    if (el) {
      duration = finite(Number(el.duration), 0);
      position = finite(Number(el.currentTime), 0);
      rate = finite(Number(el.playbackRate), 1) || 1;
    }
    // La posición que declara la página manda (reproductores con MSE o anuncios).
    if (sitePosition && Number.isFinite(sitePosition.duration) && sitePosition.duration > 0) {
      duration = sitePosition.duration;
      const elapsed = playing ? (Date.now() - sitePosition.at) / 1000 * (sitePosition.rate || 1) : 0;
      position = Math.min(duration, finite(sitePosition.position, 0) + elapsed);
      rate = finite(sitePosition.rate, rate) || 1;
    }
    if (!(duration > 0) || duration === Infinity) duration = 0;
    const seek = duration > 0 && (handlers.has("seekto") || !!el);
    return {
      present, playing, title, artist, album, artwork,
      duration: Math.round(duration * 10) / 10,
      position: Math.round(Math.max(0, position) * 10) / 10,
      rate: Math.round(rate * 100) / 100,
      next: handlers.has("nexttrack"),
      prev: handlers.has("previoustrack"),
      seek,
      video: !!(el && el.tagName === "VIDEO")
    };
  }

  function report(force) {
    const s = mediaState();
    if (!s.present && !isTop && !hadMedia) return;
    hadMedia = s.present;
    // La posición avanza sola: no cuenta como cambio.
    const key = JSON.stringify([s.present, s.playing, s.title, s.artist, s.album, s.artwork, s.duration, s.rate, s.next, s.prev, s.seek, s.video]);
    if (!force && key === last) return;
    last = key;
    const payload = Object.assign({ type: "media_state", active: s.present }, s);
    delete payload.present;
    send(payload);
  }

  function soon(force) {
    if (force) { clearTimeout(scheduled); scheduled = 0; }
    if (scheduled) return;
    scheduled = setTimeout(() => { scheduled = 0; report(force === true); }, force ? 60 : 250);
  }

  function callHandler(action, details) {
    const handler = handlers.get(action);
    if (!handler) return false;
    try { handler(Object.assign({ action }, details || {})); return true; } catch (_) { return false; }
  }

  function seekElement(el, time) {
    try {
      if (el.seekable && el.seekable.length) {
        const start = el.seekable.start(0);
        const end = el.seekable.end(el.seekable.length - 1);
        el.currentTime = Math.max(start, Math.min(end, time));
      } else {
        const dur = Number(el.duration);
        el.currentTime = Number.isFinite(dur) && dur > 0 ? Math.max(0, Math.min(dur, time)) : Math.max(0, time);
      }
    } catch (_) {}
  }

  function command(message) {
    if (!hadMedia) return;
    const action = String(message.action || "");
    const el = primary();
    if (action === "seekto") {
      const time = Math.max(0, Number(message.time) || 0);
      if (!callHandler("seekto", { seekTime: time, fastSeek: false }) && el) seekElement(el, time);
    } else if (action === "seekby") {
      const delta = Math.max(-600, Math.min(600, Number(message.delta) || 0));
      if (!delta) return;
      const name = delta > 0 ? "seekforward" : "seekbackward";
      if (!callHandler(name, { seekOffset: Math.abs(delta) }) && el) seekElement(el, (Number(el.currentTime) || 0) + delta);
    } else if (action === "nexttrack" || action === "previoustrack") {
      callHandler(action);
    } else if (action === "play" || action === "pause") {
      if (!callHandler(action) && el) {
        try { if (action === "play") el.play().catch(() => {}); else el.pause(); } catch (_) {}
      }
    }
    soon(true);
  }

  port.addEventListener("message", e => {
    try {
      const m = JSON.parse(e.data);
      if (m.event !== "bridge" || !m.message) return;
      if (m.message.type === "lifecycle" && m.message.state === "foreground") report(true);
      else if (m.message.type === "media_session_command") command(m.message);
    } catch (_) {}
  });
  ["play", "pause", "playing", "ended", "emptied", "volumechange", "loadedmetadata", "durationchange", "ratechange"]
    .forEach(e => document.addEventListener(e, () => soon(false), true));
  // Tras saltar, Android necesita la posición nueva aunque nada más cambie.
  document.addEventListener("seeked", () => soon(true), true);
  setInterval(() => report(false), 2000);
  report(false);
})();
