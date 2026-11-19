/*
 * Nexo (Chromium/Cefrium): `browser.*` del anfitrión de IA de PhantomShield.
 *
 * El anfitrión es una página oculta de Nexo (`host/ai-host.html`, en el
 * origen propio del panel) que ejecuta el clasificador de IA original
 * (`data/ai-config.js`, `background/ai-memory.js`, `ai-domains.js`,
 * `ai-classifier.js`). Habla con Nexo por el puerto `__nexoShield`
 * (cefrium-port.js):
 *
 * - `storage.local` y `storage.onChanged`: el mismo almacén que el panel.
 * - `runtime.onMessage`: peticiones que Nexo ya autorizó (de una página o
 *   del panel); la respuesta vuelve con `reply`.
 * - `fetch`: solo las llamadas a la API de IA, hechas por Nexo (HTTPS, sin
 *   cookies, sin redirecciones). Así no depende de CORS de cada proveedor.
 *
 * Este código no concede nada: Nexo decide qué acepta (PhantomShield.kt).
 */
function nexoAiHostShim(base, manifest) {
  'use strict';
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== 'function') return null;
  try { delete globalThis.__nexoShield; } catch (_) {}
  const pending = new Map();
  const storageListeners = [];
  const messageListeners = [];
  let seq = 0;

  function call(kind, payload) {
    return new Promise(function(resolve, reject) {
      const id = ++seq;
      pending.set(id, { resolve: resolve, reject: reject });
      try {
        port.postMessage(JSON.stringify({ id: id, kind: kind, payload: payload === undefined ? null : payload }));
      } catch (error) {
        pending.delete(id);
        reject(error);
      }
    });
  }

  /** Petición de Nexo: el primer oyente que responde decide; sin respuesta, `undefined`. */
  async function dispatch(rid, message, sender) {
    let result;
    for (const listener of messageListeners.slice()) {
      let value;
      try { value = listener(message, sender || {}); } catch (_) { continue; }
      if (value !== undefined) { result = value; break; }
    }
    let settled = null;
    try { settled = await result; } catch (error) {
      settled = { ok: false, error: error && error.message ? String(error.message) : 'Error de IA' };
    }
    call('reply', { rid: rid, result: settled === undefined ? null : settled }).catch(function() {});
  }

  port.addEventListener('message', function(event) {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (!message || typeof message !== 'object') return;
    if (typeof message.id === 'number' && pending.has(message.id)) {
      const entry = pending.get(message.id);
      pending.delete(message.id);
      if (typeof message.error === 'string') entry.reject(new Error(message.error));
      else entry.resolve(message.result);
      return;
    }
    if (message.event === 'storage') {
      for (const listener of storageListeners.slice()) {
        try { listener(message.changes || {}, 'local'); } catch (_) {}
      }
    } else if (message.event === 'request' && typeof message.rid === 'number') {
      dispatch(message.rid, message.message, message.sender);
    }
  });

  function event(list) {
    return {
      addListener: function(fn) { if (typeof fn === 'function' && !list.includes(fn)) list.push(fn); },
      removeListener: function(fn) { const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); },
      hasListener: function(fn) { return list.includes(fn); }
    };
  }

  function abortError() {
    try { return new DOMException('Aborted', 'AbortError'); } catch (_) { return new Error('Aborted'); }
  }

  /** `fetch` del clasificador: lo hace Nexo. Solo POST HTTPS con cuerpo de texto. */
  function nativeFetch(input, init) {
    init = init || {};
    const url = String(input && input.url ? input.url : input);
    const signal = init.signal;
    if (signal && signal.aborted) return Promise.reject(abortError());
    const headers = {};
    const source = init.headers || {};
    if (typeof source.forEach === 'function' && !Array.isArray(source)) source.forEach(function(value, key) { headers[key] = String(value); });
    else Object.keys(source).forEach(function(key) { headers[key] = String(source[key]); });
    const request = call('fetch', {
      url: url, method: String(init.method || 'GET').toUpperCase(), headers: headers,
      body: typeof init.body === 'string' ? init.body : null
    }).then(function(reply) {
      if (!reply || typeof reply.status !== 'number') throw new TypeError('Failed to fetch');
      const responseHeaders = new Headers();
      if (reply.headers && typeof reply.headers === 'object') {
        Object.keys(reply.headers).forEach(function(key) {
          try { responseHeaders.set(key, String(reply.headers[key])); } catch (_) {}
        });
      }
      return new Response(reply.status === 204 || reply.status === 304 ? null : String(reply.body || ''),
        { status: reply.status, headers: responseHeaders });
    }, function(err) {
      if (err && err.message === 'timeout') {
        const timeoutErr = new Error('Timeout');
        timeoutErr.code = 'timeout';
        throw timeoutErr;
      }
      throw new TypeError('Failed to fetch');
    });
    if (!signal) return request;
    return new Promise(function(resolve, reject) {
      const onAbort = function() { reject(abortError()); };
      signal.addEventListener('abort', onAbort, { once: true });
      request.then(resolve, reject).finally(function() { signal.removeEventListener('abort', onAbort); });
    });
  }
  try { Object.defineProperty(globalThis, 'fetch', { value: nativeFetch, configurable: true, writable: true }); } catch (_) {}

  return Object.freeze({
    runtime: Object.freeze({
      id: 'phantomshield',
      getURL: function(path) { return base + String(path || '').replace(/^\/+/, ''); },
      getManifest: function() { return manifest; },
      // El anfitrión no habla con otras páginas; los avisos los da Nexo.
      sendMessage: function() { return Promise.resolve(undefined); },
      onMessage: event(messageListeners),
      /** El anfitrión cargó sus scripts: Nexo puede enviarle peticiones. */
      ready: function() { return call('ready', null); }
    }),
    storage: Object.freeze({
      local: Object.freeze({
        get: function(keys) { return call('get', keys === undefined ? null : keys); },
        set: function(values) { return call('set', values).then(function() {}); },
        remove: function(keys) { return call('remove', keys).then(function() {}); }
      }),
      onChanged: event(storageListeners)
    }),
    permissions: Object.freeze({
      getAll: function() { return Promise.resolve({ origins: [], permissions: [] }); },
      request: function() { return Promise.resolve(false); },
      onRemoved: event([])
    })
  });
}
