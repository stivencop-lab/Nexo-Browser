# Auditoría de Nexo Browser v0.37.9

Revisión de la release [v0.37.9](https://github.com/stivencop414-bot/Nexo-Browser/releases/tag/v0.37.9) (UI 7.0 «Liquid Craft»):
peso del APK, rendimiento, bugs visuales, seguridad y dos funciones nuevas (una API para todas las funciones de IA y
posición de subtítulos). Incluye el diseño propuesto para un escáner de páginas maliciosas con IA.

## 1. Peso del APK

Medido sobre `Nexo-v0.37.9-arm64-v8a.apk` (120,7 MB):

| Componente | Comprimido | Sin comprimir | Comentario |
|---|---:|---:|---|
| `lib/arm64-v8a/libcef.so` (Chromium 156) | 89,6 MB | 211,7 MB | 74 % del APK. No se puede recortar desde Nexo. |
| Ficheros Cefrium en `assets/` (`icudtl.dat` 9,3 MB, `.pak`) | ~11 MB | ~11,5 MB | Datos de ICU y recursos de Chromium. |
| `classes.dex` + `classes2.dex` | 11,7 MB | 11,7 MB | ~80 % es código Java de Chromium/Cefrium. |
| `resources.arsc` | 4,75 MB | 4,75 MB | ~120 idiomas que trae Chromium. |
| Fuente Inter (solo Nexo Reader) | 0,46 MB | 0,88 MB | Duplicaba la tipografía de la app. |
| Inter WOFF2 del panel de PhantomShield | 0,35 MB | 0,35 MB | Igual. |
| Firma v1 (`META-INF/*.SF`, `MANIFEST.MF`) | ~0,23 MB | ~0,47 MB | Inútil con `minSdk 29`. |

### Aplicado en este cambio

- `androidResources.localeFilters = es, en`: `resources.arsc` pasa de **4,75 MB a 1,52 MB**.
- Nexo Reader usa Plus Jakarta Sans y se elimina `inter_variable.ttf` (**−0,46 MB**).
- PhantomShield usa Plus Jakarta Sans en WOFF2 (60 KB) en lugar de Inter (350 KB) (**−0,29 MB**).
- El CI firma solo con v2 + v3 (`--v1-signing-enabled false`) (**−~0,23 MB**). `verify_apk_signature.py`
  sigue exigiendo un único firmante con la huella persistente.

Resultado medido con `assembleRelease` local: **arm64 120,7 → ~116,9 MB** y **armeabi-v7a 91,9 → ~88,0 MB**
(≈ −3,9 MB por APK).

### Recomendado, no aplicado

1. **Espacio instalado (más importante que la descarga).** Con `useLegacyPackaging = true`, Android extrae `libcef.so`
   al instalar: Nexo ocupa ~121 MB de APK **+ 212 MB** de librería extraída ≈ **330 MB** en el teléfono. Con
   `useLegacyPackaging = false` el APK descargado crece a ~245 MB, pero el instalado baja a ~245 MB (la librería se
   mapea desde el APK). Si se distribuye por Google Play (AAB), Play comprime la descarga y se obtiene lo mejor de
   ambos. Para descargas directas desde GitHub conviene decidirlo según el tipo de usuario.
2. **Dejar de publicar armeabi-v7a** si las estadísticas lo permiten (dispositivos de 32 bits con Android 10+ son
   minoritarios): ahorra compilación, CI y confusión al descargar.
3. Pedir a Cefrium un build con **`symbol_level=0` y `is_official_build`** verificado, y la variante sin locales
   extra de Chromium en `assets/` (hoy solo `en-US.pak`, correcto).
4. `darkreader.js` (360 KB) se empaqueta sin minificar; minificarlo ahorraría ~150 KB sin comprimir (≈ 25 KB en APK).

## 2. Rendimiento

| Problema | Impacto | Estado |
|---|---|---|
| La píldora de Nexo AI «respiraba» en estado *Listo* indefinidamente | Redibujo continuo a 60 fps mientras estaba visible (batería) | **Corregido**: solo anima mientras llegan tokens. |
| `NexoGlassDrawable` creaba un `LinearGradient` en cada `draw()` | Asignaciones por fotograma en el cromo | **Corregido**: se cachea por tamaño. |
| El cristal del cromo no se repintaba al desplazar Inicio | Desenfoque «congelado» (también visual) | **Corregido**: `NexoGlass.follow()` conectado. |
| `styleChromeGlass()` crea drawables en cada `renderTab` | Asignaciones leves al cambiar de pestaña | Aceptable; se puede reutilizar si el perfilado lo pide. |

## 3. Bugs visuales de la nueva interfaz

| Bug | Estado |
|---|---|
| Subtítulos sin video detectado quedaban **detrás del cromo flotante** (la web ahora va por debajo) | **Corregido**: el área de colocación descuenta el cromo (`captionBottomObstruction`). Prueba `captionsPositions`. |
| Tarjetas de pestañas sin captura en **Paper**: degradado oscuro y texto blanco sobre fondo claro | **Corregido**: degradado y texto claro solo sobre capturas reales. |
| Paneles HTML de PhantomShield con la estética antigua: azul-pizarra `#0c1222`, MAYÚSCULAS, pesos 700–800, degradados, **icono de chispas**, botones azul fijo | **Corregido**: tokens Obsidian/Paper, Plus Jakarta Sans, emblema Aperture y acciones en tinta. |
| La captura de regresión del menú mostraba textos antiguos | **Corregido** y se añade un barrido Paper/OLED (`liquidCraftThemeSweep`). |

Pendiente de verificar en dispositivo (Robolectric no dibuja sombras ni `RenderEffect`): profundidad del cristal en
Paper, desenfoque real y la web desplazándose bajo el cromo. Si ves algún fallo concreto, una captura basta para
reproducirlo.

## 4. Seguridad

| # | Hallazgo | Severidad | Estado |
|---|---|---|---|
| S1 | `NexoApplication` fija `ALLOW_CAPTURE_BY_ALL`: cualquier app con permiso de grabación de pantalla podía capturar el **audio de pestañas privadas** | Media | **Corregido**: en privado solo lo captura el sistema (Subtítulos instantáneos), salvo mientras Nexo captura su propio audio para los subtítulos en vivo. |
| S2 | Las claves de la IA de PhantomShield se guardan **en claro** en `files/…/storage.json` (las de Nexo AI sí van en Android Keystore) | Media | **Mitigado**: con «Una API para todo» (activado por defecto) la clave maestra vive en Keystore y nunca se escribe en ese JSON. Recomendado: migrar también las claves propias de PhantomShield a `SecretStore`. |
| S3 | Endpoints de IA personalizados aceptan cualquier host HTTPS | Baja | Advertir al guardar un host desconocido (posible ingeniería social para robar la clave). |
| S4 | `usesCleartextTraffic=true` | Baja | Necesario para sitios `http://`. Recomendado: modo **HTTPS-First** por defecto (intentar HTTPS y avisar antes de caer a HTTP). |
| S5 | Puente de PhantomShield con `anyOrigin().withSubframes()` | Informativo | Deliberado: solo atiende mensajes de estado de bloqueo y selectores, con pruebas de paridad. Recomendado: prueba de *fuzzing* del parser `PageChannel`. |
| S6 | Actualizador | Correcto | Verifica SHA-256 contra `SHA256SUMS.txt` y el certificado oficial (o el instalado); Android impide el *downgrade*. |
| S7 | Resto de la superficie | Correcto | `allowBackup=false`, `FLAG_SECURE` en privado, claves AES-GCM en Keystore, intents externos limitados a http/https, proveedor heredado con permiso `signature`, CI que prohíbe `addJavascriptInterface`, `javascript:` y aceptar errores TLS. |

## 5. «Una API para todo» (implementado)

Nueva opción en **Nexo AI › Ajustes › Usar esta API para todo**, **activada por defecto**. Una sola conexión
(proveedor, modelo, endpoint y clave en Keystore) para el asistente, Nexo Reader, la traducción de páginas, los
subtítulos en vivo (modelo 1) y la IA de PhantomShield (`AiHub`).

- Cada función conserva su propio interruptor de uso; la opción solo decide con qué API hablan.
- Si Nexo AI no está configurado, cada función usa sus ajustes propios, como antes.
- En la hoja de subtítulos, los campos de modelo/clave se sustituyen por «Usa la API de Nexo AI».
- PhantomShield recibe la conexión al leer su configuración y su almacén nunca guarda la clave maestra
  (`AiHubTest`). OpenRouter, Gemini, Groq, etc. se le presentan como «Compatible» con su dirección real.

## 6. Posición de los subtítulos (implementado)

En **Subtítulos en vivo › Posición**: *Sobre el video* (por defecto), *Arriba*, *Centro*, *Abajo* o *Donde lo
arrastre*. Además, el subtítulo se puede **arrastrar con el dedo**; al soltarlo queda guardado (posición relativa, así
que se adapta a la rotación). En todos los modos el texto se mantiene fuera de la barra de estado y del cromo flotante.

## 7. Escáner de páginas maliciosas con IA (implementado)

Detecta *phishing*, estafas y descargas maliciosas al terminar de cargar cada página, sin enviar nada a internet
salvo que el usuario active la segunda opinión con IA.

### Capas

1. **Safe Browsing de Chromium** (ya activo con «Avisar de sitios peligrosos»).
2. **Heurística local** (`shield/MalwareSignals.kt`, Kotlin puro con 12 pruebas JVM). Puntuación 0–100:
   - URL: marcas suplantadas fuera de su dominio (`paypal-login.secure-check.xyz`), punycode, IP como host, `@` en la
     URL, TLD de alto abuso, subdominios muy largos, rutas que imitan páginas de acceso.
   - DOM (`PROBE_SCRIPT`, solo recuentos y metadatos; nunca valores de campos ni cookies): contraseñas o tarjetas
     que se envían a otro dominio, formularios por HTTP, *iframes* ocultos, scripts ofuscados, descargas de
     `.apk/.exe`, `meta refresh` a otro dominio, textos de falso soporte técnico, títulos que suplantan una marca.
3. **Segunda opinión con IA** (`shield/MalwareScan.kt`): solo si la puntuación local es ≥ 40, el ajuste está activado
   y hay conexión en Nexo AI («Una API para todo»). Se envía un resumen acotado (URL, dominio registrable, señales,
   título y ≤ 2 KB de texto visible) como `<UNTRUSTED_PAGE_CONTEXT>` y se exige JSON estricto
   (`veredicto`, `tipo`, `suplanta`, `confianza`, `motivos`). Respeta la lista de sitios excluidos de Nexo AI.
4. **Decisión** (`MalwareSignals.decide`):
   - Local ≥ 85, o IA «peligroso» con confianza ≥ 0,8 → pantalla de aviso a pantalla completa
     (`shield/MalwareWarning.kt`): «Volver a un sitio seguro» (también con el gesto atrás) y
     «Continuar bajo mi responsabilidad» (vale para ese origen mientras la app siga abierta).
   - Sospechoso → icono de escudo con aviso en la barra (ámbar o rojo) y motivos en Control del sitio.
   - Un «seguro» de la IA rebaja un caso dudoso, pero no anula una puntuación local muy alta (la página podría haber
     inyectado instrucciones).

### Privacidad y coste

- Nunca analiza pestañas privadas ni Burner.
- Caché del resultado por origen durante 24 h (máx. 300 orígenes, en memoria).
- Ajustes › Privacidad: «Avisar de páginas fraudulentas» (activado; todo en el dispositivo) y
  «Segunda opinión con IA» (desactivado; explica qué se envía).

### Pendiente

- Corpus de URLs/HTML de *phishing* reales en `app/src/test` para medir falsos positivos antes de bajar umbrales.
- Bloquear el envío de formularios de contraseña en páginas sospechosas hasta confirmar.
- Límite de peticiones a la IA por hora.
