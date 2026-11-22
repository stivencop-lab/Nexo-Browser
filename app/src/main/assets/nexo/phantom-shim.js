/*
 * Nexo Chromium: subconjunto de `browser.*` para los scripts de PhantomShield.
 *
 * Se evalúa dentro del mismo script que los scripts de contenido (o que el
 * panel), así que `browser` solo existe en su clausura. Habla con Nexo por el
 * objeto que inyecta WebView (`__nexoShield`), que se retira de `window` en
 * cuanto se captura. Nexo decide qué puede pedir cada origen: este código no
 * concede nada por sí mismo.
 */
function nexoPhantomShim(base, manifest, isPanel) {
  'use strict';
  const jsonParse = JSON.parse;
  const jsonStringify = JSON.stringify;
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== 'function') return null;
  try { delete globalThis.__nexoShield; } catch (_) {}
  const pending = new Map();
  const storageListeners = [];
  const messageListeners = [];
  let seq = 0;

  port.addEventListener('message', function(event) {
    let message;
    try { message = jsonParse(event.data); } catch (_) { return; }
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
    } else if (message.event === 'message') {
      deliver(message.message, typeof message.rid === 'number' ? message.rid : null);
    }
  });

  /*
   * Mensaje de PhantomShield (Nexo) para los oyentes de `runtime.onMessage`.
   * Con `rid`, Nexo espera la respuesta del primer oyente que devuelve algo
   * (p. ej. «Analizar con IA» del panel).
   */
  async function deliver(payload, rid) {
    let result;
    for (const listener of messageListeners.slice()) {
      let value;
      try { value = listener(payload, { id: 'phantomshield' }); } catch (_) { continue; }
      if (value !== undefined && result === undefined) result = value;
    }
    if (rid === null) return;
    let settled = null;
    try { settled = await result; } catch (_) { settled = null; }
    call('reply', { rid: rid, result: settled === undefined ? null : settled }).catch(function() {});
  }

  function call(kind, payload) {
    return new Promise(function(resolve, reject) {
      const id = ++seq;
      pending.set(id, { resolve: resolve, reject: reject });
      try {
        port.postMessage(jsonStringify({ id: id, kind: kind, payload: payload === undefined ? null : payload }));
      } catch (error) {
        pending.delete(id);
        reject(error);
      }
    });
  }

  if (isPanel === true && typeof globalThis.window === 'object' && globalThis.window) {
    try {
      globalThis.window.close = function() { call('close', null).catch(function() {}); };
    } catch (_) {}
  }

  function event(list) {
    return {
      addListener: function(fn) { if (typeof fn === 'function' && !list.includes(fn)) list.push(fn); },
      removeListener: function(fn) { const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); },
      hasListener: function(fn) { return list.includes(fn); }
    };
  }

  return Object.freeze({
    runtime: Object.freeze({
      id: 'phantomshield',
      getURL: function(path) { return base + String(path || '').replace(/^\/+/, ''); },
      getManifest: function() { return manifest; },
      sendMessage: function(message) { return call('message', message); },
      openOptionsPage: function() { return call('openOptions', null); },
      onMessage: event(messageListeners)
    }),
    storage: Object.freeze({
      local: Object.freeze({
        get: function(keys) { return call('get', keys === undefined ? null : keys); },
        set: function(values) { return call('set', values).then(function() {}); },
        remove: function(keys) { return call('remove', keys).then(function() {}); }
      }),
      onChanged: event(storageListeners)
    }),
    tabs: Object.freeze({
      query: function() { return call('tabs.query', null); },
      reload: function(tabId) { return call('tabs.reload', tabId === undefined ? null : tabId).then(function() {}); },
      sendMessage: function(tabId, message) { return call('tabs.sendMessage', { tabId: tabId, message: message }); }
    }),
    permissions: Object.freeze({
      getAll: function() { return Promise.resolve({ origins: [], permissions: [] }); },
      request: function() { return Promise.resolve(false); }
    })
  });
}
