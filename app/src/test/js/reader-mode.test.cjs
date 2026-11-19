'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const reader = require('../../main/assets/nexo/reader-mode.js');

function el(tag, attrs = {}, children = []) {
  const node = {
    nodeType: 1,
    tagName: tag.toUpperCase(),
    attrs: { ...attrs },
    childNodes: [],
    style: {},
    parentElement: null,
    getAttribute(k) {
      return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null;
    },
    get textContent() {
      return this.childNodes.map(c => (c.nodeType === 3 ? c.nodeValue : c.textContent)).join('');
    },
    matchesSimple(sel) {
      const s = sel.trim();
      if (s === '[role="main"]') return (this.getAttribute('role') || '').toLowerCase() === 'main';
      if (s === '[rel="author"]') return (this.getAttribute('rel') || '').toLowerCase() === 'author';
      if (s.startsWith('.')) {
        const cls = (this.getAttribute('class') || '').split(/\s+/);
        return cls.includes(s.slice(1));
      }
      const metaMatch = /^meta\[(property|name)="([^"]+)"\]$/i.exec(s);
      if (metaMatch) {
        return this.tagName === 'META' && this.getAttribute(metaMatch[1]) === metaMatch[2];
      }
      return this.tagName.toLowerCase() === s.toLowerCase();
    },
    querySelectorAll(selector) {
      const parts = selector.split(',').map(p => p.trim()).filter(Boolean);
      const out = [];
      function walk(cur) {
        if (!cur || cur.nodeType !== 1) return;
        if (parts.some(p => cur.matchesSimple(p))) out.push(cur);
        for (const ch of cur.childNodes) walk(ch);
      }
      for (const ch of this.childNodes) walk(ch);
      return out;
    },
    querySelector(selector) {
      return this.querySelectorAll(selector)[0] || null;
    }
  };
  for (const ch of children) {
    ch.parentElement = node;
    node.childNodes.push(ch);
  }
  return node;
}

function txt(value) {
  return { nodeType: 3, nodeValue: value, parentElement: null };
}

test('extractArticle parses clean article structure and strips scripts, nav, ads and unsafe links', () => {
  const metaAuthor = el('meta', { name: 'author', content: 'Elena Morales' });
  const metaSite = el('meta', { property: 'og:site_name', content: 'Revista Nexo' });
  const nav = el('nav', {}, [el('a', { href: 'https://example.org/home' }, [txt('Inicio')]), el('a', { href: 'https://example.org/about' }, [txt('Acerca')])]);
  const adBanner = el('div', { class: 'cookie-banner promo' }, [el('p', {}, [txt('Acepta las cookies publicitarias de terceros ahora mismo.')])]);
  const hiddenNote = el('p', { 'aria-hidden': 'true' }, [txt('Texto oculto que no debe aparecer en el lector.')]);
  const script = el('script', {}, [txt('alert("xss")')]);

  const article = el('article', { class: 'article-content' }, [
    el('h1', {}, [txt('Cómo funciona la arquitectura limpia en navegadores móviles')]),
    el('p', {}, [
      txt('Los navegadores modernos separan cuidadosamente la capa de interfaz de usuario del motor de renderizado para ofrecer velocidad, estabilidad y ahorro de memoria en dispositivos Android con recursos limitados. '),
      el('a', { href: 'https://docs.example.org/architecture?utm_source=ads&token=secret&lang=es' }, [txt('Ver especificaciones')]),
      txt(' y '),
      el('a', { href: 'javascript:alert(1)' }, [txt('enlace peligroso')])
    ]),
    el('h2', {}, [txt('Aislamiento y seguridad')]),
    el('p', {}, [txt('Cada pestaña y extensión debe operar bajo el principio de mínimo privilegio, verificando permisos por dominio y evitando exponer claves privadas o sesiones del usuario en scripts de terceros.')]),
    el('blockquote', {}, [txt('Una interfaz clara y predecible reduce la carga cognitiva y acelera la lectura diaria.')]),
    el('ul', {}, [
      el('li', {}, [txt('Tipografía configurable con contraste AAA verificado.')]),
      el('li', {}, [txt('Lectura en voz alta usando las voces reales instaladas en Android.')])
    ]),
    hiddenNote,
    script
  ]);
  const footer = el('footer', {}, [el('p', {}, [txt('Copyright 2026 Todos los derechos reservados.')])]);

  const body = el('body', {}, [metaAuthor, metaSite, nav, adBanner, article, footer]);
  const doc = {
    title: 'Fallback Title',
    location: { href: 'https://user:pass@revista.example.org/post/arquitectura?utm_campaign=x&page=1' },
    documentElement: el('html', { lang: 'es-ES' }),
    body,
    querySelector: (s) => body.querySelector(s),
    querySelectorAll: (s) => body.querySelectorAll(s)
  };

  const res = reader.extractArticle(doc);
  assert.equal(res.ok, true);
  assert.equal(res.title, 'Cómo funciona la arquitectura limpia en navegadores móviles');
  assert.equal(res.byline, 'Elena Morales');
  assert.equal(res.siteName, 'Revista Nexo');
  assert.equal(res.lang, 'es-ES');
  assert.equal(res.url, 'https://revista.example.org/post/arquitectura?page=1');
  assert.ok(res.wordCount >= 45);
  assert.ok(res.readingMinutes >= 1);

  const joined = res.blocks.map(b => b.text).join('\n');
  assert.ok(joined.includes('navegadores modernos separan cuidadosamente'));
  assert.ok(joined.includes('Aislamiento y seguridad'));
  assert.ok(!joined.includes('alert("xss")'));
  assert.ok(!joined.includes('cookies publicitarias'));
  assert.ok(!joined.includes('Texto oculto'));
  assert.ok(!joined.includes('Copyright 2026'));

  // Solo el enlace HTTPS saneado se conserva; javascript: se descarta.
  const firstPara = res.blocks.find(b => b.type === 'p');
  assert.equal(firstPara.links.length, 1);
  assert.equal(firstPara.links[0].href, 'https://docs.example.org/architecture?lang=es');
  assert.ok(res.blocks.some(b => b.type === 'quote'));
  assert.ok(res.blocks.some(b => b.type === 'li'));
});

test('extractArticle honestly reports non-article pages without fabricating text', () => {
  const body = el('body', {}, [
    el('h1', {}, [txt('Iniciar sesión')]),
    el('form', {}, [el('p', {}, [txt('Introduce tu usuario y contraseña')])]),
    el('p', {}, [txt('Bienvenido a tu cuenta.')])
  ]);
  const doc = {
    title: 'Login',
    location: { href: 'https://accounts.example.org/login' },
    documentElement: el('html', { lang: 'es' }),
    body,
    querySelector: (s) => body.querySelector(s),
    querySelectorAll: (s) => body.querySelectorAll(s)
  };

  const res = reader.extractArticle(doc);
  assert.equal(res.ok, false);
  assert.ok(res.reason.includes('artículo'));
});
