/*
 * Nexo AI Page Agent: extracción segura de contexto de página, indexación de
 * elementos interactivos ([e1], [e2]…) y acciones asistidas acotadas.
 *
 * Seguridad estricta:
 * - Excluye contraseñas, tarjetas (cc-*), OTP, campos hidden y cualquier
 *   valor de formulario sensible.
 * - Nunca lee cookies, localStorage ni tokens en URL.
 * - Acota el tamaño total de contexto enviado al proveedor.
 */
(function(root) {
  'use strict';

  const SENSITIVE_AUTOCOMPLETE = /(password|cc-|credit|card|cvv|cvc|otp|one-time|secret|token)/i;
  const SENSITIVE_NAME = /(pass|pwd|secret|token|csrf|credit|card|cvv|cvc|ssn|pin|otp)/i;
  const SENSITIVE_QUERY_KEYS = new Set([
    'token', 'access_token', 'id_token', 'auth', 'key', 'api_key', 'apikey',
    'password', 'pwd', 'secret', 'session', 'sid', 'code', 'signature', 'sig'
  ]);
  const SKIP_TEXT_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'CANVAS', 'TEMPLATE']);
  const SECRET_PATTERNS = [
    /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}/gi,
    /\bsk-(?:proj-|ant-|live-|test-)?[A-Za-z0-9_-]{16,}\b/g,
    /\bAIza[0-9A-Za-z_-]{20,}\b/g,
    /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g
  ];

  // Mapa volátil de referencias [e1..e35] por ejecución en la página (sin contaminar atributos DOM).
  const indexedRefs = new Map();

  function redactInlineSecrets(text) {
    let out = String(text || '');
    for (let i = 0; i < SECRET_PATTERNS.length; i++) {
      out = out.replace(SECRET_PATTERNS[i], '[REDACTADO]');
    }
    return out;
  }

  function isHiddenElement(el, win) {
    if (!el || el.nodeType !== 1) return false;
    if (el.hidden === true) return true;
    const getAttr = typeof el.getAttribute === 'function' ? function(k) { return el.getAttribute(k); } : function() { return null; };
    if (getAttr('hidden') !== null) return true;
    if (String(getAttr('aria-hidden') || '').toLowerCase() === 'true') return true;
    if (getAttr('inert') !== null) return true;

    const inline = el.style || {};
    const computed = (win && typeof win.getComputedStyle === 'function')
      ? (function() { try { return win.getComputedStyle(el) || {}; } catch (_) { return {}; } })()
      : {};

    const display = String(computed.display || inline.display || '').toLowerCase().trim();
    if (display === 'none') return true;
    const visibility = String(computed.visibility || inline.visibility || '').toLowerCase().trim();
    if (visibility === 'hidden' || visibility === 'collapse') return true;
    const opacityRaw = computed.opacity !== undefined && computed.opacity !== '' ? computed.opacity : inline.opacity;
    if (opacityRaw !== undefined && opacityRaw !== '' && Number(opacityRaw) === 0) return true;
    const fontSize = String(computed.fontSize || inline.fontSize || '').toLowerCase().trim();
    if (fontSize === '0' || fontSize === '0px') return true;

    // Detectar técnicas sr-only / off-screen usadas en ataques de prompt injection ocultos.
    const clip = String(computed.clip || inline.clip || '').replace(/\s+/g, '').toLowerCase();
    if (clip === 'rect(0px,0px,0px,0px)' || clip === 'rect(0,0,0,0)') return true;
    const clipPath = String(computed.clipPath || inline.clipPath || '').replace(/\s+/g, '').toLowerCase();
    if (clipPath.includes('inset(50%)') || clipPath.includes('inset(100%)')) return true;
    const overflow = String(computed.overflow || inline.overflow || '').toLowerCase().trim();
    const width = String(computed.width || inline.width || '').toLowerCase().trim();
    const height = String(computed.height || inline.height || '').toLowerCase().trim();
    if (overflow === 'hidden' && (width === '0px' || width === '1px') && (height === '0px' || height === '1px')) {
      return true;
    }
    const leftVal = parseFloat(String(computed.left || inline.left || ''));
    const textIndentVal = parseFloat(String(computed.textIndent || inline.textIndent || ''));
    if ((!isNaN(leftVal) && leftVal <= -1000) || (!isNaN(textIndentVal) && textIndentVal <= -1000)) {
      return true;
    }
    return false;
  }

  function hasHiddenAncestor(el, win) {
    let cur = el;
    let depth = 0;
    while (cur && cur.nodeType === 1 && depth < 32) {
      if (isHiddenElement(cur, win)) return true;
      cur = cur.parentElement;
      depth++;
    }
    return false;
  }

  function sanitizeUrl(rawUrl) {
    try {
      const u = new URL(String(rawUrl || ''));
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      u.username = '';
      u.password = '';
      const cleanParams = [];
      u.searchParams.forEach(function(val, key) {
        if (!SENSITIVE_QUERY_KEYS.has(String(key).toLowerCase())) {
          cleanParams.push([key, val.slice(0, 80)]);
        }
      });
      u.search = '';
      cleanParams.forEach(function(pair) { u.searchParams.append(pair[0], pair[1]); });
      return u.toString().slice(0, 400);
    } catch (_) {
      return '';
    }
  }

  function isSensitiveField(el) {
    if (!el || el.nodeType !== 1) return true;
    const type = String((el.getAttribute && el.getAttribute('type')) || el.type || '').toLowerCase();
    if (type === 'password' || type === 'hidden' || type === 'file') return true;
    const ac = String((el.getAttribute && el.getAttribute('autocomplete')) || '');
    if (SENSITIVE_AUTOCOMPLETE.test(ac)) return true;
    const nameId = String((el.getAttribute && el.getAttribute('name')) || '') + ' ' +
      String((el.getAttribute && el.getAttribute('id')) || '') + ' ' +
      String((el.getAttribute && el.getAttribute('aria-label')) || '');
    if (SENSITIVE_NAME.test(nameId)) return true;
    return false;
  }

  function formHasSensitiveFields(el) {
    try {
      const form = el && el.form ? el.form : (el && typeof el.closest === 'function' ? el.closest('form') : null);
      if (!form || !form.querySelectorAll) return false;
      const inputs = form.querySelectorAll('input');
      for (let i = 0; i < inputs.length; i++) {
        if (isSensitiveField(inputs[i])) return true;
      }
    } catch (_) {}
    return false;
  }

  function collectVisibleText(node, chunks, state, win) {
    if (!node || state.chars >= state.budget) return;
    if (node.nodeType === 3) {
      const parent = node.parentElement;
      if (parent && SKIP_TEXT_TAGS.has(String(parent.tagName || '').toUpperCase())) return;
      const t = redactInlineSecrets(String(node.nodeValue || '').replace(/\s+/g, ' ').trim());
      if (!t) return;
      const room = state.budget - state.chars;
      const piece = t.slice(0, room);
      chunks.push(piece);
      state.chars += piece.length + 1;
      return;
    }
    if (node.nodeType === 1) {
      const tag = String(node.tagName || '').toUpperCase();
      if (SKIP_TEXT_TAGS.has(tag)) return;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (isHiddenElement(node, win)) return;
      const children = node.childNodes || [];
      for (let i = 0; i < children.length && state.chars < state.budget; i++) {
        collectVisibleText(children[i], chunks, state, win);
      }
    }
  }

  function visibleSubtreeText(el, win, maxLen) {
    const parts = [];
    collectVisibleText(el, parts, { chars: 0, budget: maxLen }, win);
    return parts.join(' ').slice(0, maxLen).trim();
  }

  function computeScrollInfo(doc, win) {
    try {
      const el = doc && (doc.documentElement || doc.body);
      const scrollY = Math.max(0, Math.round((win && win.scrollY) || (el && el.scrollTop) || 0));
      const viewportHeight = Math.max(1, Math.round((win && win.innerHeight) || (el && el.clientHeight) || 800));
      const scrollHeight = Math.max(viewportHeight, Math.round((el && el.scrollHeight) || viewportHeight));
      const maxScroll = Math.max(1, scrollHeight - viewportHeight);
      const percent = Math.min(100, Math.max(0, Math.round((scrollY / maxScroll) * 100)));
      return { scrollY, viewportHeight, scrollHeight, percent };
    } catch (_) {
      return { scrollY: 0, viewportHeight: 800, scrollHeight: 800, percent: 0 };
    }
  }

  function extractSafeSnapshot(doc, win, maxChars) {
    const budget = Math.max(1000, Math.min(16000, Number(maxChars) || 8000));
    const title = redactInlineSecrets(String((doc && doc.title) || '').replace(/\s+/g, ' ').trim()).slice(0, 200);
    const url = sanitizeUrl(doc && doc.location ? doc.location.href : '');

    let selection = '';
    try {
      const sel = win && typeof win.getSelection === 'function' ? win.getSelection() : null;
      selection = redactInlineSecrets(String(sel ? sel.toString() : '').replace(/\s+/g, ' ').trim()).slice(0, 2000);
    } catch (_) {}

    const headings = [];
    try {
      const hs = doc.querySelectorAll ? doc.querySelectorAll('h1, h2, h3') : [];
      for (let i = 0; i < hs.length && headings.length < 20; i++) {
        if (hasHiddenAncestor(hs[i], win)) continue;
        const text = visibleSubtreeText(hs[i], win, 140);
        if (text) headings.push(text);
      }
    } catch (_) {}

    const links = [];
    try {
      const as = doc.querySelectorAll ? doc.querySelectorAll('a[href]') : [];
      for (let i = 0; i < as.length && links.length < 20; i++) {
        if (hasHiddenAncestor(as[i], win)) continue;
        const href = sanitizeUrl(as[i].href || (as[i].getAttribute && as[i].getAttribute('href')));
        const text = visibleSubtreeText(as[i], win, 100);
        if (href && text) links.push({ text, href });
      }
    } catch (_) {}

    const tables = [];
    try {
      const tbls = doc.querySelectorAll ? doc.querySelectorAll('table') : [];
      for (let i = 0; i < tbls.length && tables.length < 4; i++) {
        if (hasHiddenAncestor(tbls[i], win)) continue;
        const rows = [];
        const trs = tbls[i].querySelectorAll ? tbls[i].querySelectorAll('tr') : [];
        for (let r = 0; r < trs.length && rows.length < 10; r++) {
          if (isHiddenElement(trs[r], win)) continue;
          const cells = [];
          const tds = trs[r].querySelectorAll ? trs[r].querySelectorAll('th, td') : [];
          for (let c = 0; c < tds.length && cells.length < 8; c++) {
            if (isHiddenElement(tds[c], win)) continue;
            cells.push(visibleSubtreeText(tds[c], win, 80));
          }
          if (cells.some(Boolean)) rows.push(cells);
        }
        if (rows.length) tables.push(rows);
      }
    } catch (_) {}

    const forms = [];
    try {
      const fields = doc.querySelectorAll ? doc.querySelectorAll('input, textarea, select') : [];
      for (let i = 0; i < fields.length && forms.length < 15; i++) {
        const el = fields[i];
        if (isSensitiveField(el) || hasHiddenAncestor(el, win)) continue;
        const name = String((el.getAttribute && el.getAttribute('name')) || (el.getAttribute && el.getAttribute('id')) || '').trim();
        const type = String((el.getAttribute && el.getAttribute('type')) || el.tagName || 'text').toLowerCase();
        const placeholder = redactInlineSecrets(String((el.getAttribute && el.getAttribute('placeholder')) || '').trim()).slice(0, 80);
        if (name || placeholder) forms.push({ name: name.slice(0, 80), type: type.slice(0, 24), placeholder });
      }
    } catch (_) {}

    // Indexar elementos interactivos seguros [e1..e35] para el Modo Agente.
    indexedRefs.clear();
    const interactives = [];
    try {
      const nodes = doc.querySelectorAll ? doc.querySelectorAll('a[href], button, input, textarea, select') : [];
      for (let i = 0; i < nodes.length && interactives.length < 35; i++) {
        const el = nodes[i];
        if (!el || hasHiddenAncestor(el, win)) continue;
        const tag = String(el.tagName || '').toLowerCase();
        if ((tag === 'input' || tag === 'textarea' || tag === 'select') && isSensitiveField(el)) continue;
        const text = redactInlineSecrets(
          visibleSubtreeText(el, win, 80) ||
          String((el.getAttribute && el.getAttribute('aria-label')) || '') ||
          String((el.getAttribute && el.getAttribute('placeholder')) || '') ||
          String((el.getAttribute && el.getAttribute('title')) || '')
        ).replace(/\s+/g, ' ').trim().slice(0, 80);
        const href = tag === 'a' ? sanitizeUrl(el.href || (el.getAttribute && el.getAttribute('href'))) : '';
        const fieldName = String((el.getAttribute && el.getAttribute('name')) || (el.getAttribute && el.getAttribute('id')) || '').trim().slice(0, 60);
        if (!text && !fieldName) continue;
        const refId = 'e' + (interactives.length + 1);
        indexedRefs.set(refId, el);
        interactives.push({
          ref: refId,
          tag,
          text: text || fieldName,
          href,
          fieldName
        });
      }
    } catch (_) {}

    const textChunks = [];
    const textBudget = Math.max(800, budget - 2000);
    const body = doc && (doc.body || doc.documentElement);
    if (body) collectVisibleText(body, textChunks, { chars: 0, budget: textBudget }, win);
    const bodyText = textChunks.join(' ').slice(0, textBudget);

    return {
      title,
      url,
      selection,
      headings,
      links,
      tables,
      forms,
      interactives,
      scroll: computeScrollInfo(doc, win),
      text: bodyText
    };
  }

  /** Acción segura de solo lectura: resalta y desplaza hasta la primera coincidencia de texto. */
  function highlightText(doc, query) {
    const term = String(query || '').trim().toLowerCase();
    if (!term || !doc || !doc.querySelectorAll) return { ok: false, found: false };
    const candidates = doc.querySelectorAll('p, li, h1, h2, h3, h4, td, span, div');
    for (let i = 0; i < candidates.length; i++) {
      const el = candidates[i];
      if (!el || SKIP_TEXT_TAGS.has(String(el.tagName || '').toUpperCase())) continue;
      const text = String(el.textContent || '').toLowerCase();
      if (text.includes(term) && text.length < 800) {
        try {
          if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          if (el.style) {
            el.style.outline = '2px solid #f59e0b';
            el.style.outlineOffset = '2px';
          }
        } catch (_) {}
        return { ok: true, found: true };
      }
    }
    return { ok: true, found: false };
  }

  function setNativeValue(el, value) {
    try {
      const proto = el.tagName && String(el.tagName).toUpperCase() === 'TEXTAREA'
        ? (typeof HTMLTextAreaElement !== 'undefined' ? HTMLTextAreaElement.prototype : null)
        : (typeof HTMLInputElement !== 'undefined' ? HTMLInputElement.prototype : null);
      const desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
      if (desc && typeof desc.set === 'function') {
        desc.set.call(el, value);
        return;
      }
    } catch (_) {}
    el.value = value;
  }

  /** Acción asistida que requiere confirmación explícita del usuario antes de ejecutarse. */
  function fillDraftField(doc, fieldName, draftValue) {
    const targetName = String(fieldName || '').trim();
    if (!targetName || !doc || !doc.querySelectorAll) return { ok: false, error: 'Campo no indicado.' };

    // 1. Buscar por referencia indexada [eN] si existe
    const byRef = indexedRefs.get(targetName.toLowerCase());
    if (byRef && !isSensitiveField(byRef)) {
      const val = String(draftValue || '').slice(0, 2000);
      setNativeValue(byRef, val);
      try { byRef.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
      try { byRef.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
      return { ok: true, field: targetName };
    }

    const fields = doc.querySelectorAll('input, textarea');
    const lowerTarget = targetName.toLowerCase();
    for (let i = 0; i < fields.length; i++) {
      const el = fields[i];
      if (isSensitiveField(el)) continue;
      const name = String((el.getAttribute && el.getAttribute('name')) || '');
      const id = String((el.getAttribute && el.getAttribute('id')) || '');
      const placeholder = String((el.getAttribute && el.getAttribute('placeholder')) || '');
      const aria = String((el.getAttribute && el.getAttribute('aria-label')) || '');
      if (name === targetName || id === targetName ||
          placeholder.toLowerCase() === lowerTarget || aria.toLowerCase() === lowerTarget) {
        const val = String(draftValue || '').slice(0, 2000);
        setNativeValue(el, val);
        try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
        try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
        return { ok: true, field: targetName };
      }
    }
    return { ok: false, error: 'Campo no encontrado o protegido.' };
  }

  /** Selecciona una opción en un <select> no sensible (requiere confirmación). */
  function selectOption(doc, fieldName, optionText) {
    const target = String(fieldName || '').trim().toLowerCase();
    const wanted = String(optionText || '').trim().toLowerCase();
    if (!target || !wanted || !doc || !doc.querySelectorAll) return { ok: false, error: 'Parámetros incompletos.' };
    const selects = doc.querySelectorAll('select');
    for (let i = 0; i < selects.length; i++) {
      const sel = selects[i];
      if (isSensitiveField(sel)) continue;
      const name = String((sel.getAttribute && sel.getAttribute('name')) || '').toLowerCase();
      const id = String((sel.getAttribute && sel.getAttribute('id')) || '').toLowerCase();
      const refEl = indexedRefs.get(target);
      if (sel === refEl || name === target || id === target) {
        const opts = sel.options || [];
        for (let j = 0; j < opts.length; j++) {
          const txt = String(opts[j].text || opts[j].textContent || '').trim().toLowerCase();
          const val = String(opts[j].value || '').trim().toLowerCase();
          if (txt.includes(wanted) || val === wanted) {
            sel.selectedIndex = j;
            try { sel.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
            return { ok: true, selected: opts[j].text || opts[j].value };
          }
        }
      }
    }
    return { ok: false, error: 'Selector u opción no encontrada.' };
  }

  /** Resalta brevemente el elemento sobre el que actúa el agente en vivo. */
  function flashAgentTarget(doc, win, el) {
    try {
      if (!doc || !doc.createElement || !doc.body || typeof el.getBoundingClientRect !== 'function') return;
      const rect = el.getBoundingClientRect();
      if (!rect || (rect.width <= 0 && rect.height <= 0)) return;
      const ring = doc.createElement('div');
      ring.setAttribute('data-nexo-agent-pulse', '1');
      ring.style.cssText = [
        'position:fixed',
        'left:' + Math.max(0, rect.left - 4) + 'px',
        'top:' + Math.max(0, rect.top - 4) + 'px',
        'width:' + Math.max(16, rect.width + 8) + 'px',
        'height:' + Math.max(16, rect.height + 8) + 'px',
        'border:2px solid #2563EB',
        'background:rgba(37,99,235,0.14)',
        'border-radius:10px',
        'box-sizing:border-box',
        'pointer-events:none',
        'z-index:2147483647',
        'transition:opacity 0.35s ease'
      ].join(';');
      doc.body.appendChild(ring);
      if (win && typeof win.setTimeout === 'function') {
        win.setTimeout(function () {
          try { ring.style.opacity = '0'; } catch (_) {}
          win.setTimeout(function () {
            try { if (ring.parentNode) ring.parentNode.removeChild(ring); } catch (_) {}
          }, 360);
        }, 480);
      }
    } catch (_) {}
  }

  /** Pulsa de forma segura un enlace o botón visible (rechaza formularios sensibles y controles ocultos). */
  function clickElement(doc, win, target) {
    const raw = String(target || '').trim();
    if (!raw || !doc) return { ok: false, error: 'Elemento no indicado.' };
    let el = indexedRefs.get(raw.toLowerCase()) || null;
    if (!el && doc.querySelectorAll) {
      const lower = raw.toLowerCase();
      const nodes = doc.querySelectorAll('a[href], button, input');
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        if (!n || hasHiddenAncestor(n, win)) continue;
        const txt = (visibleSubtreeText(n, win, 100) ||
          String((n.getAttribute && n.getAttribute('aria-label')) || '')).toLowerCase();
        if (txt && (txt === lower || txt.includes(lower))) {
          el = n;
          break;
        }
      }
    }
    if (!el || hasHiddenAncestor(el, win)) return { ok: false, error: 'Elemento no visible o no encontrado.' };
    const tag = String(el.tagName || '').toLowerCase();
    if (tag === 'input' && isSensitiveField(el)) return { ok: false, error: 'Elemento protegido.' };
    const type = String((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
    if ((type === 'submit' || tag === 'button') && formHasSensitiveFields(el)) {
      return { ok: false, error: 'Acción bloqueada sobre formulario con datos sensibles.' };
    }
    try {
      if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      flashAgentTarget(doc, win, el);
      if (typeof el.click === 'function') el.click();
      return { ok: true, clicked: (visibleSubtreeText(el, win, 60) || raw).slice(0, 60) };
    } catch (_) {
      return { ok: false, error: 'No se pudo activar el elemento.' };
    }
  }

  /** Desplaza la página y devuelve la nueva posición de scroll. */
  function scrollPage(doc, win, direction) {
    const dir = String(direction || 'down').toLowerCase();
    const info = computeScrollInfo(doc, win);
    const step = Math.round(info.viewportHeight * 0.78);
    try {
      if (win && typeof win.scrollTo === 'function') {
        if (dir === 'top') win.scrollTo({ top: 0, behavior: 'smooth' });
        else if (dir === 'bottom') win.scrollTo({ top: info.scrollHeight, behavior: 'smooth' });
        else if (dir === 'up') win.scrollBy({ top: -step, behavior: 'smooth' });
        else win.scrollBy({ top: step, behavior: 'smooth' });
      }
    } catch (_) {
      try {
        if (win && typeof win.scrollTo === 'function') {
          if (dir === 'top') win.scrollTo(0, 0);
          else if (dir === 'bottom') win.scrollTo(0, info.scrollHeight);
          else win.scrollBy(0, dir === 'up' ? -step : step);
        }
      } catch (_) {}
    }
    return { ok: true, scroll: computeScrollInfo(doc, win) };
  }

  /** Extrae texto detallado alrededor de un encabezado o término concreto. */
  function extractSection(doc, win, query, maxChars) {
    const q = String(query || '').trim().toLowerCase();
    const limit = Math.max(400, Math.min(4000, Number(maxChars) || 2400));
    if (!q || !doc || !doc.querySelectorAll) return { ok: false, text: '' };
    const candidates = doc.querySelectorAll('h1, h2, h3, h4, section, article, p');
    for (let i = 0; i < candidates.length; i++) {
      const el = candidates[i];
      if (!el || hasHiddenAncestor(el, win)) continue;
      const t = visibleSubtreeText(el, win, 300).toLowerCase();
      if (!t.includes(q)) continue;
      const container = el.parentElement || el;
      const extracted = visibleSubtreeText(container, win, limit);
      if (extracted) return { ok: true, text: extracted };
    }
    return { ok: true, text: '' };
  }

  /** Ajusta la velocidad de reproducción (0.5x–2.5x) de los elementos <video>/<audio> visibles. */
  function setMediaSpeed(doc, speed) {
    const rate = Math.max(0.5, Math.min(2.5, Number(speed) || 1.0));
    if (!doc || !doc.querySelectorAll) return { ok: false, count: 0 };
    const media = doc.querySelectorAll('video, audio');
    let count = 0;
    for (let i = 0; i < media.length; i++) {
      try {
        media[i].playbackRate = rate;
        count++;
      } catch (_) {}
    }
    return { ok: count > 0, count: count, rate: rate };
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      extractSafeSnapshot,
      highlightText,
      fillDraftField,
      selectOption,
      clickElement,
      scrollPage,
      extractSection,
      setMediaSpeed,
      sanitizeUrl,
      isSensitiveField,
      isHiddenElement,
      redactInlineSecrets
    };
  } else if (root) {
    root.__nexoAiPageAgent = Object.freeze({
      extractSafeSnapshot,
      highlightText,
      fillDraftField,
      selectOption,
      clickElement,
      scrollPage,
      extractSection,
      setMediaSpeed,
      sanitizeUrl
    });
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
