package com.nexo.browser.extensions.hub

import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import java.io.File

/**
 * Gestor transaccional de actualizaciones para extensiones instaladas desde
 * Chrome Web Store o Microsoft Edge Add-ons.
 *
 * Garantías de seguridad:
 * - Comprueba diferencias de permisos y hosts antes de aplicar la actualización.
 * - Exige consentimiento explícito del usuario si la nueva versión añade permisos o sitios.
 * - Delega la verificación de firma CRX + continuidad de clave pública (`publicKeySha256`)
 *   y el intercambio transaccional con reversión a [NxrPackageInstaller.install].
 * Puro sobre [File]: probado en la JVM.
 */
object ExtensionUpdateManager {
    data class PermissionDiff(
        val addedPermissions: List<String>,
        val addedHosts: List<String>,
        val removedPermissions: List<String>,
        val removedHosts: List<String>
    ) {
        val requiresConsent: Boolean
            get() = addedPermissions.isNotEmpty() || addedHosts.isNotEmpty()
    }

    class UpdateConsentRequiredException(
        val diff: PermissionDiff,
        val inspection: NxrPackageInstaller.Inspection
    ) : Exception("La actualización solicita nuevos permisos y requiere confirmación del usuario.")

    data class UpdateCheckResult(
        val installed: NxrPackageInstaller.InstalledExtension,
        val remoteVersion: String? = null,
        val hasUpdate: Boolean = false,
        val listing: ExtensionStoreClient.StoreListing? = null,
        val error: String? = null
    )

    fun storeKindFor(ext: NxrPackageInstaller.InstalledExtension): ExtensionStoreResolver.StoreKind? =
        when (ext.sourceOrigin) {
            NxrPackageInstaller.ORIGIN_CHROME_WEB_STORE -> ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE
            NxrPackageInstaller.ORIGIN_EDGE_ADDONS -> ExtensionStoreResolver.StoreKind.EDGE_ADDONS
            else -> null
        }

    fun isUpdatableFromStore(ext: NxrPackageInstaller.InstalledExtension): Boolean {
        val store = storeKindFor(ext) ?: return false
        val id = ext.storeId ?: ext.id
        return store != ExtensionStoreResolver.StoreKind.DIRECT_HTTPS_PACKAGE &&
            ExtensionStoreResolver.isValidExtensionId(id)
    }

    fun computePermissionDiff(
        installed: NxrPackageInstaller.InstalledExtension,
        incoming: NxrPackageInstaller.Inspection
    ): PermissionDiff {
        val oldPerms = installed.permissions.toSet()
        val newPerms = incoming.manifest.permissions.toSet()
        val oldHosts = installed.hostPermissions.toSet()
        val newHosts = incoming.requestedHosts.toSet()
        return PermissionDiff(
            addedPermissions = (newPerms - oldPerms).sorted(),
            addedHosts = (newHosts - oldHosts).sorted(),
            removedPermissions = (oldPerms - newPerms).sorted(),
            removedHosts = (oldHosts - newHosts).sorted()
        )
    }

    fun checkForUpdate(
        installed: NxrPackageInstaller.InstalledExtension,
        fetcher: (ExtensionStoreResolver.StoreKind, String) -> ExtensionStoreClient.StoreListing = { store, id ->
            ExtensionStoreClient.fetchListing(
                ExtensionStoreResolver.ResolvedTarget.StoreExtension(
                    store = store,
                    extensionId = id,
                    detailUrl = if (store == ExtensionStoreResolver.StoreKind.EDGE_ADDONS) {
                        ExtensionStoreResolver.edgeDetailUrl(id)
                    } else {
                        ExtensionStoreResolver.cwsDetailUrl(id)
                    }
                )
            )
        }
    ): UpdateCheckResult {
        val store = storeKindFor(installed)
            ?: return UpdateCheckResult(installed, error = "Instalada desde archivo local: no tiene canal de actualización.")
        val storeId = (installed.storeId ?: installed.id).lowercase()
        if (!ExtensionStoreResolver.isValidExtensionId(storeId)) {
            return UpdateCheckResult(installed, error = "Sin identificador de tienda verificable.")
        }
        return runCatching {
            val listing = fetcher(store, storeId)
            val hasUpdate = listing.version.isNotBlank() &&
                ExtensionStoreResolver.compareVersions(listing.version, installed.version) > 0
            UpdateCheckResult(
                installed = installed,
                remoteVersion = listing.version.ifBlank { null },
                hasUpdate = hasUpdate,
                listing = listing
            )
        }.getOrElse { err ->
            UpdateCheckResult(installed, error = err.message ?: "No se pudo consultar la tienda.")
        }
    }

    /**
     * Aplica una actualización ya descargada y verificada, bloqueando cualquier ampliación de
     * permisos sin [consentGranted] y validando la continuidad de clave en [NxrPackageInstaller].
     */
    fun applyVerifiedUpdate(
        rootDir: File,
        installed: NxrPackageInstaller.InstalledExtension,
        downloadedFile: File,
        listing: ExtensionStoreClient.StoreListing,
        consentGranted: Boolean
    ): NxrPackageInstaller.InstalledExtension {
        val inspection = NxrPackageInstaller.inspect(downloadedFile, expectedId = installed.id)
        val diff = computePermissionDiff(installed, inspection)
        if (diff.requiresConsent && !consentGranted) {
            throw UpdateConsentRequiredException(diff, inspection)
        }
        val sourceInfo = NxrPackageInstaller.SourceInfo(
            sourceOrigin = listing.store.sourceOrigin,
            storeId = listing.extensionId,
            sourceUrl = listing.detailUrl,
            updateUrl = listing.downloadUrl,
            publicKeySha256 = inspection.publicKeySha256,
            developer = listing.developer.ifBlank { installed.developer },
            signatureVerified = inspection.signatureVerified
        )
        return NxrPackageInstaller.install(
            rootDir = rootDir,
            packageFile = downloadedFile,
            grantedHosts = inspection.requestedHosts,
            sourceInfo = sourceInfo,
            expectedId = installed.id
        )
    }
}
