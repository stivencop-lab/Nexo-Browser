/**
 * PhantomShield - Ocultación de elementos publicitarios.
 *
 * El fondo decide qué selectores corresponden a este documento (reglas del
 * sitio + genéricas) y los aplica como hoja de estilos de usuario. Este
 * script solo le informa qué clases e ids van apareciendo, para activar las
 * reglas genéricas que hagan falta, y mantiene un respaldo local por si el
 * navegador no admite esa inserción.
 */
(function() {
  'use strict';

  const hostname = location.hostname.toLowerCase();
  const hiddenAttribute = 'data-phantomshield-hidden';
  const MAX_TOKENS = 12000;          // clases + ids recordados por documento
  const FLUSH_DELAY_MS = 350;
  // Trabajo incremental: los nodos añadidos se revisan por tramos, con un
  // presupuesto de elementos y de tiempo por pasada, y nada en pestañas ocultas.
  const SLICE_NODES = 1500;
  const SLICE_MS = 8;
  const MAX_QUEUE = 4000;

  let enabled = false;
  let generic = false;
  let styleElement = null;
  let adoptedSheet = null;
  let observer = null;
  let flushTimer = null;
  let epoch = 0;
  // true cuando la inserción de CSS del navegador no funciona en este motor.
  let inline = false;
  const fallbackSelectors = [];
  const seen = new Set();
  let pendingClasses = [];
  let pendingIds = [];
  let surveyQueue = [];
  let surveyTimer = null;
  let surveyIndex = 0;
  let needsRescan = false;

  function hostMatches(domain) {
    return hostname === domain || hostname.endsWith('.' + domain);
  }

  function isValidSelector(selector) {
    try {
      document.createDocumentFragment().querySelector(selector);
      return true;
    } catch (error) {
      return false;
    }
  }

  /** Reglas incluidas (formato ABP) que aplican a este documento: solo respaldo. */
  function bundledSelectors() {
    const raw = globalThis.PHANTOM_COSMETIC_RULES;
    const result = [];
    if (!Array.isArray(raw)) return result;
    for (const line of raw) {
      if (typeof line !== 'string') continue;
      const separator = line.indexOf('##');
      if (separator === -1) continue;
      const selector = line.slice(separator + 2).trim();
      const domains = line.slice(0, separator).split(',')
        .map(function(d) { return d.trim().toLowerCase(); })
        .filter(Boolean);
      if (!selector) continue;
      const included = domains.filter(function(d) { return !d.startsWith('~'); });
      const excluded = domains.filter(function(d) { return d.startsWith('~'); })
        .map(function(d) { return d.slice(1); });
      if (excluded.some(hostMatches)) continue;
      if (included.length > 0 && !included.some(hostMatches)) continue;
      result.push(selector);
    }
    return result;
  }

  // ---------------------------------------------------------------
  // Hoja cosmética: preferir CSSStyleSheet (inmune a CSP), con respaldo <style>
  // ---------------------------------------------------------------

  function renderFallbackStyle() {
    if (!enabled || !fallbackSelectors.length) return;
    let css = '';
    for (let i = 0; i < fallbackSelectors.length; i += 40) {
      css += fallbackSelectors.slice(i, i + 40).join(',\n') +
        ' {\n  display: none !important;\n  visibility: hidden !important;\n  pointer-events: none !important;\n}\n';
    }
    try {
      if (typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in document) {
        if (!adoptedSheet) {
          adoptedSheet = new CSSStyleSheet();
          document.adoptedStyleSheets = document.adoptedStyleSheets.concat([adoptedSheet]);
        }
        adoptedSheet.replaceSync(css);
        return;
      }
    } catch (_) {
      adoptedSheet = null;
    }
    if (!styleElement) {
      styleElement = document.createElement('style');
      styleElement.id = 'phantomshield-cosmetic-style';
    }
    styleElement.textContent = css;
    if (!styleElement.isConnected) (document.head || document.documentElement).appendChild(styleElement);
  }

  function addFallback(selectors) {
    let changed = false;
    for (const selector of selectors || []) {
      if (typeof selector !== 'string' || fallbackSelectors.includes(selector) || !isValidSelector(selector)) continue;
      fallbackSelectors.push(selector);
      changed = true;
    }
    if (changed) renderFallbackStyle();
  }

  function removeStyle() {
    if (adoptedSheet) {
      try {
        adoptedSheet.replaceSync('');
        if ('adoptedStyleSheets' in document) {
          document.adoptedStyleSheets = document.adoptedStyleSheets.filter(function(s) { return s !== adoptedSheet; });
        }
      } catch (_) {}
      adoptedSheet = null;
    }
    if (styleElement) styleElement.remove();
    styleElement = null;
    fallbackSelectors.length = 0;
  }

  // ---------------------------------------------------------------
  // Clases e ids presentes -> reglas genéricas
  // ---------------------------------------------------------------

  function note(element) {
    if (seen.size >= MAX_TOKENS) return;
    const id = element.id;
    if (id && typeof id === 'string' && id.length <= 80 && !seen.has('#' + id)) {
      seen.add('#' + id);
      pendingIds.push(id);
    }
    const classes = element.classList;
    if (!classes) return;
    for (let i = 0; i < classes.length && i < 24 && seen.size < MAX_TOKENS; i++) {
      const name = classes[i];
      if (name.length > 80 || seen.has('.' + name)) continue;
      seen.add('.' + name);
      pendingClasses.push(name);
    }
  }

  function queueSurvey(node, single = false) {
    if (!generic || seen.size >= MAX_TOKENS) return;
    if (surveyQueue.length - surveyIndex >= MAX_QUEUE) {
      needsRescan = true;
      return;
    }
    surveyQueue.push({ node, single, walker: null });
    scheduleSurvey();
  }

  function scheduleSurvey() {
    if (surveyTimer !== null || surveyIndex >= surveyQueue.length || document.visibilityState === 'hidden') return;
    surveyTimer = setTimeout(runSurvey, 0);
  }

  function runSurvey() {
    surveyTimer = null;
    if (!enabled || !generic || document.visibilityState === 'hidden') return;
    const deadline = performance.now() + SLICE_MS;
    for (let count = 0; count < SLICE_NODES && performance.now() < deadline &&
        surveyIndex < surveyQueue.length && seen.size < MAX_TOKENS; count++) {
      const item = surveyQueue[surveyIndex];
      if (item.node.isConnected === false) { surveyIndex++; continue; }
      if (item.single) { note(item.node); surveyIndex++; continue; }
      if (!item.walker) {
        item.walker = document.createTreeWalker(item.node, NodeFilter.SHOW_ELEMENT);
        note(item.node);
        continue;
      }
      const next = item.walker.nextNode();
      if (next) note(next);
      else surveyIndex++;
    }
    if (surveyIndex === surveyQueue.length || seen.size >= MAX_TOKENS) {
      surveyQueue = [];
      surveyIndex = 0;
      if (needsRescan) {
        needsRescan = false;
        queueSurvey(document.documentElement);
      }
    } else if (surveyIndex >= SLICE_NODES) {
      surveyQueue = surveyQueue.slice(surveyIndex);
      surveyIndex = 0;
    }
    if (pendingClasses.length || pendingIds.length) scheduleFlush();
    scheduleSurvey();
  }

  function scheduleFlush() {
    if (flushTimer !== null) return;
    flushTimer = setTimeout(flush, FLUSH_DELAY_MS);
  }

  function flush() {
    flushTimer = null;
    if (!enabled || !generic || (!pendingClasses.length && !pendingIds.length)) return;
    const classes = pendingClasses.splice(0, 1500);
    const ids = pendingIds.splice(0, 1500);
    const revision = epoch;
    browser.runtime.sendMessage({ type: 'COSMETIC_LOOKUP', classes: classes, ids: ids, inline: inline }).then(function(reply) {
      if (revision !== epoch || !reply || !reply.enabled) return;
      if (!reply.injected) addFallback(reply.selectors);
    }).catch(function() {});
    if (pendingClasses.length || pendingIds.length) scheduleFlush();
  }

  // ---------------------------------------------------------------
  // Elementos añadidos después de cargar
  // ---------------------------------------------------------------

  function hideElement(element) {
    if (element && element.nodeType === Node.ELEMENT_NODE) {
      element.setAttribute(hiddenAttribute, 'true');
    }
  }

  function nodeLooksLikeAd(node) {
    const className = typeof node.className === 'string' ? node.className.toLowerCase() : '';
    const id = (node.id || '').toLowerCase();
    return /(^|[\s_-])ad(?:s|vert)?([\s_-]|$)/.test(className) ||
      /(^|[_-])ad(?:s|vert)?([_-]|$)/.test(id) ||
      className.includes('sponsored-post');
  }

  function startObserver() {
    if (!enabled || observer || !document.documentElement) return;
    observer = new MutationObserver(function(mutations) {
      let remaining = SLICE_NODES;
      for (const mutation of mutations) {
        if (remaining-- <= 0) { needsRescan = true; break; }
        if (mutation.type === 'attributes') {
          queueSurvey(mutation.target, true);
          continue;
        }
        for (const node of mutation.addedNodes) {
          if (remaining-- <= 0) { needsRescan = true; break; }
          if (!(node instanceof Element)) continue;
          // Ocultar un anuncio evidente no espera: es barato y evita el parpadeo.
          if (nodeLooksLikeAd(node)) hideElement(node);
          queueSurvey(node);
        }
      }
    });
    observer.observe(document.documentElement, {
      childList: true, subtree: true, attributes: generic, attributeFilter: generic ? ['class', 'id'] : undefined
    });
    queueSurvey(document.documentElement);
  }

  function stopObserver() {
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(flushTimer);
    flushTimer = null;
    clearTimeout(surveyTimer);
    surveyTimer = null;
    surveyQueue = [];
    surveyIndex = 0;
    needsRescan = false;
    seen.clear();
    pendingClasses = [];
    pendingIds = [];
  }

  // Pestaña oculta: la cola espera; al volver se retoma donde quedó.
  document.addEventListener('visibilitychange', function() {
    if (document.visibilityState !== 'hidden') scheduleSurvey();
  });

  /**
   * ¿La hoja insertada por el navegador está activa? Un elemento de prueba con
   * el atributo de ocultación debe quedar con display:none.
   */
  function injectionWorks() {
    const root = document.documentElement;
    if (!root) return true;
    try {
      const probe = document.createElement('div');
      probe.setAttribute(hiddenAttribute, 'true');
      root.appendChild(probe);
      const hidden = getComputedStyle(probe).display === 'none';
      probe.remove();
      return hidden;
    } catch (error) {
      return true;
    }
  }

  function apply(state) {
    enabled = Boolean(state && state.enabled);
    generic = Boolean(enabled && state.generic);
    if (!enabled) {
      stopObserver();
      removeStyle();
      return;
    }
    if (state.injected && !inline && !injectionWorks()) {
      // El navegador aceptó la inserción pero no la aplicó: pedir los
      // selectores y usar el <style> de la página desde ahora.
      inline = true;
      refreshState();
      return;
    }
    if (!state.injected) {
      addFallback(['[' + hiddenAttribute + '="true"]']);
      addFallback(state.selectors && state.selectors.length ? state.selectors : bundledSelectors());
    }
    startObserver();
  }

  function refreshState() {
    const revision = ++epoch;
    browser.runtime.sendMessage({ type: 'GET_COSMETIC', inline: inline }).then(function(state) {
      if (revision === epoch) apply(state);
    }).catch(function() {
      // Sin respuesta del fondo: proteger con las reglas incluidas.
      if (revision === epoch) apply({ enabled: true, generic: false, injected: false, selectors: bundledSelectors() });
    });
  }

  refreshState();

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName === 'local' && (changes.adblockEnabled || changes.disabled_domains || changes.annoyancesEnabled || changes.user_cosmetic_rules)) {
      enabled = false;
      stopObserver();
      removeStyle();
      refreshState();
    }
  });

  // ---------------------------------------------------------------
  // Selector visual (Element Zapper): oculta cualquier elemento al tocarlo
  // ---------------------------------------------------------------

  let zapperActive = false;
  function buildSafeSelector(el) {
    if (!el || !(el instanceof Element) || el === document.body || el === document.documentElement) return null;
    const esc = function(v) {
      if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(v);
      return String(v).replace(/[^a-zA-Z0-9_-]/g, '\\$&');
    };
    if (el.id && el.id.length <= 64 && !/^\d/.test(el.id)) {
      const idSel = '#' + esc(el.id);
      if (isValidSelector(idSel)) return idSel;
    }
    const parts = [];
    let cur = el;
    for (let depth = 0; cur && cur instanceof Element && cur !== document.body && cur !== document.documentElement && depth < 4; depth++) {
      let part = cur.tagName.toLowerCase();
      if (cur.id && cur.id.length <= 48 && !/^\d/.test(cur.id)) {
        parts.unshift('#' + esc(cur.id));
        break;
      }
      const cls = Array.from(cur.classList || [])
        .filter(function(c) { return c && c.length <= 40 && !/^phantomshield/i.test(c) && !/^(active|hover|focus|open|show|visible|hidden)$/i.test(c); })
        .slice(0, 2);
      if (cls.length) {
        part += '.' + cls.map(esc).join('.');
      } else if (cur.parentElement) {
        const siblings = Array.from(cur.parentElement.children).filter(function(s) { return s.tagName === cur.tagName; });
        if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(cur) + 1) + ')';
      }
      parts.unshift(part);
      const candidate = parts.join(' > ');
      try {
        if (cls.length && document.querySelectorAll(candidate).length <= 3 && isValidSelector(candidate)) return candidate;
      } catch (_) {}
      cur = cur.parentElement;
    }
    const finalSel = parts.join(' > ');
    return finalSel && finalSel.length <= 240 && isValidSelector(finalSel) ? finalSel : null;
  }

  function startElementZapper() {
    if (zapperActive || window !== window.top || !document.documentElement) return;
    zapperActive = true;
    let zappedCount = 0;
    let highlighted = null;

    const host = document.createElement('div');
    host.id = 'phantomshield-zapper-host';
    host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';
    const box = document.createElement('div');
    box.style.cssText = 'position:fixed;display:none;pointer-events:none;border:2px solid #57d98b;background:rgba(87,217,139,0.18);border-radius:6px;transition:all 80ms ease;';
    const bar = document.createElement('div');
    bar.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);pointer-events:auto;display:flex;align-items:center;gap:12px;padding:10px 16px;border-radius:999px;background:rgba(19,19,22,0.92);color:#f4f4f5;border:1px solid rgba(87,217,139,0.45);box-shadow:0 8px 28px rgba(0,0,0,0.45);font:600 13px/1.3 system-ui,-apple-system,sans-serif;max-width:92vw;';
    const label = document.createElement('span');
    label.textContent = 'Toca un elemento para ocultarlo';
    const doneBtn = document.createElement('button');
    doneBtn.type = 'button';
    doneBtn.textContent = 'Listo';
    doneBtn.style.cssText = 'border:0;border-radius:999px;padding:6px 14px;background:#57d98b;color:#09090b;font:700 12px/1 system-ui,sans-serif;cursor:pointer;';
    bar.appendChild(label);
    bar.appendChild(doneBtn);
    host.appendChild(box);
    host.appendChild(bar);
    document.documentElement.appendChild(host);

    function highlight(el) {
      if (!el || host.contains(el) || el === document.body || el === document.documentElement) {
        box.style.display = 'none';
        highlighted = null;
        return;
      }
      highlighted = el;
      const r = el.getBoundingClientRect();
      box.style.display = 'block';
      box.style.left = Math.max(0, r.left) + 'px';
      box.style.top = Math.max(0, r.top) + 'px';
      box.style.width = Math.max(4, r.width) + 'px';
      box.style.height = Math.max(4, r.height) + 'px';
    }

    function stop() {
      if (!zapperActive) return;
      zapperActive = false;
      window.removeEventListener('pointermove', onMove, true);
      window.removeEventListener('click', onClick, true);
      window.removeEventListener('keydown', onKey, true);
      host.remove();
    }

    function onMove(ev) {
      if (host.contains(ev.target)) return;
      highlight(ev.target);
    }

    function onClick(ev) {
      if (host.contains(ev.target)) return;
      ev.preventDefault();
      ev.stopImmediatePropagation();
      const target = ev.target instanceof Element ? ev.target : highlighted;
      if (!target || target === document.body || target === document.documentElement) return;
      const selector = buildSafeSelector(target);
      hideElement(target);
      box.style.display = 'none';
      zappedCount++;
      label.textContent = zappedCount === 1 ? '1 elemento ocultado · toca otro o Listo' : zappedCount + ' elementos ocultados · toca otro o Listo';
      if (selector) {
        addFallback([selector]);
        browser.runtime.sendMessage({ type: 'ZAP_ELEMENT', selector: selector }).catch(function() {});
      }
    }

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); stop(); }
    }

    doneBtn.addEventListener('click', function(ev) { ev.stopPropagation(); stop(); });
    window.addEventListener('pointermove', onMove, true);
    window.addEventListener('click', onClick, true);
    window.addEventListener('keydown', onKey, true);
  }

  if (browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener(function(message) {
      if (message && message.type === 'START_ZAPPER') {
        startElementZapper();
        return { ok: true };
      }
    });
  }
})();
