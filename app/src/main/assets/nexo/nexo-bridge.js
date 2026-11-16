/*
 * Puente de video de Nexo (Chromium/Cefrium): mini reproductor (PiP) y
 * subtítulos de la página. Habla con Nexo por el puerto `__nexoShield`
 * (cefrium-port.js) y solo envía cambios:
 * - estado del video: al cambiar, y mientras suena un latido cada 2 s (Nexo
 *   considera "reproducción reciente" 6 s); un marco interno sin video calla
 *   para no pisar el video de otro marco;
 * - subtítulos: la pista se anuncia una vez (anunciarla reinicia el flujo en
 *   Nexo), las cues solo las nuevas, las activas y el texto solo si cambian.
 * - medios con sonido (notificación y segundo plano): solo cambios.
 * Privacidad: nunca envía URL ni título de la página (solo el título que la
 * página da a su sesión multimedia, como hacía Gecko).
 */
(() => {
  "use strict";
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== "function") return;
  const isTop = window === window.top;
  const send = payload => { try { port.postMessage(JSON.stringify({ id: 0, kind: "bridge", payload })); return true; } catch (_) { return false; } };

  let pipVideo = null, pip = false, captions = false, track = null, trackIds = new WeakMap(), nextId = 1;
  let lastReport = "", lastReportAt = 0, hadVideo = false, scheduled = 0, lastMedia = "", hadMedia = false;
  let announced = "", sentCues = new Set(), lastActive = "", lastDomText = null;

  const captionSelectors = [".ytp-caption-segment", ".player-timedtext-text-container", ".vjs-text-track-display",
    ".jw-text-track-cue", ".shaka-text-container", ".plyr__caption"].join(",");
  const area = v => (v.videoWidth * v.videoHeight) || (v.clientWidth * v.clientHeight);
  const bestVideo = () => {
    let best = null;
    for (const v of document.querySelectorAll("video")) if (!best || area(v) > area(best)) best = v;
    return best;
  };
  const rect = v => {
    try {
      const r = v.getBoundingClientRect(), d = devicePixelRatio || 1;
      return { x: Math.round(r.left * d), y: Math.round(r.top * d), w: Math.round(r.width * d), h: Math.round(r.height * d) };
    } catch (_) { return null; }
  };

  function report(force) {
    const v = pipVideo && pipVideo.isConnected ? pipVideo : bestVideo();
    if (!v && !isTop && !hadVideo) return;
    hadVideo = !!v;
    const r = v && rect(v);
    const state = {
      type: "video_state", present: !!v, playing: !!v && !v.paused && !v.ended,
      width: v ? v.videoWidth || v.clientWidth : 0, height: v ? v.videoHeight || v.clientHeight : 0,
      rx: r ? r.x : 0, ry: r ? r.y : 0, rw: r ? r.w : 0, rh: r ? r.h : 0
    };
    const key = JSON.stringify(state), now = Date.now();
    if (!force && key === lastReport && !(state.playing && now - lastReportAt >= 2000)) return;
    lastReport = key;
    lastReportAt = now;
    send(state);
  }

  /**
   * Medios con sonido de la página, para la notificación y la reproducción en
   * segundo plano: elementos <audio>/<video> audibles y `navigator.mediaSession`
   * (cubre el audio que la página no pone en el documento). Un video silenciado
   * (portadas en bucle) no cuenta. El título es solo el que la página da a su
   * sesión multimedia.
   */
  function mediaState() {
    let present = false, playing = false;
    for (const m of document.querySelectorAll("video,audio")) {
      if (m.muted || m.volume === 0 || !(m.currentSrc || m.src)) continue;
      present = true;
      if (!m.paused && !m.ended) playing = true;
    }
    let title = "";
    try {
      const session = navigator.mediaSession;
      if (session) {
        if (session.playbackState === "playing") { present = true; playing = true; }
        else if (session.playbackState === "paused") present = true;
        if (present && session.metadata && session.metadata.title) title = String(session.metadata.title).slice(0, 200);
      }
    } catch (_) {}
    return { present, playing, title };
  }

  function reportMedia(force) {
    const s = mediaState();
    if (!s.present && !isTop && !hadMedia) return;
    hadMedia = s.present;
    const key = JSON.stringify(s);
    if (!force && key === lastMedia) return;
    lastMedia = key;
    send({ type: "media_state", active: s.present, playing: s.playing, title: s.title });
  }

  /** Eventos seguidos (desplazamiento, medios) se agrupan en un solo envío. */
  function soon() {
    if (scheduled) return;
    scheduled = setTimeout(() => { scheduled = 0; report(false); reportMedia(false); }, 250);
  }

  function announce(source, lang) {
    const key = source + "|" + lang;
    if (key === announced) return;
    announced = key;
    sentCues = new Set();
    lastActive = "";
    lastDomText = null;
    send({ type: "captions_track", source, lang });
  }

  function textCues(v) {
    const list = [];
    try {
      for (const t of v.textTracks || []) {
        if (t.kind !== "subtitles" && t.kind !== "captions") continue;
        if (!track) { track = t; try { t.mode = "hidden"; } catch (_) {} }
        for (const c of t.cues || []) {
          if (!trackIds.has(c)) trackIds.set(c, nextId++);
          list.push({ i: trackIds.get(c), s: c.startTime, e: c.endTime, t: String(c.text || "").replace(/<[^>]*>/g, "").trim().slice(0, 300) });
        }
      }
    } catch (_) {}
    return list;
  }

  function captionsTick() {
    if (!captions) return;
    const v = pipVideo && pipVideo.isConnected ? pipVideo : bestVideo();
    if (!v) return;
    const cues = textCues(v);
    if (cues.length) {
      announce("track", track && track.language || "");
      const fresh = cues.filter(c => !sentCues.has(c.i)).slice(0, 3000);
      if (fresh.length) {
        fresh.forEach(c => sentCues.add(c.i));
        send({ type: "captions_cues", cues: fresh });
      }
      const now = v.currentTime;
      const ids = cues.filter(c => c.s <= now && c.e > now).slice(0, 4).map(c => c.i);
      const active = ids.join(",");
      if (active !== lastActive) { lastActive = active; send({ type: "captions_active", ids }); }
      return;
    }
    const node = document.querySelector(captionSelectors);
    if (!node && lastDomText === null) return;
    announce("dom", "");
    const text = node ? node.textContent.trim().slice(0, 400) : "";
    if (text !== lastDomText) { lastDomText = text; send({ type: "captions_text", text }); }
  }

  function play(v) { try { const p = v.play(); if (p && p.catch) p.catch(() => {}); } catch (_) {} }

  function command(m) {
    if (m.type === "pip_mode") {
      pip = !!m.active;
      pipVideo = pip ? (pipVideo || bestVideo()) : (captions ? pipVideo : null);
      document.documentElement.toggleAttribute("data-nexo-pip-active", pip);
      report(true);
    } else if (m.type === "pip_command") {
      const v = pipVideo || bestVideo();
      if (!v) return;
      if (m.command === "pause") v.pause();
      else if (m.command === "play") play(v);
      else if (m.command === "toggle") { if (v.paused) play(v); else v.pause(); }
      else if (m.command === "seek_backward") v.currentTime = Math.max(0, v.currentTime - 10);
      else if (m.command === "seek_forward") v.currentTime += 10;
      report(true);
    } else if (m.type === "captions") {
      captions = !!m.active;
      announced = "";
      if (captions) { pipVideo = pipVideo || bestVideo(); captionsTick(); }
      else if (!pip) pipVideo = null;
    } else if (m.type === "captions_restart") {
      announced = "";
      captionsTick();
    } else if (m.type === "lifecycle" && m.state === "foreground") {
      report(true);
      reportMedia(true);
    }
  }

  port.addEventListener("message", e => {
    try { const m = JSON.parse(e.data); if (m.event === "native" && m.message) command(m.message); } catch (_) {}
  });
  ["play", "pause", "playing", "ended", "loadedmetadata", "canplay", "emptied", "volumechange"].forEach(e => document.addEventListener(e, soon, true));
  window.addEventListener("scroll", soon, { passive: true });
  setInterval(() => { report(false); reportMedia(false); captionsTick(); }, 1000);
  report(false);
  reportMedia(false);
})();
