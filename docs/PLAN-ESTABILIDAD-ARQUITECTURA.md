# Plan de estabilidad y arquitectura

## Objetivos aprobados

- Reforzar seguridad de red y compatibilidad Android.
- Separar gradualmente las clases monolíticas.
- Medir memoria, arranque y pestañas.
- Modernizar la interfaz y accesibilidad.
- Aumentar cobertura Android sin romper GeckoView.

## Cambios de esta fase

- usesCleartextTraffic queda desactivado globalmente.
- targetSdk sube a 35.
- Se documentan criterios de aceptación y fases de migración.

## Próximas fases

1. Extraer controladores de MainActivity para navegación, permisos, medios y omnibox.
2. Separar runtime, instalación y validación de extensiones.
3. Dividir TabManager en repositorio, ciclo de vida, grupos y política de memoria.
4. Añadir pruebas de SafetyRules, descargas, intents, migración y ciclo de vida.
5. Añadir Material, strings accesibles, layouts 320/390/600dp y modo horizontal.
6. Incorporar benchmarks y política de descarte bajo presión de memoria.

## Aceptación

- Tests JS, unitarios, release y lint pasan.
- Servicios propios usan HTTPS.
- Navegación HTTP iniciada por el usuario continúa funcionando.
- El estado se conserva tras rotación y background/foreground.
- Los controles principales tienen descripciones accesibles.
