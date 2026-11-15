package com.nexo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.nexo.browser.shield.PhantomShield
import java.io.ByteArrayInputStream

/**
 * Panel de PhantomShield en la variante Chromium: el mismo `popup/popup.html`
 * de la extensión, servido desde los recursos de la app en un origen propio
 * (`https://phantomshield.nexo.invalid`) que solo existe dentro de este
 * WebView. No carga nada de la red; los enlaces externos se abren como
 * pestañas.
 */
object PhantomShieldPanel {
    private var current: Dialog? = null

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun show(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed || !PhantomShield.panelSupported()) return false
        current?.dismiss()
        val p = ThemeManager.palette(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val radius = 24f * density
        val dialog = Dialog(activity)

        val web = WebView(activity).apply {
            setBackgroundColor(p.surface)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith(PhantomShield.BASE_URL)) return false
                if (UrlSafety.isWebUrl(url)) {
                    dialog.dismiss()
                    TabManager.createTab(url)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(activity, request)

            /** El proceso del panel terminó: se cierra el panel, no Nexo. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                dialog.dismiss()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { dialog.dismiss() }
        }
        PhantomShield.attachPanel(web) { dialog.dismiss() }

        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            dragZonePx = dp(30f)
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dimWindow = dialog.window
            onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        }
        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setTitle("PhantomShield")
            setCanceledOnTouchOutside(true)
            setContentView(sheet)
            setOnDismissListener {
                runCatching { web.stopLoading(); web.destroy() }
                if (current === this) current = null
            }
        }
        WindowPrivacy.inherit(dialog.window, activity.window)
        current = dialog
        dialog.show()
        dialog.window?.apply {
            val metrics = activity.resources.displayMetrics
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, minOf((metrics.heightPixels * 0.85f).toInt(), dp(720f)))
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        web.loadUrl(PhantomShield.BASE_URL + "popup/popup.html")
        return true
    }

    /** Archivos de la extensión; cualquier otra dirección se rechaza. */
    private fun serve(activity: Activity, request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        val path = uri.path.orEmpty().removePrefix("/")
        val allowed = uri.scheme == "https" && uri.host == PhantomShield.PANEL_HOST &&
            path.isNotEmpty() && ".." !in path && !path.startsWith("background/")
        val bytes = if (allowed) runCatching {
            activity.assets.open(PhantomShield.ASSET_ROOT + path).use { it.readBytes() }
        }.getOrNull() else null
        if (bytes == null) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
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
        return WebResourceResponse(mime, "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'"),
            ByteArrayInputStream(bytes))
    }
}
