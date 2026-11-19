// Nexo sobre Chromium (Cefrium): el puerto `__nexoShield` (assets/nexo/cefrium-port.js)
// sobre window.cefriumQuery, con el shim de PhantomShield, el informe de medios y el shim
// del puente de Nexo (content_script.js de Gecko) reales.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const nexo = path.join(__dirname, '../../main/assets/nexo');
const portSource = fs.readFileSync(path.join(nexo, 'cefrium-port.js'), 'utf8');
const shimSource = fs.readFileSync(path.join(nexo, 'phantom-shim.js'), 'utf8');
const mediaSource = fs.readFileSync(path.join(nexo, 'media-reporter.js'), 'utf8');
const bridgeShimSource = fs.readFileSync(path.join(nexo, 'bridge-shim.js'), 'utf8');
const geckoBridge = fs.readFileSync(path.join(nexo, 'bridge/page-bridge.js'), 'utf8');

/** Nexo falso con el protocolo de PageChannel.kt: {m, s} -> {reply, seq, events}. */
function nativeSide(answer) {
  const events = [];
  const received = [];
  let seq = 0;
  return {
    received,
    push(text) { events.push([++seq, text]); },
    query({ request, onSuccess }) {
      const { m, s } = JSON.parse(request);
      const before = seq;
      if (m !== null) received.push(JSON.parse(m));
      const reply = m === null ? undefined : answer(JSON.parse(m));
      const envelope = { seq };
      if (reply !== undefined) envelope.reply = JSON.stringify(Object.assign({ id: JSON.parse(m).id }, reply));
      const cutoff = s >= 0 ? s : before;
      if (cutoff < seq) {
        const pending = events.filter(([n]) => n > cutoff).map(([, text]) => text);
        if (pending.length) envelope.events = pending;
      }
      Promise.resolve().then(() => onSuccess(JSON.stringify(envelope)));
    }
  };
}

function page(native, { top = true, media = [], mediaSession } = {}) {
  const timers = [];
  const document = new EventTarget();
  document.visibilityState = 'visible';
  document.querySelectorAll = (selector) => selector.includes('audio') ? media : media.filter(m => m.tagName === 'VIDEO');
  document.querySelector = () => null;
  document.documentElement = { toggleAttribute() {} };
  const context = vm.createContext({
    console, JSON, Promise, Map, Object, Error, Array, WeakMap, Math, document,
    setInterval: (fn) => { timers.push(fn); return timers.length; },
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    addEventListener() {},
    devicePixelRatio: 1,
    navigator: { mediaSession },
    cefriumQuery: (options) => native.query(options)
  });
  context.globalThis = context;
  context.window = context;
  context.top = top ? context : {};
  return { context, tick: () => timers.forEach(fn => fn()) };
}

const element = (tagName, props) => Object.assign({
  tagName, paused: true, ended: false, muted: false, volume: 1, currentSrc: 'https://media.example/a',
  videoWidth: 640, videoHeight: 360, clientWidth: 320, clientHeight: 180,
  getBoundingClientRect: () => ({ left: 0, top: 0, width: 320, height: 180 })
}, props);

const bridgeMessages = (native, type) => native.received.filter(m => m.kind === 'bridge' && m.payload.type === type).map(m => m.payload);

test('a subframe without media stays silent so it cannot clear the video of another frame', async () => {
  const native = nativeSide(() => undefined);
  const { context, tick } = page(native, { top: false });
  vm.runInContext(portSource + '\nnexoCefriumPort(false);\n' + mediaSource, context);
  tick();
  await flush();
  assert.deepEqual(bridgeMessages(native, 'video_state'), []);
  assert.deepEqual(bridgeMessages(native, 'media_state'), []);
});

test('audible media is reported once per change; muted background loops are ignored', async () => {
  const audio = element('AUDIO', { paused: false });
  const loop = element('VIDEO', { paused: false, muted: true });
  const native = nativeSide(() => undefined);
  const { context, tick } = page(native, { media: [loop, audio], mediaSession: { playbackState: 'none', metadata: { title: 'Canción' } } });
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + mediaSource, context);
  tick();
  tick();
  await flush();
  assert.deepEqual(bridgeMessages(native, 'media_state'), [{ type: 'media_state', active: true, playing: true, title: 'Canción' }]);
  audio.paused = true;
  tick();
  await flush();
  assert.deepEqual(bridgeMessages(native, 'media_state').at(-1), { type: 'media_state', active: true, playing: false, title: 'Canción' });
});

