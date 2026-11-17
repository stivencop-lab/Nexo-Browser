# PhantomShield en Nexo (Chromium)

Bloqueo de anuncios y rastreadores de Nexo. Funciona con el motor de reglas nativo de
`app/src/main/java/com/nexo/browser/shield/` y estos archivos; no se instala como extensión.

| Carpeta | Uso en Nexo |
| --- | --- |
| `content/` | Scripts de cada página, inyectados al inicio del documento por `PhantomShield.kt`: `page-hooks.js` (popups, Canvas, anuncios de YouTube), `content-script.js`, `cosmetic-filter.js`, `heuristic-vision.js` (Facebook/Instagram) y `ai-detector.js` (solo la página principal, al cargar el documento). Hablan con Nexo por `browser.*` de `assets/nexo/phantom-shim.js`. |
| `data/` | Listas incluidas (servidores de anuncios, rastreadores, popups, reglas cosméticas, seguridad funcional). Las lee `BundledData` al arrancar. |
| `popup/` | Panel del escudo (`PhantomShieldPanel.kt`), servido en `https://phantomshield.nexo.invalid` dentro del perfil del panel. |
| `options/` | Ajustes de IA («Configurar IA» del panel), en la misma hoja; atrás vuelve al panel. |
| `host/` | Anfitrión oculto de la IA (`ShieldAiHost.kt`): carga `data/ai-config.js` y los módulos de IA de `background/` con `browser.*` de `assets/nexo/ai-host-shim.js` (almacén del escudo y `fetch` nativo). |
| `web-accessible/` | Scripts sustitutos (Google Analytics, GTM…) que el interceptor de red sirve en lugar del rastreador. |
| `privacy/`, `fonts/`, `icons/` | Recursos del panel. |
| `background/` | Solo `ai-memory.js`, `ai-domains.js` y `ai-classifier.js` se ejecutan (en el anfitrión de IA). El resto es **referencia**: el motor de reglas original en JavaScript; las pruebas (`app/src/test/js/`, `ShieldParityTest.kt`) comprueban que el motor nativo decide igual. El panel no sirve esos archivos. |
| `manifest.json` | Versión y recursos; el panel lo lee con `browser.runtime.getManifest()`. |

Pruebas:

```bash
node --test app/src/test/js/*.test.cjs
./gradlew :app:testDebugUnitTest --tests '*ShieldParityTest'
```
