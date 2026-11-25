// Nexo sobre Chromium (Cefrium): el puerto `__nexoShield` (assets/nexo/cefrium-port.js)
// sobre window.cefriumQuery asíncrono (sin polling con setInterval), con el shim de
// PhantomShield, el informe de medios y el shim del puente de Nexo reales.
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

/**
 * Nexo falso con el protocolo asíncrono de PageChannel.kt:
 * - `{m, s}`: respuesta inmediata o diferida directa vía `resolve(reply)` sobre el mismo `cefriumQuery`.
 * - `{m: null, s, w: true, k}`: espera aparcada que se resuelve al instante en `push()`.
 */
function nativeSide(answer) {
  const events = [];
  const received = [];
  const waiters = new Map();
  let seq = 0;

  function buildEnvelope(reply, s, generatedAfter = Number.MAX_SAFE_INTEGER) {
    const envelope = { seq };
    if (reply !== undefined) envelope.reply = JSON.stringify(reply);
    const cutoff = s >= 0 ? s : generatedAfter;
    if (cutoff < seq) {
      const pending = events.filter(([n]) => n > cutoff).map(([, text]) => text);
      if (pending.length) envelope.events = pending;
    }
    return JSON.stringify(envelope);
  }

  return {
    received,
    waiters,
    push(text) {
      events.push([++seq, text]);
      const ready = [...waiters.values()];
      waiters.clear();
      for (const w of ready) {
        const payload = buildEnvelope(undefined, w.s);
        Promise.resolve().then(() => w.onSuccess(payload));
      }
    },
    query({ request, onSuccess }) {
      const { m, s, w, k } = JSON.parse(request);
      if (m === null) {
        if (w) {
          if (s < 0) {
            const payload = JSON.stringify({ seq });
            Promise.resolve().then(() => onSuccess(payload));
          } else if (s < seq) {
            const payload = buildEnvelope(undefined, s);
            Promise.resolve().then(() => onSuccess(payload));
          } else {
            waiters.set(k || String(waiters.size), { s, onSuccess });
          }
          return;
        }
        const payload = buildEnvelope(undefined, s);
        Promise.resolve().then(() => onSuccess(payload));
        return;
      }
      const before = seq;
      const parsed = JSON.parse(m);
      received.push(parsed);
      let asyncHandled = false;
      const reply = answer(parsed, (deferred) => {
        asyncHandled = true;
        const payload = JSON.stringify({ reply: JSON.stringify(Object.assign({ id: parsed.id }, deferred)) });
        Promise.resolve().then(() => onSuccess(payload));
      });
      if (asyncHandled) return;
      const wrapped = reply === undefined ? undefined : Object.assign({ id: parsed.id }, reply);
      const payload = buildEnvelope(wrapped, s, before);
      Promise.resolve().then(() => onSuccess(payload));
    }
  };
}

function page(native, { top = true, media = [], mediaSession } = {}) {
  const timers = [];
  const intervals = [];
  const document = new EventTarget();
  document.visibilityState = 'visible';
  document.querySelectorAll = (selector) => selector.includes('audio') ? media : media.filter(m => m.tagName === 'VIDEO');
  document.querySelector = () => null;
  document.documentElement = { toggleAttribute() {} };
  const context = vm.createContext({
    console, JSON, Promise, Map, Object, Error, Array, WeakMap, Math, document,
    setInterval: (fn, ms) => { intervals.push({ fn, ms }); timers.push(fn); return timers.length; },
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout() {},
    addEventListener() {},
    devicePixelRatio: 1,
    navigator: { mediaSession },
    cefriumQuery: (options) => native.query(options)
  });
  context.globalThis = context;
  context.window = context;
  context.top = top ? context : {};
  return { context, intervals, tick: () => timers.forEach(fn => fn()) };
}

const element = (tagName, props) => Object.assign({
  tagName, paused: true, ended: false, muted: false, volume: 1, currentSrc: 'https://media.example/a',
  videoWidth: 640, videoHeight: 360, clientWidth: 320, clientHeight: 180,
  getBoundingClientRect: () => ({ left: 0, top: 0, width: 320, height: 180 })
}, props);

