// Nexo sobre Chromium (Cefrium): el puerto `__nexoShield` (assets/nexo/cefrium-port.js)
// sobre window.cefriumQuery, con el shim de PhantomShield y el puente de video reales.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const nexo = path.join(__dirname, '../../main/assets/nexo');
const portSource = fs.readFileSync(path.join(nexo, 'cefrium-port.js'), 'utf8');
const shimSource = fs.readFileSync(path.join(nexo, 'phantom-shim.js'), 'utf8');
const bridgeSource = fs.readFileSync(path.join(nexo, 'nexo-bridge.js'), 'utf8');

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
      if (m !== null) received.push(JSON.parse(m));
      const reply = m === null ? undefined : answer(JSON.parse(m));
      const envelope = { seq };
      if (reply !== undefined) envelope.reply = JSON.stringify(Object.assign({ id: JSON.parse(m).id }, reply));
      if (s >= 0) {
        const pending = events.filter(([n]) => n > s).map(([, text]) => text);
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
  vm.runInContext(portSource + '\nnexoCefriumPort(false);\n' + bridgeSource, context);
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
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + bridgeSource, context);
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

test('the video bridge reports state and obeys native commands over the same port', async () => {
  const native = nativeSide(() => undefined);
  const { context } = page(native);
  vm.runInContext(portSource + '\nnexoCefriumPort(true);\n' + bridgeSource, context);
  await flush();
  const report = native.received.find(m => m.kind === 'bridge' && m.payload.type === 'video_state');
  assert.ok(report, 'the bridge reports the video state on start');
  assert.equal(report.payload.present, false);
  native.push(JSON.stringify({ event: 'native', message: { type: 'lifecycle', state: 'foreground' } }));
  const before = native.received.length;
  vm.runInContext('__nexoWake()', context);
  await flush();
  await flush();
  assert.ok(native.received.length > before, 'a native command makes the bridge report again');
});
