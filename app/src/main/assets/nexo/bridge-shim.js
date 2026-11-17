/*
 * Nexo (Chromium/Cefrium): `browser.runtime` mínimo para el puente de Nexo
 * (`extensions/nexo_bridge/content_script.js`, el mismo script que con
 * Gecko), sobre el puerto `__nexoShield` de cefrium-port.js.
 *
 * Con Gecko cada marco tenía su puerto nativo. Aquí cada marco lleva un token
 * aleatorio: Nexo lo usa para conceder los subtítulos a un solo marco y para
 * enviarle mensajes solo a él (`to`), a todos o al principal (`to: "top"`).
 * Las llamadas con respuesta usan ids negativos, distintos de los de
 * phantom-shim.js, que comparte el puerto. Este código no concede nada: Nexo
 * decide qué acepta de cada marco (PageBridge.kt).
 */
function nexoBridgeShim(port, isTop) {
  'use strict';
  if (!port || typeof port.postMessage !== 'function' || typeof port.addEventListener !== 'function') return null;

  function randomToken() {
    try {
      const values = new Uint32Array(4);
      crypto.getRandomValues(values);
      return Array.from(values, function(n) { return n.toString(36); }).join('');
    } catch (_) {
      return (Math.random().toString(36) + Date.now().toString(36)).replace(/[^a-z0-9]/g, '').slice(0, 24);
    }
  }

  const frame = randomToken().slice(0, 48).padEnd(8, '0');
  // Base aleatoria: dos marcos nunca esperan la misma respuesta diferida.
  let seq = Math.floor(Math.random() * 1e9);
  const pending = new Map();
  const messageListeners = [];
  const disconnectListeners = [];

  function send(kind, payload, id) {
    port.postMessage(JSON.stringify({ id: id || 0, kind: kind, payload: payload, frame: frame }));
  }

  port.addEventListener('message', function(event) {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (!message || typeof message !== 'object') return;
    if (typeof message.id === 'number' && message.id < 0) {
      const entry = pending.get(message.id);
      if (!entry) return;
      pending.delete(message.id);
      if (typeof message.error === 'string') entry.reject(new Error(message.error));
      else entry.resolve(message.result);
      return;
    }
    if (message.event !== 'bridge' || !message.message || typeof message.message !== 'object') return;
    const to = message.to;
    if (to === 'top' ? !isTop : (typeof to === 'string' && to !== frame)) return;
    for (const listener of messageListeners.slice()) {
      try { listener(message.message); } catch (_) {}
    }
  });

  function call(payload) {
    return new Promise(function(resolve, reject) {
      const id = -(++seq);
      pending.set(id, { resolve: resolve, reject: reject });
      try { send('bridge_call', payload, id); } catch (error) { pending.delete(id); reject(error); }
    });
  }

  function event(list) {
    return Object.freeze({
      addListener: function(fn) { if (typeof fn === 'function' && !list.includes(fn)) list.push(fn); },
      removeListener: function(fn) { const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); },
      hasListener: function(fn) { return list.includes(fn); }
    });
  }

  const nativePort = Object.freeze({
    name: 'browser',
    postMessage: function(message) {
      if (!message || typeof message !== 'object') return;
      send('bridge', message);
    },
    disconnect: function() {},
    onMessage: event(messageListeners),
    onDisconnect: event(disconnectListeners)
  });

  return Object.freeze({
    runtime: Object.freeze({
      // Un solo canal por marco: volver a conectar devuelve el mismo puerto.
      connectNative: function() { return nativePort; },
      sendNativeMessage: function(_app, message) { return call(message); },
      sendMessage: function(message) { return call(message); }
    })
  });
}
