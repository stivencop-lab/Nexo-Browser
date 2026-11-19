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