const flush = () => new Promise(resolve => setImmediate(resolve));

test('without cefriumQuery the port is not created', () => {
  const context = vm.createContext({ Object, JSON });
  context.globalThis = context;
  assert.equal(vm.runInContext(portSource + '\nnexoCefriumPort(true)', context), false);
  assert.equal('__nexoShield' in context, false);
});

test('the shim resolves calls through cefriumQuery and removes the port from the page', async () => {
  const native = nativeSide(message => message.kind === 'get' ? { result: { adblockEnabled: false } } : { error: 'No disponible' });
  const { context } = page(native);
  vm.runInContext(portSource + '\n' + shimSource +
    '\nnexoCefriumPort(true);\nglobalThis.shim = nexoPhantomShim("https://phantomshield.nexo.invalid/", {version:"9.2.1"});', context);
  assert.equal('__nexoShield' in context, false, 'the port is not left on window');
  const shim = context.shim;
  assert.deepEqual({ ...(await shim.storage.local.get({ adblockEnabled: true })) }, { adblockEnabled: false });
  await assert.rejects(shim.tabs.sendMessage(1, { type: 'AI_SCAN' }), /No disponible/);
});

test('events published by Nexo reach the page once, in order, through polling', async () => {
  const native = nativeSide(() => ({ result: null }));
  const { context, tick } = page(native);
  vm.runInContext(portSource + '\n' + shimSource +
    '\nnexoCefriumPort(true);\nglobalThis.shim = nexoPhantomShim("https://x/", {});\nglobalThis.seen = [];' +
    '\nshim.storage.onChanged.addListener(changes => seen.push(Object.keys(changes)[0]));', context);
  await flush();
  native.push(JSON.stringify({ event: 'storage', changes: { adblockEnabled: { newValue: false } } }));
  native.push(JSON.stringify({ event: 'storage', changes: { privacyEnabled: { newValue: true } } }));
  tick();
  await flush();
  tick();
  await flush();
  assert.deepEqual([...context.seen], ['adblockEnabled', 'privacyEnabled']);
  vm.runInContext('__nexoWake()', context);
  await flush();
  assert.deepEqual([...context.seen], ['adblockEnabled', 'privacyEnabled'], 'events are not delivered twice');
});

test('the page bridge only needs connectNative, sendNativeMessage and sendMessage from browser.runtime', () => {
  const used = new Set([...geckoBridge.matchAll(/browser\s*\.\s*([a-zA-Z]+)\s*\.\s*([a-zA-Z]+)/g)].map(m => `browser.${m[1]}.${m[2]}`));
  assert.deepEqual([...used].sort(), ['browser.runtime.connectNative', 'browser.runtime.sendMessage', 'browser.runtime.sendNativeMessage']);
});

test('the bridge shim gives each frame its own token, targets messages and answers calls', async () => {
  const native = nativeSide(message => {
    if (message.kind !== 'bridge_call') return undefined;
    if (message.payload.type === 'get_state') return { result: { ok: true, backgroundMedia: false } };
    return undefined; // respuesta diferida (Dark Reader)
  });
  const { context, tick } = page(native);
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + bridgeShimSource + `
    globalThis.shim = nexoBridgeShim(globalThis.__nexoShield, true);
    globalThis.got = [];
    globalThis.nativePort = shim.runtime.connectNative('browser');
    nativePort.onMessage.addListener(m => got.push(m.type));
    nativePort.postMessage({ type: 'content_script_ready' });`, context);
  await flush();
  const ready = native.received.find(m => m.kind === 'bridge' && m.payload.type === 'content_script_ready');
  assert.ok(ready && /^[a-z0-9]{8,48}$/.test(ready.frame), 'the frame sends a token');
  assert.equal(context.shim.runtime.connectNative('browser'), context.nativePort, 'one port per frame');

  const state = await context.shim.runtime.sendNativeMessage('browser', { type: 'get_state' });
  assert.deepEqual({ ...state }, { ok: true, backgroundMedia: false });

  // Mensajes de Nexo: a todos, al principal, a este marco y a otro marco.
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'browser_state' } }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'pip_mode' }, to: 'top' }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'captions_grant' }, to: ready.frame }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'captions_restart' }, to: 'otroMarco123' }));
  tick();
  await flush();
  assert.deepEqual([...context.got], ['browser_state', 'pip_mode', 'captions_grant']);

  // Respuesta diferida: llega después por el canal con el id negativo de la llamada.
  const dark = context.shim.runtime.sendMessage({ type: 'nexo_dark_load' });
  await flush();
  const call = native.received.filter(m => m.kind === 'bridge_call').at(-1);
  assert.ok(call.id < 0);
  native.push(JSON.stringify({ id: call.id, result: true }));
  tick();
  await flush();
  assert.equal(await dark, true);
});

