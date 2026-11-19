'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createPageTranslator } = require('../../main/assets/nexo/page-translator.js');

function el(tag, opts = {}, children = []) {
  const classes = new Set(opts.classes || []);
  const attrs = opts.attrs || {};
  const node = {
    nodeType: 1,
    tagName: tag.toUpperCase(),
    childNodes: [],
    parentElement: null,
    classList: { contains: (c) => classes.has(c) },
    getAttribute: (k) => (Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null)
  };
  for (const c of children) {
    c.parentElement = node;
    node.childNodes.push(c);
  }
  return node;
}

function txt(val) {
  return { nodeType: 3, nodeValue: val, parentElement: null };
}

test('extractSegments skips code/script/input/notranslate and restores originals without reload', () => {
  const tTitle = txt('  Welcome to Nexo  ');
  const tBody = txt('Fast private browsing.');
  const tCode = txt('const x = 1;');
  const tNoTrans = txt('BrandName');
  const tEditable = txt('User draft');
  const tScript = txt('window.alert(1);');

  const body = el('body', {}, [
    el('h1', {}, [tTitle]),
    el('p', {}, [tBody]),
    el('pre', {}, [el('code', {}, [tCode])]),
    el('span', { attrs: { translate: 'no' } }, [tNoTrans]),
    el('div', { attrs: { contenteditable: 'true' } }, [tEditable]),
    el('script', {}, [tScript]),
    el('span', { classes: ['notranslate'] }, [txt('Do not translate')])
  ]);

  const doc = { body };
  const translator = createPageTranslator(doc, {});
  const segs = translator.extractSegments();

  assert.equal(segs.length, 2);
  assert.equal(segs[0].t, 'Welcome to Nexo');
  assert.equal(segs[1].t, 'Fast private browsing.');

  const applied = translator.applyTranslations([
    { i: 0, t: 'Bienvenido a Nexo' },
    { i: 1, t: 'Navegación rápida y privada.' }
  ]);
  assert.equal(applied, 2);
  assert.equal(translator.isTranslated(), true);
  assert.equal(tTitle.nodeValue, '  Bienvenido a Nexo  ');
  assert.equal(tBody.nodeValue, 'Navegación rápida y privada.');
  assert.equal(tCode.nodeValue, 'const x = 1;');
  assert.equal(tNoTrans.nodeValue, 'BrandName');

  const restored = translator.restore();
  assert.equal(restored, 2);
  assert.equal(translator.isTranslated(), false);
  assert.equal(tTitle.nodeValue, '  Welcome to Nexo  ');
  assert.equal(tBody.nodeValue, 'Fast private browsing.');
});

test('applyTranslations ignores disconnected SPA nodes and stale generations after restore', () => {
  const t1 = txt('Live paragraph one.');
  t1.isConnected = true;
  const t2 = txt('Removed SPA paragraph.');
  t2.isConnected = true;
  const body = el('body', {}, [el('p', {}, [t1]), el('p', {}, [t2])]);
  const translator = createPageTranslator({ body }, {});

  const meta = translator.extractSegmentsWithMeta();
  assert.equal(meta.segments.length, 2);
  assert.equal(meta.totalEligible, 2);
  assert.equal(meta.truncated, false);
  const gen = meta.generation;

  // Simulate SPA unmount of second node before batch returns
  t2.isConnected = false;
  const applied = translator.applyTranslations([
    { i: 0, t: 'Párrafo vivo uno.' },
    { i: 1, t: 'No debería aplicarse.' }
  ], gen);
  assert.equal(applied, 1);
  assert.equal(t1.nodeValue, 'Párrafo vivo uno.');
  assert.equal(t2.nodeValue, 'Removed SPA paragraph.');

  // Restore advances generation; late batches from the previous generation must be ignored
  assert.equal(translator.restore(), 1);
  assert.equal(t1.nodeValue, 'Live paragraph one.');
  const staleApplied = translator.applyTranslations([{ i: 0, t: 'Late batch' }], gen);
  assert.equal(staleApplied, 0);
  assert.equal(t1.nodeValue, 'Live paragraph one.');
});

test('extractSegmentsWithMeta reports totalEligible and truncated honestly on long documents', () => {
  const children = [];
  for (let i = 0; i < 425; i++) {
    children.push(el('p', {}, [txt(`Paragraph number ${i} with translatable prose.`)]));
  }
  const translator = createPageTranslator({ body: el('body', {}, children) }, {});
  const meta = translator.extractSegmentsWithMeta();
  assert.equal(meta.segments.length, 400);
  assert.equal(meta.totalEligible, 425);
  assert.equal(meta.truncated, true);
});
