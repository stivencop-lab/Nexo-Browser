package com.nexo.browser.reader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.UrlSafety
import com.nexo.browser.ai.NexoAiClient
import com.nexo.browser.ai.NexoAiConfig
import com.nexo.browser.ai.NexoAiPageAgent
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nexo Reader: vista de lectura nativa saneada (cero WebView / cero HTML crudo / cero bypass DRM),
 * controles tipográficos, lectura en voz alta real con [TextToSpeech] de Android,
 * guardado offline acotado ([OfflineArticleStore]) y resumen/traducción opcional con Nexo AI
 * estrictamente bajo demanda del usuario.
 */
object NexoReader {
    private const val EXTRACTOR_ASSET = "nexo/reader-mode.js"
    private const val PREFS = "nexo_reader_prefs"
    private const val EXTRACT_TIMEOUT_MS = 3000L

    data class ReaderPrefs(
        val fontFamily: String = FONT_SANS,
        val fontSizeSp: Int = 18,
        val lineSpacing: Float = 1.5f,
        val widthMode: String = WIDTH_NORMAL,
        val ttsRate: Float = 1.0f,
        val ttsVoiceName: String = ""
    ) {
        companion object {
            const val FONT_SANS = "sans"
            const val FONT_SERIF = "serif"
            const val FONT_MONO = "mono"
            const val WIDTH_NARROW = "narrow"
            const val WIDTH_NORMAL = "normal"
            const val WIDTH_WIDE = "wide"

            val SPEECH_RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

            fun clampFontSize(sp: Int): Int = sp.coerceIn(14, 26)
            fun clampLineSpacing(mult: Float): Float = mult.coerceIn(1.2f, 1.8f)
            fun clampTtsRate(rate: Float): Float = rate.coerceIn(0.75f, 2.0f)
        }
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newSingleThreadExecutor { Thread(it, "NexoReader").apply { isDaemon = true } } }
    @Volatile private var cachedExtractorJs: String? = null

    fun readPrefs(context: Context): ReaderPrefs {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReaderPrefs(
            fontFamily = sp.getString("fontFamily", ReaderPrefs.FONT_SANS) ?: ReaderPrefs.FONT_SANS,
            fontSizeSp = ReaderPrefs.clampFontSize(sp.getInt("fontSizeSp", 18)),
            lineSpacing = ReaderPrefs.clampLineSpacing(sp.getFloat("lineSpacing", 1.5f)),
            widthMode = sp.getString("widthMode", ReaderPrefs.WIDTH_NORMAL) ?: ReaderPrefs.WIDTH_NORMAL,
            ttsRate = ReaderPrefs.clampTtsRate(sp.getFloat("ttsRate", 1.0f)),
            ttsVoiceName = sp.getString("ttsVoiceName", "") ?: ""
        )
    }

