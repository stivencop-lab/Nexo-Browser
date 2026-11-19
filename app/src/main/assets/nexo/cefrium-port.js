/*
 * Nexo (Chromium/Cefrium): el puerto `__nexoShield` que esperan
 * `media-reporter.js`, `bridge-shim.js` (puente de Nexo) y `phantom-shim.js`,
 * sobre `window.cefriumQuery`.
 *
 * Cada mensaje viaja como `{m, s}` y cada respuesta trae la contestación y
 * los eventos que Nexo publicó desde `s` (ver PageChannel.kt). El puerto se
 * define como propiedad configurable para que el shim lo retire de `window`
 * en cuanto lo captura. Nexo decide qué puede pedir cada origen: este código
 * no concede nada por sí mismo.
 */
function nexoCefriumPort(isTop, options) {
  'use strict';
  const query = globalThis.cefriumQuery;
  if (typeof query !== 'function') return false;
  // Congelar cefriumQuery en document_start impide que scripts de la página la reemplacen para espiar tokens NXR.
  try {
    Object.defineProperty(globalThis, 'cefriumQuery', {
      value: query,
      writable: false,
      configurable: false
    });
  } catch (_) {}
  const alwaysPoll = Boolean(options && options.alwaysPoll);
  const pollMs = options && typeof options.pollMs === 'number' && options.pollMs > 0
    ? options.pollMs
    : (isTop ? 2000 : 1500);
  const listeners = [];
  const seen = Object.create(null);
  const seenOrder = [];
  let seq = -1;
  let polling = false;

  function markSeen(n) {
    if (seen[n]) return false;
    seen[n] = true;
    seenOrder.push(n);
    if (seenOrder.length > 256) delete seen[seenOrder.shift()];
    return true;
  }

  function deliver(text) {
    for (const fn of listeners.slice()) {
      try { fn({ data: text }); } catch (_) {}
    }
  }

  function handle(raw) {
    if (typeof raw !== 'string' || !raw) return;
    let envelope;
    try { envelope = JSON.parse(raw); } catch (_) { return; }
    if (!envelope || typeof envelope !== 'object') return;
    const hasSeq = typeof envelope.seq === 'number';
    if (hasSeq && envelope.seq > seq) seq = envelope.seq;
    if (Array.isArray(envelope.events)) {
      const base = hasSeq ? envelope.seq - envelope.events.length + 1 : -1;
      envelope.events.forEach(function(text, index) {
        if (typeof text !== 'string') return;
        if (hasSeq && !markSeen(base + index)) return;
        deliver(text);
      });
    }
    if (typeof envelope.reply === 'string') deliver(envelope.reply);
  }

  function ask(message, done) {
    try {
      query({
        request: JSON.stringify({ m: message, s: seq }),
        onSuccess: function(response) { if (done) done(); handle(response); },
        onFailure: function() { if (done) done(); }
      });
      return true;
    } catch (_) {
      if (done) done();
      return false;
    }
  }

  function poll() {
    if (polling) return;
    polling = true;
    if (!ask(null, function() { polling = false; })) polling = false;
  }

  const port = Object.freeze({
    postMessage: function(text) {
      if (!ask(String(text), null)) throw new Error('Nexo no está disponible');
    },
    addEventListener: function(type, fn) {
      if (type === 'message' && typeof fn === 'function' && !listeners.includes(fn)) listeners.push(fn);
    },
    wake: poll
  });
  Object.defineProperty(globalThis, '__nexoShield', { value: port, configurable: true, writable: true });
  if (isTop) {
    try { Object.defineProperty(globalThis, '__nexoWake', { value: poll, configurable: true }); } catch (_) {}
  }
  // Respaldo: Nexo despierta al principal (__nexoWake); el anfitrión oculto
  // consulta incluso en segundo plano (alwaysPoll) y los iframes cada 1,5 s.
  setInterval(function() {
    if (alwaysPoll || (typeof document !== 'undefined' && document.visibilityState !== 'hidden')) poll();
  }, pollMs);
  poll();
  return true;
}
