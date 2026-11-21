package com.nexo.browser.extensions.nxr

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumContentRequest
import com.cefrium.CefriumContentResponse
import com.cefrium.CefriumContentServer
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumNavigationRequest
import com.cefrium.CefriumProfile
import com.nexo.browser.DragDismissLayout
import com.nexo.browser.NexoGlass
import com.nexo.browser.NexoUi
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.UiLayout
import com.nexo.browser.UrlSafety
import com.nexo.browser.WindowPrivacy
import com.nexo.browser.engine.cefrium.EngineDiagnostics
import com.nexo.browser.extensions.CrxPackage
import com.nexo.browser.extensions.ExtensionManifest
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Visor aislado Nexo UI 5.0 para abrir la interfaz HTML real (`action.default_popup` u `options_page`)
 * de cualquier extensión instalada en NXR sobre las APIs públicas de Cefrium 0.21.0
 * (`CefriumProfile.registerContentServer` + `CefriumBrowser.createWithSurface`).
 *
 * Seguridad:
 * - Un origen sintético por extensión en el perfil independiente `nexo_nxr_ext_ui`.
 * - Sirve exclusivamente archivos extraídos dentro de `files/` validando ruta canónica (anti Zip Slip).
 * - Los enlaces web externos se abren en una pestaña normal de Nexo, nunca dentro del panel.
 * - Expone `chrome`/`browser` acotado a un token efímero de sesión de panel (almacenamiento aislado y
 *   envío de mensajes a la pestaña activa).
 */
object NxrExtensionPanel {
    private const val PROFILE_NAME = "nexo_nxr_ext_ui"
    private var currentDialog: Dialog? = null
    private val registeredOrigins = mutableSetOf<String>()
    private var viewportScript: String? = null

    private fun ensureProfile(context: Context, extensionId: String, origin: String): CefriumProfile? {
        val profile = runCatching { CefriumProfile.get(PROFILE_NAME) }
            .onFailure { EngineDiagnostics.log("NXR panel: perfil no disponible", it) }
            .getOrNull() ?: return null
        if (origin !in registeredOrigins) {
            val rootDir = NxrRuntime.rootDirectory(context)
            val ok = runCatching {
                profile.registerContentServer(origin, ExtensionFilesServer(rootDir, extensionId))
            }.onFailure {
                EngineDiagnostics.log("NXR panel: servidor de contenido rechazado", it)
            }.isSuccess
            if (!ok) return null
            registeredOrigins.add(origin)
        }
        return profile
    }

