'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const agent = require('../../main/assets/nexo/ai-page-agent.js');

function makeElement(tag, attrs = {}, children = []) {
  const el = {
    nodeType: 1,
    tagName: tag.toUpperCase(),
    attrs: { ...attrs },
    childNodes: [],
    style: {},
    value: attrs.value || '',
    scrolled: false,
    dispatched: [],
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; },
    scrollIntoView() { this.scrolled = true; },
    dispatchEvent(ev) { this.dispatched.push(ev && ev.type ? ev.type : 'event'); },
    get textContent() {
      return this.childNodes.map(c => (c.nodeType === 3 ? c.nodeValue : c.textContent)).join('');
    },
    querySelectorAll(sel) {
      const tags = sel.split(',').map(s => s.trim().toLowerCase());
      const out = [];
      function walk(node) {
        if (!node || node.nodeType !== 1) return;
        const tagLower = node.tagName.toLowerCase();
        const matches = tags.some(t => {
          if (t === 'a[href]') return tagLower === 'a' && !!node.getAttribute('href');
          return t === tagLower;
        });
        if (matches) out.push(node);
        for (const ch of node.childNodes) walk(ch);
      }
      for (const ch of this.childNodes) walk(ch);
      return out;
    }
  };
  for (const ch of children) {
    ch.parentElement = el;
    el.childNodes.push(ch);
  }
  return el;
}

function makeText(val) {
  return { nodeType: 3, nodeValue: val, parentElement: null };
}

test('sanitizeUrl removes credentials and sensitive query parameters', () => {
  const cleaned = agent.sanitizeUrl('https://alice:s3cr3t@example.com/path?q=nexo&api_key=abc&token=xyz&page=2');
  assert.equal(cleaned, 'https://example.com/path?q=nexo&page=2');
  assert.equal(agent.sanitizeUrl('javascript:alert(1)'), '');
});

test('extractSafeSnapshot excludes password/hidden/cc/csrf fields and respects budget', () => {
  const pwd = makeElement('input', { type: 'password', name: 'user_pass', value: 'TOPSECRET' });
  const hidden = makeElement('input', { type: 'hidden', name: 'csrf_token', value: 'tok123' });
  const card = makeElement('input', { type: 'text', name: 'card_number', autocomplete: 'cc-number' });
  const search = makeElement('input', { type: 'text', name: 'query', placeholder: 'Buscar artículos' });
  const script = makeElement('script', {}, [makeText('var secret = "NO_EXTRACT";')]);
  const heading = makeElement('h1', {}, [makeText('Guía de Nexo Browser')]);
  const para = makeElement('p', {}, [makeText('Contenido visible para resumir en el asistente.')]);
  const link = makeElement('a', { href: 'https://docs.example.com/guide?token=leaked&lang=es' }, [makeText('Documentación')]);
  const table = makeElement('table', {}, [
    makeElement('tr', {}, [makeElement('th', {}, [makeText('Motor')]), makeElement('th', {}, [makeText('Estado')])]),
    makeElement('tr', {}, [makeElement('td', {}, [makeText('Chromium')]), makeElement('td', {}, [makeText('Activo')])])
  ]);
  const body = makeElement('body', {}, [script, heading, para, link, table, pwd, hidden, card, search]);
  const doc = {
    title: 'Nexo Docs',
    location: { href: 'https://docs.example.com/?session=secret123&topic=ai' },
    body,
    querySelectorAll: (sel) => body.querySelectorAll(sel)
  };
  const win = { getSelection: () => ({ toString: () => 'Texto seleccionado' }) };

  const snap = agent.extractSafeSnapshot(doc, win, 2000);
  assert.equal(snap.title, 'Nexo Docs');
  assert.equal(snap.url, 'https://docs.example.com/?topic=ai');
  assert.equal(snap.selection, 'Texto seleccionado');
  assert.deepEqual(snap.headings, ['Guía de Nexo Browser']);
  assert.equal(snap.links.length, 1);
  assert.equal(snap.links[0].href, 'https://docs.example.com/guide?lang=es');
  assert.equal(snap.tables.length, 1);
  assert.deepEqual(snap.tables[0][0], ['Motor', 'Estado']);
  assert.equal(snap.forms.length, 1);
  assert.equal(snap.forms[0].name, 'query');
  assert.ok(!snap.text.includes('NO_EXTRACT'));
  assert.ok(!snap.text.includes('TOPSECRET'));
  assert.ok(snap.text.includes('Contenido visible para resumir'));
});

test('fillDraftField refuses sensitive fields and updates safe fields', () => {
  const pwd = makeElement('input', { type: 'password', name: 'password' });
  const comment = makeElement('textarea', { name: 'comment' });
  const body = makeElement('body', {}, [pwd, comment]);
  const doc = { querySelectorAll: (sel) => body.querySelectorAll(sel) };

  const rejected = agent.fillDraftField(doc, 'password', 'hack');
  assert.equal(rejected.ok, false);
  assert.equal(pwd.value, '');

  const ok = agent.fillDraftField(doc, 'comment', 'Borrador aprobado');
  assert.equal(ok.ok, true);
  assert.equal(comment.value, 'Borrador aprobado');
});

test('highlightText marks matching paragraph and scrolls into view', () => {
  const p = makeElement('p', {}, [makeText('El precio mensual incluye soporte completo.')]);
  const body = makeElement('body', {}, [p]);
  const doc = { querySelectorAll: (sel) => body.querySelectorAll(sel) };

  const res = agent.highlightText(doc, 'precio mensual');
  assert.equal(res.ok, true);
  assert.equal(res.found, true);
  assert.equal(p.scrolled, true);
  assert.ok(p.style.outline.includes('solid'));
});
