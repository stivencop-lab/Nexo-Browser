package com.nexo.browser

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
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumContentRequest
import com.cefrium.CefriumContentResponse
import com.cefrium.CefriumContentServer
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumNavigationRequest
import com.cefrium.CefriumProfile
import com.cefrium.CefriumRendererTerminationStatus
import com.nexo.browser.engine.cefrium.EngineDiagnostics
import com.nexo.browser.shield.PhantomShield
import java.io.ByteArrayInputStream

/**
 * Panel de PhantomShield: el mismo `popup/popup.html` de la extensión,
 * servido desde los recursos de la app en un origen propio
 * (`https://phantomshield.nexo.invalid`) que solo existe en el perfil del
 * panel. No carga nada de la red; los enlaces externos se abren como pestañas.
 */
object PhantomShieldPanel {
    private const val PROFILE = "nexo_shield_panel"
    private const val AI_PROFILE = "nexo_shield_ai"
    /** Recargas automáticas del panel tras perder su proceso, antes de cerrarlo. */
    private const val MAX_RENDERER_RECOVERIES = 2
    private var current: Dialog? = null
    private var currentBrowser: CefriumBrowser? = null
    private val servedProfiles = HashSet<String>()

    /** Perfil del panel con el origen propio ya servido. */
    internal fun profile(context: Context): CefriumProfile? = served(context, PROFILE)

    /**
     * Perfil propio del anfitrión de IA. Otro perfil es otro contexto de
     * Chromium, así que su proceso nunca es el del panel: si Android lo cierra
     * por memoria, o el clasificador falla, el panel (y los ajustes de IA que
     * se están editando) siguen abiertos. El almacén es nativo y común.
     */
    internal fun aiProfile(context: Context): CefriumProfile? = served(context, AI_PROFILE)

    private fun served(context: Context, name: String): CefriumProfile? {
        val profile = runCatching { CefriumProfile.get(name) }
            .onFailure { EngineDiagnostics.log("PhantomShield: perfil $name no disponible", it) }
            .getOrNull() ?: return null
        if (name !in servedProfiles) {
            runCatching { profile.registerContentServer(PhantomShield.PANEL_ORIGIN, PanelServer(context.applicationContext)) }
                .onSuccess { servedProfiles += name }
                .onFailure { EngineDiagnostics.log("PhantomShield: servidor de contenido rechazado ($name)", it); return null }
        }
        return profile
    }

    /** «Configurar IA» del panel: los ajustes de IA en la misma hoja (atrás vuelve al panel). */
    internal fun openOptions(): Boolean {
        val web = currentBrowser?.takeIf { !it.isClosed } ?: return false
        web.loadUrl(PhantomShield.BASE_URL + "options/options.html")
        return true
    }

