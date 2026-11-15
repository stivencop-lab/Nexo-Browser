/*
 * Nexo Chromium: subconjunto de `browser.*` para los scripts de PhantomShield.
 *
 * Se evalúa dentro del mismo script que los scripts de contenido (o que el
 * panel), así que `browser` solo existe en su clausura. Habla con Nexo por el
 * objeto que inyecta WebView (`__nexoShield`), que se retira de `window` en
 * cuanto se captura. Nexo decide qué puede pedir cada origen: este código no
 * concede nada por sí mismo.
 */
function nexoPhantomShim(base, manifest) {
  'use strict';
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== 'function') return null;
  try { delete globalThis.__nexoShield; } catch (_) {}
  const pending = new Map();
  const storageListeners = [];
  const messageListeners = [];
  let seq = 0;

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
    const list = message.event === 'storage' ? storageListeners : message.event === 'message' ? messageListeners : null;
    if (!list) return;
    for (const listener of list.slice()) {
      try {
        if (message.event === 'storage') listener(message.changes || {}, 'local');
        else listener(message.message, {});
      } catch (_) {}
    }
  });

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
      sendMessage: function(_tabId, message) { return call('tabs.sendMessage', message); }
    }),
    permissions: Object.freeze({
      getAll: function() { return Promise.resolve({ origins: [], permissions: [] }); },
      request: function() { return Promise.resolve(false); }
    })
  });
}
