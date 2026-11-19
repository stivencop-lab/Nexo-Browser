/*
 * Nexo Page Translator: extracción segmentada de nodos de texto visibles y
 * restauración instantánea sin recargar la página.
 *
 * Excluye explícitamente scripts, estilos, bloques de código, formularios,
 * campos editables y nodos marcados con translate="no" / .notranslate.
 */
(function(root) {
  'use strict';

  const SKIP_TAGS = new Set([
    'SCRIPT', 'STYLE', 'NOSCRIPT', 'CODE', 'PRE', 'KBD', 'SAMP', 'VAR',
    'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON', 'SVG', 'MATH', 'CANVAS', 'VIDEO', 'AUDIO'
  ]);
  const MAX_SEGMENTS = 180;
  const MAX_CHARS_PER_SEGMENT = 500;

  function isSkippedElement(el) {
    let cur = el;
    while (cur && cur.nodeType === 1) {
      const tag = String(cur.tagName || '').toUpperCase();
      if (SKIP_TAGS.has(tag)) return true;
      if (cur.getAttribute && (cur.getAttribute('translate') === 'no' || cur.getAttribute('contenteditable') === 'true')) {
        return true;
      }
      if (cur.classList && cur.classList.contains('notranslate')) return true;
      cur = cur.parentElement;
    }
    return false;
  }

  function isVisible(el, win) {
    if (!el || el.nodeType !== 1) return true;
    try {
      const getStyle = (win && win.getComputedStyle) || (typeof getComputedStyle === 'function' ? getComputedStyle : null);
      if (!getStyle) return true;
      const st = getStyle(el);
      if (!st) return true;
      return st.display !== 'none' && st.visibility !== 'hidden';
    } catch (_) {
      return true;
    }
  }

  function createPageTranslator(doc, win) {
    const state = {
      nodes: [],
      originals: [],
      translated: false
    };

    function collectTextNodes(node, out) {
      if (!node || out.length >= MAX_SEGMENTS) return;
      if (node.nodeType === 3) {
        const parent = node.parentElement;
        if (!parent || isSkippedElement(parent) || !isVisible(parent, win)) return;
        const raw = String(node.nodeValue || '');
        const trimmed = raw.replace(/\s+/g, ' ').trim();
        if (trimmed.length < 2 || /^[\d\s.,:;!?/%+\-–—()[\]{}]+$/.test(trimmed)) return;
        out.push(node);
        return;
      }
      if (node.nodeType === 1) {
        if (isSkippedElement(node) || !isVisible(node, win)) return;
        const children = node.childNodes || [];
        for (let i = 0; i < children.length && out.length < MAX_SEGMENTS; i++) {
          collectTextNodes(children[i], out);
        }
      }
    }

    function extractSegments() {
      if (state.translated) restore();
      const body = doc && (doc.body || doc.documentElement);
      if (!body) return [];
      const nodes = [];
      collectTextNodes(body, nodes);
      state.nodes = nodes;
      state.originals = nodes.map(n => String(n.nodeValue || ''));
      return nodes.map((n, index) => ({
        i: index,
        t: String(n.nodeValue || '').replace(/\s+/g, ' ').trim().slice(0, MAX_CHARS_PER_SEGMENT)
      }));
    }

    function applyTranslations(entries) {
      if (!Array.isArray(entries) || !state.nodes.length) return 0;
      let applied = 0;
      for (const item of entries) {
        if (!item || typeof item.i !== 'number' || typeof item.t !== 'string') continue;
        const node = state.nodes[item.i];
        if (!node || !item.t.trim()) continue;
        const orig = state.originals[item.i] || '';
        const lead = /^\s*/.exec(orig)[0];
        const trail = /\s*$/.exec(orig)[0];
        node.nodeValue = lead + item.t.trim() + trail;
        applied++;
      }
      if (applied > 0) state.translated = true;
      return applied;
    }

    function restore() {
      let restored = 0;
      for (let i = 0; i < state.nodes.length; i++) {
        const node = state.nodes[i];
        if (node && typeof state.originals[i] === 'string') {
          node.nodeValue = state.originals[i];
          restored++;
        }
      }
      state.translated = false;
      return restored;
    }

    return {
      extractSegments,
      applyTranslations,
      restore,
      isTranslated: () => state.translated
    };
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { createPageTranslator };
  } else if (root) {
    root.__nexoCreatePageTranslator = createPageTranslator;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
