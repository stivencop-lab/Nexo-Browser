package com.nexo.browser.extensions.hub

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrRuntime
import java.io.File
import java.util.concurrent.Executors

/**
 * Hojas nativas Nexo Flow 3.0 del Extension Hub:
 * - Descarga verificada desde Chrome Web Store, Microsoft Edge Add-ons o URL HTTPS directa.
 * - Ficha de inspección previa a instalar con estado «Descargada», veredicto honesto,
 *   desglose de APIs en Cefrium 0.21.0, huella de clave CRX y activación segura por defecto.
 * - Revisión de escalado de permisos en actualizaciones.
 * - Matriz pública completa de las 20 APIs de WebExtensions en Cefrium 0.21.0.
 */
object ExtensionHubSheets {
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun openStoreInstallFlow(
        activity: Activity,
        target: ExtensionStoreResolver.ResolvedTarget.StoreExtension,
        onInstalled: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val progress = showLoadingDialog(
            activity,
            "Consultando ${target.store.label}",
            "Descargando y verificando firma CRX para ${target.extensionId}…"
        )
        io.execute {
            val temp = File.createTempFile("nxr-store-", ".crx", activity.cacheDir)
            try {
                val listing = ExtensionStoreClient.fetchListing(target)
                ExtensionStoreClient.downloadAndVerifyPackage(listing, temp)
                val inspection = NxrPackageInstaller.inspect(temp, expectedId = target.extensionId)
                val sourceInfo = NxrPackageInstaller.SourceInfo(
                    sourceOrigin = listing.store.sourceOrigin,
                    storeId = listing.extensionId,
                    sourceUrl = listing.detailUrl,
                    updateUrl = listing.downloadUrl,
                    publicKeySha256 = inspection.publicKeySha256,
                    developer = listing.developer.ifBlank { null },
                    signatureVerified = inspection.signatureVerified
                )
                main.post {
                    runCatching { progress.dismiss() }
                    if (activity.isFinishing || activity.isDestroyed) {
                        temp.delete()
                        return@post
                    }
                    showInstallReviewSheet(activity, temp, inspection, sourceInfo, listing, onInstalled)
                }
            } catch (err: Throwable) {
                temp.delete()
                main.post {
                    runCatching { progress.dismiss() }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showErrorDialog(
                            activity,
                            "No se pudo preparar la extensión",
                            err.message ?: "Error al descargar o verificar el paquete de ${target.store.label}."
                        )
                    }
                }
            }
        }
    }

    fun openDirectUrlInstallFlow(
        activity: Activity,
        url: String,
        onInstalled: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val progress = showLoadingDialog(
            activity,
            "Descargando paquete HTTPS",
            "Validando integridad y manifiesto de la extensión…"
        )
        io.execute {
            val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "pkg")
            val temp = File.createTempFile("nxr-direct-", ".$ext", activity.cacheDir)
            try {
                ExtensionStoreClient.downloadDirectHttpsPackage(url, temp)
                val inspection = NxrPackageInstaller.inspect(temp)
                val sourceInfo = NxrPackageInstaller.SourceInfo(
                    sourceOrigin = NxrPackageInstaller.ORIGIN_DIRECT_URL,
                    storeId = inspection.id.takeIf { ExtensionStoreResolver.isValidExtensionId(it) },
                    sourceUrl = url,
                    updateUrl = null,
                    publicKeySha256 = inspection.publicKeySha256,
                    developer = null,
                    signatureVerified = inspection.signatureVerified
                )
                main.post {
                    runCatching { progress.dismiss() }
                    if (activity.isFinishing || activity.isDestroyed) {
                        temp.delete()
                        return@post
                    }
                    showInstallReviewSheet(activity, temp, inspection, sourceInfo, null, onInstalled)
                }
            } catch (err: Throwable) {
                temp.delete()
                main.post {
                    runCatching { progress.dismiss() }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showErrorDialog(
                            activity,
                            "Paquete rechazado",
                            err.message ?: "No se pudo descargar o validar el paquete."
                        )
                    }
                }
            }
        }
    }

    fun openSingleUpdateFlow(
        activity: Activity,
        installed: NxrPackageInstaller.InstalledExtension,
        onUpdated: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val progress = showLoadingDialog(
            activity,
            "Comprobando actualización",
            "Consultando la tienda oficial para ${installed.name}…"
        )
        io.execute {
            val check = ExtensionUpdateManager.checkForUpdate(installed)
            if (check.error != null) {
                main.post {
                    runCatching { progress.dismiss() }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toastish.show(activity, check.error)
                    }
                }
                return@execute
            }
            val listing = check.listing
            if (!check.hasUpdate || listing == null) {
                main.post {
                    runCatching { progress.dismiss() }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toastish.show(activity, "${installed.name} ya está en la última versión (v${installed.version}).")
                    }
                }
                return@execute
            }
            val temp = File.createTempFile("nxr-upd-", ".crx", activity.cacheDir)
            try {
                ExtensionStoreClient.downloadAndVerifyPackage(listing, temp)
                val inspection = NxrPackageInstaller.inspect(temp, expectedId = installed.id)
                val diff = ExtensionUpdateManager.computePermissionDiff(installed, inspection)
                main.post {
                    runCatching { progress.dismiss() }
                    if (activity.isFinishing || activity.isDestroyed) {
                        temp.delete()
                        return@post
                    }
                    showUpdateConsentSheet(activity, installed, listing, inspection, diff, temp, onUpdated)
                }
            } catch (err: Throwable) {
                temp.delete()
                main.post {
                    runCatching { progress.dismiss() }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showErrorDialog(
                            activity,
                            "Actualización rechazada",
                            err.message ?: "No se pudo verificar la nueva versión."
                        )
                    }
                }
            }
        }
    }

    /** Hoja de consentimiento Nexo Flow 3.0 para cualquier paquete ya descargado y validado. */
    fun showInstallReviewSheet(
        activity: Activity,
        tempFile: File,
        inspection: NxrPackageInstaller.Inspection,
        sourceInfo: NxrPackageInstaller.SourceInfo,
        listing: ExtensionStoreClient.StoreListing? = null,
        onInstalled: () -> Unit = {}
    ) {
        val p = ThemeManager.palette(activity)
        val body = NexoUi.column(activity)
        var consumed = false
        var enableAfterInstall = inspection.recommendedEnabled
        lateinit var sheet: Dialog

        val existing = NxrRuntime.installed(activity).firstOrNull { it.id == inspection.id }
        val diff = existing?.let { ExtensionUpdateManager.computePermissionDiff(it, inspection) }

        // Píldoras de estado honesto: Descargada + Firma + Veredicto
        val pills = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NexoUi.dp(activity, 8f))
        }
        pills.addView(NexoUi.statusPill(activity, "Estado: ${ExtensionCompatibility.LifecycleState.DOWNLOADED.label}", NexoUi.Tone.INFO))
        pills.addView(
            NexoUi.statusPill(
                activity,
                if (inspection.signatureVerified) "Firma CRX verificada" else "Sin firma CRX",
                if (inspection.signatureVerified) NexoUi.Tone.SUCCESS else NexoUi.Tone.WARNING
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = NexoUi.dp(activity, 6f)
            }
        )
        body.addView(pills)

        val verdictTone = when (inspection.installVerdict) {
            ExtensionCompatibility.InstallVerdict.FULL -> NexoUi.Tone.SUCCESS
            ExtensionCompatibility.InstallVerdict.PARTIAL,
            ExtensionCompatibility.InstallVerdict.LIMITED_INSTALLABLE -> NexoUi.Tone.WARNING
            ExtensionCompatibility.InstallVerdict.UNVERIFIABLE -> NexoUi.Tone.WARNING
            ExtensionCompatibility.InstallVerdict.UNSUPPORTED,
            ExtensionCompatibility.InstallVerdict.BLOCKED_SECURITY -> NexoUi.Tone.DANGER
        }
        body.addView(
            NexoUi.statusPill(activity, inspection.installVerdict.label, verdictTone),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = NexoUi.dp(activity, 8f)
            }
        )

        // Resumen técnico verificado
        val summary = buildString {
            appendLine("Fuente: ${sourceInfo.originLabel()} · Formato: ${inspection.sourceType} · Manifest V${inspection.manifest.manifestVersion}")
            appendLine("Versión: ${inspection.manifest.version} · Archivos: ${inspection.fileCount} (${(inspection.totalUncompressedBytes / 1024L).coerceAtLeast(1L)} KB)")
            if (!listing?.developer.isNullOrBlank()) appendLine("Desarrollador: ${listing.developer}")
            if (listing?.rating != null && listing.ratingCount != null) {
                appendLine("Valoración oficial en tienda: %.1f/5 (%d reseñas)".format(listing.rating, listing.ratingCount))
            }
            if (!inspection.publicKeySha256.isNullOrBlank()) {
                appendLine("Clave pública SHA-256: ${inspection.publicKeySha256.take(16)}…${inspection.publicKeySha256.takeLast(8)}")
            }
            append("Content scripts ejecutables: ${inspection.capabilities.runnableContentScripts}")
        }.trim()
        body.addView(NexoUi.note(activity, summary))

        if (inspection.riskReasons.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Avisos de seguridad y límites"))
            body.addView(NexoUi.note(activity, inspection.riskReasons.joinToString("\n") { "• $it" }))
        }

        if (diff != null && diff.requiresConsent) {
            body.addView(NexoUi.section(activity, "Nuevos permisos respecto a v${existing.version}"))
            val diffText = buildString {
                if (diff.addedPermissions.isNotEmpty()) appendLine("Permisos añadidos: ${diff.addedPermissions.joinToString(", ")}")
                if (diff.addedHosts.isNotEmpty()) append("Sitios añadidos: ${diff.addedHosts.joinToString(", ")}")
            }.trim()
            body.addView(NexoUi.note(activity, diffText))
        }

        if (inspection.requestedHosts.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Sitios web solicitados (${inspection.requestedHosts.size})"))
            body.addView(NexoUi.note(activity, inspection.requestedHosts.take(8).joinToString("\n") { "• $it" }))
        }

        if (inspection.capabilities.apiBreakdown.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Compatibilidad real en Cefrium 0.21.0"))
            val apiText = inspection.capabilities.apiBreakdown.take(10).joinToString("\n") { row ->
                val icon = when (row.support) {
                    ExtensionCompatibility.ApiSupport.SUPPORTED -> "✓"
                    ExtensionCompatibility.ApiSupport.LIMITED -> "◐"
                    ExtensionCompatibility.ApiSupport.UNSUPPORTED -> "✕"
                }
                "$icon ${row.api} (${row.support.label}): ${row.note}"
            }
            body.addView(NexoUi.note(activity, apiText))
        }

        body.addView(NexoUi.switchRow(
            activity,
            "Habilitar inmediatamente al instalar",
            if (inspection.recommendedEnabled) {
                "Paquete firmado, acotado y compatible: listo para activar."
            } else {
                "Desactivado por seguridad por defecto. Actívalo solo si confías en esta extensión."
            },
            enableAfterInstall
        ) { checked ->
            enableAfterInstall = checked
        })

        val actionLabel = if (existing != null) "Actualizar a v${inspection.manifest.version}" else "Instalar en Nexo"
        body.addView(NexoUi.button(activity, actionLabel, primary = true) {
            consumed = true
            sheet.dismiss()
            io.execute {
                val res = runCatching {
                    NxrPackageInstaller.install(
                        rootDir = NxrRuntime.rootDirectory(activity),
                        packageFile = tempFile,
                        grantedHosts = inspection.requestedHosts,
                        sourceInfo = sourceInfo,
                        enableAfterInstall = enableAfterInstall,
                        expectedId = if (sourceInfo.storeId != null) inspection.id else null
                    )
                }
                tempFile.delete()
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    res.onSuccess { installed ->
                        val stateLabel = if (enableAfterInstall) "instalada y habilitada" else "instalada (desactivada por seguridad)"
                        Toastish.show(activity, "${installed.name} v${installed.version} $stateLabel.")
                        onInstalled()
                    }.onFailure { err ->
                        showErrorDialog(
                            activity,
                            "Error de instalación",
                            err.message ?: "No se pudo completar la instalación."
                        )
                    }
                }
            }
        }, NexoUi.buttonParams(activity, 10f))

        body.addView(NexoUi.button(activity, "Cancelar", primary = false) {
            sheet.dismiss()
        }, NexoUi.buttonParams(activity, 6f))

        val displayTitle = inspection.localizedName.ifBlank { listing?.name ?: inspection.id }
        val subtitle = inspection.localizedDescription.ifBlank { listing?.shortDescription.orEmpty() }
            .ifBlank { "${sourceInfo.originLabel()} · v${inspection.manifest.version}" }

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, displayTitle, subtitle), body)
        )
        sheet.setOnDismissListener {
            if (!consumed) tempFile.delete()
        }
    }

    private fun showUpdateConsentSheet(
        activity: Activity,
        installed: NxrPackageInstaller.InstalledExtension,
        listing: ExtensionStoreClient.StoreListing,
        inspection: NxrPackageInstaller.Inspection,
        diff: ExtensionUpdateManager.PermissionDiff,
        tempFile: File,
        onUpdated: () -> Unit
    ) {
        val body = NexoUi.column(activity)
        var consumed = false
        lateinit var sheet: Dialog

        body.addView(NexoUi.statusPill(
            activity,
            "v${installed.version} → v${inspection.manifest.version}",
            if (diff.requiresConsent) NexoUi.Tone.WARNING else NexoUi.Tone.SUCCESS
        ))
        body.addView(NexoUi.note(
            activity,
            if (diff.requiresConsent) {
                "Esta actualización solicita nuevos permisos o acceso a nuevos sitios. Revísalos antes de continuar."
            } else {
                "Firma CRX y continuidad de clave pública verificadas. Sin nuevos permisos solicitados."
            }
        ))
        if (diff.addedPermissions.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Nuevos permisos"))
            body.addView(NexoUi.note(activity, diff.addedPermissions.joinToString(", ")))
        }
        if (diff.addedHosts.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Nuevos sitios web"))
            body.addView(NexoUi.note(activity, diff.addedHosts.joinToString("\n") { "• $it" }))
        }

        body.addView(NexoUi.button(activity, "Aplicar actualización", primary = true) {
            consumed = true
            sheet.dismiss()
            io.execute {
                val res = runCatching {
                    ExtensionUpdateManager.applyVerifiedUpdate(
                        rootDir = NxrRuntime.rootDirectory(activity),
                        installed = installed,
                        downloadedFile = tempFile,
                        listing = listing,
                        consentGranted = true
                    )
                }
                tempFile.delete()
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    res.onSuccess { updated ->
                        Toastish.show(activity, "${updated.name} actualizada a v${updated.version}.")
                        onUpdated()
                    }.onFailure { err ->
                        showErrorDialog(
                            activity,
                            "Actualización revertida",
                            err.message ?: "No se pudo aplicar la actualización."
                        )
                    }
                }
            }
        }, NexoUi.buttonParams(activity, 10f))

        body.addView(NexoUi.button(activity, "Cancelar", primary = false) { sheet.dismiss() }, NexoUi.buttonParams(activity, 6f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Actualizar ${installed.name}", listing.store.label), body)
        )
        sheet.setOnDismissListener {
            if (!consumed) tempFile.delete()
        }
    }

    /** Hoja informativa transparente con la matriz de las 20 APIs sobre Cefrium 0.21.0. */
    fun showCompatibilityMatrixSheet(activity: Activity) {
        val p = ThemeManager.palette(activity)
        val body = NexoUi.column(activity)
        body.addView(NexoUi.note(
            activity,
            "NXR ejecuta extensiones de terceros únicamente sobre las APIs públicas de Cefrium 0.21.0 (Chromium 156), " +
                "sin APIs internas ni reflexión privilegiada. Esta es la matriz real de soporte:"
        ))
        for (row in ExtensionCompatibility.API_MATRIX) {
            val tone = when (row.support) {
                ExtensionCompatibility.ApiSupport.SUPPORTED -> NexoUi.Tone.SUCCESS
                ExtensionCompatibility.ApiSupport.LIMITED -> NexoUi.Tone.WARNING
                ExtensionCompatibility.ApiSupport.UNSUPPORTED -> NexoUi.Tone.DANGER
            }
            val card = NexoUi.card(activity).apply {
                val top = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(TextView(activity).apply {
                    text = row.api
                    textSize = 13.5f
                    typeface = NexoUi.bold(activity)
                    setTextColor(p.text)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(NexoUi.statusPill(activity, row.support.label, tone))
                addView(top)
                addView(TextView(activity).apply {
                    text = row.note
                    textSize = 12f
                    setTextColor(p.muted)
                    setPadding(0, NexoUi.dp(activity, 4f), 0, 0)
                })
            }
            body.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = NexoUi.dp(activity, 6f) })
        }
        NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Compatibilidad NXR (20 APIs)", "Cefrium 0.21.0 · Chromium 156"),
                body
            )
        )
    }

    private fun showLoadingDialog(activity: Activity, title: String, message: String): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .show()

    private fun showErrorDialog(activity: Activity, title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Aceptar", null)
            .show()
    }
}
