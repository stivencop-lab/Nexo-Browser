const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { createNxrScope } = require(path.join(__dirname, '../../main/assets/nexo/nxr-runtime-shim.js'));

function makeMockQuery() {
  const store = Object.create(null);
  const calls = [];
  const query = ({ request, onSuccess }) => {
    const outer = JSON.parse(request);
    const msg = JSON.parse(outer.m);
    calls.push(msg);
    let result = null;
    if (msg.kind === 'nxr_storage') {
      const p = msg.payload;
      if (p.op === 'set') {
        Object.assign(store, p.values || {});
        result = true;
      } else if (p.op === 'get') {
        if (typeof p.keys === 'string') {
          result = Object.prototype.hasOwnProperty.call(store, p.keys) ? { [p.keys]: store[p.keys] } : {};
        } else {
          result = Object.assign({}, store);
        }
      } else if (p.op === 'remove') {
        delete store[p.keys];
        result = true;
      } else if (p.op === 'clear') {
        for (const k of Object.keys(store)) delete store[k];
        result = true;
      }
    } else if (msg.kind === 'nxr_error') {
      result = true;
    } else if (msg.kind === 'nxr_message') {
      result = { ok: true, nxr: true };
    }
    onSuccess(JSON.stringify({ reply: JSON.stringify({ id: msg.id, result }) }));
  };
  return { query, calls, store };
}

test('nxr-runtime-shim supports chrome.storage.local promises and callbacks with session token', async () => {
  const mock = makeMockQuery();
  const scope = createNxrScope({
    extId: 'demo@nxr.test',
    token: 'tok-abc-123',
    manifest: { name: 'Demo', version: '1.0' },
    messages: { hello: 'Hola Nexo' },
    query: mock.query
  });

  await scope.chrome.storage.local.set({ count: 42 });
  const got = await scope.chrome.storage.local.get('count');
  assert.equal(got.count, 42);
  assert.equal(scope.chrome.i18n.getMessage('hello'), 'Hola Nexo');
  assert.equal(scope.chrome.runtime.id, 'demo@nxr.test');
  assert.equal(scope.chrome.runtime.getManifest().name, 'Demo');

  // Every native call must carry the scoped session token and extId
  assert.ok(mock.calls.length >= 2);
  for (const c of mock.calls) {
    assert.equal(c.payload.extId, 'demo@nxr.test');
    assert.equal(c.payload.token, 'tok-abc-123');
  }

  // Callback style also works
  const cbVal = await new Promise(resolve => scope.chrome.storage.local.get('count', resolve));
  assert.equal(cbVal.count, 42);
});

test('nxr-runtime-shim dispatches action messages and reports unsupported APIs honestly', async () => {
  const mock = makeMockQuery();
  const scope = createNxrScope({
    extId: 'demo@nxr.test',
    token: 'tok-xyz',
    manifest: { name: 'Demo', version: '1.0' },
    query: mock.query
  });

  const received = [];
  scope.chrome.runtime.onMessage.addListener(msg => received.push(msg));
  const handled = scope.dispatchIncoming({ type: 'NXR_ACTION_CLICKED', url: 'https://example.com' });
  assert.equal(handled, true);
  assert.equal(received.length, 1);
  assert.equal(received[0].type, 'NXR_ACTION_CLICKED');

  await assert.rejects(() => scope.chrome.debugger.attach(), /no soportada/i);
  const errCall = mock.calls.find(c => c.kind === 'nxr_error' && c.payload.source === 'unsupported_api');
  assert.ok(errCall, 'unsupported API usage should report to per-extension diagnostics');
});

test('nxr-runtime-shim rejects oversized envelopes and cefrium-port freezes cefriumQuery against page hooking', async () => {
  const mock = makeMockQuery();
  const scope = createNxrScope({
    extId: 'demo@nxr.test',
    token: 'tok-cap-secret',
    manifest: { name: 'Demo', version: '1.0' },
    query: mock.query
  });

  const huge = 'x'.repeat(40 * 1024);
  await assert.rejects(() => scope.chrome.storage.local.set({ blob: huge }), /demasiado grande/i);

  // Verify cefrium-port freezes cefriumQuery so a malicious page cannot intercept NXR tokens
  const fs = require('node:fs');
  const vm = require('node:vm');
  const portSource = fs.readFileSync(path.join(__dirname, '../../main/assets/nexo/cefrium-port.js'), 'utf8');
  let realQueryCalled = 0;
  const ctx = vm.createContext({
    Object, JSON, Promise, Map, Array, Error,
    setInterval: () => 1,
    document: { visibilityState: 'visible' },
    cefriumQuery: () => { realQueryCalled++; }
  });
  ctx.globalThis = ctx;
  vm.runInContext(portSource + '\nnexoCefriumPort(true);', ctx);

  // Page attempts to monkey-patch cefriumQuery to steal tokens
  vm.runInContext(`
    try { globalThis.cefriumQuery = function() { throw new Error('hooked!'); }; } catch (_) {}
  `, ctx);
  const desc = vm.runInContext(`Object.getOwnPropertyDescriptor(globalThis, 'cefriumQuery')`, ctx);
  assert.equal(desc.writable, false);
  assert.equal(desc.configurable, false);
  ctx.cefriumQuery({ request: '{}', onSuccess() {}, onFailure() {} });
  assert.ok(realQueryCalled >= 2, 'original cefriumQuery must remain intact');
});

test('nxr-runtime-shim protects getManifest from JSON monkey-patching and exposes honest stubs', async () => {
  const mock = makeMockQuery();
  const origParse = JSON.parse;
  const origStringify = JSON.stringify;
  const scope = createNxrScope({
    extId: 'demo@nxr.test',
    token: 'tok-json',
    manifest: { name: 'Protected', version: '2.0' },
    query: mock.query
  });
  try {
    JSON.parse = () => ({ name: 'HIJACKED' });
    JSON.stringify = () => '{"name":"HIJACKED"}';
    assert.equal(scope.chrome.runtime.getManifest().name, 'Protected');
  } finally {
    JSON.parse = origParse;
    JSON.stringify = origStringify;
  }

  await assert.rejects(() => scope.chrome.scripting.executeScript(), /no soportada/i);
  await assert.rejects(() => scope.chrome.cookies.get(), /no soportada/i);
  await assert.rejects(() => scope.chrome.alarms.create(), /no soportada/i);
  await assert.rejects(() => scope.chrome.declarativeNetRequest.updateDynamicRules(), /no soportada/i);
});

