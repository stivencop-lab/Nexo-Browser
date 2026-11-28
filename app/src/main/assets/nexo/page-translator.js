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
      const editable = cur.getAttribute && cur.getAttribute('contenteditable');
      if (cur.isContentEditable || editable === '' || editable === 'true' || editable === 'plaintext-only' ||
          (cur.getAttribute && cur.getAttribute('translate') === 'no')) return true;
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
      records: [],
      pieces: [],
      translated: false,
      generation: 0
    };

    function splitText(text) {
      const parts = [];
      for (let start = 0; start < text.length;) {
        let end = Math.min(start + MAX_CHARS_PER_SEGMENT, text.length);
        if (end < text.length) {
          const breakAt = text.lastIndexOf(' ', end);
          if (breakAt > start) end = breakAt;
          else if (/[\uD800-\uDBFF]/.test(text[end - 1])) end--;
        }
        const chunk = text.slice(start, end).trim();
        let next = end;
        while (text[next] === ' ') next++;
        if (chunk) parts.push({ text: chunk, separator: text.slice(end, next) });
        start = next;
      }
      return parts;
    }

    function extractSegmentsWithMeta() {
      if (state.translated) restore();
      state.generation++;
      state.records = [];
      state.pieces = [];
      const body = doc && (doc.body || doc.documentElement);
      const segments = [];
      let totalEligible = 0;
      const pending = body ? [body] : [];
      while (pending.length) {
        const node = pending.pop();
        if (node.nodeType === 1) {
          if (isSkippedElement(node) || !isVisible(node, win)) continue;
          const children = node.childNodes || [];
          for (let i = children.length - 1; i >= 0; i--) pending.push(children[i]);
          continue;
        }
        if (node.nodeType !== 3 || !node.parentElement) continue;
        const raw = String(node.nodeValue || '');
        const trimmed = raw.replace(/\s+/g, ' ').trim();
        if (trimmed.length < 2 || /^[\d\s.,:;!?/%+\-–—()[\]{}]+$/.test(trimmed)) continue;
        if (trimmed.length > MAX_SEGMENTS * MAX_CHARS_PER_SEGMENT) {
          totalEligible += Math.ceil(trimmed.length / MAX_CHARS_PER_SEGMENT);
          continue;
        }
        const parts = splitText(trimmed);
        totalEligible += parts.length;
        if (segments.length + parts.length > MAX_SEGMENTS) continue;
        const record = { node, raw, parts, translated: null, remaining: parts.length };
        state.records.push(record);
        for (const part of parts) {
          segments.push({ i: state.pieces.length, t: part.text });
          state.pieces.push({ record, part });
        }
      }
      return {
        segments,
        totalEligible,
        truncated: totalEligible > segments.length,
        generation: state.generation
      };
    }

    function extractSegments() {
      return extractSegmentsWithMeta().segments;
    }

    function applyTranslations(entries, expectedGen) {
      if (!Array.isArray(entries) || !state.pieces.length) return 0;
      if (typeof expectedGen === 'number' && expectedGen !== state.generation) return 0;
      let applied = 0;
      for (const item of entries) {
        if (!item || !Number.isInteger(item.i) || typeof item.t !== 'string' || !item.t.trim()) continue;
        const piece = state.pieces[item.i];
        if (!piece) continue;
        const { record, part } = piece;
        if (part.result !== undefined || record.translated !== null || !isNodeConnected(record.node) ||
            record.node.nodeValue !== record.raw || isSkippedElement(record.node.parentElement)) continue;
        part.result = item.t.trim();
        if (--record.remaining > 0) continue;
        const lead = /^\s*/.exec(record.raw)[0];
        const trail = /\s*$/.exec(record.raw)[0];
        record.translated = lead + record.parts.map(p => p.result + p.separator).join('') + trail;
        record.node.nodeValue = record.translated;
        applied += record.parts.length;
      }
      if (applied > 0) state.translated = true;
      return applied;
    }

    function restore() {
      state.generation++;
      let restored = 0;
      for (const record of state.records) {
        if (record.translated !== null && isNodeConnected(record.node) &&
            record.node.nodeValue === record.translated) {
          record.node.nodeValue = record.raw;
          restored++;
        }
      }
      state.records = [];
      state.pieces = [];
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
