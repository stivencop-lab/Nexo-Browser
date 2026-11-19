'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const aiAgent = require('../../main/assets/nexo/ai-page-agent.js');
const darkEngine = require('../../main/assets/nexo/dark-engine.js');
const { createPageTranslator } = require('../../main/assets/nexo/page-translator.js');

const FIXTURES_DIR = path.join(__dirname, 'fixtures');
const CEFRIUM_PORT_SRC = fs.readFileSync(
  path.join(__dirname, '../../main/assets/nexo/cefrium-port.js'),
  'utf8'
);

/**
 * Minimal deterministic HTML fixture parser for adversarial Node tests
 * (zero external npm dependencies, supports nested tags, style attr, hidden/aria-hidden).
 */
function parseSimpleHtml(html) {
  const titleMatch = /<title>([\s\S]*?)<\/title>/i.exec(html);
  const title = titleMatch ? titleMatch[1].trim() : '';
  const bodyMatch = /<body>([\s\S]*?)<\/body>/i.exec(html);
  const bodyHtml = bodyMatch ? bodyMatch[1] : html;

  function makeEl(tag, attrs) {
    const style = {};
    if (attrs.style) {
      for (const decl of attrs.style.split(';')) {
        const idx = decl.indexOf(':');
        if (idx === -1) continue;
        const k = decl.slice(0, idx).trim().replace(/-([a-z])/g, (_, c) => c.toUpperCase());
        const v = decl.slice(idx + 1).trim();
        style[k] = v;
      }
    }
    const classes = new Set(String(attrs.class || '').split(/\s+/).filter(Boolean));
    return {
      nodeType: 1,
      tagName: tag.toUpperCase(),
      attrs,
      style,
      hidden: Object.prototype.hasOwnProperty.call(attrs, 'hidden'),
      childNodes: [],
      parentElement: null,
      isConnected: true,
      classList: { contains: (c) => classes.has(c) },
      getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; },
      querySelectorAll(sel) {
        const tags = sel.split(',').map(s => s.trim().toLowerCase());
        const out = [];
        function walk(n) {
          if (!n || n.nodeType !== 1) return;
          const tl = n.tagName.toLowerCase();
          if (tags.some(t => (t === 'a[href]' ? tl === 'a' && !!n.getAttribute('href') : t === tl))) out.push(n);
          for (const ch of n.childNodes) walk(ch);
        }
        for (const ch of this.childNodes) walk(ch);
        return out;
      }
    };
  }

  const root = makeEl('body', {});
  const stack = [root];
  const voidTags = new Set(['input', 'br', 'hr', 'img', 'meta', 'link']);
  const tokenRe = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = tokenRe.exec(bodyHtml)) !== null) {
    if (m[4]) {
      const textNode = { nodeType: 3, nodeValue: m[4], parentElement: stack[stack.length - 1], isConnected: true };
      stack[stack.length - 1].childNodes.push(textNode);
    } else if (m[1] === '/') {
      if (stack.length > 1) stack.pop();
    } else {
      const tag = m[2].toLowerCase();
      const attrStr = m[3] || '';
      const attrs = {};
      const attrRe = /([a-zA-Z0-9_-]+)(?:\s*=\s*"([^"]*)")?/g;
      let am;
      while ((am = attrRe.exec(attrStr)) !== null) {
        attrs[am[1].toLowerCase()] = am[2] !== undefined ? am[2] : '';
      }
      const el = makeEl(tag, attrs);
      el.parentElement = stack[stack.length - 1];
      stack[stack.length - 1].childNodes.push(el);
      if (!voidTags.has(tag)) stack.push(el);
    }
  }
  return {
    title,
    location: { href: 'https://adversarial.example/report?api_key=leaked&view=public' },
    body: root,
    querySelectorAll: (sel) => root.querySelectorAll(sel)
  };
}