const idleDetails = { artist: '', album: '', artwork: '', duration: 0, position: 0, rate: 1, next: false, prev: false, seek: false, video: false };

const bridgeMessages = (native, type) => native.received.filter(m => m.kind === 'bridge' && m.payload.type === type).map(m => m.payload);

const flush = async (n = 4) => { for (let i = 0; i < n; i++) await new Promise(resolve => setImmediate(resolve)); };

test('cefrium-port installs no setInterval polling timers', async () => {
  const native = nativeSide(() => undefined);
  const { context, intervals } = page(native);
  vm.runInContext(portSource + '\nnexoCefriumPort(true);', context);
  await flush();
  assert.equal(intervals.length, 0, 'no polling interval should be registered');
  assert.equal(native.waiters.size, 1, 'a single parked cefriumQuery waiter listens for push events');
});

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
  assert.deepEqual(bridgeMessages(native, 'media_state'), [Object.assign({ type: 'media_state', active: true, playing: true, title: 'Canción' }, idleDetails)]);
  audio.paused = true;
  tick();
  await flush();
  assert.deepEqual(bridgeMessages(native, 'media_state').at(-1), Object.assign({ type: 'media_state', active: true, playing: false, title: 'Canción' }, idleDetails));
});

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

test('events published by Nexo reach the page immediately and once, without polling', async () => {
  const native = nativeSide(() => ({ result: null }));
  const { context } = page(native);
  vm.runInContext(portSource + '\n' + shimSource +
    '\nnexoCefriumPort(true);\nglobalThis.shim = nexoPhantomShim("https://x/", {});\nglobalThis.seen = [];' +
    '\nshim.storage.onChanged.addListener(changes => seen.push(Object.keys(changes)[0]));', context);
  await flush();
  native.push(JSON.stringify({ event: 'storage', changes: { adblockEnabled: { newValue: false } } }));
  native.push(JSON.stringify({ event: 'storage', changes: { privacyEnabled: { newValue: true } } }));
  await flush();
  assert.deepEqual([...context.seen], ['adblockEnabled', 'privacyEnabled']);
  // Another unrelated RPC must not redeliver already-seen events.
  await context.shim.storage.local.get({ adblockEnabled: true });
  await flush();
  assert.deepEqual([...context.seen], ['adblockEnabled', 'privacyEnabled'], 'events are not delivered twice');
});

test('the page bridge only needs connectNative, sendNativeMessage and sendMessage from browser.runtime', () => {
  const used = new Set([...geckoBridge.matchAll(/browser\s*\.\s*([a-zA-Z]+)\s*\.\s*([a-zA-Z]+)/g)].map(m => `browser.${m[1]}.${m[2]}`));
  assert.deepEqual([...used].sort(), ['browser.runtime.connectNative', 'browser.runtime.sendMessage', 'browser.runtime.sendNativeMessage']);
});

test('the bridge shim gives each frame its own token, targets messages and resolves deferred cefriumQuery calls directly', async () => {
  let finishDark = null;
  const native = nativeSide((message, defer) => {
    if (message.kind !== 'bridge_call') return undefined;
    if (message.payload.type === 'get_state') return { result: { ok: true, backgroundMedia: false } };
    // Respuesta diferida directa sobre el mismo QueryCallback (Dark Reader).
    finishDark = defer;
    return undefined;
  });
  const { context } = page(native);
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

  // Mensajes de Nexo: a todos, al principal, a este marco y a otro marco (entrega push sin tick).
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'browser_state' } }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'pip_mode' }, to: 'top' }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'captions_grant' }, to: ready.frame }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'captions_restart' }, to: 'otroMarco123' }));
  await flush();
  assert.deepEqual([...context.got], ['browser_state', 'pip_mode', 'captions_grant']);

  // Respuesta diferida directa sobre cefriumQuery: no hace falta push ni polling.
  const dark = context.shim.runtime.sendMessage({ type: 'nexo_dark_load' });
  await flush();
  const call = native.received.filter(m => m.kind === 'bridge_call').at(-1);
  assert.ok(call.id < 0);
  assert.equal(typeof finishDark, 'function');
  finishDark({ result: true });
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
  vm.runInContext(portSource + '\n' + shimSource + `
    nexoCefriumPort(true);
    globalThis.shim = nexoPhantomShim('https://x/', {});
    globalThis.seen = [];
    shim.storage.onChanged.addListener(changes => seen.push(Object.keys(changes)[0]));`, context);
  await flush();
  native.push(JSON.stringify({ event: 'storage', changes: { aiConfig: { newValue: {} } } }));
  const p1 = context.shim.storage.local.get({ adblockEnabled: true });
  const p2 = context.shim.storage.local.get({ privacyEnabled: false });
  await Promise.all([p1, p2]);
  await flush();
  assert.deepEqual([...context.seen], ['aiConfig']);
});