test('events pushed during the first query (s = -1) reach the frame without replaying older events', async () => {
  const native = nativeSide(message => {
    if (message.kind === 'bridge' && message.payload.type === 'content_script_ready') {
      native.push(JSON.stringify({ event: 'bridge', message: { type: 'browser_state' } }));
      native.push(JSON.stringify({ event: 'bridge', message: { type: 'captions', active: true } }));
    }
    return undefined;
  });
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'stale_old_event' } }));
  const { context } = page(native);
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + bridgeShimSource + `
    globalThis.shim = nexoBridgeShim(globalThis.__nexoShield, true);
    globalThis.got = [];
    globalThis.nativePort = shim.runtime.connectNative('browser');
    nativePort.onMessage.addListener(m => got.push(m.type));
    nativePort.postMessage({ type: 'content_script_ready' });`, context);
  await flush();
  assert.deepEqual([...context.got], ['browser_state', 'captions']);
});

test('concurrent in-flight queries with the same sequence never deliver duplicate events', async () => {
  const native = nativeSide(() => ({ result: null }));
  const { context } = page(native);
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + shimSource + `
    globalThis.shim = nexoPhantomShim('https://x/', {});
    globalThis.seen = [];
    shim.storage.onChanged.addListener(changes => seen.push(Object.keys(changes)[0]));`, context);
  await flush();
  native.push(JSON.stringify({ event: 'storage', changes: { aiConfig: { newValue: {} } } }));
  // Dos llamadas concurrentes que salen con el mismo `s` antes de recibir respuesta.
  const p1 = context.shim.storage.local.get({ adblockEnabled: true });
  const p2 = context.shim.storage.local.get({ privacyEnabled: false });
  await Promise.all([p1, p2]);
  await flush();
  assert.deepEqual([...context.seen], ['aiConfig']);
});

test('alwaysPoll polls when hidden and subframe captions_offer schedules a fast wake', async () => {
  const native = nativeSide(() => undefined);
  const { context, tick } = page(native, { top: true });
  context.document.visibilityState = 'hidden';
  vm.runInContext(portSource + '\nnexoCefriumPort(true, { alwaysPoll: true, pollMs: 1000 });\n' +
    'globalThis.got = []; __nexoShield.addEventListener("message", e => got.push(JSON.parse(e.data).event));', context);
  await flush();
  native.push(JSON.stringify({ event: 'request', rid: 1 }));
  tick();
  await flush();
  assert.deepEqual([...context.got], ['request']);

  // Submarco: captions_offer programa port.wake para recoger captions_grant rápido.
  const subNative = nativeSide(message => {
    if (message.kind === 'bridge' && message.payload.type === 'captions_offer') {
      subNative.push(JSON.stringify({ event: 'bridge', message: { type: 'captions_grant' }, to: message.frame }));
    }
    return undefined;
  });
  const sub = page(subNative, { top: false });
  vm.runInContext(portSource + '\nnexoCefriumPort(false);\n' + bridgeShimSource + `
    globalThis.shim = nexoBridgeShim(globalThis.__nexoShield, false);
    globalThis.got = [];
    globalThis.p = shim.runtime.connectNative('browser');
    p.onMessage.addListener(m => got.push(m.type));
    p.postMessage({ type: 'captions_offer', score: 100 });`, sub.context);
  await flush();
  sub.tick();
  await flush();
  assert.deepEqual([...sub.context.got], ['captions_grant']);
});
