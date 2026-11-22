package com.nexo.browser.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.nexo.browser.BrowserPalette
import com.nexo.browser.NexoGlass
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.ThemeManager
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renderizador nativo de gráficos en Canvas para Nexo AI (`bar`, `horizontal_bar`, `line`, `area`,
 * `pie`, `donut`, `comparison`). Sin WebView ni dependencias externas; se adapta a todos los temas
 * de Nexo (Obsidian, OLED y Paper) con interacción táctil para inspeccionar valores exactos.
 */
class NexoAiChartView(
    context: Activity,
    private val spec: NexoAiRichContent.ChartSpec
) : LinearLayout(context) {

    private val palette: BrowserPalette = ThemeManager.palette(context)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val seriesColors: IntArray = buildSeriesPalette(palette)
    private val readoutView: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(14f), dp(14f), dp(14f), dp(12f))
        background = ThemeManager.rounded(context, NexoSheets.groupFill(palette), NexoUi.Tokens.RADIUS_CARD)
        NexoGlass.paperShadow(this, palette)

        // Cabecera del gráfico
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply { orientation = VERTICAL }
        titleCol.addView(TextView(context).apply {
            text = spec.title
            textSize = 15f
            typeface = NexoUi.semiBold(context)
            setTextColor(palette.text)
        })
        if (spec.subtitle.isNotBlank()) {
            titleCol.addView(TextView(context).apply {
                text = spec.subtitle
                textSize = 12f
                typeface = NexoUi.regular(context)
                setTextColor(palette.muted)
                setPadding(0, dp(2f), 0, 0)
            })
        }
        titleRow.addView(titleCol, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (spec.unit.isNotBlank()) {
            titleRow.addView(TextView(context).apply {
                text = spec.unit
                textSize = 11.5f
                typeface = NexoUi.mono(context)
                setTextColor(palette.muted)
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                background = ThemeManager.rounded(
                    context,
                    ColorUtils.setAlphaComponent(palette.text, if (palette.isDark) 18 else 12),
                    NexoUi.Tokens.RADIUS_PILL
                )
            })
        }
        addView(titleRow)

        readoutView = TextView(context).apply {
            textSize = 12f
            typeface = NexoUi.mono(context)
            fontFeatureSettings = "tnum"
            setTextColor(palette.muted)
            setPadding(0, dp(6f), 0, dp(4f))
            text = defaultReadout()
        }
        addView(readoutView)

        val canvasView = ChartCanvas(context, spec, palette, seriesColors) { idx ->
            readoutView.text = if (idx in spec.labels.indices) formatReadout(idx) else defaultReadout()
            readoutView.setTextColor(if (idx in spec.labels.indices) palette.text else palette.muted)
        }
        val canvasHeightDp = when (spec.type) {
            "horizontal_bar" -> (spec.labels.size * 34f + 24f).coerceIn(130f, 340f)
            "pie", "donut" -> 196f
            else -> 192f
        }
        addView(canvasView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(canvasHeightDp)).apply {
            topMargin = dp(4f)
        })

        // Leyenda (multi-serie o pie/donut)
        val legendItems: List<Pair<String, Int>> = when {
            spec.type == "pie" || spec.type == "donut" -> {
                val vals = spec.series.firstOrNull()?.values.orEmpty()
                val total = vals.sumOf { max(0.0, it) }.takeIf { it > 0 } ?: 1.0
                spec.labels.mapIndexed { idx, lbl ->
                    val v = max(0.0, vals.getOrElse(idx) { 0.0 })
                    val pct = ((v / total) * 100.0)
                    "$lbl · ${formatNumber(v)}${unitSuffix()} (${String.format(Locale.US, "%.0f%%", pct)})" to seriesColors[idx % seriesColors.size]
                }
            }
            spec.series.size > 1 -> spec.series.mapIndexed { idx, s ->
                (s.name.ifBlank { "Serie ${idx + 1}" }) to seriesColors[idx % seriesColors.size]
            }
            else -> emptyList()
        }

        if (legendItems.isNotEmpty()) {
            val legendBox = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            val chunkSize = if (spec.type == "pie" || spec.type == "donut") 2 else 3
            legendItems.chunked(chunkSize).forEach { rowItems ->
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2f), 0, dp(2f))
                }
                rowItems.forEach { (label, color) ->
                    val item = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 0, dp(10f), 0)
                    }
                    item.addView(View(context).apply {
                        background = ThemeManager.rounded(context, color, 4f)
                    }, LayoutParams(dp(9f), dp(9f)).apply { marginEnd = dp(6f) })
                    item.addView(TextView(context).apply {
                        text = label
                        textSize = 11.5f
                        typeface = NexoUi.regular(context)
                        setTextColor(palette.muted)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(item, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                legendBox.addView(row)
            }
            addView(legendBox)
        }
    }

    private fun unitSuffix(): String = if (spec.unit.isBlank()) "" else " ${spec.unit}"

    private fun defaultReadout(): String {
        val vals = spec.series.firstOrNull()?.values.orEmpty()
        if (vals.isEmpty()) return ""
        return if (spec.type == "pie" || spec.type == "donut") {
            "Total: ${formatNumber(vals.sumOf { max(0.0, it) })}${unitSuffix()} · toca un segmento"
        } else {
            val maxVal = spec.series.flatMap { it.values }.maxOrNull() ?: 0.0
            val minVal = spec.series.flatMap { it.values }.minOrNull() ?: 0.0
            "Rango: ${formatNumber(minVal)} – ${formatNumber(maxVal)}${unitSuffix()} · toca para detalle"
        }
    }

    private fun formatReadout(index: Int): String {
        val lbl = spec.labels[index]
        val parts = spec.series.mapIndexed { sIdx, s ->
            val v = s.values.getOrElse(index) { 0.0 }
            val prefix = if (spec.series.size > 1 && s.name.isNotBlank()) "${s.name}: " else ""
            "$prefix${formatNumber(v)}${unitSuffix()}"
        }
        return "$lbl → ${parts.joinToString(" · ")}"
    }

    private class ChartCanvas(
        context: Activity,
        private val spec: NexoAiRichContent.ChartSpec,
        private val palette: BrowserPalette,
        private val colors: IntArray,
        private val onSelect: (Int) -> Unit
    ) : View(context) {

        private val d = resources.displayMetrics.density
        private fun dpF(v: Float): Float = v * d

        private var selectedIndex = -1

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.text, if (palette.isDark) 26 else 22)
            strokeWidth = dpF(1f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpF(3f), dpF(4f)), 0f)
        }
        private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = dpF(10.5f)
            typeface = NexoUi.mono(context)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = dpF(11f)
            typeface = NexoUi.regular(context)
            textAlign = Paint.Align.CENTER
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text
            textSize = dpF(10.5f)
            typeface = NexoUi.mono(context)
            textAlign = Paint.Align.CENTER
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2.4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.accent, 34)
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val path = Path()
        private val fillPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || spec.labels.isEmpty() || spec.series.isEmpty()) return
            when (spec.type) {
                "horizontal_bar" -> drawHorizontalBars(canvas)
                "line", "area" -> drawLineOrArea(canvas, fill = spec.type == "area")
                "pie", "donut" -> drawPieOrDonut(canvas, donut = spec.type == "donut")
                else -> drawVerticalBars(canvas)
            }
        }

        private fun drawVerticalBars(canvas: Canvas) {
            val leftPad = dpF(38f)
            val rightPad = dpF(8f)
            val topPad = dpF(18f)
            val bottomPad = dpF(26f)
            val plotW = (width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (height - topPad - bottomPad).coerceAtLeast(1f)

            val allVals = spec.series.flatMap { it.values }
            val maxV = max(allVals.maxOrNull() ?: 1.0, 0.0001)
            val minV = min(allVals.minOrNull() ?: 0.0, 0.0)
            val range = (maxV - minV).coerceAtLeast(0.0001)
            val zeroY = topPad + ((maxV / range) * plotH).toFloat()

            // Rejilla horizontal (3 niveles)
            axisTextPaint.textAlign = Paint.Align.RIGHT
            for (step in 0..3) {
                val frac = step / 3f
                val y = topPad + plotH * frac
                val v = maxV - range * frac
                canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
                canvas.drawText(formatCompact(v), leftPad - dpF(6f), y + dpF(3.5f), axisTextPaint)
            }

            val count = spec.labels.size
            val groupW = plotW / count
            val seriesCount = spec.series.size
            val innerGap = dpF(if (seriesCount > 1) 3f else 0f)
            val groupPad = (groupW * 0.18f).coerceIn(dpF(4f), dpF(18f))
            val barW = ((groupW - groupPad * 2 - innerGap * (seriesCount - 1)) / seriesCount).coerceIn(dpF(6f), dpF(36f))
            val totalBarsW = barW * seriesCount + innerGap * (seriesCount - 1)
            val corner = min(dpF(5f), barW / 2f)

            for (i in 0 until count) {
                val gx = leftPad + i * groupW
                if (i == selectedIndex) {
                    rect.set(gx + dpF(2f), topPad, gx + groupW - dpF(2f), topPad + plotH)
                    canvas.drawRoundRect(rect, dpF(6f), dpF(6f), highlightPaint)
                }
                val startX = gx + (groupW - totalBarsW) / 2f
                for (sIdx in 0 until seriesCount) {
                    val v = spec.series[sIdx].values.getOrElse(i) { 0.0 }
                    val valY = topPad + (((maxV - v) / range) * plotH).toFloat()
                    val topY = min(zeroY, valY)
                    val botY = max(zeroY, valY)
                    val bx = startX + sIdx * (barW + innerGap)
                    barPaint.color = if (seriesCount == 1) colors[i % colors.size] else colors[sIdx % colors.size]
                    barPaint.alpha = if (selectedIndex == -1 || selectedIndex == i) 255 else 115
                    rect.set(bx, topY, bx + barW, max(botY, topY + dpF(2f)))
                    canvas.drawRoundRect(rect, corner, corner, barPaint)

                    if (seriesCount == 1 && count <= 8) {
                        canvas.drawText(formatCompact(v), bx + barW / 2f, topY - dpF(4f), valuePaint)
                    }
                }
                val maxChars = (groupW / dpF(6.5f)).toInt().coerceIn(3, 12)
                val lbl = spec.labels[i].let { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
                labelPaint.color = if (i == selectedIndex) palette.text else palette.muted
                canvas.drawText(lbl, gx + groupW / 2f, height - dpF(6f), labelPaint)
            }
        }

        private fun drawHorizontalBars(canvas: Canvas) {
            val count = spec.labels.size
            val rowH = (height - dpF(8f)) / count
            val labelW = min(width * 0.34f, dpF(116f))
            val valueW = dpF(48f)
            val trackStart = labelW + dpF(8f)
            val trackW = (width - trackStart - valueW - dpF(6f)).coerceAtLeast(dpF(32f))
            val values = spec.series.first().values
            val maxV = max(values.maxOfOrNull { max(0.0, it) } ?: 1.0, 0.0001)
            val barH = (rowH * 0.48f).coerceIn(dpF(10f), dpF(18f))

            for (i in 0 until count) {
                val cy = dpF(4f) + i * rowH + rowH / 2f
                val v = max(0.0, values.getOrElse(i) { 0.0 })
                val frac = (v / maxV).toFloat().coerceIn(0f, 1f)
                if (i == selectedIndex) {
                    rect.set(0f, cy - rowH / 2f + dpF(2f), width.toFloat(), cy + rowH / 2f - dpF(2f))
                    canvas.drawRoundRect(rect, dpF(8f), dpF(8f), highlightPaint)
                }

                labelPaint.textAlign = Paint.Align.LEFT
                labelPaint.color = if (i == selectedIndex) palette.text else palette.muted
                val maxChars = (labelW / dpF(6.2f)).toInt().coerceIn(6, 18)
                val lbl = spec.labels[i].let { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
                canvas.drawText(lbl, dpF(4f), cy + dpF(4f), labelPaint)

                // Fondo de pista
                barPaint.color = ColorUtils.setAlphaComponent(palette.text, if (palette.isDark) 18 else 14)
                rect.set(trackStart, cy - barH / 2f, trackStart + trackW, cy + barH / 2f)
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint)

                // Barra real
                barPaint.color = colors[i % colors.size]
                barPaint.alpha = if (selectedIndex == -1 || selectedIndex == i) 255 else 130
                val filledW = max(barH, trackW * frac)
                rect.set(trackStart, cy - barH / 2f, trackStart + filledW, cy + barH / 2f)
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint)

                valuePaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(formatCompact(v), width - dpF(4f), cy + dpF(4f), valuePaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
            valuePaint.textAlign = Paint.Align.CENTER
        }

        private fun drawLineOrArea(canvas: Canvas, fill: Boolean) {
            val leftPad = dpF(38f)
            val rightPad = dpF(14f)
            val topPad = dpF(16f)
            val bottomPad = dpF(26f)
            val plotW = (width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (height - topPad - bottomPad).coerceAtLeast(1f)

            val allVals = spec.series.flatMap { it.values }
            val maxV = allVals.maxOrNull() ?: 1.0
            val minV = min(allVals.minOrNull() ?: 0.0, if (fill) 0.0 else (allVals.minOrNull() ?: 0.0))
            val range = (maxV - minV).coerceAtLeast(0.0001)

            axisTextPaint.textAlign = Paint.Align.RIGHT
            for (step in 0..3) {
                val frac = step / 3f
                val y = topPad + plotH * frac
                val v = maxV - range * frac
                canvas.drawLine(leftPad, y, width - rightPad, y, gridPaint)
                canvas.drawText(formatCompact(v), leftPad - dpF(6f), y + dpF(3.5f), axisTextPaint)
            }

            val count = spec.labels.size
            fun xAt(idx: Int): Float = if (count <= 1) leftPad + plotW / 2f else leftPad + (idx.toFloat() / (count - 1)) * plotW
            fun yAt(v: Double): Float = topPad + (((maxV - v) / range) * plotH).toFloat()

            if (selectedIndex in 0 until count) {
                val sx = xAt(selectedIndex)
                canvas.drawLine(sx, topPad, sx, topPad + plotH, gridPaint)
            }

            spec.series.forEachIndexed { sIdx, s ->
                val color = colors[sIdx % colors.size]
                path.reset()
                fillPath.reset()
                for (i in 0 until count) {
                    val x = xAt(i)
                    val y = yAt(s.values.getOrElse(i) { 0.0 })
                    if (i == 0) {
                        path.moveTo(x, y)
                        if (fill) {
                            fillPath.moveTo(x, topPad + plotH)
                            fillPath.lineTo(x, y)
                        }
                    } else {
                        path.lineTo(x, y)
                        if (fill) fillPath.lineTo(x, y)
                    }
                }
                if (fill && count >= 2) {
                    fillPath.lineTo(xAt(count - 1), topPad + plotH)
                    fillPath.close()
                    areaPaint.shader = LinearGradient(
                        0f, topPad, 0f, topPad + plotH,
                        ColorUtils.setAlphaComponent(color, 72),
                        ColorUtils.setAlphaComponent(color, 6),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(fillPath, areaPaint)
                    areaPaint.shader = null
                }
                linePaint.color = color
                canvas.drawPath(path, linePaint)

                // Puntos
                for (i in 0 until count) {
                    val x = xAt(i)
                    val y = yAt(s.values.getOrElse(i) { 0.0 })
                    val r = dpF(if (i == selectedIndex) 5f else 3.2f)
                    barPaint.color = color
                    barPaint.alpha = 255
                    canvas.drawCircle(x, y, r, barPaint)
                }
            }

            // Etiquetas X (muestreadas si hay muchas)
            val step = max(1, (count + 5) / 6)
            labelPaint.textAlign = Paint.Align.CENTER
            for (i in 0 until count step step) {
                val lbl = spec.labels[i].take(8)
                labelPaint.color = if (i == selectedIndex) palette.text else palette.muted
                canvas.drawText(lbl, xAt(i), height - dpF(6f), labelPaint)
            }
        }

        private fun drawPieOrDonut(canvas: Canvas, donut: Boolean) {
            val values = spec.series.first().values.map { max(0.0, it) }
            val total = values.sum().takeIf { it > 0.0 } ?: return
            val cx = width / 2f
            val cy = height / 2f
            val radius = (min(width, height) * 0.43f).coerceAtLeast(dpF(36f))
            var startAngle = -90f

            for (i in values.indices) {
                val sweep = ((values[i] / total) * 360.0).toFloat()
                if (sweep <= 0.2f) continue
                val expand = if (i == selectedIndex) dpF(5f) else 0f
                val midRad = ((startAngle + sweep / 2f) * PI / 180.0)
                val ox = (cos(midRad) * expand).toFloat()
                val oy = (sin(midRad) * expand).toFloat()
                rect.set(cx - radius + ox, cy - radius + oy, cx + radius + ox, cy + radius + oy)
                barPaint.color = colors[i % colors.size]
                barPaint.alpha = if (selectedIndex == -1 || selectedIndex == i) 255 else 155
                canvas.drawArc(rect, startAngle, sweep - 1.2f, true, barPaint)
                startAngle += sweep
            }

            if (donut) {
                barPaint.color = NexoSheets.groupFill(palette)
                barPaint.alpha = 255
                canvas.drawCircle(cx, cy, radius * 0.56f, barPaint)

                val centerMain = if (selectedIndex in values.indices) {
                    String.format(Locale.US, "%.0f%%", (values[selectedIndex] / total) * 100.0)
                } else {
                    formatCompact(total)
                }
                val centerSub = if (selectedIndex in spec.labels.indices) {
                    spec.labels[selectedIndex].take(12)
                } else {
                    "Total"
                }
                valuePaint.textAlign = Paint.Align.CENTER
                valuePaint.textSize = dpF(14f)
                canvas.drawText(centerMain, cx, cy + dpF(2f), valuePaint)
                valuePaint.textSize = dpF(10.5f)
                labelPaint.color = palette.muted
                canvas.drawText(centerSub, cx, cy + dpF(16f), labelPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val idx = hitTest(event.x, event.y)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onSelect(idx)
                    invalidate()
                }
                return true
            }
            return super.onTouchEvent(event)
        }

        private fun hitTest(x: Float, y: Float): Int {
            val count = spec.labels.size
            if (count == 0) return -1
            return when (spec.type) {
                "horizontal_bar" -> ((y - dpF(4f)) / ((height - dpF(8f)) / count)).toInt().coerceIn(0, count - 1)
                "pie", "donut" -> {
                    val values = spec.series.first().values.map { max(0.0, it) }
                    val total = values.sum().takeIf { it > 0.0 } ?: return -1
                    val dx = x - width / 2f
                    val dy = y - height / 2f
                    val r = min(width, height) * 0.48f
                    if (hypot(dx.toDouble(), dy.toDouble()) > r) return -1
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                    if (angle < 0f) angle += 360f
                    var acc = 0f
                    for (i in values.indices) {
                        acc += ((values[i] / total) * 360.0).toFloat()
                        if (angle <= acc) return i
                    }
                    values.lastIndex
                }
                else -> {
                    val leftPad = dpF(38f)
                    val rightPad = dpF(10f)
                    val plotW = (width - leftPad - rightPad).coerceAtLeast(1f)
                    val frac = ((x - leftPad) / plotW).coerceIn(0f, 0.999f)
                    (frac * count).toInt().coerceIn(0, count - 1)
                }
            }
        }
    }

    companion object {
        private fun buildSeriesPalette(p: BrowserPalette): IntArray = intArrayOf(
            if (p.isMonochrome) (if (p.isDark) 0xFF60A5FA.toInt() else 0xFF2563EB.toInt()) else p.accent,
            if (p.isDark) 0xFF34D399.toInt() else 0xFF059669.toInt(),
            if (p.isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt(),
            if (p.isDark) 0xFFA78BFA.toInt() else 0xFF7C3AED.toInt(),
            if (p.isDark) 0xFFF472B6.toInt() else 0xFFDB2777.toInt(),
            if (p.isDark) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt()
        )

        private fun formatNumber(v: Double): String {
            val absV = kotlin.math.abs(v)
            return when {
                absV == kotlin.math.floor(absV) && absV < 1_000_000 -> String.format(Locale.US, "%,.0f", v)
                absV < 10 -> String.format(Locale.US, "%.2f", v)
                absV < 1000 -> String.format(Locale.US, "%.1f", v)
                else -> String.format(Locale.US, "%,.0f", v)
            }
        }

        private fun formatCompact(v: Double): String {
            val absV = kotlin.math.abs(v)
            return when {
                absV >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", v / 1_000_000_000.0)
                absV >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
                absV >= 10_000 -> String.format(Locale.US, "%.1fk", v / 1_000.0)
                absV == kotlin.math.floor(absV) -> String.format(Locale.US, "%.0f", v)
                else -> String.format(Locale.US, "%.1f", v)
            }
        }
    }
}