test('hidden host and subframes receive pushed events immediately without polling', async () => {
  const native = nativeSide(() => undefined);
  const { context } = page(native, { top: true });
  context.document.visibilityState = 'hidden';
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' +
    'globalThis.got = []; __nexoShield.addEventListener("message", e => got.push(JSON.parse(e.data).event));', context);
  await flush();
  native.push(JSON.stringify({ event: 'request', rid: 1 }));
  await flush();
  assert.deepEqual([...context.got], ['request']);

  // Submarco: captions_grant llega al instante tras captions_offer sin setTimeout ni polling.
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
  assert.deepEqual([...sub.context.got], ['captions_grant']);
});

test('origin denied (-403) stops listener retries immediately', async () => {
  let calls = 0;
  const context = vm.createContext({
    console, JSON, Promise, Object, Array, Math,
    setTimeout() { throw new Error('must not schedule retry on -403'); },
    cefriumQuery({ onFailure }) {
      calls++;
      Promise.resolve().then(() => onFailure(-403, 'origin not allowed'));
    }
  });
  context.globalThis = context;
  vm.runInContext(portSource + '\nnexoCefriumPort(true);', context);
  await flush();
  assert.equal(calls, 1);
});

test('media details: https artwork, position, site handlers drive next/previous and seeking', async () => {
  const calls = [];
  const audio = element('AUDIO', { paused: false, duration: 200, currentTime: 12.34, playbackRate: 1 });
  const native = nativeSide(() => undefined);
  const mediaSession = {
    playbackState: 'playing',
    metadata: { title: 'Pista', artist: 'Artista', album: 'Disco', artwork: [
      { src: 'http://insecure.example/a.jpg', sizes: '2048x2048' },
      { src: '/art/512.jpg', sizes: '512x512' },
      { src: '/art/96.jpg', sizes: '96x96' }
    ] },
    setActionHandler() {}
  };
  const { context, tick } = page(native, { media: [audio], mediaSession });
  context.location = { href: 'https://music.example/play' };
  context.URL = URL;
  context.Date = Date;
  context.Number = Number;
  context.String = String;
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + mediaSource, context);
  context.navigator.mediaSession.setActionHandler('nexttrack', () => calls.push('next'));
  context.navigator.mediaSession.setActionHandler('seekto', d => calls.push(['seekto', d.seekTime]));
  tick();
  await flush();
  const state = bridgeMessages(native, 'media_state').at(-1);
  assert.equal(state.artwork, 'https://music.example/art/512.jpg', 'the largest https artwork, never http');
  assert.equal(state.artist, 'Artista');
  assert.equal(state.position, 12.3);
  assert.equal(state.duration, 200);
  assert.equal(state.next, true);
  assert.equal(state.prev, false);
  assert.equal(state.seek, true);
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'media_session_command', action: 'nexttrack' } }));
  native.push(JSON.stringify({ event: 'bridge', message: { type: 'media_session_command', action: 'seekto', time: 90 } }));
  await flush();
  assert.deepEqual(calls, ['next', ['seekto', 90]]);
});
