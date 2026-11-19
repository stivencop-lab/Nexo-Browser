/* Nexo Reader — Semantic Article Extractor (Zero DRM / Zero Paywall Bypass)
 * Extrae únicamente contenido visible ya renderizado en el DOM para mostrarlo en
 * la vista nativa saneada de Nexo Reader y en la lectura en voz alta (TTS).
 */
(function (root) {
  'use strict';

  var MAX_BLOCKS = 400;
  var MAX_TOTAL_CHARS = 120000;
  var MIN_WORDS = 45;
  var MIN_CHARS = 220;

  var SKIP_TAGS = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, SVG: 1, CANVAS: 1,
    IFRAME: 1, OBJECT: 1, EMBED: 1, AUDIO: 1, VIDEO: 1, PICTURE: 1,
    NAV: 1, FOOTER: 1, ASIDE: 1, FORM: 1, BUTTON: 1, INPUT: 1,
    SELECT: 1, TEXTAREA: 1, DIALOG: 1, MENU: 1
  };

  var BOILERPLATE_RE = /(^|[\s_-])(nav|navbar|menu|sidebar|side-bar|aside|footer|header|breadcrumb|breadcrumbs|comment|comments|cookie|consent|gdpr|banner|promo|sponsor|advert|ad-slot|ads|social|share|sharing|related|recommend|newsletter|subscribe|popup|modal|toolbar|pagination)($|[\s_-])/i;
  var POSITIVE_RE = /(^|[\s_-])(article|story|post|entry|content|main|body|text|prose|markdown|reader|blog|news)($|[\s_-])/i;
  var SENSITIVE_PARAM_RE = /^(token|access_token|auth|session|sid|key|api_key|apikey|password|pwd|secret|sig|signature|code|jwt|utm_[a-z0-9_]+|fbclid|gclid|mc_eid)$/i;

  function cleanWhitespace(text) {
    if (!text) return '';
    return String(text)
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function countWords(text) {
    var clean = cleanWhitespace(text);
    if (!clean) return 0;
    return clean.split(' ').filter(Boolean).length;
  }

  function sanitizeUrl(raw, baseHref) {
    if (!raw || typeof raw !== 'string') return '';
    var trimmed = raw.trim();
    if (!trimmed) return '';
    if (/^(javascript|data|vbscript|file|blob):/i.test(trimmed)) return '';
    try {
      var u = baseHref ? new URL(trimmed, baseHref) : new URL(trimmed);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      u.username = '';
      u.password = '';
      var toDelete = [];
      u.searchParams.forEach(function (_, k) {
        if (SENSITIVE_PARAM_RE.test(k)) toDelete.push(k);
      });
      for (var i = 0; i < toDelete.length; i++) u.searchParams.delete(toDelete[i]);
      return u.toString();
    } catch (_) {
      return '';
    }
  }

  function getAttr(el, name) {
    if (!el || typeof el.getAttribute !== 'function') return '';
    var v = el.getAttribute(name);
    return v == null ? '' : String(v);
  }

  function isHidden(el) {
    if (!el || el.nodeType !== 1) return false;
    if (getAttr(el, 'hidden') !== '' && el.getAttribute('hidden') !== null) return true;
    if (el.getAttribute && el.getAttribute('hidden') === '') return true;
    var ariaHidden = getAttr(el, 'aria-hidden').toLowerCase();
    if (ariaHidden === 'true') return true;
    var style = el.style || {};
    if (style.display === 'none' || style.visibility === 'hidden') return true;
    var inlineStyle = getAttr(el, 'style').toLowerCase();
    if (/display\s*:\s*none|visibility\s*:\s*hidden/.test(inlineStyle)) return true;
    return false;
  }

  function isBoilerplate(el) {
    if (!el || el.nodeType !== 1) return false;
    var tag = (el.tagName || '').toUpperCase();
    if (SKIP_TAGS[tag]) return true;
    if (isHidden(el)) return true;
    var role = getAttr(el, 'role').toLowerCase();
    if (role === 'navigation' || role === 'banner' || role === 'contentinfo' || role === 'complementary' || role === 'search' || role === 'dialog') {
      return true;
    }
    var classAndId = (getAttr(el, 'class') + ' ' + getAttr(el, 'id')).trim();
    if (classAndId && BOILERPLATE_RE.test(classAndId) && !POSITIVE_RE.test(classAndId)) {
      return true;
    }
    return false;
  }

  function collectVisibleText(node) {
    if (!node) return '';
    if (node.nodeType === 3) return node.nodeValue || '';
    if (node.nodeType !== 1) return '';
    if (isBoilerplate(node)) return '';
    var out = '';
    var kids = node.childNodes || [];
    for (var i = 0; i < kids.length; i++) {
      var part = collectVisibleText(kids[i]);
      if (part) out += (out && !/\s$/.test(out) && !/^\s/.test(part) ? ' ' : '') + part;
    }
    return out;
  }

  function collectLinks(node, baseHref, out) {
    if (!node || node.nodeType !== 1 || isBoilerplate(node)) return;
    var tag = (node.tagName || '').toUpperCase();
    if (tag === 'A') {
      var href = sanitizeUrl(getAttr(node, 'href'), baseHref);
      var label = cleanWhitespace(collectVisibleText(node));
      if (href && label && out.length < 6) {
        out.push({ label: label.slice(0, 120), href: href });
      }
      return;
    }
    var kids = node.childNodes || [];
    for (var i = 0; i < kids.length; i++) {
      collectLinks(kids[i], baseHref, out);
    }
  }

  function scoreContainer(el) {
    if (!el || el.nodeType !== 1 || isBoilerplate(el)) return -1;
    var tag = (el.tagName || '').toUpperCase();
    var score = 0;
    if (tag === 'ARTICLE') score += 45;
    else if (tag === 'MAIN') score += 35;
    else if (getAttr(el, 'role').toLowerCase() === 'main') score += 30;

    var classAndId = getAttr(el, 'class') + ' ' + getAttr(el, 'id');
    if (POSITIVE_RE.test(classAndId)) score += 20;

    var paragraphs = typeof el.querySelectorAll === 'function' ? el.querySelectorAll('p') : [];
    var pTextLen = 0;
    var pCount = 0;
    for (var i = 0; i < paragraphs.length; i++) {
      var p = paragraphs[i];
      if (hasBoilerplateAncestor(p, el)) continue;
      var txt = cleanWhitespace(collectVisibleText(p));
      if (txt.length >= 25) {
        pTextLen += txt.length;
        pCount++;
      }
    }
    if (pCount === 0) {
      var fallback = cleanWhitespace(collectVisibleText(el));
      if (fallback.length < MIN_CHARS) return -1;
      return score + Math.min(fallback.length / 40, 60);
    }

    var links = typeof el.querySelectorAll === 'function' ? el.querySelectorAll('a') : [];
    var linkChars = 0;
    for (var j = 0; j < links.length; j++) {
      linkChars += cleanWhitespace(collectVisibleText(links[j])).length;
    }
    var totalLen = Math.max(1, cleanWhitespace(collectVisibleText(el)).length);
    var linkDensity = Math.min(1, linkChars / totalLen);

    score += pCount * 8 + Math.min(pTextLen / 25, 220);
    if (linkDensity > 0.45) score *= (1 - linkDensity);
    return score;
  }

  function hasBoilerplateAncestor(node, stopAt) {
    var cur = node;
    while (cur && cur !== stopAt) {
      if (isBoilerplate(cur)) return true;
      cur = cur.parentElement || cur.parentNode;
    }
    return false;
  }

  function pickContentRoot(doc) {
    if (!doc || !doc.body) return null;
    var candidates = [];
    if (typeof doc.querySelectorAll === 'function') {
      var found = doc.querySelectorAll('article, main, [role="main"], section, div');
      for (var i = 0; i < found.length; i++) candidates.push(found[i]);
    }
    candidates.push(doc.body);

    var best = null;
    var bestScore = -1;
    for (var k = 0; k < candidates.length; k++) {
      var c = candidates[k];
      var s = scoreContainer(c);
      if (s > bestScore) {
        bestScore = s;
        best = c;
      }
    }
    return bestScore > 0 ? best : doc.body;
  }

  function extractMeta(doc, selectorList) {
    if (!doc || typeof doc.querySelector !== 'function') return '';
    for (var i = 0; i < selectorList.length; i++) {
      var node = null;
      try { node = doc.querySelector(selectorList[i]); } catch (_) { node = null; }
      if (!node) continue;
      var val = cleanWhitespace(getAttr(node, 'content') || collectVisibleText(node));
      if (val) return val;
    }
    return '';
  }

  function extractBlocks(rootEl, baseHref) {
    var blocks = [];
    var totalChars = 0;
    var seenTexts = Object.create(null);

    function pushBlock(type, text, links) {
      if (blocks.length >= MAX_BLOCKS || totalChars >= MAX_TOTAL_CHARS) return;
      var clean = cleanWhitespace(text);
      if (!clean) return;
      if (type === 'p' && clean.length < 15) return;
      var key = type + ':' + clean;
      if (seenTexts[key]) return;
      seenTexts[key] = 1;
      var remaining = MAX_TOTAL_CHARS - totalChars;
      if (clean.length > remaining) clean = clean.slice(0, remaining);
      totalChars += clean.length;
      var block = { type: type, text: clean };
      if (links && links.length > 0) block.links = links;
      blocks.push(block);
    }

    function walk(node) {
      if (!node || node.nodeType !== 1) return;
      if (blocks.length >= MAX_BLOCKS || totalChars >= MAX_TOTAL_CHARS) return;
      if (isBoilerplate(node)) return;

      var tag = (node.tagName || '').toUpperCase();
      if (tag === 'H1') {
        // El título principal ya se muestra en la cabecera; encabezados H1 extra pasan como h2.
        return;
      }
      if (tag === 'H2') {
        pushBlock('h2', collectVisibleText(node), []);
        return;
      }
      if (tag === 'H3' || tag === 'H4') {
        pushBlock('h3', collectVisibleText(node), []);
        return;
      }
      if (tag === 'BLOCKQUOTE') {
        var qLinks = [];
        collectLinks(node, baseHref, qLinks);
        pushBlock('quote', collectVisibleText(node), qLinks);
        return;
      }
      if (tag === 'PRE') {
        var codeText = (node.textContent || '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim();
        if (codeText) pushBlock('code', codeText.slice(0, 4000), []);
        return;
      }
      if (tag === 'LI') {
        var liLinks = [];
        collectLinks(node, baseHref, liLinks);
        pushBlock('li', collectVisibleText(node), liLinks);
        return;
      }
      if (tag === 'P') {
        var pLinks = [];
        collectLinks(node, baseHref, pLinks);
        pushBlock('p', collectVisibleText(node), pLinks);
        return;
      }

      var kids = node.childNodes || [];
      for (var i = 0; i < kids.length; i++) {
        walk(kids[i]);
      }
    }

    walk(rootEl);
    return blocks;
  }

  function extractArticle(doc) {
    if (!doc || !doc.body) {
      return { ok: false, reason: 'La página todavía no tiene contenido listo para lectura.' };
    }
    var baseHref = (doc.location && doc.location.href) ? String(doc.location.href) : '';
    var safeUrl = sanitizeUrl(baseHref);
    var rootEl = pickContentRoot(doc);
    if (!rootEl) {
      return { ok: false, reason: 'No se encontró un bloque principal legible en esta página.' };
    }

    var h1Text = '';
    if (typeof rootEl.querySelector === 'function') {
      var h1 = rootEl.querySelector('h1');
      if (h1 && !isBoilerplate(h1)) h1Text = cleanWhitespace(collectVisibleText(h1));
    }
    if (!h1Text && typeof doc.querySelector === 'function') {
      var docH1 = doc.querySelector('h1');
      if (docH1 && !isBoilerplate(docH1)) h1Text = cleanWhitespace(collectVisibleText(docH1));
    }
    var title = h1Text ||
      extractMeta(doc, ['meta[property="og:title"]', 'meta[name="twitter:title"]']) ||
      cleanWhitespace(doc.title || '') ||
      'Artículo sin título';

    var byline = extractMeta(doc, [
      'meta[name="author"]',
      'meta[property="article:author"]',
      '[rel="author"]',
      '.byline',
      '.author'
    ]).slice(0, 140);

    var siteName = extractMeta(doc, ['meta[property="og:site_name"]', 'meta[name="application-name"]']).slice(0, 80);
    if (!siteName && safeUrl) {
      try {
        siteName = new URL(safeUrl).hostname.replace(/^www\./i, '');
      } catch (_) {
        siteName = '';
      }
    }

    var lang = '';
    if (doc.documentElement && typeof doc.documentElement.getAttribute === 'function') {
      lang = cleanWhitespace(doc.documentElement.getAttribute('lang') || '');
    }
    if (!lang) lang = 'es';

    var blocks = extractBlocks(rootEl, safeUrl || baseHref);
    var wordCount = 0;
    var charCount = 0;
    for (var i = 0; i < blocks.length; i++) {
      wordCount += countWords(blocks[i].text);
      charCount += blocks[i].text.length;
    }

    if (wordCount < MIN_WORDS || charCount < MIN_CHARS) {
      return {
        ok: false,
        reason: 'Esta página no parece un artículo largo (solo se detectaron ' + wordCount + ' palabras legibles).'
      };
    }

    return {
      ok: true,
      title: title.slice(0, 200),
      byline: byline,
      siteName: siteName,
      lang: lang.slice(0, 16),
      url: safeUrl,
      wordCount: wordCount,
      readingMinutes: Math.max(1, Math.round(wordCount / 200)),
      blocks: blocks
    };
  }

  var api = {
    extractArticle: extractArticle,
    sanitizeUrl: sanitizeUrl,
    countWords: countWords,
    MAX_BLOCKS: MAX_BLOCKS,
    MAX_TOTAL_CHARS: MAX_TOTAL_CHARS
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  if (root && typeof root === 'object') {
    root.__nexoReader = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
