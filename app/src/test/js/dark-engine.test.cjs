const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const darkEngine = require(path.join(__dirname, "../../main/assets/nexo/dark-engine.js"));

function createFakeDom(opts = {}) {
  const attrs = new Map();
  const styles = new Map();
  let metaTag = opts.metaColorScheme
    ? {
        name: "color-scheme",
        content: opts.metaColorScheme,
        attrs: new Map(),
        hasAttribute(k) { return this.attrs.has(k); },
        setAttribute(k, v) { this.attrs.set(k, String(v)); },
        remove() { metaTag = null; }
      }
    : null;

  const head = {
    children: [],
    appendChild(el) {
      if (el.id) styles.set(el.id, el);
      if (el.name === "color-scheme") metaTag = el;
      this.children.push(el);
      return el;
    }
  };
  const documentElement = {
    head,
    setAttribute(k, v) { attrs.set(k, String(v)); },
    removeAttribute(k) { attrs.delete(k); },
    getAttribute(k) { return attrs.has(k) ? attrs.get(k) : null; },
    appendChild(el) { return head.appendChild(el); }
  };
  const body = {
    children: opts.isPdf ? [{ tagName: "EMBED", type: "application/pdf" }] : []
  };
  const doc = {
    readyState: opts.readyState || "complete",
    contentType: opts.contentType || "text/html",
    documentElement,
    head,
    body,
    getElementById(id) { return styles.get(id) || null; },
    querySelector(sel) {
      if (sel === 'meta[name="color-scheme"]') return metaTag;
      return null;
    },
    createElement(tag) {
      const localAttrs = new Map();
      const el = {
        tagName: tag.toUpperCase(),
        id: "",
        name: "",
        content: "",
        textContent: "",
        setAttribute(k, v) { localAttrs.set(k, String(v)); },
        hasAttribute(k) { return localAttrs.has(k); },
        remove() {
          if (el.id) styles.delete(el.id);
          if (el === metaTag) metaTag = null;
        }
      };
      return el;
    },
    addEventListener() {}
  };
  const win = {
    matchMedia(q) { return { matches: Boolean(opts.prefersDark && q.includes("dark")) }; },
    getComputedStyle(el) {
      if (el === body) return { backgroundColor: opts.bodyBg || "rgb(255, 255, 255)" };
      return { backgroundColor: opts.rootBg || "rgba(0, 0, 0, 0)" };
    },
    addEventListener() {}
  };
  return { doc, win, attrs, styles, getMeta: () => metaTag };
}

test("bootstrapEarly injects dark canvas style when Chromium announces dark prefers-color-scheme", () => {
  const { doc, win, attrs, styles } = createFakeDom({ prefersDark: true });
  const ctrl = darkEngine.createController({ document: doc, window: win });
  assert.equal(ctrl.bootstrapEarly(), true);
  assert.equal(attrs.get("data-nexo-dark-early"), "true");
  assert.ok(styles.has(darkEngine.EARLY_STYLE_ID));
});

test("native dark site skips forced CSS and Dark Reader", () => {
  const { doc, win, styles } = createFakeDom({ bodyBg: "rgb(18, 22, 30)" });
  let drEnabled = 0;
  const ctrl = darkEngine.createController({
    document: doc,
    window: win,
    getDarkReader: () => ({ enable() { drEnabled++; }, disable() {} })
  });
  const engine = ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "hybrid" });
  assert.equal(engine, "native");
  assert.equal(drEnabled, 0);
  assert.equal(styles.has(darkEngine.ENGINE_STYLE_ID), false);
});

test("CSS strategy protects media, canvas, editors, PDF and avoids duplicate style nodes across updates", () => {
  const { doc, win, styles } = createFakeDom({ bodyBg: "rgb(255, 255, 255)" });
  const ctrl = darkEngine.createController({ document: doc, window: win });
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "css" });
  ctrl.applyConfig({ enabled: true, background: "#000000", oled: true, strategy: "css" });

  assert.equal(ctrl.state.activeEngine, "css");
  assert.equal(styles.size, 2); // early + engine, never duplicated
  const css = styles.get(darkEngine.ENGINE_STYLE_ID).textContent;
  assert.ok(css.includes("#000000"));
  assert.ok(css.includes("img,picture,video,canvas,svg,iframe"));
  assert.ok(css.includes(".monaco-editor"));
  assert.ok(css.includes(".StripeElement"));
});

