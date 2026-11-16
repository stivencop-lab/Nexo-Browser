/*
 * Nexo (Chromium/Cefrium): el puerto `__nexoShield` que esperan
 * `nexo-bridge.js` y `phantom-shim.js`, sobre `window.cefriumQuery`.
 *
 * Cada mensaje viaja como `{m, s}` y cada respuesta trae la contestación y
 * los eventos que Nexo publicó desde `s` (ver PageChannel.kt). El puerto se
 * define como propiedad configurable para que el shim lo retire de `window`
 * en cuanto lo captura. Nexo decide qué puede pedir cada origen: este código
 * no concede nada por sí mismo.
 */
function nexoCefriumPort(isTop) {
  'use strict';
  const query = globalThis.cefriumQuery;
  if (typeof query !== 'function') return false;
  const listeners = [];
  let seq = -1;
  let polling = false;

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
    if (typeof envelope.seq === 'number' && envelope.seq > seq) seq = envelope.seq;
    if (Array.isArray(envelope.events)) envelope.events.forEach(function(text) { if (typeof text === 'string') deliver(text); });
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
    }
  });
  Object.defineProperty(globalThis, '__nexoShield', { value: port, configurable: true, writable: true });
  if (isTop) {
    try { Object.defineProperty(globalThis, '__nexoWake', { value: poll, configurable: true }); } catch (_) {}
  }
  // Marcos visibles: cada segundo la página principal, cada tres los marcos internos.
  setInterval(function() {
    if (document.visibilityState !== 'hidden') poll();
  }, isTop ? 1000 : 3000);
  poll();
  return true;
}