    fun savePrefs(context: Context, prefs: ReaderPrefs) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("fontFamily", prefs.fontFamily)
            .putInt("fontSizeSp", ReaderPrefs.clampFontSize(prefs.fontSizeSp))
            .putFloat("lineSpacing", ReaderPrefs.clampLineSpacing(prefs.lineSpacing))
            .putString("widthMode", prefs.widthMode)
            .putFloat("ttsRate", ReaderPrefs.clampTtsRate(prefs.ttsRate))
            .putString("ttsVoiceName", prefs.ttsVoiceName)
            .apply()
    }

    private fun loadExtractorJs(context: Context): String? =
        cachedExtractorJs ?: runCatching {
            context.applicationContext.assets.open(EXTRACTOR_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.also { cachedExtractorJs = it }

    /** Extrae el artículo de la pestaña activa usando `evaluateJavaScript` público de Cefrium. */
    fun openActiveTab(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val tab = TabManager.activeTab()
        val session = tab?.session as? CefriumEngineSession
        if (tab == null || session == null || BrowserPrefs.isNexoHome(tab.url) || !UrlSafety.isWebUrl(tab.url)) {
            Toastish.show(activity, "Abre un artículo web para activar Nexo Reader.")
            return
        }
        val source = loadExtractorJs(activity)
        if (source.isNullOrBlank()) {
            Toastish.show(activity, "No se pudo cargar el extractor de lectura.")
            return
        }
        val script = """
            (function(){
              try {
                $source
                var res = window.__nexoReader ? window.__nexoReader.extractArticle(document) : null;
                return res ? JSON.stringify(res) : null;
              } catch (_) {
                return null;
              }
            })();
        """.trimIndent()

        val sourceUrl = tab.url
        val sourceGeneration = session.documentGeneration()
        val completed = AtomicBoolean(false)
        val timeout = Runnable {
            if (completed.compareAndSet(false, true) && !activity.isFinishing && !activity.isDestroyed) {
                Toastish.show(activity, "La página tardó demasiado en responder al modo lectura.")
            }
        }
        main.postDelayed(timeout, EXTRACT_TIMEOUT_MS)

        session.evaluateJsonInMainFrame(script) { raw ->
            main.removeCallbacks(timeout)
            if (!completed.compareAndSet(false, true)) return@evaluateJsonInMainFrame
            main.post {
                if (activity.isFinishing || activity.isDestroyed || TabManager.activeTab() !== tab ||
                    tab.session !== session || tab.url != sourceUrl || session.documentGeneration() != sourceGeneration
                ) return@post
                val obj = NexoAiPageAgent.unwrapJsonObject(raw)
                if (obj == null) {
                    Toastish.show(activity, "No se pudo analizar el contenido de esta página.")
                    return@post
                }
                if (!obj.optBoolean("ok", false)) {
                    val reason = obj.optString("reason").ifBlank {
                        "Esta página no contiene un artículo compatible con el modo lectura."
                    }
                    Toastish.show(activity, reason)
                    return@post
                }
                val article = OfflineArticleStore.parseFromExtractorJson(obj, sourceUrl)
                if (article == null) {
                    Toastish.show(activity, "No se encontró texto legible suficiente en esta página.")
                    return@post
                }
                showArticleDialog(activity, article, openedOffline = false, privateSource = tab.isPrivate) { url ->
                    if (TabManager.activeTab() === tab) TabManager.navigate(url)
                }
            }
        }
    }

    internal fun loadSavedArticles(context: Context, onReady: (OfflineArticleStore.SavedArticles) -> Unit) {
        val dir = OfflineArticleStore.directory(context)
        io.execute {
            val snapshot = OfflineArticleStore.metadata(dir)
            main.post { onReady(snapshot) }
        }
    }

    internal fun openSavedArticle(activity: Activity, id: String, onOpenUrl: (String) -> Unit) {
        val dir = OfflineArticleStore.directory(activity)
        io.execute {
            val article = OfflineArticleStore.get(dir, id)
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (article == null) Toastish.show(activity, "Este artículo ya no está disponible.")
                else showArticleDialog(activity, article, openedOffline = true, onOpenUrl = onOpenUrl)
            }
        }
    }

    /** Hoja con todos los artículos guardados para lectura sin conexión. */
    fun openSavedArticlesSheet(activity: Activity, onOpenUrl: (String) -> Unit) {
        val dir = OfflineArticleStore.directory(activity)
        lateinit var sheet: Dialog

        fun buildContent(snapshot: OfflineArticleStore.SavedArticles): View {
            val saved = snapshot.articles
            val totalKb = snapshot.totalBytes / 1024L
            val body = NexoUi.column(activity)
            if (saved.isEmpty()) {
                body.addView(NexoUi.emptyState(
                    activity,
                    R.drawable.ic_menu_reader,
                    "Sin artículos guardados",
                    "Abre cualquier artículo web en Nexo Reader y pulsa «Guardar» para leerlo o escucharlo sin conexión (hasta ${OfflineArticleStore.MAX_ARTICLES} artículos / 10 MB)."
                ))
                return NexoSheets.column(activity, NexoSheets.header(activity, "Artículos sin conexión", "Nexo Reader"), body)
            }

            body.addView(NexoUi.note(
                activity,
                "${saved.size} / ${OfflineArticleStore.MAX_ARTICLES} artículos · ${totalKb} KB usados de 10 MB."
            ))
            val fmt = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
            saved.forEach { art ->
                val sub = buildString {
                    if (art.siteName.isNotBlank()) append(art.siteName).append(" · ")
                    append("${art.readingMinutes} min · ${fmt.format(Date(art.savedAtMs))}")
                }
                body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_reader, art.title, sub) {
                    sheet.dismiss()
                    openSavedArticle(activity, art.id, onOpenUrl)
                }, NexoUi.buttonParams(activity, 6f))
            }
            body.addView(NexoUi.button(activity, "Vaciar artículos guardados", danger = true) {
                AlertDialog.Builder(activity)
                    .setTitle("¿Eliminar artículos sin conexión?")
                    .setMessage("Se borrarán los ${saved.size} artículos guardados localmente.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Vaciar") { _, _ ->
                        sheet.dismiss()
                        io.execute {
                            val removed = runCatching { OfflineArticleStore.clear(dir) }.getOrDefault(false)
                            main.post {
                                if (!activity.isDestroyed) Toastish.show(activity, if (removed)
                                    "Artículos sin conexión eliminados." else "Quedan artículos sin eliminar.")
                            }
                        }
                    }.show()
            }, NexoUi.buttonParams(activity, 12f))
            return NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Artículos sin conexión", "Toca para abrir en Nexo Reader"),
                body
            )
        }

        loadSavedArticles(activity) { snapshot ->
            if (!activity.isFinishing && !activity.isDestroyed) sheet = NexoSheets.show(activity, buildContent(snapshot))
        }
    }

    /**
     * Muestra el diálogo nativo de Nexo Reader (pantalla completa) con tipografía ajustable,
     * lectura en voz alta mediante Android [TextToSpeech], guardado offline y resumen/traducción
     * opcional con Nexo AI.
     */
    fun showArticleDialog(
        activity: Activity,
        article: OfflineArticleStore.ReaderArticle,
        openedOffline: Boolean,
        privateSource: Boolean = false,
        onOpenUrl: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = ThemeManager.palette(activity)
        val dir = OfflineArticleStore.directory(activity)
        var prefs = readPrefs(activity)
        var savedId: String? = null

        // Estado real de TextToSpeech (inicializado solo cuando el usuario pulsa Escuchar).
        var tts: TextToSpeech? = null
        var ttsReady = false
        var ttsPlaying = false
        var ttsIndex = 0
        var ttsOffset = 0
        var ttsChunkEnd = 0
        var ttsGeneration = 0L
        var ttsUtterance: String? = null
        var ttsRequested = false
        val readableBlocks = article.blocks.map { it.text }.filter { it.isNotBlank() }
        val blockViews = ArrayList<TextView>()
        var activeAiCancel: NexoAiClient.CancelHandle? = null

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (privateSource) com.nexo.browser.WindowPrivacy.protect(dialog.window, true)
        else com.nexo.browser.WindowPrivacy.inherit(dialog.window, activity.window)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = p.background
            navigationBarColor = p.surface
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

        // --- Barra superior ---
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexoUi.dp(activity, 8f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 8f))
            background = ThemeManager.barBackground(activity, lineOnTop = false)
        }
        val backBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_back)
            contentDescription = "Ver página original"
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { dialog.dismiss() }
        }
        val topTitle = TextView(activity).apply {
            text = if (openedOffline) "Nexo Reader · Sin conexión" else "Nexo Reader"
            textSize = 14f
            typeface = NexoUi.bold(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val topSub = TextView(activity).apply {
            text = "${article.readingMinutes} min · ${article.wordCount} palabras"
            textSize = 11.5f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(NexoUi.dp(activity, 6f), 0, NexoUi.dp(activity, 6f), 0)
            addView(topTitle)
            addView(topSub)
        }
        val saveBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_bookmark)
            contentDescription = "Guardar sin conexión"
            ThemeManager.styleIconButton(activity, this, false)
            isEnabled = false
        }
        val ttsBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_volume_on)
            contentDescription = "Leer en voz alta"
            ThemeManager.styleIconButton(activity, this, false)
        }
        val typoBtn = TextView(activity).apply {
            text = "Aa"
            textSize = 15f
            typeface = NexoUi.bold(activity)
            gravity = Gravity.CENTER
            contentDescription = "Tipografía y diseño de lectura"
            setTextColor(p.text)
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.elevated, 12f, p.outlineVariant), 12f)
            isClickable = true
            isFocusable = true
        }
        val btnPx = NexoUi.dp(activity, 44f)
        topBar.addView(backBtn, LinearLayout.LayoutParams(btnPx, btnPx))
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(saveBtn, LinearLayout.LayoutParams(btnPx, btnPx).apply { marginEnd = NexoUi.dp(activity, 4f) })
        topBar.addView(ttsBtn, LinearLayout.LayoutParams(btnPx, btnPx).apply { marginEnd = NexoUi.dp(activity, 4f) })
        topBar.addView(typoBtn, LinearLayout.LayoutParams(btnPx, btnPx))
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Contenido desplazable del artículo ---
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val articleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val aiBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // --- Barra inferior de lectura en voz alta (oculta hasta pulsar Escuchar) ---
        val ttsBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = ThemeManager.barBackground(activity, lineOnTop = true)
            setPadding(NexoUi.dp(activity, 14f), NexoUi.dp(activity, 10f), NexoUi.dp(activity, 14f), NexoUi.dp(activity, 12f))
        }
        val ttsStatus = TextView(activity).apply {
            text = "Preparando lectura en voz alta…"
            textSize = 12f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val ttsControlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, NexoUi.dp(activity, 6f), 0, 0)
        }
        val playPauseBtn = NexoUi.button(activity, "Pausar", primary = true) {}
        val speedBtn = NexoUi.button(activity, "%.2gx".format(Locale.US, prefs.ttsRate)) {}
        val voiceBtn = NexoUi.button(activity, "Voz") {}
        val stopBtn = NexoUi.button(activity, "Detener") {}
        ttsControlsRow.addView(playPauseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = NexoUi.dp(activity, 6f) })
        ttsControlsRow.addView(speedBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = NexoUi.dp(activity, 6f) })
        ttsControlsRow.addView(voiceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply { marginEnd = NexoUi.dp(activity, 6f) })
        ttsControlsRow.addView(stopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        ttsBar.addView(ttsStatus)
        ttsBar.addView(ttsControlsRow)

        fun typefaceFor(family: String, bold: Boolean = false, italic: Boolean = false): Typeface {
            val base = when (family) {
                ReaderPrefs.FONT_SERIF -> Typeface.SERIF
                ReaderPrefs.FONT_MONO -> runCatching { activity.resources.getFont(R.font.jetbrains_mono) }.getOrDefault(Typeface.MONOSPACE)
                else -> runCatching { activity.resources.getFont(R.font.plus_jakarta_sans) }.getOrDefault(Typeface.SANS_SERIF)
            }
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            return Typeface.create(base, style)
        }

        fun highlightSpeakingBlock(activeIdx: Int) {
            blockViews.forEachIndexed { idx, tv ->
                if (idx == activeIdx && ttsPlaying) {
                    tv.background = ThemeManager.rounded(activity, p.accentSoft, 10f)
                    tv.setPadding(NexoUi.dp(activity, 10f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 10f), NexoUi.dp(activity, 8f))
                } else {
                    tv.background = null
                    tv.setPadding(0, 0, 0, 0)
                }
            }
        }

        fun renderArticleContent() {
            val sideDp = when (prefs.widthMode) {
                ReaderPrefs.WIDTH_NARROW -> 32f
                ReaderPrefs.WIDTH_WIDE -> 14f
                else -> 22f
            }
            val sidePx = NexoUi.dp(activity, sideDp)
            articleContainer.setPadding(sidePx, NexoUi.dp(activity, 18f), sidePx, NexoUi.dp(activity, 32f))
            articleContainer.removeAllViews()
            blockViews.clear()

            // Cabecera del artículo
            if (article.siteName.isNotBlank()) {
                articleContainer.addView(NexoUi.statusPill(activity, article.siteName, NexoUi.Tone.INFO),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = NexoUi.dp(activity, 10f)
                    })
            }
            articleContainer.addView(TextView(activity).apply {
                text = article.title
                textSize = (prefs.fontSizeSp + 6).toFloat()
                typeface = typefaceFor(prefs.fontFamily, bold = true)
                setTextColor(p.text)
                setLineSpacing(0f, 1.2f)
            })
            val metaLine = listOf(article.byline, "${article.readingMinutes} min de lectura")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            articleContainer.addView(TextView(activity).apply {
                text = metaLine
                textSize = 13f
                setTextColor(p.muted)
                setPadding(0, NexoUi.dp(activity, 6f), 0, NexoUi.dp(activity, 14f))
            })

            // Acciones contextuales: Ver página original + Nexo AI opcional (solo si está habilitado)
            val actionChips = mutableListOf<View>()
            if (article.url.isNotBlank()) {
                actionChips += NexoUi.chip(activity, if (openedOffline) "Abrir original en navegador" else "Ver página original", false) {
                    dialog.dismiss()
                    if (openedOffline) onOpenUrl(article.url)
                }
            }
            val aiSettings = NexoAiConfig.read(activity)
            val articleHost = runCatching { URI(article.url).host }.getOrNull()
            val aiPermitted = aiSettings.enabled && (!privateSource || aiSettings.allowPrivateTabs) &&
                (articleHost.isNullOrBlank() || NexoAiConfig.isHostAllowed(activity, articleHost))
            if (aiPermitted) {
                actionChips += NexoUi.chip(activity, "Resumir con Nexo AI", false) {
                    runReaderAiTask(activity, article, privateSource, "Resume este artículo en 4-6 puntos clave claros y concisos.", aiBox) { handle ->
                        activeAiCancel?.cancel()
                        activeAiCancel = handle
                    }
                }
                actionChips += NexoUi.chip(activity, "Traducir resumen (IA)", false) {
                    val targetLang = BrowserPrefs.translatorTarget(activity)
                    runReaderAiTask(activity, article, privateSource, "Traduce los puntos principales de este artículo al idioma '$targetLang'.", aiBox) { handle ->
                        activeAiCancel?.cancel()
                        activeAiCancel = handle
                    }
                }
            }
            if (actionChips.isNotEmpty()) {
                articleContainer.addView(NexoUi.chipRow(activity, actionChips, sidePaddingDp = 0f),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = NexoUi.dp(activity, 12f)
                    })
            }

            (aiBox.parent as? ViewGroup)?.removeView(aiBox)
            articleContainer.addView(aiBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = NexoUi.dp(activity, 12f)
            })
            articleContainer.addView(NexoUi.divider(activity, horizontalMarginDp = 0f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexoUi.dp(activity, 1f)).apply {
                    bottomMargin = NexoUi.dp(activity, 16f)
                })

            // Bloques de lectura saneados (nunca HTML crudo)
            article.blocks.forEach { block ->
                val tv = TextView(activity).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(0f, prefs.lineSpacing)
                    when (block.type) {
                        "h2" -> {
                            text = block.text
                            textSize = (prefs.fontSizeSp + 3).toFloat()
                            typeface = typefaceFor(prefs.fontFamily, bold = true)
                            setTextColor(p.text)
                        }
                        "h3" -> {
                            text = block.text
                            textSize = (prefs.fontSizeSp + 1).toFloat()
                            typeface = typefaceFor(prefs.fontFamily, bold = true)
                            setTextColor(p.text)
                        }
                        "quote" -> {
                            text = "“${block.text}”"
                            textSize = prefs.fontSizeSp.toFloat()
                            typeface = typefaceFor(prefs.fontFamily, italic = true)
                            setTextColor(p.muted)
                        }
                        "li" -> {
                            text = "•  ${block.text}"
                            textSize = prefs.fontSizeSp.toFloat()
                            typeface = typefaceFor(prefs.fontFamily)
                            setTextColor(p.text)
                        }
                        "code" -> {
                            text = block.text
                            textSize = (prefs.fontSizeSp - 2).coerceAtLeast(12).toFloat()
                            typeface = typefaceFor(ReaderPrefs.FONT_MONO)
                            setTextColor(p.text)
                            background = ThemeManager.rounded(activity, p.surfaceContainerHigh, 10f, p.outlineVariant)
                            setPadding(NexoUi.dp(activity, 12f), NexoUi.dp(activity, 10f), NexoUi.dp(activity, 12f), NexoUi.dp(activity, 10f))
                        }
                        else -> {
                            text = block.text
                            textSize = prefs.fontSizeSp.toFloat()
                            typeface = typefaceFor(prefs.fontFamily)
                            setTextColor(p.text)
                        }
                    }
                }
                if (block.text.isNotBlank()) blockViews.add(tv)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = when (block.type) {
                        "h2" -> NexoUi.dp(activity, 18f)
                        "h3" -> NexoUi.dp(activity, 14f)
                        else -> 0
                    }
                    bottomMargin = NexoUi.dp(activity, 12f)
                    if (block.type == "quote") marginStart = NexoUi.dp(activity, 12f)
                }
                articleContainer.addView(tv, lp)

                if (block.links.isNotEmpty()) {
                    val chips = block.links.map { link ->
                        NexoUi.chip(activity, "↗ ${link.label.take(32)}", false) {
                            dialog.dismiss()
                            onOpenUrl(link.href)
                        }
                    }
                    articleContainer.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 0f),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = NexoUi.dp(activity, 10f)
                        })
                }
            }
        }

        // --- Control real de TextToSpeech ---
        fun stopTtsPlayback(hideBar: Boolean = false) {
            ttsUtterance = null
            ttsRequested = false
            ttsPlaying = false
            runCatching { tts?.stop() }
            playPauseBtn.text = "Reproducir"
            ThemeManager.styleIconButton(activity, ttsBtn, !hideBar)
            highlightSpeakingBlock(-1)
            if (hideBar) {
                ttsBar.visibility = View.GONE
            } else {
                ttsStatus.text = "Lectura pausada (${ttsIndex + 1}/${readableBlocks.size.coerceAtLeast(1)})"
            }
        }

        fun shutdownTts() {
            ttsUtterance = null
            ttsRequested = false
            ttsPlaying = false
            ttsReady = false
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
        }

        fun speakCurrentBlock() {
            val engine = tts ?: return
            if (!ttsReady || readableBlocks.isEmpty()) return
            if (ttsIndex !in readableBlocks.indices) {
                ttsIndex = 0
                ttsOffset = 0
                stopTtsPlayback(hideBar = false)
                ttsStatus.text = "Lectura completada."
                return
            }
            ttsPlaying = true
            playPauseBtn.text = "Pausar"
            ThemeManager.styleIconButton(activity, ttsBtn, true)
            ttsStatus.text = "Leyendo párrafo ${ttsIndex + 1} de ${readableBlocks.size} · %.2gx".format(Locale.US, prefs.ttsRate)
            highlightSpeakingBlock(ttsIndex)
            val text = readableBlocks[ttsIndex]
            val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(2)
            var end = minOf(ttsOffset + limit, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > ttsOffset + limit / 2) end = space + 1
            }
            ttsChunkEnd = end
            val utterance = "nexo-reader-${++ttsGeneration}"
            ttsUtterance = utterance
            runCatching {
                engine.setSpeechRate(prefs.ttsRate)
                check(engine.speak(text.substring(ttsOffset, end), TextToSpeech.QUEUE_FLUSH, null, utterance) ==
                    TextToSpeech.SUCCESS)
            }.onFailure {
                stopTtsPlayback(hideBar = false)
                ttsStatus.text = "No se pudo reproducir el audio TTS en este dispositivo."
            }
        }

        fun ensureTtsAndStart() {
            if (readableBlocks.isEmpty()) {
                Toastish.show(activity, "No hay texto disponible para leer en voz alta.")
                return
            }
            ttsBar.visibility = View.VISIBLE
            ttsRequested = true
            if (tts != null) {
                if (ttsReady) speakCurrentBlock()
                return
            }
            ttsStatus.text = "Inicializando motor de voz de Android…"
            tts = TextToSpeech(activity.applicationContext) { status ->
                main.post {
                    if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) {
                        shutdownTts()
                        return@post
                    }
                    if (status != TextToSpeech.SUCCESS) {
                        shutdownTts()
                        ttsStatus.text = "Motor de síntesis de voz no disponible en este dispositivo."
                        Toastish.show(activity, "Android TextToSpeech no está disponible.")
                        return@post
                    }
                    val engine = tts ?: return@post
                    ttsReady = true
                    val targetLocale = runCatching { Locale.forLanguageTag(article.lang) }.getOrDefault(Locale.getDefault())
                    val langResult = engine.setLanguage(targetLocale)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.getDefault())
                    }
                    if (prefs.ttsVoiceName.isNotBlank()) {
                        runCatching {
                            engine.voices?.firstOrNull { it.name == prefs.ttsVoiceName }?.let { engine.voice = it }
                        }
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            main.post {
                                if (!ttsPlaying || !dialog.isShowing || utteranceId != ttsUtterance) return@post
                                ttsOffset = ttsChunkEnd
                                if (ttsOffset >= readableBlocks[ttsIndex].length) {
                                    ttsIndex++
                                    ttsOffset = 0
                                }
                                if (ttsIndex < readableBlocks.size) speakCurrentBlock()
                                else {
                                    ttsIndex = 0
                                    stopTtsPlayback(hideBar = false)
                                    ttsStatus.text = "Fin del artículo."
                                }
                            }
                        }
                        @Deprecated("Deprecated in Android API")
                        override fun onError(utteranceId: String?) {
                            main.post {
                                if (utteranceId != null && utteranceId == ttsUtterance && dialog.isShowing) {
                                    stopTtsPlayback(hideBar = false)
                                    ttsStatus.text = "No se pudo reproducir el audio TTS en este dispositivo."
                                }
                            }
                        }
                    })
                    if (ttsRequested) speakCurrentBlock()
                }
            }
        }

        // --- Eventos de controles ---
        fun updateSavedState(id: String?) {
            savedId = id
            ThemeManager.styleIconButton(activity, saveBtn, id != null)
            saveBtn.contentDescription = if (id == null) "Guardar sin conexión" else "Quitar de sin conexión"
            saveBtn.isEnabled = true
        }

        if (!privateSource) loadSavedArticles(activity) { snapshot ->
            if (dialog.isShowing) updateSavedState(snapshot.articles.firstOrNull {
                it.id == article.id || (article.url.isNotBlank() && it.url == article.url)
            }?.id)
        }

        saveBtn.setOnClickListener {
            val id = savedId
            saveBtn.isEnabled = false
            io.execute {
                val ok = runCatching {
                    if (id != null) OfflineArticleStore.delete(dir, id) else OfflineArticleStore.save(dir, article)
                }.getOrDefault(false)
                main.post {
                    if (!dialog.isShowing) return@post
                    updateSavedState(if (ok) article.id.takeIf { id == null } else id)
                    Toastish.show(activity, when {
                        !ok -> "No se pudo actualizar el artículo sin conexión."
                        id != null -> "Quitado de artículos sin conexión."
                        else -> "Guardado para lectura sin conexión."
                    })
                }
            }
        }

        ttsBtn.setOnClickListener {
            if (ttsBar.visibility == View.VISIBLE && (ttsPlaying || ttsRequested)) {
                stopTtsPlayback(hideBar = false)
            } else {
                ensureTtsAndStart()
            }
        }

        playPauseBtn.setOnClickListener {
            if (ttsPlaying || ttsRequested) stopTtsPlayback(hideBar = false) else ensureTtsAndStart()
        }

        stopBtn.setOnClickListener {
            ttsIndex = 0
            ttsOffset = 0
            stopTtsPlayback(hideBar = true)
        }

        speedBtn.setOnClickListener {
            val rates = ReaderPrefs.SPEECH_RATES
            val idx = rates.indexOfFirst { kotlin.math.abs(it - prefs.ttsRate) < 0.05f }
            val next = rates[(idx + 1) % rates.size]
            prefs = prefs.copy(ttsRate = next)
            savePrefs(activity, prefs)
            speedBtn.text = "%.2gx".format(Locale.US, next)
            if (ttsPlaying) speakCurrentBlock()
        }

        voiceBtn.setOnClickListener {
            val engine = tts
            val voices = runCatching { engine?.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                .sortedWith(compareBy<android.speech.tts.Voice> { it.locale.language != Locale.getDefault().language }.thenBy { it.name })
                .take(25)
            if (voices.isEmpty()) {
                Toastish.show(activity, "No hay voces instaladas expuestas por el motor TTS del dispositivo.")
                return@setOnClickListener
            }
            val labels = voices.map { v ->
                "${v.locale.displayLanguage} (${v.locale.country.ifBlank { v.locale.language }}) · ${v.name.takeLast(24)}"
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Voces instaladas en el dispositivo")
                .setItems(labels) { _, which ->
                    val chosen = voices[which]
                    runCatching { engine?.voice = chosen }
                    prefs = prefs.copy(ttsVoiceName = chosen.name)
                    savePrefs(activity, prefs)
                    Toastish.show(activity, "Voz seleccionada: ${chosen.locale.displayName}")
                    if (ttsPlaying) speakCurrentBlock()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        typoBtn.setOnClickListener {
            showTypographySheet(activity, prefs) { updated ->
                prefs = updated
                savePrefs(activity, updated)
                renderArticleContent()
            }
        }

        renderArticleContent()
        scroll.addView(articleContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ttsBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            activeAiCancel?.cancel()
            shutdownTts()
        }
        dialog.show()
    }

    private fun showTypographySheet(
        activity: Activity,
        initial: ReaderPrefs,
        onChanged: (ReaderPrefs) -> Unit
    ) {
        var current = initial
        val body = NexoUi.column(activity)
        val sizeLabel = TextView(activity).apply {
            text = "Tamaño de texto: ${current.fontSizeSp} sp"
            textSize = 14f
            typeface = NexoUi.bold(activity)
            setTextColor(ThemeManager.palette(activity).text)
        }
        val spacingLabel = TextView(activity).apply {
            text = "Interlineado: %.1fx".format(Locale.US, current.lineSpacing)
            textSize = 14f
            typeface = NexoUi.bold(activity)
            setTextColor(ThemeManager.palette(activity).text)
        }

        body.addView(NexoUi.section(activity, "Fuente"))
        val fontHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderFontChips() {
            fontHolder.removeAllViews()
            fontHolder.addView(NexoUi.chipRow(activity, listOf(
                ReaderPrefs.FONT_SANS to "Sans",
                ReaderPrefs.FONT_SERIF to "Serif",
                ReaderPrefs.FONT_MONO to "Monoespaciada"
            ).map { (id, label) ->
                NexoUi.chip(activity, label, current.fontFamily == id) {
                    current = current.copy(fontFamily = id)
                    renderFontChips()
                    onChanged(current)
                }
            }, sidePaddingDp = 0f))
        }
        renderFontChips()
        body.addView(fontHolder)

        body.addView(NexoUi.section(activity, "Tamaño"))
        body.addView(sizeLabel)
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, NexoUi.dp(activity, 6f), 0, 0)
            addView(NexoUi.button(activity, "A−") {
                current = current.copy(fontSizeSp = ReaderPrefs.clampFontSize(current.fontSizeSp - 2))
                sizeLabel.text = "Tamaño de texto: ${current.fontSizeSp} sp"
                onChanged(current)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = NexoUi.dp(activity, 8f) })
            addView(NexoUi.button(activity, "18 sp") {
                current = current.copy(fontSizeSp = 18)
                sizeLabel.text = "Tamaño de texto: ${current.fontSizeSp} sp"
                onChanged(current)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = NexoUi.dp(activity, 8f) })
            addView(NexoUi.button(activity, "A+") {
                current = current.copy(fontSizeSp = ReaderPrefs.clampFontSize(current.fontSizeSp + 2))
                sizeLabel.text = "Tamaño de texto: ${current.fontSizeSp} sp"
                onChanged(current)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(sizeRow)

        body.addView(NexoUi.section(activity, "Interlineado"))
        body.addView(spacingLabel)
        val spacingRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, NexoUi.dp(activity, 6f), 0, 0)
            listOf(1.2f to "Compacto", 1.5f to "Equilibrado", 1.8f to "Amplio").forEachIndexed { i, (mult, label) ->
                addView(NexoUi.button(activity, label) {
                    current = current.copy(lineSpacing = mult)
                    spacingLabel.text = "Interlineado: %.1fx".format(Locale.US, current.lineSpacing)
                    onChanged(current)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i < 2) marginEnd = NexoUi.dp(activity, 6f)
                })
            }
        }
        body.addView(spacingRow)

        body.addView(NexoUi.section(activity, "Ancho de columna"))
        val widthHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderWidthChips() {
            widthHolder.removeAllViews()
            widthHolder.addView(NexoUi.chipRow(activity, listOf(
                ReaderPrefs.WIDTH_NARROW to "Estrecho",
                ReaderPrefs.WIDTH_NORMAL to "Normal",
                ReaderPrefs.WIDTH_WIDE to "Amplio"
            ).map { (id, label) ->
                NexoUi.chip(activity, label, current.widthMode == id) {
                    current = current.copy(widthMode = id)
                    renderWidthChips()
                    onChanged(current)
                }
            }, sidePaddingDp = 0f))
        }
        renderWidthChips()
        body.addView(widthHolder)

        NexoSheets.show(activity, NexoSheets.column(
            activity,
            NexoSheets.header(activity, "Tipografía de Nexo Reader", "El tema Claro, Oscuro u OLED sigue el ajuste global de Nexo"),
            body
        ))
    }

    private fun runReaderAiTask(
        activity: Activity,
        article: OfflineArticleStore.ReaderArticle,
        privateSource: Boolean,
        prompt: String,
        aiBox: LinearLayout,
        onHandle: (NexoAiClient.CancelHandle) -> Unit
    ) {
        val settings = NexoAiConfig.read(activity)
        val apiKey = NexoAiConfig.apiKey(activity)
        if (!settings.enabled || !settings.consentAccepted) {
            Toastish.show(activity, "Activa y autoriza Nexo AI en su panel primero.")
            return
        }
        val host = runCatching { URI(article.url).host }.getOrNull()
        if ((privateSource && !settings.allowPrivateTabs) ||
            (!host.isNullOrBlank() && !NexoAiConfig.isHostAllowed(activity, host))
        ) {
            Toastish.show(activity, "Nexo AI está desactivado para este artículo.")
            return
        }
        val err = NexoAiConfig.validate(settings, apiKey)
        if (err != null) {
            Toastish.show(activity, err)
            return
        }
        val p = ThemeManager.palette(activity)
        val handle = NexoAiClient.CancelHandle()
        onHandle(handle)

        aiBox.removeAllViews()
        aiBox.visibility = View.VISIBLE
        val card = NexoUi.card(activity)
        val header = TextView(activity).apply {
            text = "Nexo AI · procesando artículo…"
            textSize = 13f
            typeface = NexoUi.bold(activity)
            setTextColor(p.accent)
        }
        val cancelBtn = NexoUi.button(activity, "Cancelar solicitud") {
            handle.cancel()
            aiBox.visibility = View.GONE
        }
        card.addView(header)
        card.addView(cancelBtn, NexoUi.buttonParams(activity, 8f))
        aiBox.addView(card)

        val contextText = article.plainText(NexoAiConfig.clampContextChars(settings.maxContextChars))
        io.execute {
            val result = NexoAiClient.execute(settings, apiKey, prompt, contextText, handle)
            main.post {
                if (activity.isFinishing || activity.isDestroyed || handle.isCancelled) return@post
                aiBox.removeAllViews()
                val resCard = NexoUi.card(activity)
                result.onSuccess { reply ->
                    resCard.addView(TextView(activity).apply {
                        text = "Nexo AI"
                        textSize = 13f
                        typeface = NexoUi.bold(activity)
                        setTextColor(p.accent)
                    })
                    resCard.addView(TextView(activity).apply {
                        text = reply.text
                        textSize = 14.5f
                        setTextColor(p.text)
                        setTextIsSelectable(true)
                        setLineSpacing(0f, 1.35f)
                        setPadding(0, NexoUi.dp(activity, 6f), 0, NexoUi.dp(activity, 6f))
                    })
                    reply.usage?.format()?.takeIf { it.isNotBlank() }?.let { tokens ->
                        resCard.addView(TextView(activity).apply {
                            text = "Tokens reportados: $tokens"
                            textSize = 11.5f
                            setTextColor(p.muted)
                        })
                    }
                    resCard.addView(NexoUi.button(activity, "Ocultar respuesta IA") {
                        aiBox.visibility = View.GONE
                    }, NexoUi.buttonParams(activity, 8f))
                }.onFailure { fail ->
                    resCard.addView(NexoUi.statusPill(activity, "Error de Nexo AI", NexoUi.Tone.DANGER))
                    resCard.addView(TextView(activity).apply {
                        text = fail.message ?: "No se pudo completar la petición."
                        textSize = 13f
                        setTextColor(p.text)
                        setPadding(0, NexoUi.dp(activity, 6f), 0, 0)
                    })
                }
                aiBox.addView(resCard)
            }
        }
    }
}
