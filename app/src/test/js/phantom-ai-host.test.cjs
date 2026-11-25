// Nexo Chromium: el anfitrión oculto de IA (host/ai-host.html) con los módulos
// originales de PhantomShield y el shim nexo/ai-host-shim.js; `native` hace de Nexo.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const nexo = path.join(__dirname, '../../main/assets/nexo');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const hostShim = fs.readFileSync(path.join(nexo, 'ai-host-shim.js'), 'utf8');
const pageShim = fs.readFileSync(path.join(nexo, 'phantom-shim.js'), 'utf8');
const flush = async (n = 20) => { for (let i = 0; i < n; i++) await new Promise(setImmediate); };

function scriptsOf(html) {
  return [...html.matchAll(/<script src="([^"]+)"><\/script>/g)].map(m => path.join(assets, 'host', m[1]));
}

/** Nexo falso: almacén, `fetch` hacia un proveedor simulado y peticiones con `rid`. */
function host(provider) {
  const stored = {};
  const listeners = [];
  const fetches = [];
  const replies = new Map();
  const events = [];
  let ready = false;
  let rid = 0;
  const deliver = data => listeners.forEach(fn => fn({ data: JSON.stringify(data) }));
  const port = {
    postMessage(text) {
      const call = JSON.parse(text);
      const reply = result => deliver({ id: call.id, result: result === undefined ? null : result });
      switch (call.kind) {
        case 'ready': ready = true; reply(null); break;
        case 'get': {
          const keys = call.payload;
          const out = {};
          if (keys && typeof keys === 'object' && !Array.isArray(keys)) {
            for (const key of Object.keys(keys)) out[key] = key in stored ? stored[key] : keys[key];
          } else Object.assign(out, stored);
          reply(JSON.parse(JSON.stringify(out)));
          break;
        }
        case 'set': {
          const changes = {};
          for (const [key, value] of Object.entries(call.payload)) { changes[key] = { newValue: value, oldValue: stored[key] }; stored[key] = value; }
          reply(null);
          deliver({ event: 'storage', changes });
          break;
        }
        case 'fetch':
          fetches.push(call.payload);
          Promise.resolve(provider(call.payload)).then(result => reply(result));
          break;
        case 'reply': replies.get(call.payload.rid)(call.payload.result); reply(null); break;
        default: deliver({ id: call.id, error: 'No disponible' });
      }
    },
    addEventListener(type, fn) { if (type === 'message') listeners.push(fn); }
  };
  const context = vm.createContext({
    console, URL, Map, Set, Promise, JSON, Object, Array, Error, TypeError, Math, Date, Number, String, Boolean, RegExp,
    TextEncoder, TextDecoder, AbortController, Response, Headers, DOMException, crypto: globalThis.crypto,
    setTimeout: (fn, ms) => { const t = setTimeout(fn, ms); t.unref(); return t; }, clearTimeout, __nexoShield: port
  });
  context.globalThis = context;
  context.window = context;
  vm.runInContext(hostShim + '\nglobalThis.browser = nexoAiHostShim("https://phantomshield.nexo.invalid/", {version:"9.2.1"});', context);
  for (const file of scriptsOf(fs.readFileSync(path.join(assets, 'host/ai-host.html'), 'utf8'))) {
    vm.runInContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  }
  return {
    stored, fetches, events, context, isReady: () => ready,
    request(message, sender) {
      const id = ++rid;
      return new Promise(resolve => {
        replies.set(id, resolve);
        deliver({ event: 'request', rid: id, message, sender });
      });
    },
    async configure(values) {
      await new Promise(resolve => { port.postMessage(JSON.stringify({ id: 9999, kind: 'set', payload: { aiConfig: values } })); setImmediate(resolve); });
    }
  };
}

const CONFIG = {
  enabled: true, consent: true, provider: 'openai', endpoint: 'https://api.openai.com/v1', model: 'gpt-test',
  apiKey: 'sk-test', scope: 'public', detail: 'basic', learnDomains: true, jsonMode: false
};
const CANDIDATES = [
  { id: 'c1', tag: 'DIV', signals: ['ad-class', 'ad-size'], width: 300, height: 250,
    localFrame: 'https://ads.adnet-example.com/frame', localUrls: ['https://click.trk-example.net/click?adid=1'] },
  { id: 'c2', tag: 'ARTICLE', signals: ['ad-class'], width: 600, height: 400 }
];
const answer = decisions => ({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
  choices: [{ message: { content: JSON.stringify({ decisions }) } }], usage: { prompt_tokens: 10, completion_tokens: 5 } }) });

test('the host announces itself and keeps AI off until it is configured', async () => {
  const h = host(() => { throw new Error('no provider call expected'); });
  await flush();
  assert.equal(h.isReady(), true, 'Nexo learns that the host loaded');
  const result = await h.request({ type: 'AI_CLASSIFY', candidates: CANDIDATES },
    { tabId: 1, page: 1, host: 'news-site.com', incognito: false, known: [] });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'off');
  assert.equal(h.fetches.length, 0);
});