test("PDF documents are never darkened", () => {
  const { doc, win, styles } = createFakeDom({ isPdf: true, contentType: "application/pdf" });
  const ctrl = darkEngine.createController({ document: doc, window: win });
  const engine = ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "css" });
  assert.equal(engine, "none");
  assert.equal(styles.has(darkEngine.ENGINE_STYLE_ID), false);
});

test("disable cleans up styles, attributes, synthetic meta tag, and Dark Reader without reload", () => {
  const { doc, win, attrs, styles, getMeta } = createFakeDom({ bodyBg: "rgb(255, 255, 255)" });
  let disabledCalls = 0;
  const ctrl = darkEngine.createController({
    document: doc,
    window: win,
    getDarkReader: () => ({ enable() {}, disable() { disabledCalls++; }, setFetchMethod() {} })
  });
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "dark_reader" });
  assert.equal(ctrl.state.activeEngine, "dark_reader");
  ctrl.applyConfig({ enabled: false });
  assert.equal(ctrl.state.activeEngine, "none");
  assert.equal(styles.size, 0);
  assert.equal(attrs.size, 0);
  assert.equal(getMeta(), null);
  assert.ok(disabledCalls >= 1);
});

test("bootstrapEarly does not fool detectNativeDark into treating a light transparent-body page as native dark", () => {
  const { doc, win, attrs, styles } = createFakeDom({
    prefersDark: true,
    bodyBg: "rgba(0, 0, 0, 0)",
    rootBg: "rgb(255, 255, 255)"
  });
  // Simulate real browser computedStyle: when data-nexo-dark-early is active on <html>,
  // <html> computes as #0c1222 unless Nexo suspends its own early style during measurement.
  win.getComputedStyle = (el) => {
    if (el === doc.body) return { backgroundColor: "rgba(0, 0, 0, 0)" };
    const earlyStyle = styles.get(darkEngine.EARLY_STYLE_ID);
    const earlyActive = attrs.get("data-nexo-dark-early") === "true" && (!earlyStyle || !earlyStyle.disabled);
    return { backgroundColor: earlyActive ? "rgb(12, 18, 34)" : "rgb(255, 255, 255)" };
  };

  const ctrl = darkEngine.createController({ document: doc, window: win });
  assert.equal(ctrl.bootstrapEarly(), true);
  assert.equal(attrs.get("data-nexo-dark-early"), "true");

  const engine = ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "css" });
  assert.equal(engine, "css", "must NOT misclassify light page as native dark after bootstrapEarly");
  assert.equal(styles.has(darkEngine.ENGINE_STYLE_ID), true);
  assert.equal(attrs.get("data-nexo-dark-css"), "true");
});

/** DOM en carga con eventos controlables, para el arranque en document_start. */
function loadingDom(opts = {}) {
  const dom = createFakeDom(Object.assign({ readyState: "loading" }, opts));
  const docListeners = {};
  const winListeners = {};
  dom.doc.addEventListener = (type, fn) => { (docListeners[type] = docListeners[type] || []).push(fn); };
  dom.win.addEventListener = (type, fn) => { (winListeners[type] = winListeners[type] || []).push(fn); };
  dom.fire = (type) => {
    if (type === "DOMContentLoaded") dom.doc.readyState = "interactive";
    if (type === "load") dom.doc.readyState = "complete";
    for (const fn of (docListeners[type] || []).concat(winListeners[type] || [])) fn({ type, persisted: false });
  };
  return dom;
}

test("Nexo boot config paints the full dark layer at document_start, before any bridge round-trip", () => {
  const { doc, win, attrs, styles } = loadingDom({ prefersDark: false });
  win[darkEngine.BOOT_KEY] = Object.freeze({ enabled: true, excluded: false, background: "#000000", oled: true, strategy: "hybrid" });
  const ctrl = darkEngine.createController({ document: doc, window: win, loadDarkReader: () => Promise.resolve(false) });
  assert.equal(ctrl.bootstrapEarly(), true);
  assert.equal(attrs.get("data-nexo-dark-css"), "true", "CSS layer (with !important body colours) from the first paint");
  assert.ok(styles.get(darkEngine.ENGINE_STYLE_ID).textContent.includes("#000000"), "OLED colours from the start, not navy then black");
  assert.equal(win[darkEngine.BOOT_KEY], undefined, "the page never sees the boot config");
});

