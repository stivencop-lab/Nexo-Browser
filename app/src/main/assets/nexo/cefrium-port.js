/*
 * Nexo (Chromium/Cefrium): el puerto `__nexoShield` que esperan
 * `media-reporter.js`, `bridge-shim.js` (puente de Nexo) y `phantom-shim.js`,
 * sobre `window.cefriumQuery` con respuestas asíncronas.
 *
 * - Llamadas RPC (`{m, s}`): Nexo responde al instante o conserva el callback
 *   de `cefriumQuery` y resuelve cuando termina el trabajo asíncrono.
 * - Eventos push (`{m: null, s, w: true, k}`): una sola consulta aparcada por
 *   marco que Nexo resuelve en cuanto publica un evento nuevo (sin `setInterval`
 *   ni sondeos periódicos, también en iframes y en el anfitrión oculto de IA).
 *
 * El puerto se define como propiedad configurable para que el shim lo retire de
 * `window` en cuanto lo captura. Nexo decide qué puede pedir cada origen: este
 * código no concede nada por sí mismo.
 */
function nexoCefriumPort(isTop, _options) {
  'use strict';
  const query = globalThis.cefriumQuery;
  if (typeof query !== 'function') return false;
  const jsonParse = JSON.parse;
  const jsonStringify = JSON.stringify;
  const createNullObj = Object.create;
  // Congelar cefriumQuery en document_start impide que scripts de la página la reemplacen para espiar tokens NXR.
  try {
    Object.defineProperty(globalThis, 'cefriumQuery', {
      value: query,
      writable: false,
      configurable: false
    });
  } catch (_) {}

  function randomKey() {
    try {
      if (typeof crypto !== 'undefined' && crypto && typeof crypto.getRandomValues === 'function') {
        const values = new Uint32Array(3);
        crypto.getRandomValues(values);
        return Array.from(values, function(n) { return n.toString(36); }).join('').slice(0, 24);
      }
    } catch (_) {}
    return (Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)).slice(0, 20) || 'frame';
  }

  const waiterKey = (isTop ? 't_' : 'f_') + randomKey();
  const listeners = [];
  const seen = createNullObj(null);
  const seenOrder = [];
  let seq = -1;
  let listening = false;
  let stopped = false;
  let failures = 0;

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
    try { envelope = jsonParse(raw); } catch (_) { return; }
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
      const req = createNullObj(null);
      req.m = message;
      req.s = seq;
      const opts = createNullObj(null);
      opts.request = jsonStringify(req);
      opts.onSuccess = function(response) { if (done) done(); handle(response); };
      opts.onFailure = function() { if (done) done(); };
      query(opts);
      return true;
    } catch (_) {
      if (done) done();
      return false;
    }
  }

  function scheduleListen() {
    if (stopped || listening) return;
    if (typeof Promise === 'function') {
      Promise.resolve().then(listen);
    } else if (typeof setTimeout === 'function') {
      setTimeout(listen, 0);
    }
  }

  function listen() {
    if (stopped || listening) return;
    listening = true;
    try {
      const req = createNullObj(null);
      req.m = null;
      req.s = seq;
      req.w = true;
      req.k = waiterKey;
      const opts = createNullObj(null);
      opts.request = jsonStringify(req);
      opts.onSuccess = function(response) {
        listening = false;
        failures = 0;
        handle(response);
        scheduleListen();
      };
      opts.onFailure = function(code) {
        listening = false;
        // -403 = QUERY_ERROR_ORIGIN_DENIED: no reintentar si el origen está denegado.
        if (code === -403) {
          stopped = true;
          return;
        }
        failures++;
        if (failures > 8 || typeof setTimeout !== 'function') {
          stopped = true;
          return;
        }
        const delay = Math.min(250 * (1 << (failures - 1)), 4000);
        setTimeout(listen, delay);
      };
      query(opts);
    } catch (_) {
      listening = false;
      stopped = true;
    }
  }

  const port = Object.freeze({
    postMessage: function(text) {
      if (!ask(String(text), null)) throw new Error('Nexo no está disponible');
    },
    addEventListener: function(type, fn) {
      if (type === 'message' && typeof fn === 'function' && !listeners.includes(fn)) listeners.push(fn);
    },
    wake: function() { if (!stopped) listen(); }
  });
  Object.defineProperty(globalThis, '__nexoShield', { value: port, configurable: true, writable: true });
  listen();
  return true;
}