test('classification calls the provider through Nexo, never sends local URLs and learns ad domains', async () => {
  const h = host(() => answer([{ id: 'c1', isAd: true, confidence: 0.97 }, { id: 'c2', isAd: false, confidence: 0.9 }]));
  await flush();
  await h.configure(CONFIG);
  await flush();
  const result = await h.request({ type: 'AI_CLASSIFY', candidates: CANDIDATES },
    { tabId: 1, page: 1, host: 'news-site.com', incognito: false, known: [] });
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(h.fetches.length, 1);
  const call = h.fetches[0];
  assert.equal(call.method, 'POST');
  assert.ok(call.url.startsWith('https://api.openai.com/'));
  assert.ok(!call.body.includes('adnet-example') && !call.body.includes('trk-example'), 'local URLs stay on the device');
  const hidden = result.decisions.filter(d => d.hide).map(d => d.id);
  assert.deepEqual(hidden, ['c1']);
  assert.ok(result.learned >= 0);
  await flush();
  const domains = await h.request({ type: 'AI_DOMAINS_LIST' }, { panel: true });
  assert.equal(domains.ok, true);
  assert.ok(domains.domains.some(d => d.domain === 'adnet-example.com'), JSON.stringify(domains.domains));
});

test('incognito and provider failures are reported without exposing provider bodies', async () => {
  const h = host(() => ({ status: 401, headers: {}, body: 'secret echo sk-test' }));
  await flush();
  await h.configure(CONFIG);
  await flush();
  const privateResult = await h.request({ type: 'AI_CLASSIFY', candidates: CANDIDATES },
    { tabId: 2, page: 1, host: 'news-site.com', incognito: true, known: [] });
  assert.equal(privateResult.ok, false);
  const probe = await h.request({ type: 'AI_TEST', config: CONFIG }, { panel: true });
  assert.equal(probe.ok, false);
  assert.equal(probe.code, 'auth');
  assert.ok(!JSON.stringify(probe).includes('secret echo'));
});

test('memory management answers from the host', async () => {
  const h = host(() => answer([]));
  await flush();
  const list = await h.request({ type: 'AI_MEMORY_LIST' }, { panel: true });
  assert.equal(list.ok, true);
  assert.equal((await h.request({ type: 'AI_MEMORY_FORGET', host: 'localhost' }, { panel: true })).ok, false);
  assert.equal((await h.request({ type: 'UNKNOWN' }, { panel: true })), null);
});

test('page shim: Nexo requests with rid reach listeners as PhantomShield and the first answer is returned', async () => {
  const listeners = [];
  const sent = [];
  const port = {
    postMessage(text) { const m = JSON.parse(text); sent.push(m); listeners.forEach(fn => fn({ data: JSON.stringify({ id: m.id, result: null }) })); },
    addEventListener(type, fn) { if (type === 'message') listeners.push(fn); }
  };
  const context = vm.createContext({ JSON, Promise, Map, Object, Error, __nexoShield: port });
  context.globalThis = context;
  vm.runInContext(pageShim + '\nglobalThis.shim = nexoPhantomShim("https://phantomshield.nexo.invalid/", {});', context);
  context.shim.runtime.onMessage.addListener(() => undefined);
  context.shim.runtime.onMessage.addListener((message, sender) =>
    sender.id === context.shim.runtime.id && message.type === 'AI_SCAN' ? Promise.resolve({ ok: true, hidden: 2 }) : undefined);
  listeners.forEach(fn => fn({ data: JSON.stringify({ event: 'message', rid: 7, message: { type: 'AI_SCAN' } }) }));
  await flush();
  const reply = sent.find(m => m.kind === 'reply');
  assert.deepEqual(reply.payload, { rid: 7, result: { ok: true, hidden: 2 } });
  await context.shim.tabs.sendMessage(5, { type: 'AI_SCAN' });
  assert.deepEqual(sent.find(m => m.kind === 'tabs.sendMessage').payload, { tabId: 5, message: { type: 'AI_SCAN' } });
});

