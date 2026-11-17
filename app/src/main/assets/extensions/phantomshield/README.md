# PhantomShield en Nexo (Chromium)

Bloqueo de anuncios y rastreadores de Nexo. Funciona con el motor de reglas nativo de
`app/src/main/java/com/nexo/browser/shield/` y estos archivos; no se instala como extensión.

| Carpeta | Uso en Nexo |
| --- | --- |
| `content/` | Scripts de cada página, inyectados al inicio del documento por `PhantomShield.kt`: `page-hooks.js` (popups, Canvas, anuncios de YouTube), `content-script.js`, `cosmetic-filter.js` y `heuristic-vision.js` (Facebook/Instagram). Hablan con Nexo por `browser.*` de `assets/nexo/phantom-shim.js`. |
| `data/` | Listas incluidas (servidores de anuncios, rastreadores, popups, reglas cosméticas, seguridad funcional). Las lee `BundledData` al arrancar. |
| `popup/` | Panel del escudo (`PhantomShieldPanel.kt`), servido en `https://phantomshield.nexo.invalid` dentro del perfil del panel. |
| `web-accessible/` | Scripts sustitutos (Google Analytics, GTM…) que el interceptor de red sirve en lugar del rastreador. |
| `options/`, `privacy/`, `fonts/`, `icons/` | Recursos del panel. |
| `background/` | **Referencia, no se ejecuta.** Implementación original del motor de reglas en JavaScript; las pruebas (`app/src/test/js/`, `ShieldParityTest.kt`) comprueban que el motor nativo decide igual. El panel nunca sirve esta carpeta. |
| `manifest.json` | Versión y recursos; el panel lo lee con `browser.runtime.getManifest()`. |

Pruebas:

```bash
node --test app/src/test/js/*.test.cjs
./gradlew :app:testDebugUnitTest --tests '*ShieldParityTest'
```
