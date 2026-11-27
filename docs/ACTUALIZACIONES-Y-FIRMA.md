# Actualizaciones, firma y publicación de Nexo

El paquete Android actual es `com.nexo.browser`. Android actualiza en el sitio una instalación con el mismo paquete y certificado de firma cuando el nuevo APK tiene un `versionCode` mayor. Desde v0.25.0, las instalaciones del paquete anterior `com.ejemplo.navegador` siguen la ruta de importación. [Cambio de identidad](IDENTIDAD-NEXO.md) · [Transferencia de perfil](TRANSFERENCIA-PERFIL.md)

## Actualizar en el teléfono

1. Abre GitHub Releases y descarga el APK firmado para tu arquitectura. [Última publicación](https://github.com/stivencop-lab/Nexo-Browser/releases/latest)
2. Compara el hash del archivo con `SHA256SUMS.txt` de esa misma release.
3. Abre el APK para actualizar. Si Android indica que el paquete entra en conflicto con una app existente, comprueba el paquete y el certificado del APK instalado antes de desinstalar. Las compilaciones debug se firman con otra clave.

En Linux puedes verificar el archivo desde la carpeta de descargas.

```bash
sha256sum --ignore-missing -c SHA256SUMS.txt
```

En macOS, calcula el hash con `shasum -a 256 Nexo-v0.40.1-arm64-v8a.apk`. En PowerShell, usa `Get-FileHash -Algorithm SHA256 .\Nexo-v0.40.1-arm64-v8a.apk`. Sustituye el nombre del ejemplo por el de tu descarga y compara el resultado con su línea en `SHA256SUMS.txt`.

## Publicar una versión

1. Integra los cambios de la app en `main`. Comprueba que `versionName` y `versionCode` en `app/build.gradle.kts` coinciden con la versión y superan la última distribución.
2. Verifica los avisos que acompañarán a los APK, la disponibilidad del código fuente correspondiente de los componentes que lo requieran y las condiciones para redistribuir Cefrium. [Licencias y avisos](LICENCIAS.md)
3. Ejecuta **Nexo Secure CI and Release › Run workflow** sobre `main`, con `mode=release` y el campo tag vacío para usar `v<versionName>`. También puedes publicar un tag `v<versionName>` que apunte al commit integrado. Usa uno de los dos disparadores por versión.
4. Comprueba que GitHub Releases muestra los APK `arm64-v8a` y `armeabi-v7a`, además de `SHA256SUMS.txt`. Contrasta el paquete, versión y huella con el APK firmado antes de actualizar los enlaces del README. [Workflow de publicación](../.github/workflows/main.yml)

El flujo de publicación valida constructores de arranque tras R8, identidad del paquete y certificado de firma. La compilación se ejecuta antes de acceder al keystore. La release conserva sus APK y sumas, por lo que una corrección requiere otra versión y otro tag.

## Firma y certificado fijado

El paso de firma usa cuatro secretos de GitHub Actions. Guarda el keystore y sus credenciales en el respaldo privado del proyecto.

| Secreto | Contenido |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore persistente codificado en Base64. |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del almacén. |
| `ANDROID_KEY_ALIAS` | Alias de firma del almacén. |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave. |

La huella pública en [`config/android-signing-certificate.sha256`](../config/android-signing-certificate.sha256) permite verificar la continuidad de firma. Si la huella de un APK difiere, recupera el keystore de la misma línea de firma. Puedes comprobar el certificado del APK descargado con el verificador del proyecto.

```bash
python3 tools/verify_apk_signature.py \
  --apksigner "$ANDROID_HOME/build-tools/37.0.0/apksigner" \
  --certificate config/android-signing-certificate.sha256 \
  /ruta/descargas/Nexo-v0.40.1-arm64-v8a.apk
```

<details>
<summary>Firma local y antecedentes del certificado</summary>

Para producir APK firmados localmente, usa el respaldo privado del proyecto y carga las contraseñas en `KEYSTORE_PASSWORD` y `KEY_PASSWORD` en tu entorno de ejecución.

```bash
./gradlew :app:assembleRelease
APKSIGNER="$ANDROID_HOME/build-tools/37.0.0/apksigner"
for abi in arm64-v8a armeabi-v7a; do
  "$APKSIGNER" sign --ks /ruta/privada/nexo-release.jks --ks-key-alias nexo \
    --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "Nexo-$abi.apk" "app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
  python3 tools/verify_apk_signature.py --apksigner "$APKSIGNER" \
    --certificate config/android-signing-certificate.sha256 "Nexo-$abi.apk"
done
```

La línea de firma persistente empezó en v0.14.1 con una clave RSA de 4096 bits. El certificado de v0.13.6 tenía la huella `8a2bf18b515d613aeb8b0da920a5f102d3a1741e9b3a736be2fc70e29aef3d0b`. Para migrar de una compilación firmada con otro certificado, conserva primero una copia comprobada de tus datos. [Transferencia de perfil](TRANSFERENCIA-PERFIL.md)

</details>
