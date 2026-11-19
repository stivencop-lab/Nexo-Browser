/*
 * NXR Runtime Shim: construye un ámbito `chrome`/`browser` acotado para un
 * content script de extensión instalada en Nexo sobre Cefrium.
 *
 * Seguridad:
 * - No expone SecretStore, BrowserPrefs ni APIs nativas del navegador.
 * - Cada llamada `nxr_storage` / `nxr_message` / `nxr_error` lleva el token
 *   privado de la sesión (solo presente dentro de este cierre) y el `extId`;
 *   el lado nativo valida token, origen verificado por Chromium y permisos.
 * - No usa eval() ni Function().
 */
function createNxrScope(config) {
  'use strict';
  const extId = String(config && config.extId || '');
  const token = String(config && config.token || '');
  const manifest = (config && config.manifest && typeof config.manifest === 'object') ? config.manifest : {};
  const messages = (config && config.messages && typeof config.messages === 'object') ? config.messages : {};
  const queryFn = (config && typeof config.query === 'function')
    ? config.query
    : (typeof globalThis.cefriumQuery === 'function' ? globalThis.cefriumQuery : null);

  let nextCallId = 1;
  const messageListeners = [];

  function callNative(kind, payload) {
    return new Promise(function(resolve, reject) {
      if (!queryFn) {
        reject(new Error('Canal NXR no disponible.'));
        return;
      }
      const id = nextCallId++;
      const envelope = JSON.stringify({
        id: id,
        kind: kind,
        payload: Object.assign({}, payload || {}, { extId: extId, token: token })
      });
      try {
        queryFn({
          request: JSON.stringify({ m: envelope, s: -1 }),
          onSuccess: function(raw) {
            try {
              const parsed = typeof raw === 'string' && raw ? JSON.parse(raw) : null;
              const replyText = parsed && typeof parsed.reply === 'string' ? parsed.reply : null;
              const reply = replyText ? JSON.parse(replyText) : null;
              if (reply && reply.error) {
                reject(new Error(String(reply.error)));
              } else {
                resolve(reply ? reply.result : null);
              }
            } catch (err) {
              reject(err);
            }
          },
          onFailure: function(code, msg) {
            reject(new Error(String(msg || code || 'Error NXR')));
          }
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  function reportError(source, err) {
    const text = err && err.message ? String(err.message) : String(err || 'Error desconocido');
    callNative('nxr_error', { source: String(source || 'script'), message: text.slice(0, 400) }).catch(function() {});
  }

  function withOptionalCallback(promise, callback) {
    if (typeof callback === 'function') {
      promise.then(
        function(result) {
          runtime.lastError = null;
          try { callback(result); } catch (err) { reportError('callback', err); }
        },
        function(err) {
          runtime.lastError = { message: err && err.message ? String(err.message) : String(err) };
          try { callback(undefined); } catch (cbErr) { reportError('callback', cbErr); }
          runtime.lastError = null;
        }
      );
      return undefined;
    }
    return promise;
  }

  function unsupportedStub(apiName) {
    return function() {
      const msg = 'API no soportada por NXR sobre Cefrium: ' + apiName;
      reportError('unsupported_api', msg);
      return Promise.reject(new Error(msg));
    };
  }

  const storageLocal = Object.freeze({
    get: function(keys, callback) {
      let actualKeys = keys;
      let cb = callback;
      if (typeof keys === 'function') {
        cb = keys;
        actualKeys = null;
      }
      return withOptionalCallback(
        callNative('nxr_storage', { op: 'get', keys: actualKeys == null ? null : actualKeys }).then(function(r) {
          return (r && typeof r === 'object') ? r : {};
        }),
        cb
      );
    },
    set: function(items, callback) {
      if (!items || typeof items !== 'object') {
        return withOptionalCallback(Promise.resolve(), callback);
      }
      return withOptionalCallback(
        callNative('nxr_storage', { op: 'set', values: items }).then(function() { return undefined; }),
        callback
      );
    },
    remove: function(keys, callback) {
      return withOptionalCallback(
        callNative('nxr_storage', { op: 'remove', keys: keys }).then(function() { return undefined; }),
        callback
      );
    },
    clear: function(callback) {
      return withOptionalCallback(
        callNative('nxr_storage', { op: 'clear' }).then(function() { return undefined; }),
        callback
      );
    }
  });

  const runtime = {
    id: extId,
    lastError: null,
    getManifest: function() {
      return JSON.parse(JSON.stringify(manifest));
    },
    getURL: function(path) {
      return 'nxr://' + extId + '/' + String(path || '').replace(/^\/+/, '');
    },
    sendMessage: function(message, callback) {
      return withOptionalCallback(
        callNative('nxr_message', { message: message == null ? null : message }),
        callback
      );
    },
    onMessage: Object.freeze({
      addListener: function(fn) {
        if (typeof fn === 'function' && !messageListeners.includes(fn)) messageListeners.push(fn);
      },
      removeListener: function(fn) {
        const idx = messageListeners.indexOf(fn);
        if (idx >= 0) messageListeners.splice(idx, 1);
      },
      hasListener: function(fn) {
        return messageListeners.includes(fn);
      }
    })
  };

  const i18n = Object.freeze({
    getMessage: function(key) {
      const entry = messages[String(key || '')];
      return typeof entry === 'string' ? entry : '';
    },
    getUILanguage: function() {
      return (typeof navigator !== 'undefined' && navigator.language) ? navigator.language : 'es';
    }
  });

  const api = Object.freeze({
    runtime: runtime,
    storage: Object.freeze({ local: storageLocal }),
    i18n: i18n,
    tabs: Object.freeze({
      query: unsupportedStub('tabs.query'),
      create: unsupportedStub('tabs.create'),
      sendMessage: unsupportedStub('tabs.sendMessage')
    }),
    webRequest: Object.freeze({
      onBeforeRequest: Object.freeze({ addListener: unsupportedStub('webRequest.onBeforeRequest') })
    }),
    debugger: Object.freeze({ attach: unsupportedStub('debugger.attach') }),
    proxy: Object.freeze({ settings: Object.freeze({ set: unsupportedStub('proxy.settings.set') }) })
  });

  function dispatchIncoming(message) {
    let handled = false;
    for (const fn of messageListeners.slice()) {
      try {
        fn(message, { id: extId }, function() {});
        handled = true;
      } catch (err) {
        reportError('onMessage', err);
      }
    }
    return handled;
  }

  return Object.freeze({
    chrome: api,
    browser: api,
    reportError: reportError,
    dispatchIncoming: dispatchIncoming
  });
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { createNxrScope: createNxrScope };
}
