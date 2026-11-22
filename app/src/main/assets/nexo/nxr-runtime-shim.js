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
  const baseUrl = (config && typeof config.baseUrl === 'string' && config.baseUrl) ? config.baseUrl.replace(/\/+$/, '') + '/' : ('nxr://' + extId + '/');
  const manifest = (config && config.manifest && typeof config.manifest === 'object') ? config.manifest : {};
  const messages = (config && config.messages && typeof config.messages === 'object') ? config.messages : {};
  const queryFn = (config && typeof config.query === 'function')
    ? config.query
    : (typeof globalThis.cefriumQuery === 'function' ? globalThis.cefriumQuery : null);
  const jsonStringify = JSON.stringify;
  const jsonParse = JSON.parse;
  const objectAssign = Object.assign;
  const createNullObj = Object.create;
  const MAX_ENVELOPE_CHARS = 32 * 1024;

  let nextCallId = 1;
  const messageListeners = [];
  const connectListeners = [];
  const installedListeners = [];
  const actionClickedListeners = [];
  const storageChangedListeners = [];
  const sessionStore = createNullObj(null);

  function callNative(kind, payload) {
    return new Promise(function(resolve, reject) {
      if (!queryFn) {
        reject(new Error('Canal NXR no disponible.'));
        return;
      }
      const id = nextCallId++;
      let envelope;
      try {
        const inner = objectAssign(createNullObj(null), payload || {});
        inner.extId = extId;
        inner.token = token;
        const msg = createNullObj(null);
        msg.id = id;
        msg.kind = kind;
        msg.payload = inner;
        envelope = jsonStringify(msg);
      } catch (err) {
        reject(err);
        return;
      }
      if (envelope.length > MAX_ENVELOPE_CHARS) {
        reject(new Error('Mensaje NXR demasiado grande.'));
        return;
      }
      try {
        const req = createNullObj(null);
        req.m = envelope;
        req.s = -1;
        const opts = createNullObj(null);
        opts.request = jsonStringify(req);
        opts.onSuccess = function(raw) {
          try {
            const parsed = typeof raw === 'string' && raw ? jsonParse(raw) : null;
            const replyText = parsed && typeof parsed.reply === 'string' ? parsed.reply : null;
            const reply = replyText ? jsonParse(replyText) : null;
            if (reply && reply.error) {
              reject(new Error(String(reply.error)));
            } else {
              resolve(reply ? reply.result : null);
            }
          } catch (err) {
            reject(err);
          }
        };
        opts.onFailure = function(code, msg) {
          reject(new Error(String(msg || code || 'Error NXR')));
        };
        queryFn(opts);
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

  function makeEventTarget(list) {
    return Object.freeze({
      addListener: function(fn) {
        if (typeof fn === 'function' && !list.includes(fn)) list.push(fn);
      },
      removeListener: function(fn) {
        const idx = list.indexOf(fn);
        if (idx >= 0) list.splice(idx, 1);
      },
      hasListener: function(fn) {
        return list.includes(fn);
      },
      hasListeners: function() {
        return list.length > 0;
      }
    });
  }

  function emitStorageChanges(changes, areaName) {
    if (!changes || Object.keys(changes).length === 0) return;
    for (const fn of storageChangedListeners.slice()) {
      try {
        fn(changes, areaName);
      } catch (err) {
        reportError('storage.onChanged', err);
      }
    }
  }

  function unsupportedStub(apiName) {
    return function() {
      const msg = 'API no soportada por NXR sobre Cefrium: ' + apiName;
      reportError('unsupported_api', msg);
      return Promise.reject(new Error(msg));
    };
  }

  function createPersistentStorageArea(areaName) {
    const isSync = areaName === 'sync';
    return Object.freeze({
      QUOTA_BYTES: isSync ? 102400 : 4194304,
      QUOTA_BYTES_PER_ITEM: isSync ? 8192 : 524288,
      MAX_ITEMS: isSync ? 512 : 2048,
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
          callNative('nxr_storage', { op: 'set', values: items }).then(function() {
            const changes = {};
            for (const k of Object.keys(items)) {
              changes[k] = { newValue: items[k] };
            }
            emitStorageChanges(changes, areaName);
            return undefined;
          }),
          callback
        );
      },
      remove: function(keys, callback) {
        return withOptionalCallback(
          callNative('nxr_storage', { op: 'remove', keys: keys }).then(function() {
            const keyList = Array.isArray(keys) ? keys : (typeof keys === 'string' ? [keys] : []);
            const changes = {};
            for (const k of keyList) changes[k] = {};
            emitStorageChanges(changes, areaName);
            return undefined;
          }),
          callback
        );
      },
      clear: function(callback) {
        return withOptionalCallback(
          callNative('nxr_storage', { op: 'clear' }).then(function() { return undefined; }),
          callback
        );
      },
      getBytesInUse: function(keys, callback) {
        const cb = typeof keys === 'function' ? keys : callback;
        return withOptionalCallback(Promise.resolve(0), cb);
      }
    });
  }

  const storageLocal = createPersistentStorageArea('local');
  const storageSync = createPersistentStorageArea('sync');

  const storageSession = Object.freeze({
    QUOTA_BYTES: 1048576,
    get: function(keys, callback) {
      let actualKeys = keys;
      let cb = callback;
      if (typeof keys === 'function') {
        cb = keys;
        actualKeys = null;
      }
      const out = {};
      if (actualKeys == null) {
        Object.assign(out, sessionStore);
      } else if (typeof actualKeys === 'string') {
        if (Object.prototype.hasOwnProperty.call(sessionStore, actualKeys)) out[actualKeys] = sessionStore[actualKeys];
      } else if (Array.isArray(actualKeys)) {
        for (const k of actualKeys) {
          if (Object.prototype.hasOwnProperty.call(sessionStore, k)) out[k] = sessionStore[k];
        }
      } else if (typeof actualKeys === 'object') {
        for (const k of Object.keys(actualKeys)) {
          out[k] = Object.prototype.hasOwnProperty.call(sessionStore, k) ? sessionStore[k] : actualKeys[k];
        }
      }
      return withOptionalCallback(Promise.resolve(out), cb);
    },
    set: function(items, callback) {
      if (items && typeof items === 'object') {
        const changes = {};
        for (const k of Object.keys(items)) {
          const oldValue = sessionStore[k];
          sessionStore[k] = items[k];
          changes[k] = { oldValue: oldValue, newValue: items[k] };
        }
        emitStorageChanges(changes, 'session');
      }
      return withOptionalCallback(Promise.resolve(undefined), callback);
    },
    remove: function(keys, callback) {
      const keyList = Array.isArray(keys) ? keys : (typeof keys === 'string' ? [keys] : []);
      const changes = {};
      for (const k of keyList) {
        if (Object.prototype.hasOwnProperty.call(sessionStore, k)) {
          changes[k] = { oldValue: sessionStore[k] };
          delete sessionStore[k];
        }
      }
      emitStorageChanges(changes, 'session');
      return withOptionalCallback(Promise.resolve(undefined), callback);
    },
    clear: function(callback) {
      for (const k of Object.keys(sessionStore)) delete sessionStore[k];
      return withOptionalCallback(Promise.resolve(undefined), callback);
    }
  });

  function resolveUrl(path) {
    return baseUrl + String(path || '').replace(/^\/+/, '');
  }

  function createSafePort(name) {
    const portMessageListeners = [];
    const portDisconnectListeners = [];
    let disconnected = false;
    const port = {
      name: String(name || ''),
      sender: { id: extId },
      onMessage: makeEventTarget(portMessageListeners),
      onDisconnect: makeEventTarget(portDisconnectListeners),
      postMessage: function(msg) {
        if (disconnected) return;
        callNative('nxr_message', { message: msg == null ? null : msg }).catch(function() {});
      },
      disconnect: function() {
        if (disconnected) return;
        disconnected = true;
        for (const fn of portDisconnectListeners.slice()) {
          try { fn(port); } catch (err) { reportError('port.onDisconnect', err); }
        }
      }
    };
    return port;
  }

  const runtime = {
    id: extId,
    lastError: null,
    getManifest: function() {
      return jsonParse(jsonStringify(manifest));
    },
    getURL: resolveUrl,
    getPlatformInfo: function(callback) {
      return withOptionalCallback(
        Promise.resolve({ os: 'android', arch: 'arm', nacl_arch: 'arm' }),
        callback
      );
    },
    getBrowserInfo: function(callback) {
      return withOptionalCallback(
        Promise.resolve({ name: 'Nexo', vendor: 'Nexo', version: '5.0', buildID: 'cefrium-0.21.0' }),
        callback
      );
    },
    sendMessage: function() {
      const args = Array.prototype.slice.call(arguments);
      let callback;
      if (args.length > 0 && typeof args[args.length - 1] === 'function') {
        callback = args.pop();
      }
      // Soporta (message), (message, options) y (extensionId, message, options).
      const message = (args.length >= 2 && typeof args[0] === 'string') ? args[1] : args[0];
      return withOptionalCallback(
        callNative('nxr_message', { message: message == null ? null : message }),
        callback
      );
    },
    connect: function(extIdOrInfo, connectInfo) {
      const info = (typeof extIdOrInfo === 'object' && extIdOrInfo) ? extIdOrInfo : (connectInfo || {});
      return createSafePort(info.name || '');
    },
    onMessage: makeEventTarget(messageListeners),
    onConnect: makeEventTarget(connectListeners),
    onInstalled: makeEventTarget(installedListeners)
  };

  const extensionLegacy = Object.freeze({
    getURL: resolveUrl,
    inIncognitoContext: false
  });

  const grantedPerms = Array.isArray(manifest.permissions) ? manifest.permissions.slice() : [];
  const grantedOrigins = Array.isArray(manifest.host_permissions) ? manifest.host_permissions.slice() : [];
  const permissionsApi = Object.freeze({
    contains: function(req, callback) {
      const wantedPerms = req && Array.isArray(req.permissions) ? req.permissions : [];
      const wantedOrigins = req && Array.isArray(req.origins) ? req.origins : [];
      const permsOk = wantedPerms.every(function(p) { return grantedPerms.includes(p); });
      const originsOk = wantedOrigins.length === 0 || grantedOrigins.includes('<all_urls>') ||
        wantedOrigins.every(function(o) { return grantedOrigins.includes(o); });
      return withOptionalCallback(Promise.resolve(permsOk && originsOk), callback);
    },
    getAll: function(callback) {
      return withOptionalCallback(
        Promise.resolve({ permissions: grantedPerms.slice(), origins: grantedOrigins.slice() }),
        callback
      );
    },
    request: function(req, callback) {
      return permissionsApi.contains(req, callback);
    }
  });

  let actionBadgeText = '';
  let actionTitle = String(manifest.name || '');
  const actionApi = Object.freeze({
    setBadgeText: function(details, cb) {
      if (details && details.text != null) actionBadgeText = String(details.text);
      return withOptionalCallback(Promise.resolve(), cb);
    },
    getBadgeText: function(_details, cb) {
      if (typeof _details === 'function') { cb = _details; }
      return withOptionalCallback(Promise.resolve(actionBadgeText), cb);
    },
    setBadgeBackgroundColor: function(_details, cb) { return withOptionalCallback(Promise.resolve(), cb); },
    setTitle: function(details, cb) {
      if (details && details.title != null) actionTitle = String(details.title);
      return withOptionalCallback(Promise.resolve(), cb);
    },
    getTitle: function(_details, cb) {
      if (typeof _details === 'function') { cb = _details; }
      return withOptionalCallback(Promise.resolve(actionTitle), cb);
    },
    setIcon: function(_details, cb) { return withOptionalCallback(Promise.resolve(), cb); },
    onClicked: makeEventTarget(actionClickedListeners)
  });

  const i18n = Object.freeze({
    getMessage: function(key, substitutions) {
      const k = String(key || '');
      if (k === '@@extension_id') return extId;
      if (k === '@@ui_locale') return i18n.getUILanguage().replace('-', '_');
      const entry = messages[k];
      if (typeof entry !== 'string') return '';
      if (substitutions == null) return entry;
      const list = Array.isArray(substitutions) ? substitutions : [substitutions];
      return entry.replace(/\$([1-9])/g, function(match, idx) {
        const i = Number(idx) - 1;
        return i < list.length && list[i] != null ? String(list[i]) : match;
      });
    },
    getUILanguage: function() {
      return (typeof navigator !== 'undefined' && navigator.language) ? navigator.language : 'es';
    },
    getAcceptLanguages: function(callback) {
      const langs = (typeof navigator !== 'undefined' && Array.isArray(navigator.languages) && navigator.languages.length)
        ? navigator.languages.slice()
        : [i18n.getUILanguage()];
      return withOptionalCallback(Promise.resolve(langs), callback);
    }
  });

  const api = Object.freeze({
    runtime: runtime,
    extension: extensionLegacy,
    permissions: permissionsApi,
    action: actionApi,
    browserAction: actionApi,
    storage: Object.freeze({
      local: storageLocal,
      sync: storageSync,
      session: storageSession,
      onChanged: makeEventTarget(storageChangedListeners)
    }),
    i18n: i18n,
    tabs: Object.freeze({
      query: unsupportedStub('tabs.query'),
      create: unsupportedStub('tabs.create'),
      update: unsupportedStub('tabs.update'),
      sendMessage: unsupportedStub('tabs.sendMessage')
    }),
    scripting: Object.freeze({
      executeScript: unsupportedStub('scripting.executeScript'),
      insertCSS: unsupportedStub('scripting.insertCSS')
    }),
    alarms: Object.freeze({
      create: unsupportedStub('alarms.create'),
      clear: unsupportedStub('alarms.clear')
    }),
    contextMenus: Object.freeze({
      create: unsupportedStub('contextMenus.create'),
      removeAll: unsupportedStub('contextMenus.removeAll')
    }),
    notifications: Object.freeze({
      create: unsupportedStub('notifications.create')
    }),
    cookies: Object.freeze({
      get: unsupportedStub('cookies.get'),
      getAll: unsupportedStub('cookies.getAll'),
      set: unsupportedStub('cookies.set')
    }),
    webNavigation: Object.freeze({
      onCommitted: Object.freeze({ addListener: unsupportedStub('webNavigation.onCommitted') })
    }),
    declarativeNetRequest: Object.freeze({
      updateDynamicRules: unsupportedStub('declarativeNetRequest.updateDynamicRules')
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
