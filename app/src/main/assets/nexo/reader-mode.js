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
  var MAX_NODES = 12000;
  var MAX_DEPTH = 128;

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
    var view = el.ownerDocument && el.ownerDocument.defaultView;
    if (view && typeof view.getComputedStyle === 'function') {
      var computed = view.getComputedStyle(el);
      return computed.display === 'none' || computed.visibility === 'hidden' ||
        computed.visibility === 'collapse' || computed.opacity === '0' || computed.contentVisibility === 'hidden';
    }
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

  function budget(nodes, millis) {
    var deadline = Date.now() + millis;
    return {
      take: function () {
        if (nodes <= 0 || ((--nodes & 63) === 0 && Date.now() > deadline)) {
          nodes = 0;
          return false;
        }
        return true;
      }
    };
  }

  // Walk a child at a time so a wide or deep DOM cannot create a large queue or stack.
  function traverse(rootEl, work, enter, leave) {
    if (!rootEl) return;
    var stack = [{ node: rootEl, index: -1 }];
    while (stack.length) {
      var frame = stack[stack.length - 1];
      if (frame.index === -1) {
        if (!work.take()) break;
        if (!enter(frame.node)) { stack.pop(); continue; }
        frame.index = 0;
      }
      var children = frame.node.childNodes;
      if (children && frame.index < children.length && stack.length < MAX_DEPTH) {
        stack.push({ node: children[frame.index++], index: -1 });
      } else {
        stack.pop();
        if (leave) leave(frame.node);
      }
    }
    if (leave) {
      while (stack.length) {
        var pending = stack.pop();
        if (pending.index !== -1) leave(pending.node);
      }
    }
  }

  function collectVisibleText(node, work) {
    var parts = [];
    var size = 0;
    traverse(node, work || budget(1024, 12), function (child) {
      if (child.nodeType === 3 && size < MAX_TOTAL_CHARS) {
        var text = (child.nodeValue || '').slice(0, MAX_TOTAL_CHARS - size);
        parts.push(text);
        size += text.length + 1;
      }
      return child.nodeType === 1 && size < MAX_TOTAL_CHARS && !isBoilerplate(child);
    });
    return parts.join(' ');
  }

  function collectLinks(node, baseHref, out, work) {
    traverse(node, work, function (el) {
      if (el.nodeType !== 1 || out.length >= 6 || isBoilerplate(el)) return false;
      if ((el.tagName || '').toUpperCase() !== 'A') return true;
      var href = sanitizeUrl(getAttr(el, 'href'), baseHref);
      var label = href && cleanWhitespace(collectVisibleText(el, work));
      if (label) out.push({ label: label.slice(0, 120), href: href });
      return false;
    });
  }

  function pickContentRoot(doc) {
    if (!doc || !doc.body) return null;
    var best = doc.body;
    var bestScore = -1;
    var path = [];

    traverse(doc.body, budget(MAX_NODES, 90), function (node) {
      if (node.nodeType === 3) {
        if (path.length) path[path.length - 1].chars += cleanWhitespace((node.nodeValue || '').slice(0, 8192)).length;
        return false;
      }
      if (node.nodeType !== 1 || isBoilerplate(node)) return false;
      path.push({ chars: 0, linkChars: 0, paragraphChars: 0, paragraphs: 0 });
      return true;
    }, function (node) {
      var stats = path.pop();
      var tag = (node.tagName || '').toUpperCase();
      if (tag === 'A') stats.linkChars = stats.chars;
      if (tag === 'P' && stats.chars >= 25) {
        stats.paragraphChars += stats.chars;
        stats.paragraphs++;
      }
      var mainRole = getAttr(node, 'role').toLowerCase() === 'main';
      var candidate = node === doc.body || mainRole || /^(ARTICLE|MAIN|SECTION|DIV)$/.test(tag);
      if (candidate && (stats.paragraphs > 0 || stats.chars >= MIN_CHARS)) {
        var score = tag === 'ARTICLE' ? 45 : tag === 'MAIN' ? 35 : mainRole ? 30 : 0;
        if (POSITIVE_RE.test(getAttr(node, 'class') + ' ' + getAttr(node, 'id'))) score += 20;
        if (stats.paragraphs) score += stats.paragraphs * 8 + Math.min(stats.paragraphChars / 25, 220);
        else score += Math.min(stats.chars / 40, 60);
        var density = stats.linkChars / Math.max(1, stats.chars);
        if (density > 0.45) score *= (1 - density);
        if (score > bestScore) { bestScore = score; best = node; }
      }
      var parent = path[path.length - 1];
      if (parent) {
        parent.chars += stats.chars;
        parent.linkChars += stats.linkChars;
        parent.paragraphChars += stats.paragraphChars;
        parent.paragraphs += stats.paragraphs;
      }
    });
    return best;
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

    var work = budget(MAX_NODES, 120);
    traverse(rootEl, work, function (node) {
      if (node.nodeType !== 1 || blocks.length >= MAX_BLOCKS || totalChars >= MAX_TOTAL_CHARS ||
          isBoilerplate(node)) return false;
      var tag = (node.tagName || '').toUpperCase();
      if (tag === 'H1') return false;
      var type = { H2: 'h2', H3: 'h3', H4: 'h3', PRE: 'code', BLOCKQUOTE: 'quote', LI: 'li', P: 'p' }[tag];
      if (!type) return true;
      var text = collectVisibleText(node, work);
      var links = [];
      if (text && (type === 'p' || type === 'li' || type === 'quote')) collectLinks(node, baseHref, links, work);
      pushBlock(type, type === 'code' ? text.slice(0, 4000) : text, links);
      return false;
    });
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
