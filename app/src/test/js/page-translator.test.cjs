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