    fun show(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        current?.dismiss()
        val p = ThemeManager.palette(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val radius = NexoUi.Tokens.RADIUS_SHEET * density
        val sheetBg = when {
            p.isAmoled -> 0xFF0C0E13.toInt()
            p.isDark -> p.surfaceContainer
            else -> p.surfaceContainerLow
        }
        val dialog = Dialog(activity)
        var recoveries = 0
        var lastRecovery = 0L

        val profile = profile(activity) ?: return false
        val web = runCatching { CefriumBrowser.createWithSurface(activity, profile) }
            .onFailure { EngineDiagnostics.log("panel de PhantomShield: no se pudo crear el navegador", it) }
            .getOrNull() ?: return false
        EngineDiagnostics.log("panel de PhantomShield abierto")
        web.setBackgroundColor(sheetBg)
        web.setDarkMode(ThemeManager.isDark(activity))
        web.setNavigationClient(object : CefriumNavigationClient {
            override fun onBeforeNavigate(browser: CefriumBrowser, request: CefriumNavigationRequest): Boolean {
                val url = request.url.orEmpty()
                if (url.startsWith(PhantomShield.BASE_URL)) return false
                if (request.isForMainFrame && UrlSafety.isWebUrl(url)) {
                    dialog.dismiss()
                    TabManager.createTab(url)
                }
                return true
            }

            /**
             * El proceso del panel terminó (memoria, fallo del renderizador): se
             * recarga la misma página (panel o ajustes de IA) en lugar de cerrar
             * la hoja de golpe. Solo si vuelve a caer enseguida se cierra.
             */
            override fun onRenderProcessTerminated(browser: CefriumBrowser, status: CefriumRendererTerminationStatus, errorCode: Int) {
                EngineDiagnostics.log("panel de PhantomShield: el proceso terminó ($status, $errorCode)")
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRecovery > 30_000L) recoveries = 0
                lastRecovery = now
                val page = runCatching { browser.url }.getOrNull()?.takeIf { it.startsWith(PhantomShield.BASE_URL) }
                    ?: (PhantomShield.BASE_URL + "popup/popup.html")
                if (++recoveries > MAX_RENDERER_RECOVERIES || browser.isClosed || !dialog.isShowing) {
                    dialog.dismiss()
                    return
                }
                activity.window.decorView.post {
                    if (!browser.isClosed && dialog.isShowing) runCatching { browser.loadUrl(page) }.onFailure { dialog.dismiss() }
                }
            }

            override fun onLoadError(browser: CefriumBrowser, error: com.cefrium.CefriumLoadError) {
                EngineDiagnostics.log("panel de PhantomShield: error de carga ${error.errorCode} (${error.description})")
            }
        })
        web.setChromeClient(object : CefriumChromeClient {
            /** Enlaces del panel con target=_blank: pestaña de Nexo, nunca ventana del panel. */
            override fun onCreateWindow(browser: CefriumBrowser, popup: CefriumBrowser, url: String?, userGesture: Boolean): Boolean {
                popup.close()
                if (userGesture && url != null && UrlSafety.isWebUrl(url)) {
                    dialog.dismiss()
                    TabManager.createTab(url)
                }
                return true
            }
        })
        PhantomShield.attachPanel(web) { dialog.dismiss() }

        val dragHandle = View(activity).apply {
            val handleColor = androidx.core.graphics.ColorUtils.setAlphaComponent(p.muted, 110)
            background = ThemeManager.rounded(activity, handleColor, 3f)
            contentDescription = "Arrastra hacia abajo para cerrar"
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10f)
                bottomMargin = dp(8f)
            }
        }
        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            dragZonePx = dp(36f)
            background = GradientDrawable().apply {
                setColor(sheetBg)
                if (p.isAmoled) setStroke(dp(1f).coerceAtLeast(1), ThemeManager.hairline(p))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
            addView(dragHandle)
            addView(web.surfaceContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dimWindow = dialog.window
            onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        }
        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setTitle("PhantomShield")
            setCanceledOnTouchOutside(true)
            setContentView(sheet)
            // Atrás: de los ajustes de IA (u otra página del panel) al panel; desde el panel, cerrar.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP &&
                    runCatching { web.canGoBack() }.getOrDefault(false)) {
                    web.goBack()
                    true
                } else keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN &&
                    runCatching { web.canGoBack() }.getOrDefault(false)
            }
            setOnDismissListener {
                PhantomShield.detachPanel()
                if (currentBrowser === web) currentBrowser = null
                runCatching { web.stopLoading(); web.close() }
                if (current === this) current = null
            }
        }
        WindowPrivacy.inherit(dialog.window, activity.window)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(safe.bottom, insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom)
            sheet.setPadding(safe.left, 0, safe.right, bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        current = dialog
        currentBrowser = web
        val metrics = activity.resources.displayMetrics
        val sheetWidth = minOf(metrics.widthPixels, dp(UiLayout.SHEET_MAX_DP.toFloat()))
        val sheetHeight = minOf((metrics.heightPixels * 0.85f).toInt(), dp(720f))
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
        androidx.core.view.ViewCompat.requestApplyInsets(sheet)
        runCatching { web.onResume(); web.setPageVisible(true) }
        web.loadUrl(PhantomShield.BASE_URL + "popup/popup.html")
        return true
    }

    private val AI_MODULES = setOf("background/ai-memory.js", "background/ai-domains.js", "background/ai-classifier.js")

    /**
     * Archivos de la extensión. Del código de fondo solo se sirven los módulos
     * de IA que ejecuta el anfitrión oculto (`host/ai-host.html`).
     */
    private class PanelServer(private val context: Context) : CefriumContentServer {
        override fun serve(request: CefriumContentRequest): CefriumContentResponse {
            val path = request.path.orEmpty().removePrefix("/")
            val allowed = path.isNotEmpty() && ".." !in path && (!path.startsWith("background/") || path in AI_MODULES)
            val bytes = if (allowed) runCatching {
                context.assets.open(PhantomShield.ASSET_ROOT + path).use { it.readBytes() }
            }.getOrNull() else null
            if (bytes == null) {
                return CefriumContentResponse("text/plain", ByteArrayInputStream(ByteArray(0))).setStatus(404, "Not Found")
            }
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "png" -> "image/png"
                "svg" -> "image/svg+xml"
                "woff2" -> "font/woff2"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            return CefriumContentResponse(mime, ByteArrayInputStream(bytes))
                .setCharset("utf-8")
                .setContentLength(bytes.size.toLong())
                .setHeader("Cache-Control", "no-store")
                .setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'")
        }
    }
}
