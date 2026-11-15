// Nexo Chromium: el shim browser.* de PhantomShield (assets/nexo/phantom-shim.js)
// con los scripts de contenido reales de la extensión.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const shimSource = fs.readFileSync(path.join(__dirname, '../../chromium/assets/nexo/phantom-shim.js'), 'utf8');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const read = file => fs.readFileSync(path.join(assets, file), 'utf8');

/** Puerto falso como el objeto que inyecta addWebMessageListener; `answer` hace de Nexo. */
function harness(answer) {
  const listeners = [];
  const sent = [];
  const port = {
    postMessage(text) {
      const message = JSON.parse(text);
      sent.push(message);
      Promise.resolve(answer(message)).then(reply => {
        if (reply === undefined) return;
        listeners.forEach(fn => fn({ data: JSON.stringify(Object.assign({ id: message.id }, reply)) }));
      });
    },
    addEventListener(type, fn) { if (type === 'message') listeners.push(fn); }
  };
  const attributes = {};
  const document = new EventTarget();
  document.documentElement = { setAttribute(name, value) { attributes[name] = value; }, getAttribute: name => attributes[name] ?? null };
  const context = vm.createContext({ console, JSON, Promise, Map, Object, Error, CustomEvent: class extends Event {
    constructor(type, init) { super(type); this.detail = init && init.detail; } }, document, __nexoShield: port });
  context.globalThis = context;
  return { context, sent, attributes, document, emit: data => listeners.forEach(fn => fn({ data: JSON.stringify(data) })) };
}

test('the shim captures the bridge, removes it from the page and resolves calls by id', async () => {
  const h = harness(message => message.kind === 'get' ? { result: { adblockEnabled: false } } : { error: 'No disponible' });
  vm.runInContext(shimSource + '\nglobalThis.shim = nexoPhantomShim("https://phantomshield.nexo.invalid/", {version:"9.2.1"});', h.context);
  assert.equal('__nexoShield' in h.context, false, 'the injected object is not left on window');
  const shim = h.context.shim;
  assert.deepEqual({ ...(await shim.storage.local.get({ adblockEnabled: true })) }, { adblockEnabled: false });
  await assert.rejects(shim.tabs.sendMessage(1, { type: 'AI_SCAN' }), /No disponible/);
  assert.equal(shim.runtime.getURL('/popup/popup.html'), 'https://phantomshield.nexo.invalid/popup/popup.html');
  assert.equal(shim.runtime.getManifest().version, '9.2.1');
  assert.ok(Object.isFrozen(shim.runtime));
});

test('without a bridge the shim returns null instead of a half-working API', () => {
  const context = vm.createContext({ JSON, Promise, Map, Object, Error });
  context.globalThis = context;
  assert.equal(vm.runInContext(shimSource + '\nnexoPhantomShim("x", {})', context), null);
});

test('content-script.js publishes the native site state and reacts to storage events', async () => {
  let enabled = true;
  const h = harness(message => {
    if (message.kind === 'get') return { result: { adblockEnabled: true, privacyEnabled: false, popupBlockEnabled: true } };
    if (message.kind === 'message' && message.payload.type === 'GET_SITE_STATE') return { result: { ok: true, enabled } };
    if (message.kind === 'message' && message.payload.type === 'POPUP_BLOCKED') return { result: { ok: true } };
    return { result: null };
  });
  const script = '(function(){' + shimSource + '\nconst browser = nexoPhantomShim("https://phantomshield.nexo.invalid/", {});\n' +
    read('content/content-script.js') + '\n})();';
  vm.runInContext(script, h.context);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(h.attributes['data-phantomshield-adblock'], 'true');
  assert.equal('browser' in h.context, false, 'browser stays inside the closure');
  enabled = false;
  h.emit({ event: 'storage', changes: { disabled_domains: {} } });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(h.attributes['data-phantomshield-adblock'], 'false', 'a paused site turns blocking off in the page');
  h.document.dispatchEvent(new Event('phantomshield:popup-blocked'));
  assert.ok(h.sent.some(message => message.payload && message.payload.type === 'POPUP_BLOCKED'));
});
