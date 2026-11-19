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
  const MAX_SEGMENTS = 400;
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

  function isNodeConnected(node) {
    if (!node) return false;
    if (typeof node.isConnected === 'boolean') return node.isConnected;
    return true;
  }

  function createPageTranslator(doc, win) {
    const state = {
      nodes: [],
      originals: [],
      translated: false,
      generation: 0
    };

    function collectTextNodes(node, out, counter) {
      if (!node) return;
      if (node.nodeType === 3) {
        const parent = node.parentElement;
        if (!parent || isSkippedElement(parent) || !isVisible(parent, win)) return;
        const raw = String(node.nodeValue || '');
        const trimmed = raw.replace(/\s+/g, ' ').trim();
        if (trimmed.length < 2 || /^[\d\s.,:;!?/%+\-–—()[\]{}]+$/.test(trimmed)) return;
        counter.totalEligible++;
        if (out.length < MAX_SEGMENTS) {
          out.push(node);
        }
        return;
      }
      if (node.nodeType === 1) {
        if (isSkippedElement(node) || !isVisible(node, win)) return;
        const children = node.childNodes || [];
        for (let i = 0; i < children.length; i++) {
          collectTextNodes(children[i], out, counter);
        }
      }
    }

    function extractSegmentsWithMeta() {
      if (state.translated) restore();
      state.generation++;
      const body = doc && (doc.body || doc.documentElement);
      if (!body) {
        state.nodes = [];
        state.originals = [];
        return { segments: [], totalEligible: 0, truncated: false, generation: state.generation };
      }
      const nodes = [];
      const counter = { totalEligible: 0 };
      collectTextNodes(body, nodes, counter);
      state.nodes = nodes;
      state.originals = nodes.map(n => String(n.nodeValue || ''));
      const segments = nodes.map((n, index) => ({
        i: index,
        t: String(n.nodeValue || '').replace(/\s+/g, ' ').trim().slice(0, MAX_CHARS_PER_SEGMENT)
      }));
      return {
        segments,
        totalEligible: counter.totalEligible,
        truncated: counter.totalEligible > segments.length,
        generation: state.generation
      };
    }

    function extractSegments() {
      return extractSegmentsWithMeta().segments;
    }

    function applyTranslations(entries, expectedGen) {
      if (!Array.isArray(entries) || !state.nodes.length) return 0;
      if (typeof expectedGen === 'number' && expectedGen !== state.generation) return 0;
      let applied = 0;
      for (const item of entries) {
        if (!item || typeof item.i !== 'number' || typeof item.t !== 'string') continue;
        const node = state.nodes[item.i];
        if (!node || !isNodeConnected(node) || !item.t.trim()) continue;
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
      state.generation++;
      let restored = 0;
      for (let i = 0; i < state.nodes.length; i++) {
        const node = state.nodes[i];
        if (node && isNodeConnected(node) && typeof state.originals[i] === 'string') {
          node.nodeValue = state.originals[i];
          restored++;
        }
      }
      state.translated = false;
      return restored;
    }

    return {
      extractSegments,
      extractSegmentsWithMeta,
      applyTranslations,
      restore,
      isTranslated: () => state.translated,
      generation: () => state.generation
    };
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { createPageTranslator, MAX_SEGMENTS };
  } else if (root) {
    root.__nexoCreatePageTranslator = createPageTranslator;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
