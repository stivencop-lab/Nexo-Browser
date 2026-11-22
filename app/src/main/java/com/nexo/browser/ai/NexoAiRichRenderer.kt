package com.nexo.browser.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.nexo.browser.BrowserPalette
import com.nexo.browser.NexoGlass
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.UrlSafety

/**
 * Renderizador nativo de respuestas enriquecidas de Nexo AI sobre vistas Android (sin WebView).
 *
 * Convierte el AST de [NexoAiRichContent] en tipografía editorial limpia, código con botón Copiar,
 * tablas desplazables, citas destacadas, tarjetas de KPIs (`nexo-card`) y gráficos Canvas (`nexo-chart`).
 */
object NexoAiRichRenderer {

    /**
     * Renderiza [rawText] dentro de [container], reemplazando solo sus hijos.
     * En streaming activo ([isStreaming] = true) oculta bloques de protocolo incompletos y muestra
     * un aviso sutil si la IA está construyendo un gráfico o ejecutando una herramienta.
     */
    fun renderInto(
        activity: Activity,
        container: LinearLayout,
        rawText: String,
        isStreaming: Boolean = false,
        textSizeSp: Float = 15f
    ) {
        container.removeAllViews()
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }

        val (displayRaw, pendingHint) = if (isStreaming) {
            NexoAiRichContent.prepareStreamingText(rawText)
        } else {
            NexoAiRichContent.stripInternalBlocks(rawText) to null
        }

