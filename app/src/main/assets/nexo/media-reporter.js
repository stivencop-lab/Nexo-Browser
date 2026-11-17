/*
 * Medios con sonido de la página para Nexo (Chromium/Cefrium): notificación
 * multimedia y reproducción en segundo plano. Gecko lo daba desde su
 * MediaSession; Chromium solo lo hace con su propia notificación, así que lo
 * informa la página: elementos <audio>/<video> audibles y
 * `navigator.mediaSession` (cubre el audio que no está en el documento). Un
 * video silenciado (portadas en bucle) no cuenta.
 *
 * Solo envía cambios. Un marco interno sin medios calla, para no borrar los
 * de otro marco. Privacidad: solo el título que la página da a su sesión
 * multimedia, como hacía Gecko; nunca la URL.
 *
 * El mini video y los subtítulos los hace el puente de Nexo
 * (extensions/nexo_bridge/content_script.js).
 */
(() => {
  "use strict";
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== "function") return;
  const isTop = window === window.top;
  const send = payload => { try { port.postMessage(JSON.stringify({ id: 0, kind: "bridge", payload })); } catch (_) {} };
  let last = "", hadMedia = false, scheduled = 0;

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

  function report(force) {
    const s = mediaState();
    if (!s.present && !isTop && !hadMedia) return;
    hadMedia = s.present;
    const key = JSON.stringify(s);
    if (!force && key === last) return;
    last = key;
    send({ type: "media_state", active: s.present, playing: s.playing, title: s.title });
  }

  function soon() {
    if (scheduled) return;
    scheduled = setTimeout(() => { scheduled = 0; report(false); }, 250);
  }

  port.addEventListener("message", e => {
    try {
      const m = JSON.parse(e.data);
      if (m.event === "bridge" && m.message && m.message.type === "lifecycle" && m.message.state === "foreground") report(true);
    } catch (_) {}
  });
  ["play", "pause", "playing", "ended", "emptied", "volumechange", "loadedmetadata"].forEach(e => document.addEventListener(e, soon, true));
  setInterval(() => report(false), 2000);
  report(false);
})();