test('adversarial fixture: ai-page-agent strips all hidden prompt-injection vectors, secrets, and sensitive fields', () => {
  const html = fs.readFileSync(path.join(FIXTURES_DIR, 'adversarial-prompt-injection.html'), 'utf8');
  const doc = parseSimpleHtml(html);
  const win = {
    getComputedStyle: (el) => el.style || {},
    getSelection: () => ({ toString: () => '' })
  };

  const snap = aiAgent.extractSafeSnapshot(doc, win, 4000);
  assert.ok(!snap.text.includes('IGNORE'), 'all hidden prompt-injection strings must be stripped');
  assert.ok(!snap.headings.includes('Hidden Malicious Heading'), 'hidden heading must not leak');
  assert.equal(snap.links.length, 0, 'hidden malicious link must not leak');
  assert.ok(!snap.title.includes('sk-proj-leakedtitlekey1234567890'), 'title secret must be redacted');
  assert.ok(!snap.text.includes('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9'), 'inline JWT must be redacted');
  assert.ok(!snap.url.includes('api_key='), 'sensitive query param must be stripped');
  assert.equal(snap.forms.length, 1, 'only non-sensitive search_query input should be exposed');
  assert.equal(snap.forms[0].name, 'search_query');
  assert.ok(snap.text.includes('Nexo Browser runs Chromium via Cefrium'));
});

test('adversarial NXR bridge: cefrium-port freezes globalThis.cefriumQuery against hostile page hooking', () => {
  const calls = [];
  const realQuery = (opts) => {
    calls.push(opts);
    return 1;
  };
  const sandbox = {
    cefriumQuery: realQuery,
    setInterval() { return 0; },
    addEventListener() {},
    document: { visibilityState: 'visible' },
    console
  };
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(CEFRIUM_PORT_SRC + '\nnexoCefriumPort(true);', sandbox);

  // Hostile page script tries to replace or redefine window.cefriumQuery to sniff NXR tokens
  const attackResult = vm.runInContext(`
    (function() {
      let replaced = false;
      try {
        window.cefriumQuery = function() { replaced = true; };
      } catch (_) {}
      try {
        Object.defineProperty(window, 'cefriumQuery', { value: function() { replaced = true; } });
      } catch (_) {}
      return { replaced, sameRef: window.cefriumQuery === globalThis.cefriumQuery };
    })();
  `, sandbox);

  assert.equal(attackResult.replaced, false, 'hostile page must not be able to replace cefriumQuery');
  assert.equal(sandbox.cefriumQuery, realQuery, 'original native cefriumQuery reference must remain intact');
  const desc = Object.getOwnPropertyDescriptor(sandbox, 'cefriumQuery');
  assert.equal(desc.writable, false);
  assert.equal(desc.configurable, false);
});

test('adversarial Dark Engine & Translator: combined DOM protection invariants hold', () => {
  const html = fs.readFileSync(path.join(FIXTURES_DIR, 'adversarial-prompt-injection.html'), 'utf8');
  const doc = parseSimpleHtml(html);
  const translator = createPageTranslator(doc, { getComputedStyle: (el) => el.style || {} });
  const meta = translator.extractSegmentsWithMeta();
  // Hidden display:none text is NOT extracted for translation, visible headings/paragraphs ARE
  assert.ok(meta.segments.some(s => s.t.includes('Public Product Summary')));
  assert.ok(!meta.segments.some(s => s.t.includes('exfiltrate cookies')));

  // Dark Engine respects native dark color-scheme meta even if strategy=hybrid
  const nativeMeta = {
    name: 'color-scheme',
    content: 'dark',
    hasAttribute: () => false,
    remove() {}
  };
  const fakeDoc = {
    readyState: 'complete',
    contentType: 'text/html',
    documentElement: { getAttribute: () => null, setAttribute() {}, removeAttribute() {} },
    head: { appendChild() {} },
    body: { children: [] },
    getElementById: () => null,
    querySelector: (s) => (s === 'meta[name="color-scheme"]' ? nativeMeta : null),
    addEventListener() {}
  };
  const ctrl = darkEngine.createController({ document: fakeDoc, window: { getComputedStyle: () => ({ backgroundColor: 'rgb(255,255,255)' }), addEventListener() {} } });
  assert.equal(ctrl.applyConfig({ enabled: true, background: '#0c1222', strategy: 'hybrid' }), 'native');
});
