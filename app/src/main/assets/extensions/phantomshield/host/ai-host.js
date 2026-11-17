/*
 * Anfitrión de la IA de PhantomShield en Nexo (Chromium).
 *
 * Nexo carga esta página oculta solo cuando hace falta (una página pide
 * clasificar, o el panel abre los ajustes de IA) y le reenvía peticiones ya
 * autorizadas: Nexo comprueba el marco, el sitio, la pausa del escudo y las
 * pestañas privadas; las listas de red y la actividad por pestaña también
 * son nativas. Aquí solo corre la parte de IA de `background/background.js`
 * con los módulos originales (`PhantomAI`, `PhantomAIMemory`,
 * `PhantomAdDomains`).
 */
(function() {
  'use strict';
  const AI = globalThis.PhantomAI;
  const memory = globalThis.PhantomAIMemory;
  const adDomains = globalThis.PhantomAdDomains;
  const Config = globalThis.PhantomAIConfig;

  // Pestaña -> dominios ya anotados en la carga actual (cada uno cuenta una vez por página).
  const pages = new Map();
  function seenFor(tabId, page) {
    let state = pages.get(tabId);
    if (!state || state.page !== page) {
      state = { page: page, seen: new Set() };
      pages.set(tabId, state);
      while (pages.size > 64) pages.delete(pages.keys().next().value);
    }
    return state.seen;
  }

  /** Copia para el clasificador: las direcciones locales nunca llegan al proveedor. */
  function forProvider(candidates) {
    if (!Array.isArray(candidates)) return candidates;
    return candidates.map(function(candidate) {
      if (!candidate || typeof candidate !== 'object') return candidate;
      const copy = Object.assign({}, candidate);
      delete copy.localUrls;
      delete copy.localFrame;
      return copy;
    });
  }

  /** Anuncios ocultados: sus dominios de infraestructura (marco y clic) se aprenden. */
  async function learnAdDomains(sender, candidates, decisions) {
    if (!adDomains || !Array.isArray(candidates) || !Array.isArray(decisions)) return 0;
    let config;
    try { config = await AI.config(); } catch (_) { return 0; }
    if (!config || config.learnDomains === false) return 0;
    const known = new Set(Array.isArray(sender.known) ? sender.known : []);
    const byId = new Map(candidates.filter(c => c && typeof c.id === 'string').map(c => [c.id, c]));
    const seen = seenFor(sender.tabId, sender.page);
    const fresh = [];
    for (const decision of decisions) {
      if (!decision || decision.hide !== true) continue;
      const candidate = byId.get(decision.id);
      if (!candidate) continue;
      const links = (Array.isArray(candidate.localUrls) ? candidate.localUrls : []).filter(url => url !== candidate.localFrame);
      for (const domain of adDomains.evidence(sender.host, candidate.localFrame, links, host => known.has(host))) {
        if (seen.has(domain) || seen.size >= 40) continue;
        seen.add(domain);
        fresh.push(domain);
      }
    }
    if (!fresh.length) return 0;
    try { return (await adDomains.learn(sender.host, fresh)).length; } catch (_) { return 0; }
  }

  async function classify(message, sender) {
    const result = await AI.classify(sender.host, forProvider(message.candidates), sender.incognito === true);
    if (result && result.ok && sender.incognito !== true) {
      result.learned = await learnAdDomains(sender, message.candidates, result.decisions);
    }
    return result;
  }

  async function memoryRequest(message) {
    try {
      if (message.type !== 'AI_MEMORY_LIST') {
        const host = message.type === 'AI_MEMORY_CLEAR' ? null : message.host;
        if (host !== null && !Config.publicHost(host)) return { ok: false, error: 'Dominio inválido.' };
        AI.invalidate();
        if (message.type === 'AI_MEMORY_EXCLUDE') {
          const config = await AI.config();
          config.excludedHosts = [...new Set([...config.excludedHosts, host])];
          await browser.storage.local.set({ aiConfig: Config.validate(config) });
        }
        await memory.forget(host);
        await browser.storage.local.set({ aiMemoryRevision: { nonce: Date.now() + Math.random(), host: host } });
        // El aviso es para las páginas abiertas; el dominio olvidado no se conserva.
        await browser.storage.local.set({ aiMemoryRevision: null });
      }
      return Object.assign({ ok: true }, await memory.summary());
    } catch (_) {
      return { ok: false, error: 'No se pudo acceder a la memoria. Inténtalo de nuevo.' };
    }
  }

  async function domainsRequest(message) {
    if (!adDomains) return { ok: false, error: 'Abre los dominios aprendidos desde los ajustes de PhantomShield.' };
    try {
      const domain = typeof message.domain === 'string' ? message.domain.toLowerCase() : '';
      if (message.type !== 'AI_DOMAINS_LIST' && message.type !== 'AI_DOMAINS_CLEAR' && !Config.publicHost(domain)) {
        return { ok: false, error: 'Dominio inválido.' };
      }
      if (message.type === 'AI_DOMAINS_FORGET') await adDomains.forget(domain);
      if (message.type === 'AI_DOMAINS_CLEAR') await adDomains.forget(null);
      if (message.type === 'AI_DOMAINS_ALLOW') await adDomains.allow(domain, true);
      if (message.type === 'AI_DOMAINS_UNALLOW') await adDomains.allow(domain, false);
      return Object.assign({ ok: true }, await adDomains.list());
    } catch (_) {
      return { ok: false, error: 'No se pudieron leer los dominios aprendidos.' };
    }
  }

  browser.runtime.onMessage.addListener(function(message, sender) {
    if (!message || typeof message.type !== 'string') return undefined;
    sender = sender || {};
    if (message.type === 'AI_CLASSIFY') return classify(message, sender);
    if (message.type === 'AI_TEST') return AI.probe(message.config);
    if (/^AI_MEMORY_(LIST|FORGET|CLEAR|EXCLUDE)$/.test(message.type)) return memoryRequest(message);
    if (/^AI_DOMAINS_(LIST|FORGET|ALLOW|UNALLOW|CLEAR)$/.test(message.type)) return domainsRequest(message);
    return undefined;
  });

  browser.runtime.ready().catch(function() {});
})();