        val blocks = NexoAiRichContent.parse(displayRaw)
        blocks.forEachIndexed { idx, block ->
            val topGap = if (idx == 0) 0 else when (block) {
                is NexoAiRichContent.Block.Heading -> dp(12f)
                is NexoAiRichContent.Block.Chart,
                is NexoAiRichContent.Block.Card,
                is NexoAiRichContent.Block.CodeBlock,
                is NexoAiRichContent.Block.Table -> dp(10f)
                is NexoAiRichContent.Block.Divider -> dp(10f)
                else -> dp(6f)
            }
            val view = buildBlockView(activity, p, block, textSizeSp)
            container.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = topGap
                }
            )
        }

        if (!pendingHint.isNullOrBlank()) {
            container.addView(TextView(activity).apply {
                text = pendingHint
                textSize = 12.5f
                typeface = NexoUi.mono(activity)
                setTextColor(p.muted)
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PILL)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }
    }

    /** Construye un [SpannableStringBuilder] formateado para textos breves o vistas de comparación. */
    fun formatInlineSpannable(
        activity: Activity,
        rawText: String,
        enableLinks: Boolean = true
    ): CharSequence {
        val p = ThemeManager.palette(activity)
        val cleaned = NexoAiRichContent.stripInternalBlocks(rawText)
        val lines = cleaned.replace("\r\n", "\n").split('\n')
        val out = SpannableStringBuilder()
        lines.forEachIndexed { idx, line ->
            if (idx > 0) out.append('\n')
            val trimmed = line.trim()
            val strippedHeading = trimmed.replace(Regex("^#{1,4}\\s+"), "")
            val isHeading = strippedHeading != trimmed
            val inlines = NexoAiRichContent.parseInlines(strippedHeading)
            val start = out.length
            appendInlines(activity, p, out, inlines, enableLinks)
            if (isHeading && out.length > start) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun buildBlockView(
        activity: Activity,
        p: BrowserPalette,
        block: NexoAiRichContent.Block,
        baseSizeSp: Float
    ): View {
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        return when (block) {
            is NexoAiRichContent.Block.Heading -> styledTextView(activity, p, block.inlines, when (block.level) {
                1 -> baseSizeSp + 4.5f
                2 -> baseSizeSp + 2.5f
                3 -> baseSizeSp + 1f
                else -> baseSizeSp
            }, boldDefault = true)

            is NexoAiRichContent.Block.Paragraph -> styledTextView(activity, p, block.inlines, baseSizeSp)

            is NexoAiRichContent.Block.BulletList -> LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                block.items.forEachIndexed { idx, inlines ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(2f), if (idx == 0) 0 else dp(4f), 0, 0)
                    }
                    row.addView(TextView(activity).apply {
                        text = "•"
                        textSize = baseSizeSp
                        typeface = NexoUi.semiBold(activity)
                        setTextColor(if (p.isMonochrome) p.muted else p.accent)
                        setPadding(0, 0, dp(8f), 0)
                    })
                    row.addView(
                        styledTextView(activity, p, inlines, baseSizeSp),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(row)
                }
            }

            is NexoAiRichContent.Block.OrderedList -> LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                block.items.forEachIndexed { idx, (num, inlines) ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(2f), if (idx == 0) 0 else dp(4f), 0, 0)
                    }
                    row.addView(TextView(activity).apply {
                        text = "$num."
                        textSize = baseSizeSp - 0.5f
                        typeface = NexoUi.mono(activity)
                        setTextColor(if (p.isMonochrome) p.muted else p.accent)
                        minWidth = dp(24f)
                        setPadding(0, 0, dp(6f), 0)
                    })
                    row.addView(
                        styledTextView(activity, p, inlines, baseSizeSp),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(row)
                }
            }

            is NexoAiRichContent.Block.Blockquote -> {
                val accentColor = when (block.callout) {
                    "WARNING", "CAUTION" -> p.warning.color
                    "TIP" -> p.success.color
                    else -> if (p.isMonochrome) p.muted else p.accent
                }
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10f), dp(8f), dp(12f), dp(8f))
                    background = ThemeManager.rounded(
                        activity,
                        ColorUtils.setAlphaComponent(accentColor, if (p.isDark) 22 else 14),
                        12f
                    )
                    addView(View(activity).apply {
                        background = ThemeManager.rounded(activity, accentColor, 2f)
                    }, LinearLayout.LayoutParams(dp(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = dp(10f)
                    })
                    val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    block.callout?.let { tag ->
                        col.addView(TextView(activity).apply {
                            text = when (tag) {
                                "TIP" -> "Consejo"
                                "WARNING", "CAUTION" -> "Atención"
                                "IMPORTANT" -> "Importante"
                                else -> "Nota"
                            }
                            textSize = 11.5f
                            typeface = NexoUi.semiBold(activity)
                            setTextColor(accentColor)
                            setPadding(0, 0, 0, dp(2f))
                        })
                    }
                    col.addView(styledTextView(activity, p, block.inlines, baseSizeSp - 0.5f))
                    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }

            is NexoAiRichContent.Block.CodeBlock -> buildCodeBlock(activity, p, block)
            is NexoAiRichContent.Block.Table -> buildTableBlock(activity, p, block, baseSizeSp - 1.5f)
            is NexoAiRichContent.Block.Chart -> NexoAiChartView(activity, block.spec)
            is NexoAiRichContent.Block.Card -> buildCardBlock(activity, p, block.spec)
            NexoAiRichContent.Block.Divider -> NexoUi.divider(activity, 0f)
        }
    }

    private fun styledTextView(
        activity: Activity,
        p: BrowserPalette,
        inlines: List<NexoAiRichContent.Inline>,
        sizeSp: Float,
        boldDefault: Boolean = false
    ): TextView {
        val sb = SpannableStringBuilder()
        val hasLink = appendInlines(activity, p, sb, inlines, enableLinks = true)
        return TextView(activity).apply {
            text = sb
            textSize = sizeSp
            typeface = if (boldDefault) NexoUi.semiBold(activity) else NexoUi.regular(activity)
            setTextColor(p.text)
            setLineSpacing(0f, 1.42f)
            setTextIsSelectable(true)
            if (hasLink) movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun appendInlines(
        activity: Activity,
        p: BrowserPalette,
        sb: SpannableStringBuilder,
        inlines: List<NexoAiRichContent.Inline>,
        enableLinks: Boolean
    ): Boolean {
        var hasLink = false
        val codeBg = ColorUtils.setAlphaComponent(p.text, if (p.isDark) 24 else 14)
        val linkColor = if (p.isMonochrome) p.text else p.accent
        for (inline in inlines) {
            val start = sb.length
            when (inline) {
                is NexoAiRichContent.Inline.Text -> sb.append(inline.value)
                is NexoAiRichContent.Inline.Bold -> {
                    sb.append(inline.value)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is NexoAiRichContent.Inline.Italic -> {
                    sb.append(inline.value)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is NexoAiRichContent.Inline.BoldItalic -> {
                    sb.append(inline.value)
                    sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is NexoAiRichContent.Inline.Strike -> {
                    sb.append(inline.value)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is NexoAiRichContent.Inline.Code -> {
                    sb.append(' ').append(inline.value).append(' ')
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is NexoAiRichContent.Inline.Link -> {
                    sb.append(inline.label)
                    sb.setSpan(ForegroundColorSpan(linkColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (enableLinks) {
                        hasLink = true
                        val targetUrl = inline.url
                        sb.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                if (UrlSafety.isWebUrl(targetUrl)) {
                                    val isPriv = TabManager.activeTab()?.isPrivate == true
                                    TabManager.createTab(targetUrl, isPrivate = isPriv, activate = true)
                                    Toastish.show(activity, "Abriendo enlace en nueva pestaña…")
                                }
                            }
                        }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        return hasLink
    }

    private fun buildCodeBlock(
        activity: Activity,
        p: BrowserPalette,
        block: NexoAiRichContent.Block.CodeBlock
    ): View {
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(
                activity,
                if (p.isDark) ColorUtils.setAlphaComponent(0xFF000000.toInt(), 110) else NexoSheets.groupFill(p),
                14f
            )
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        bar.addView(TextView(activity).apply {
            text = block.language.ifBlank { "código" }.uppercase()
            textSize = 11f
            typeface = NexoUi.mono(activity)
            setTextColor(p.muted)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(activity).apply {
            text = "Copiar"
            textSize = 12f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            gravity = Gravity.CENTER
            minHeight = dp(32f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = ThemeManager.ripple(
                activity,
                ThemeManager.rounded(activity, ColorUtils.setAlphaComponent(p.text, if (p.isDark) 20 else 12), NexoUi.Tokens.RADIUS_PILL),
                NexoUi.Tokens.RADIUS_PILL
            )
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Código Nexo AI", block.code))
                Toastish.show(activity, "Código copiado.")
            }
        })
        card.addView(bar)
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = true
            addView(TextView(activity).apply {
                text = block.code
                textSize = 12.5f
                typeface = NexoUi.mono(activity)
                setTextColor(p.text)
                setLineSpacing(0f, 1.32f)
                setTextIsSelectable(true)
            })
        }
        card.addView(scroller)
        return card
    }

    private fun buildTableBlock(
        activity: Activity,
        p: BrowserPalette,
        block: NexoAiRichContent.Block.Table,
        sizeSp: Float
    ): View {
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val table = TableLayout(activity).apply {
            isStretchAllColumns = false
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), 14f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        val headerBg = ColorUtils.setAlphaComponent(p.text, if (p.isDark) 18 else 12)
        val headerRow = TableRow(activity).apply { setBackgroundColor(headerBg) }
        block.headers.forEach { cell ->
            headerRow.addView(styledTextView(activity, p, cell, sizeSp, boldDefault = true).apply {
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                maxWidth = dp(200f)
            })
        }
        table.addView(headerRow)

        val stripeBg = ColorUtils.setAlphaComponent(p.text, if (p.isDark) 8 else 6)
        block.rows.forEachIndexed { rIdx, row ->
            val tr = TableRow(activity).apply {
                if (rIdx % 2 == 1) setBackgroundColor(stripeBg)
            }
            row.forEach { cell ->
                tr.addView(styledTextView(activity, p, cell, sizeSp).apply {
                    setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                    maxWidth = dp(200f)
                })
            }
            table.addView(tr)
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(table)
        }
    }

    private fun buildCardBlock(
        activity: Activity,
        p: BrowserPalette,
        spec: NexoAiRichContent.CardSpec
    ): View {
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(12f))
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            NexoGlass.paperShadow(this, p)
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            text = spec.title
            textSize = 15.5f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (spec.badge.isNotBlank()) {
            headerRow.addView(NexoUi.statusPill(activity, spec.badge, NexoUi.Tone.ACCENT))
        }
        card.addView(headerRow)
        if (spec.subtitle.isNotBlank()) {
            card.addView(TextView(activity).apply {
                text = spec.subtitle
                textSize = 12.5f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                setPadding(0, dp(3f), 0, dp(6f))
            })
        }
        if (spec.metrics.isNotEmpty()) {
            spec.metrics.chunked(2).forEach { pair ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6f), 0, 0)
                }
                pair.forEachIndexed { idx, m ->
                    val tile = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                        background = ThemeManager.rounded(
                            activity,
                            ColorUtils.setAlphaComponent(p.text, if (p.isDark) 14 else 9),
                            12f
                        )
                    }
                    tile.addView(TextView(activity).apply {
                        text = m.label
                        textSize = 11.5f
                        typeface = NexoUi.regular(activity)
                        setTextColor(p.muted)
                    })
                    val valColor = when (m.tone) {
                        "positive" -> p.success.color
                        "negative" -> p.danger
                        "accent" -> if (p.isMonochrome) p.text else p.accent
                        else -> p.text
                    }
                    tile.addView(TextView(activity).apply {
                        text = m.value
                        textSize = 17f
                        typeface = NexoUi.mono(activity)
                        fontFeatureSettings = "tnum"
                        setTextColor(valColor)
                        setPadding(0, dp(2f), 0, 0)
                    })
                    if (m.delta.isNotBlank()) {
                        tile.addView(TextView(activity).apply {
                            text = m.delta
                            textSize = 11f
                            typeface = NexoUi.mono(activity)
                            setTextColor(valColor)
                        })
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx == 0 && pair.size > 1) marginEnd = dp(8f)
                    })
                }
                card.addView(row)
            }
        }
        if (spec.items.isNotEmpty()) {
            val listBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            spec.items.forEach { it ->
                listBox.addView(TextView(activity).apply {
                    text = "•  ${NexoAiRichContent.inlinesToPlain(NexoAiRichContent.parseInlines(it))}"
                    textSize = 13.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.text)
                    setLineSpacing(0f, 1.32f)
                    setPadding(0, dp(2f), 0, dp(2f))
                })
            }
            card.addView(listBox)
        }
        return card
    }
}