test('queued AI requests flushed during ready are delivered over the real cefrium-port protocol', async () => {
  const portSource = fs.readFileSync(path.join(nexo, 'cefrium-port.js'), 'utf8');
  const events = [];
  let seq = 0;
  const waiters = new Map();
  const push = data => {
    events.push([++seq, JSON.stringify(data)]);
    const readyWaiters = [...waiters.values()];
    waiters.clear();
    for (const w of readyWaiters) {
      const list = events.filter(([n]) => n > w.s).map(([, text]) => text);
      Promise.resolve().then(() => w.onSuccess(JSON.stringify({ seq, events: list })));
    }
  };
  const queued = [];
  const replies = new Map();
  let rid = 0;
  let ready = false;

  const request = (message, sender) => new Promise(resolve => {
    const id = ++rid;
    replies.set(id, resolve);
    const ev = { event: 'request', rid: id, message, sender };
    if (ready) push(ev); else queued.push(ev);
  });

  // Encolar antes de que el anfitrión oculto envíe ready (como al abrir la app o probar IA).
  const pending = request({ type: 'AI_MEMORY_LIST' }, { panel: true });

  const nativeQuery = ({ request: raw, onSuccess }) => {
    const { m, s, w, k } = JSON.parse(raw);
    if (m === null && w) {
      if (s < 0) {
        Promise.resolve().then(() => onSuccess(JSON.stringify({ seq })));
      } else if (s < seq) {
        const list = events.filter(([n]) => n > s).map(([, text]) => text);
        Promise.resolve().then(() => onSuccess(JSON.stringify({ seq, events: list })));
      } else {
        waiters.set(k || 'w', { s, onSuccess });
      }
      return;
    }
    const before = seq;
    let reply;
    if (m !== null) {
      const call = JSON.parse(m);
      if (call.kind === 'ready') {
        ready = true;
        reply = JSON.stringify({ id: call.id, result: null });
        queued.splice(0).forEach(push);
      } else if (call.kind === 'get') {
        reply = JSON.stringify({ id: call.id, result: call.payload && !Array.isArray(call.payload) ? call.payload : {} });
      } else if (call.kind === 'reply') {
        replies.get(call.payload.rid)?.(call.payload.result);
        reply = JSON.stringify({ id: call.id, result: null });
      }
    }
    const envelope = { seq };
    if (reply !== undefined) envelope.reply = reply;
    const cutoff = s >= 0 ? s : before;
    if (cutoff < seq) {
      const list = events.filter(([n]) => n > cutoff).map(([, text]) => text);
      if (list.length) envelope.events = list;
    }
    Promise.resolve().then(() => onSuccess(JSON.stringify(envelope)));
  };

  const context = vm.createContext({
    console, URL, Map, Set, Promise, JSON, Object, Array, Error, TypeError, Math, Date, Number, String, Boolean, RegExp,
    TextEncoder, TextDecoder, AbortController, Response, Headers, DOMException, crypto: globalThis.crypto,
    document: { visibilityState: 'hidden' },
    setInterval: () => { throw new Error('cefrium-port must not use setInterval'); },
    setTimeout: (fn, ms) => { const t = setTimeout(fn, ms); t.unref(); return t; },
    clearTimeout,
    cefriumQuery: nativeQuery
  });
  context.globalThis = context;
  context.window = context;
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' +
    hostShim + '\nglobalThis.browser = nexoAiHostShim("https://phantomshield.nexo.invalid/", {version:"9.2.1"});', context);
  for (const file of scriptsOf(fs.readFileSync(path.join(assets, 'host/ai-host.html'), 'utf8'))) {
    vm.runInContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  }
  await flush(30);
  const res = await pending;
  assert.equal(res.ok, true);
  assert.deepEqual([...res.domains], []);
});

test('native fetch timeout error propagates with code timeout', async () => {
  const listeners = [];
  const replies = new Map();
  let rid = 0;
  const deliver = data => listeners.forEach(fn => fn({ data: JSON.stringify(data) }));
  const port = {
    postMessage(text) {
      const call = JSON.parse(text);
      if (call.kind === 'fetch') {
        Promise.resolve().then(() => deliver({ id: call.id, error: 'timeout' }));
      } else if (call.kind === 'reply') {
        replies.get(call.payload.rid)?.(call.payload.result);
        deliver({ id: call.id, result: null });
      } else if (call.kind === 'get') {
        deliver({ id: call.id, result: call.payload && !Array.isArray(call.payload) ? call.payload : {} });
      } else {
        deliver({ id: call.id, result: null });
      }
    },
    addEventListener(type, fn) { if (type === 'message') listeners.push(fn); }
  };
  const context = vm.createContext({
    console, URL, Map, Set, Promise, JSON, Object, Array, Error, TypeError, Math, Date, Number, String, Boolean, RegExp,
    TextEncoder, TextDecoder, AbortController, Response, Headers, DOMException, crypto: globalThis.crypto,
    setTimeout: (fn, ms) => { const t = setTimeout(fn, ms); t.unref(); return t; }, clearTimeout, __nexoShield: port
  });
  context.globalThis = context;
  context.window = context;
  vm.runInContext(hostShim + '\nglobalThis.browser = nexoAiHostShim("https://phantomshield.nexo.invalid/", {version:"9.2.1"});', context);
  for (const file of scriptsOf(fs.readFileSync(path.join(assets, 'host/ai-host.html'), 'utf8'))) {
    vm.runInContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  }
  await flush();
  const id = ++rid;
  const result = await new Promise(resolve => {
    replies.set(id, resolve);
    deliver({ event: 'request', rid: id, message: { type: 'AI_TEST', config: CONFIG }, sender: { panel: true } });
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'timeout');
});