    /** Abre la página HTML [relativePage] (`popup` u `options`) de [extId] en una hoja Nexo UI 5.0. */
    fun show(activity: Activity, extId: String, title: String, relativePage: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val cleanPage = ExtensionManifest.normalizePath(relativePage) ?: return false
        val rootDir = NxrRuntime.rootDirectory(activity)
        val targetFile = File(NxrPackageInstaller.filesDir(rootDir, extId), cleanPage)
        val canonicalBase = NxrPackageInstaller.filesDir(rootDir, extId).canonicalPath + File.separator
        if (!targetFile.isFile || !targetFile.canonicalPath.startsWith(canonicalBase)) return false

        val safeId = NxrStorage.sanitizeId(extId)
        val panelOrigin = "https://${CrxPackage.sha256Hex(safeId.toByteArray()).take(32)}.nxr-ui.nexo.invalid"
        val profile = ensureProfile(activity, safeId, panelOrigin) ?: return false
        val web = runCatching { CefriumBrowser.createWithSurface(activity, profile) }
            .onFailure { EngineDiagnostics.log("NXR panel: no se pudo crear navegador", it) }
            .getOrNull() ?: return false

        currentDialog?.dismiss()
        val p = ThemeManager.palette(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val radius = NexoUi.Tokens.RADIUS_SHEET * density
        val sheetBg = p.background
        val baseUrl = "$panelOrigin/$safeId/"
        val startUrl = baseUrl + cleanPage
        val panelToken = NxrRuntime.issuePanelToken(safeId)
        val bootScript = NxrRuntime.buildExtensionUiBootScript(activity, safeId, panelToken, baseUrl)

        val dialog = Dialog(activity)
        web.setBackgroundColor(sheetBg)
        web.setDarkMode(ThemeManager.isDark(activity))
        val adaptation = viewportScript ?: runCatching {
            activity.assets.open("nexo/nxr-popup-viewport.js").bufferedReader().use { it.readText() }
        }.getOrDefault("").also { viewportScript = it }
        runCatching { web.addDocumentStartJavaScript(adaptation + "\n" + bootScript.orEmpty(), setOf(panelOrigin)) }
        web.setQueryHandler(
            CefriumBrowser.QueryOriginPolicy.exactly(panelOrigin),
            object : CefriumBrowser.QueryHandler {
                override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean =
                    onQuery(queryId, request, origin, true, callback)

                override fun onQuery(
                    queryId: Long,
                    request: String,
                    origin: String,
                    isMainFrame: Boolean,
                    callback: CefriumBrowser.QueryCallback
                ): Boolean {
                    if (!isMainFrame) return false
                    val replyJson = runCatching {
                        val outer = JSONObject(request)
                        val inner = JSONObject(outer.optString("m"))
                        val callId = inner.optInt("id", 0)
                        val kind = inner.optString("kind")
                        val payload = inner.optJSONObject("payload")
                        val reply = NxrRuntime.onPanelMessage(safeId, panelToken, kind, payload)
                        val answer = JSONObject().put("id", callId)
                        if (reply.error != null) answer.put("error", reply.error)
                        else answer.put("result", reply.result ?: JSONObject.NULL)
                        JSONObject().put("reply", answer.toString()).toString()
                    }.getOrElse {
                        JSONObject().put("reply", JSONObject().put("error", "Mensaje no válido").toString()).toString()
                    }
                    callback.success(replyJson)
                    return true
                }
            }
        )
        web.setNavigationClient(object : CefriumNavigationClient {
            override fun onBeforeNavigate(browser: CefriumBrowser, request: CefriumNavigationRequest): Boolean {
                val url = request.url.orEmpty()
                if (url.startsWith(baseUrl)) return false
                if (request.isForMainFrame && UrlSafety.isWebUrl(url)) {
                    dialog.dismiss()
                    TabManager.createTab(url)
                }
                return true
            }
        })
        web.setChromeClient(object : CefriumChromeClient {
            override fun onCreateWindow(browser: CefriumBrowser, popup: CefriumBrowser, url: String?, userGesture: Boolean): Boolean {
                runCatching { popup.close() }
                if (userGesture && url != null && UrlSafety.isWebUrl(url)) {
                    dialog.dismiss()
                    TabManager.createTab(url)
                }
                return true
            }
        })

        val dragHandle = View(activity).apply {
            val handleColor = androidx.core.graphics.ColorUtils.setAlphaComponent(p.muted, 110)
            background = ThemeManager.rounded(activity, handleColor, 3f)
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10f)
                bottomMargin = dp(6f)
            }
        }
        val titleBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(2f), dp(14f), dp(10f))
            addView(TextView(activity).apply {
                text = title
                textSize = 15.5f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            dragZonePx = dp(48f)
            background = GradientDrawable().apply {
                setColor(sheetBg)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
            addView(dragHandle)
            addView(titleBar)
            addView(web.surfaceContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dimWindow = dialog.window
            onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        }

        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setTitle(title)
            setCanceledOnTouchOutside(true)
            setContentView(sheet)
            setOnDismissListener {
                NxrRuntime.revokePanelToken(panelToken)
                runCatching { web.stopLoading(); web.close() }
                if (currentDialog === this) currentDialog = null
            }
        }
        WindowPrivacy.inherit(dialog.window, activity.window)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(safe.bottom, insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom)
            val touchPadding = dp(8f)
            sheet.setPadding(safe.left + touchPadding, 0, safe.right + touchPadding, bottom + touchPadding)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        currentDialog = dialog
        val metrics = activity.resources.displayMetrics
        val sheetWidth = minOf(metrics.widthPixels, dp(UiLayout.SHEET_MAX_DP.toFloat()))
        val sheetHeight = minOf((metrics.heightPixels * 0.84f).toInt(), dp(680f))
        dialog.show()
        dialog.window?.apply {
            setLayout(sheetWidth, sheetHeight)
            setGravity(Gravity.BOTTOM)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            navigationBarColor = Color.TRANSPARENT
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        NexoGlass.blurBehind(dialog, activity)
        androidx.core.view.ViewCompat.requestApplyInsets(sheet)
        runCatching { web.onResume(); web.setPageVisible(true) }
        web.loadUrl(startUrl)
        return true
    }

    private class ExtensionFilesServer(private val rootDir: File, private val extensionId: String) : CefriumContentServer {
        override fun serve(request: CefriumContentRequest): CefriumContentResponse {
            val rawPath = request.path.orEmpty().removePrefix("/").substringBefore('?').substringBefore('#')
            val slash = rawPath.indexOf('/')
            if (slash <= 0) {
                return notFound()
            }
            if (rawPath.substring(0, slash) != extensionId) return notFound()
            val relPath = ExtensionManifest.normalizePath(rawPath.substring(slash + 1)) ?: return notFound()
            val baseDir = NxrPackageInstaller.filesDir(rootDir, extensionId)
            val target = File(baseDir, relPath)
            val canonicalBase = runCatching { baseDir.canonicalPath + File.separator }.getOrNull() ?: return notFound()
            val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return notFound()
            if (!target.isFile || !canonicalTarget.startsWith(canonicalBase) || target.length() > 16L * 1024L * 1024L) {
                return notFound()
            }
            val bytes = runCatching { target.readBytes() }.getOrNull() ?: return notFound()
            val mime = when (relPath.substringAfterLast('.').lowercase()) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "ico" -> "image/x-icon"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "ttf" -> "font/ttf"
                else -> "application/octet-stream"
            }
            return CefriumContentResponse(mime, ByteArrayInputStream(bytes))
                .setCharset("utf-8")
                .setContentLength(bytes.size.toLong())
                .setHeader("Cache-Control", "no-store")
                .setHeader(
                    "Content-Security-Policy",
                    "default-src 'self' data: blob:; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline' data:; script-src 'self' 'unsafe-inline'"
                )
        }

        private fun notFound(): CefriumContentResponse =
            CefriumContentResponse("text/plain", ByteArrayInputStream(ByteArray(0))).setStatus(404, "Not Found")
    }
}
