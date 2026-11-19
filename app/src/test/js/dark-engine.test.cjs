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
