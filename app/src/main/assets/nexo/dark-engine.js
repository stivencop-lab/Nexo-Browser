/*
 * Nexo Dark Engine — motor oscuro temprano e híbrido para páginas web.
 *
 * - Corre desde `document_start`: si Chromium anuncia `prefers-color-scheme: dark`,
 *   pinta un lienzo oscuro temprano antes del primer render para minimizar el destello blanco.
 * - Detecta páginas con modo oscuro nativo (`color-scheme: dark`, atributos de tema oscuro
 *   o luminancia de fondo < 0.35) y evita forzar filtros innecesarios.
 * - Motor híbrido: CSS ligero de Nexo con exclusión segura de medios, canvas, SVG,
 *   editores de código/texto, PDF, formularios y marcos de autenticación/pago; con
 *   Dark Reader opcional bajo demanda si la estrategia lo permite.
 * - Sin `eval`, sin duplicación de `<style>` en SPA, reversible sin recargar la página.
 */
(function (rootScope) {
  "use strict";

  const EARLY_STYLE_ID = "nexo-dark-early";
  const ENGINE_STYLE_ID = "nexo-dark-engine";
  const META_ATTR = "data-nexo-dark-meta";

  const SAFE_BACKGROUND = /^#[0-9a-f]{6}$/i;

  function parseColor(value) {
    const match = /rgba?\(([^)]+)\)/.exec(String(value || ""));
    if (!match) return null;
    const parts = match[1].split(/[ ,/]+/).filter(Boolean).map(Number);
    if (parts.length < 3 || parts.slice(0, 3).some(n => !Number.isFinite(n))) return null;
    const a = parts.length > 3 && Number.isFinite(parts[3]) ? parts[3] : 1;
    return { r: parts[0], g: parts[1], b: parts[2], a };
  }

  function luminance(color) {
    if (!color) return 1;
    return (0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b) / 255;
  }

  function isPdfDocument(doc) {
    try {
      if (!doc) return false;
      if (String(doc.contentType || "").toLowerCase().includes("pdf")) return true;
      const body = doc.body;
      if (body && body.children && body.children.length === 1) {
        const first = body.children[0];
        if (first && String(first.tagName || "").toUpperCase() === "EMBED" &&
            String(first.type || "").toLowerCase().includes("pdf")) {
          return true;
        }
      }
    } catch (_) {}
    return false;
  }

  /** Detecta si la página ya declara o dibuja un tema oscuro nativo. */
  function detectNativeDark(doc, win) {
    try {
      if (!doc) return false;
      const root = doc.documentElement;
      const meta = doc.querySelector && doc.querySelector('meta[name="color-scheme"]');
      const scheme = String((meta && !meta.hasAttribute(META_ATTR) && meta.content) || "").toLowerCase();
      if (/\bdark\b/.test(scheme) && !/\blight\b/.test(scheme)) return true;

      if (root) {
        const themeAttr = (
          String(root.getAttribute && (root.getAttribute("data-theme") || root.getAttribute("data-color-mode") || root.getAttribute("data-bs-theme")) || "")
        ).toLowerCase();
        if (themeAttr === "dark" || themeAttr === "night" || themeAttr === "dim") return true;
      }

      const getStyle = (win && typeof win.getComputedStyle === "function") ? win.getComputedStyle : globalThis.getComputedStyle;
      if (typeof getStyle === "function") {
        // Suspender temporalmente nuestra propia capa temprana/CSS mientras medimos el color real
        // del documento para que `bootstrapEarly()` nunca provoque un falso positivo de "oscuro nativo".
        const earlyEl = doc.getElementById ? doc.getElementById(EARLY_STYLE_ID) : null;
        const engineEl = doc.getElementById ? doc.getElementById(ENGINE_STYLE_ID) : null;
        const hadEarlyAttr = Boolean(root && root.getAttribute && root.getAttribute("data-nexo-dark-early") !== null);
        const hadCssAttr = Boolean(root && root.getAttribute && root.getAttribute("data-nexo-dark-css") !== null);
        const prevEarlyDisabled = earlyEl ? earlyEl.disabled : undefined;
        const prevEngineDisabled = engineEl ? engineEl.disabled : undefined;
        try {
          if (earlyEl) earlyEl.disabled = true;
          if (engineEl) engineEl.disabled = true;
          if (hadEarlyAttr && root.removeAttribute) root.removeAttribute("data-nexo-dark-early");
          if (hadCssAttr && root.removeAttribute) root.removeAttribute("data-nexo-dark-css");
          for (const el of [doc.body, root]) {
            if (!el) continue;
            const computed = getStyle(el);
            if (!computed) continue;
            const color = parseColor(computed.backgroundColor);
            if (!color || color.a < 0.5) continue;
            return luminance(color) < 0.35;
          }
        } finally {
          if (hadEarlyAttr && root.setAttribute) root.setAttribute("data-nexo-dark-early", "true");
          if (hadCssAttr && root.setAttribute) root.setAttribute("data-nexo-dark-css", "true");
          if (earlyEl && prevEarlyDisabled !== undefined) earlyEl.disabled = prevEarlyDisabled;
          else if (earlyEl) earlyEl.disabled = false;
          if (engineEl && prevEngineDisabled !== undefined) engineEl.disabled = prevEngineDisabled;
          else if (engineEl) engineEl.disabled = false;
        }
      }
    } catch (_) {}
    return false;
  }

  function buildEarlyCss(bg, text) {
    return [
      `html[data-nexo-dark-early="true"]{color-scheme:dark !important;background-color:${bg} !important;color:${text} !important;}`,
      `html[data-nexo-dark-early="true"] body{background-color:${bg};color:${text};}`
    ].join("\n");
  }

  function buildNexoCss(bg, surface, text) {
    const border = bg === "#000000" ? "#262626" : "#273248";
    const inputBg = bg === "#000000" ? "#111111" : "#182235";
    const linkColor = "#8ab4f8";
    const visitedColor = "#c58af9";
    return [
      `html[data-nexo-dark-css="true"]{color-scheme:dark !important;background-color:${bg} !important;color:${text} !important;}`,
      `html[data-nexo-dark-css="true"] body{background-color:${bg} !important;color:${text} !important;}`,
      `html[data-nexo-dark-css="true"] :where(main,article,section,header,footer,nav,aside,div,p,ul,ol,li,table,tr,td,th,blockquote,dd,dt,dl){border-color:${border};}`,
      `html[data-nexo-dark-css="true"] :where(main,article,section,header,footer,nav,aside,table,tr,td,th,dialog){background-color:transparent;color:inherit;}`,
      `html[data-nexo-dark-css="true"] :where(a[href]){color:${linkColor};}`,
      `html[data-nexo-dark-css="true"] :where(a[href]:visited){color:${visitedColor};}`,
      `html[data-nexo-dark-css="true"] :where(input,textarea,select,button):not([type="checkbox"]):not([type="radio"]):not([type="color"]){background-color:${inputBg};color:${text};border-color:${border};}`,
      `html[data-nexo-dark-css="true"] :where(pre,code){background-color:${surface};color:${text};border-color:${border};}`,
      /* Exclusiones seguras: nunca invertir ni romper medios, canvas, SVG, editores, PDF ni widgets de pago/auth. */
      `html[data-nexo-dark-css="true"] :where(img,picture,video,canvas,svg,iframe,embed,object,[contenteditable="true"],.monaco-editor,.CodeMirror,.cm-editor,.ProseMirror,.ql-editor,.StripeElement,[data-payment],embed[type="application/pdf"]){filter:none !important;}`
    ].join("\n");
  }

  function createController(env) {
    const doc = (env && env.document) || (typeof document !== "undefined" ? document : null);
    const win = (env && env.window) || (typeof window !== "undefined" ? window : globalThis);
    const loadDarkReader = (env && env.loadDarkReader) || null;
    const getDarkReader = (env && env.getDarkReader) || (() => (typeof DarkReader !== "undefined" ? DarkReader : null));

    const state = {
      want: false,
      background: "#0c1222",
      surface: "#151d30",
      text: "#e8e6e3",
      oled: false,
      strategy: "hybrid",
      mode: "auto",
      excluded: false,
      activeEngine: "none", // "none" | "native" | "css" | "dark_reader"
      earlyActive: false,
      domHooked: false
    };

    function removeStyle(id) {
      try {
        const existing = doc && doc.getElementById ? doc.getElementById(id) : null;
        if (existing && typeof existing.remove === "function") existing.remove();
      } catch (_) {}
    }

    function upsertStyle(id, css) {
      if (!doc || !doc.documentElement) return null;
      try {
        let el = doc.getElementById ? doc.getElementById(id) : null;
        if (!el) {
          el = doc.createElement("style");
          el.id = id;
          const parent = doc.head || doc.documentElement;
          parent.appendChild(el);
        }
        if (el.textContent !== css) el.textContent = css;
        return el;
      } catch (_) {
        return null;
      }
    }

    function setMetaColorScheme(enabled) {
      if (!doc || !doc.documentElement) return;
      try {
        const existing = doc.querySelector && doc.querySelector('meta[name="color-scheme"]');
        if (enabled) {
          if (!existing && doc.head) {
            const meta = doc.createElement("meta");
            meta.name = "color-scheme";
            meta.content = "dark";
            meta.setAttribute(META_ATTR, "true");
            doc.head.appendChild(meta);
          }
        } else if (existing && existing.hasAttribute && existing.hasAttribute(META_ATTR)) {
          existing.remove();
        }
      } catch (_) {}
    }

    function applyEarly(enabled) {
      if (!doc || !doc.documentElement) return;
      state.earlyActive = Boolean(enabled);
      try {
        if (enabled) {
          doc.documentElement.setAttribute("data-nexo-dark-early", "true");
          upsertStyle(EARLY_STYLE_ID, buildEarlyCss(state.background, state.text));
          setMetaColorScheme(true);
        } else {
          doc.documentElement.removeAttribute("data-nexo-dark-early");
          removeStyle(EARLY_STYLE_ID);
          setMetaColorScheme(false);
        }
      } catch (_) {}
    }

    function disableDarkReader() {
      try {
        const dr = getDarkReader();
        if (dr && typeof dr.disable === "function") dr.disable();
      } catch (_) {}
    }

    function disableCss() {
      try {
        if (doc && doc.documentElement) doc.documentElement.removeAttribute("data-nexo-dark-css");
        removeStyle(ENGINE_STYLE_ID);
      } catch (_) {}
    }

    function disable() {
      state.want = false;
      state.activeEngine = "none";
      applyEarly(false);
      disableCss();
      disableDarkReader();
    }

    function applyCssLayer() {
      if (!doc || !doc.documentElement) return;
      disableDarkReader();
      doc.documentElement.setAttribute("data-nexo-dark-css", "true");
      upsertStyle(ENGINE_STYLE_ID, buildNexoCss(state.background, state.surface, state.text));
      state.activeEngine = "css";
    }

    function enableDarkReaderLayer() {
      const dr = getDarkReader();
      const runEnable = reader => {
        if (!state.want || state.excluded || isPdfDocument(doc)) return false;
        if (!state.oled && detectNativeDark(doc, win)) {
          disableCss();
          applyEarly(false);
          state.activeEngine = "native";
          return false;
        }
        try {
          if (typeof reader.setFetchMethod === "function" && typeof fetch === "function") {
            reader.setFetchMethod(url => fetch(url, { credentials: "omit" }));
          }
          reader.enable({
            brightness: 100,
            contrast: 95,
            sepia: 0,
            darkSchemeBackgroundColor: state.background,
            darkSchemeTextColor: state.text
          });
          disableCss();
          state.activeEngine = "dark_reader";
          return true;
        } catch (_) {
          return false;
        }
      };

      if (dr && typeof dr.enable === "function") {
        if (!runEnable(dr) && state.activeEngine !== "native") applyCssLayer();
        return;
      }
      if (typeof loadDarkReader === "function") {
        Promise.resolve(loadDarkReader()).then(ok => {
          if (!state.want || state.excluded) return;
          const loaded = getDarkReader();
          if (ok && loaded && typeof loaded.enable === "function") {
            if (!runEnable(loaded) && state.activeEngine !== "native") applyCssLayer();
          } else if (state.activeEngine === "none") {
            applyCssLayer();
          }
        }, () => {
          if (state.want && !state.excluded && state.activeEngine === "none") applyCssLayer();
        });
      } else {
        applyCssLayer();
      }
    }

    function evaluate() {
      if (!state.want || state.excluded) {
        disable();
        return state.activeEngine;
      }
      if (isPdfDocument(doc)) {
        applyEarly(false);
        disableCss();
        disableDarkReader();
        state.activeEngine = "none";
        return state.activeEngine;
      }

      if (!state.oled && detectNativeDark(doc, win)) {
        applyEarly(false);
        disableCss();
        disableDarkReader();
        state.activeEngine = "native";
        return state.activeEngine;
      }

      if (state.strategy === "css") {
        applyEarly(true);
        applyCssLayer();
        return state.activeEngine;
      }

      if (state.strategy === "dark_reader") {
        applyEarly(true);
        enableDarkReaderLayer();
        return state.activeEngine;
      }

      // Estrategia híbrida: CSS ligero inmediato + opcionalmente Dark Reader si ya está cargado o pedido.
      applyEarly(true);
      applyCssLayer();
      if (typeof loadDarkReader === "function") {
        enableDarkReaderLayer();
      }
      return state.activeEngine;
    }

    const onPageShow = () => { if (state.want && !state.excluded) evaluate(); };

    function hookLifecycle() {
      if (state.domHooked || !doc || typeof doc.addEventListener !== "function") return;
      state.domHooked = true;
      if (doc.readyState === "loading") {
        doc.addEventListener("DOMContentLoaded", onPageShow, { once: true });
      }
      if (win && typeof win.addEventListener === "function") {
        win.addEventListener("pageshow", onPageShow, { passive: true });
      }
    }

    function applyConfig(config) {
      if (!config || typeof config !== "object") return state.activeEngine;
      if (typeof config.background === "string" && SAFE_BACKGROUND.test(config.background)) {
        state.background = config.background;
        state.oled = config.background.toLowerCase() === "#000000";
        state.surface = state.oled ? "#0a0a0a" : "#151d30";
      }
      if (typeof config.oled === "boolean") {
        state.oled = config.oled;
        if (state.oled) { state.background = "#000000"; state.surface = "#0a0a0a"; }
      }
      if (typeof config.strategy === "string" && (config.strategy === "hybrid" || config.strategy === "css" || config.strategy === "dark_reader")) {
        state.strategy = config.strategy;
      }
      if (typeof config.excluded === "boolean") state.excluded = config.excluded;
      if (typeof config.enabled === "boolean") state.want = config.enabled;

      if (!state.want || state.excluded) {
        disable();
        return state.activeEngine;
      }
      hookLifecycle();
      return evaluate();
    }

    /** Arranque temprano en document_start guiado por prefers-color-scheme de Chromium. */
    function bootstrapEarly() {
      try {
        if (!win || typeof win.matchMedia !== "function") return false;
        if (win.matchMedia("(prefers-color-scheme: dark)").matches) {
          applyEarly(true);
          return true;
        }
      } catch (_) {}
      return false;
    }

    return {
      state,
      applyConfig,
      evaluate,
      disable,
      bootstrapEarly,
      detectNativeDark: () => detectNativeDark(doc, win),
      isPdfDocument: () => isPdfDocument(doc)
    };
  }

  const api = Object.freeze({
    EARLY_STYLE_ID,
    ENGINE_STYLE_ID,
    parseColor,
    luminance,
    detectNativeDark,
    isPdfDocument,
    buildEarlyCss,
    buildNexoCss,
    createController
  });

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    rootScope.nexoDarkEngine = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