test("an excluded site never flashes dark even if prefers-color-scheme says dark", () => {
  const { doc, win, attrs, styles } = loadingDom({ prefersDark: true });
  win[darkEngine.BOOT_KEY] = { enabled: false, excluded: true, background: "", oled: false, strategy: "hybrid" };
  const ctrl = darkEngine.createController({ document: doc, window: win });
  assert.equal(ctrl.bootstrapEarly(), false);
  assert.equal(attrs.size, 0);
  assert.equal(styles.size, 0);
});

test("a boot script that runs after the engine hands the config through the hook; the last (site) one wins", () => {
  const { doc, win, attrs } = loadingDom({ prefersDark: true });
  const ctrl = darkEngine.createController({ document: doc, window: win });
  ctrl.bootstrapEarly();
  assert.equal(typeof win[darkEngine.BOOT_HOOK], "function");
  win[darkEngine.BOOT_HOOK]({ enabled: true, excluded: false, background: "#0c1222", strategy: "css" });
  assert.equal(attrs.get("data-nexo-dark-css"), "true");
  win[darkEngine.BOOT_HOOK]({ enabled: false, excluded: true, background: "", strategy: "css" });
  assert.equal(attrs.size, 0, "site exception overrides the global policy");
});

test("hybrid keeps the light CSS layer and skips the 360 KB Dark Reader when the page does not need it", () => {
  const dom = loadingDom();
  let loads = 0;
  dom.doc.body.querySelectorAll = () => [];
  const ctrl = darkEngine.createController({ document: dom.doc, window: dom.win, loadDarkReader: () => { loads++; return Promise.resolve(true); } });
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "hybrid" });
  assert.equal(ctrl.state.activeEngine, "css");
  dom.fire("DOMContentLoaded");
  assert.equal(ctrl.state.activeEngine, "css");
  assert.equal(loads, 0);
});

test("hybrid loads Dark Reader only when light blocks or dark text would stay unreadable", async () => {
  const dom = loadingDom();
  const card = { childElementCount: 1, textContent: "", getBoundingClientRect: () => ({ left: 0, top: 0, right: 400, bottom: 300 }) };
  dom.doc.body.querySelectorAll = () => [card];
  dom.win.innerWidth = 400; dom.win.innerHeight = 800;
  const original = dom.win.getComputedStyle;
  dom.win.getComputedStyle = el => el === card ? { backgroundColor: "rgb(255, 255, 255)", color: "rgb(20, 20, 20)" } : original(el);
  let enabled = 0;
  let reader = null;
  const ctrl = darkEngine.createController({
    document: dom.doc, window: dom.win,
    loadDarkReader: () => { reader = { enable() { enabled++; }, disable() {}, setFetchMethod() {} }; return Promise.resolve(true); },
    getDarkReader: () => reader
  });
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "hybrid" });
  dom.fire("DOMContentLoaded");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(enabled, 1);
  assert.equal(ctrl.state.activeEngine, "dark_reader");
});

test("Dark Reader's own colours are never mistaken for a native dark page (no dark→light→dark flicker)", async () => {
  const dom = loadingDom();
  let disabled = 0;
  const reader = { enable() {}, disable() { disabled++; }, setFetchMethod() {} };
  const ctrl = darkEngine.createController({ document: dom.doc, window: dom.win, getDarkReader: () => reader });
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "dark_reader" });
  dom.fire("DOMContentLoaded");
  assert.equal(ctrl.state.activeEngine, "dark_reader");
  // Dark Reader ya oscureció el fondo: medirlo ahora daría «oscuro nativo».
  dom.win.getComputedStyle = () => ({ backgroundColor: "rgb(12, 18, 34)" });
  dom.fire("load");
  ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "dark_reader" });
  assert.equal(ctrl.state.activeEngine, "dark_reader");
  assert.equal(disabled, 0);
});

test("a site's own dark theme class (Tailwind html.dark) is respected", () => {
  const { doc, win, styles } = createFakeDom({ bodyBg: "rgb(255, 255, 255)" });
  doc.documentElement.classList = { contains: name => name === "dark" };
  const ctrl = darkEngine.createController({ document: doc, window: win });
  assert.equal(ctrl.applyConfig({ enabled: true, background: "#0c1222", strategy: "css" }), "native");
  assert.equal(styles.has(darkEngine.ENGINE_STYLE_ID), false);
});
