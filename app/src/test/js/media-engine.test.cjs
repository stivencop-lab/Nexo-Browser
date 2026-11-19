const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../main/assets/nexo/bridge/page-bridge.js'), 'utf8');

function harness() {
  let now = 100000, nextId = 1, onMessage;
  const timers = new Map(), reports = [], videos = [];
  class Element extends EventTarget {
    constructor(parent = null) {
      super();
      this.parentElement = parent;
      this.isConnected = true;
      const classes = new Set();
      this.classList = { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x) };
      this.style = { setProperty() {}, removeProperty() {} };
      this.children = [];
    }
    getBoundingClientRect() { return { left: 0, top: 48, width: 640, height: 360 }; }
    appendChild(child) { child.parentElement = this; child.isConnected = true; this.children.push(child); return child; }
    remove() { this.isConnected = false; }
    setAttribute() {}
  }
  const html = new Element(), body = new Element(html), wrapper = new Element(body);
  const document = new Element();
  Object.assign(document, {
    documentElement: html,
    body,
    visibilityState: 'visible',
    getElementsByTagName: () => videos,
    querySelectorAll: () => [],
    createElement: () => new Element()
  });
  html.querySelectorAll = () => [];
  const win = new Element();
  const port = {
    postMessage: value => reports.push(value),
    onMessage: { addListener: callback => { onMessage = callback; } },
    onDisconnect: { addListener() {} }
  };
  const schedule = (callback, delay, interval = 0) => {
    const id = nextId++;
    timers.set(id, { callback, at: now + delay, interval });
    return id;
  };
  vm.runInNewContext(source, {
    document,
    window: win,
    Date: { now: () => now },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    browser: { runtime: { connectNative: () => port, sendNativeMessage: () => Promise.resolve({}) } },
    setTimeout: (cb, d) => schedule(cb, d),
    clearTimeout: id => timers.delete(id),
    setInterval: (cb, d) => schedule(cb, d, d),
    clearInterval: id => timers.delete(id),
    btoa
  });
  function tick(ms) {
    const end = now + ms;
    while (true) {
      const entry = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!entry) break;
      const [id, timer] = entry;
      now = timer.at;
      timers.delete(id);
      if (timer.interval) timers.set(id, { ...timer, at: now + timer.interval });
      timer.callback();
    }
    now = end;
  }
  function addVideo(overrides = {}) {
    const v = new Element(wrapper);
    Object.assign(v, {
      paused: false,
      ended: false,
      error: null,
      currentSrc: 'https://video.example/clip.mp4',
      readyState: 4,
      videoWidth: 1280,
      videoHeight: 720,
      playbackRate: 1.0,
      currentTime: 30,
      duration: 120,
      mediaKeys: null,
      textTracks: [],
      seekable: { length: 1, start: () => 0, end: () => 120 },
      pause() { this.paused = true; },
      play() { this.paused = false; return Promise.resolve(); }
    }, overrides);
    videos.push(v);
    return v;
  }
  return { addVideo, tick, reports, send: msg => onMessage(msg) };
}

test('video_state reports playback rate, drm status, and textTracks presence within bridge limits', () => {
  const h = harness();
  h.addVideo({ playbackRate: 1.5, mediaKeys: { keySystem: 'com.widevine.alpha' }, textTracks: [{ kind: 'subtitles' }] });
  h.tick(300);
  const state = h.reports.find(r => r.type === 'video_state');
  assert.ok(state);
  assert.equal(state.rate, 1.5);
  assert.equal(state.drm, true);
  assert.equal(state.tracks, true);
  assert.ok(Object.keys(state).length <= 16, 'video_state must stay within BridgeLimits.MAX_KEYS');
});

test('media_command set_speed and seek_relative clamp bounds and update video cleanly', () => {
  const h = harness();
  const v = h.addVideo({ currentTime: 30, playbackRate: 1.0 });
  h.send({ type: 'media_command', action: 'set_speed', speed: 1.75 });
  assert.equal(v.playbackRate, 1.75);
  h.send({ type: 'media_command', action: 'set_speed', speed: 9.0 });
  assert.equal(v.playbackRate, 2.5);
  h.send({ type: 'media_command', action: 'seek_relative', delta: 15 });
  assert.equal(v.currentTime, 45);
  h.send({ type: 'media_command', action: 'seek_relative', delta: -100 });
  assert.equal(v.currentTime, 0);
});

test('media_command set_caption_offset shifts track cues without ever mutating video.currentTime', () => {
  const h = harness();
  let timeWrites = 0;
  let currentTimeVal = 2.0;
  const track = new EventTarget();
  track.kind = 'subtitles';
  track.language = 'en';
  track.mode = 'showing';
  track.cues = [
    { startTime: 0.5, endTime: 2.5, text: 'First cue' },
    { startTime: 3.0, endTime: 5.0, text: 'Second cue' }
  ];
  track.activeCues = [track.cues[0]];

  const v = h.addVideo({ textTracks: [track] });
  Object.defineProperty(v, 'currentTime', {
    get() { return currentTimeVal; },
    set(val) { timeWrites++; currentTimeVal = val; }
  });

  h.send({ type: 'captions', active: true, live: false, lang: 'en', target: '' });
  h.send({ type: 'captions_grant' });
  h.tick(300);

  let actives = h.reports.filter(r => r.type === 'captions_active');
  assert.deepEqual(Array.from(actives[actives.length - 1].ids), [0]);

  // Apply +1500 ms offset: shifted time = 2.0 + 1.5 = 3.5 -> should select cue 1 without touching video.currentTime
  h.send({ type: 'media_command', action: 'set_caption_offset', offsetMs: 1500 });
  assert.equal(timeWrites, 0, 'caption offset MUST NEVER mutate video.currentTime');
  actives = h.reports.filter(r => r.type === 'captions_active');
  assert.deepEqual(Array.from(actives[actives.length - 1].ids), [1]);

  // Reset offset to 0 -> back to native activeCues (cue 0)
  h.send({ type: 'media_command', action: 'set_caption_offset', offsetMs: 0 });
  assert.equal(timeWrites, 0);
  actives = h.reports.filter(r => r.type === 'captions_active');
  assert.deepEqual(Array.from(actives[actives.length - 1].ids), [0]);
});
