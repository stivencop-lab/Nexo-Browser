package com.nexo.browser.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Parser puro de contenido enriquecido de Nexo AI (sin dependencias de Android, probado en JVM).
 *
 * Convierte respuestas Markdown y bloques estructurados (`nexo-chart`, `nexo-card`) en un AST
 * seguro para renderizado nativo en Android Views + Canvas:
 * - Elimina los `**`, `*`, `#`, `` ` `` crudos (incluso si llegan incompletos o sin cerrar).
 * - Soporta encabezados, párrafos, listas con viñetas/numeradas, citas, divisores, tablas,
 *   bloques de código con lenguaje, gráficos nativos (`bar`, `horizontal_bar`, `line`, `area`,
 *   `pie`, `donut`, `comparison`) y tarjetas de métricas (`nexo-card`).
 * - Oculta bloques de protocolo internos (`nexo-actions`, `nexo-tool`) y bloques incompletos
 *   durante el streaming SSE en vivo.
 */
object NexoAiRichContent {

    sealed class Inline {
        data class Text(val value: String) : Inline()
        data class Bold(val value: String) : Inline()
        data class Italic(val value: String) : Inline()
        data class BoldItalic(val value: String) : Inline()
        data class Strike(val value: String) : Inline()
        data class Code(val value: String) : Inline()
        data class Link(val label: String, val url: String) : Inline()
    }

    data class ChartSeries(
        val name: String,
        val values: List<Double>
    )

    data class ChartSpec(
        val type: String, // "bar" | "horizontal_bar" | "line" | "area" | "pie" | "donut" | "comparison"
        val title: String,
        val subtitle: String = "",
        val unit: String = "",
        val labels: List<String>,
        val series: List<ChartSeries>
    )

    data class CardMetric(
        val label: String,
        val value: String,
        val delta: String = "",
        val tone: String = "neutral" // "neutral" | "positive" | "negative" | "accent"
    )

    data class CardSpec(
        val title: String,
        val subtitle: String = "",
        val badge: String = "",
        val metrics: List<CardMetric> = emptyList(),
        val items: List<String> = emptyList()
    )

    sealed class Block {
        data class Heading(val level: Int, val inlines: List<Inline>) : Block()
        data class Paragraph(val inlines: List<Inline>) : Block()
        data class BulletList(val items: List<List<Inline>>) : Block()
        data class OrderedList(val items: List<Pair<Int, List<Inline>>>) : Block()
        data class Blockquote(val inlines: List<Inline>, val callout: String? = null) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Table(val headers: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block()
        data class Chart(val spec: ChartSpec) : Block()
        data class Card(val spec: CardSpec) : Block()
        object Divider : Block()
    }

    private val INTERNAL_FENCES = setOf("nexo-actions", "nexo-tool", "nexo-agent")
    private val RICH_FENCES = setOf("nexo-chart", "nexo-card")
    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val OPEN_THINK_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.+)$")
    private val BULLET_REGEX = Regex("^\\s*[-*•]\\s+(.+)$")
    private val ORDERED_REGEX = Regex("^\\s*(\\d{1,3})[.)]\\s+(.+)$")
    private val DIVIDER_REGEX = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val TABLE_SEP_REGEX = Regex("^\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*:?-{2,}:?\\s*\\|?$")

    /**
     * Limpia el texto durante streaming SSE para que el usuario nunca vea JSON crudo de
     * `nexo-actions`, `nexo-tool`, `nexo-chart` o `nexo-card` sin cerrar ni etiquetas `<think>`.
     */
    fun prepareStreamingText(raw: String): Pair<String, String?> {
        var text = THINK_BLOCK_REGEX.replace(raw, "")
        var pendingHint: String? = null
        if (OPEN_THINK_REGEX.containsMatchIn(text)) {
            text = OPEN_THINK_REGEX.replace(text, "")
            pendingHint = "Razonando…"
        }
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<String>(lines.size)
        var skipFence: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (skipFence == null) {
                if (trimmed.startsWith("```")) {
                    val lang = trimmed.removePrefix("```").trim().lowercase()
                    if (lang in INTERNAL_FENCES || lang in RICH_FENCES) {
                        skipFence = lang
                        if (lang == "nexo-chart") pendingHint = "Preparando gráfico…"
                        else if (lang == "nexo-card") pendingHint = "Preparando resumen visual…"
                        else if (lang == "nexo-tool" || lang == "nexo-agent") pendingHint = "Ejecutando acción del agente…"
                        continue
                    }
                }
                out += line
            } else {
                if (trimmed == "```") {
                    if (skipFence in RICH_FENCES) {
                        // Bloque visual completo: volver a incluirlo (se procesará con parse())
                        // No lo omitimos cuando ya está completo; este método solo oculta bloques abiertos o internos.
                    }
                    skipFence = null
                    pendingHint = null
                }
            }
        }
        // Para bloques ricos ya cerrados, conservarlos en la salida final; solo cortar el último abierto y los internos.
        if (skipFence != null) {
            return stripInternalBlocks(out.joinToString("\n")).trimEnd() to pendingHint
        }
        return stripInternalBlocks(text).trim() to pendingHint
    }

    /** Elimina bloques internos (`nexo-actions`, `nexo-tool`, `<think>`) ya cerrados del texto visible. */
    fun stripInternalBlocks(raw: String): String {
        var s = THINK_BLOCK_REGEX.replace(raw, "")
        s = OPEN_THINK_REGEX.replace(s, "")
        for (fence in INTERNAL_FENCES) {
            s = Regex("```${Regex.escape(fence)}\\b[\\s\\S]*?```", RegexOption.IGNORE_CASE).replace(s, "")
            s = Regex("```${Regex.escape(fence)}\\b[\\s\\S]*$", RegexOption.IGNORE_CASE).replace(s, "")
        }
        return s.trim()
    }

    /** Convierte Markdown a texto plano limpio (para vistas previas del historial). */
    fun toPlainPreview(raw: String, maxChars: Int = 120): String {
        val blocks = parse(raw)
        val sb = StringBuilder()
        for (b in blocks) {
            val piece = when (b) {
                is Block.Heading -> inlinesToPlain(b.inlines)
                is Block.Paragraph -> inlinesToPlain(b.inlines)
                is Block.BulletList -> b.items.joinToString(" · ") { inlinesToPlain(it) }
                is Block.OrderedList -> b.items.joinToString(" · ") { "${it.first}. ${inlinesToPlain(it.second)}" }
                is Block.Blockquote -> inlinesToPlain(b.inlines)
                is Block.Chart -> "[Gráfico: ${b.spec.title}]"
                is Block.Card -> "[${b.spec.title}]"
                is Block.Table -> "[Tabla: ${b.headers.joinToString(" / ") { inlinesToPlain(it) }}]"
                is Block.CodeBlock -> b.code.lineSequence().firstOrNull()?.trim().orEmpty()
                Block.Divider -> ""
            }.trim()
            if (piece.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(piece)
            }
            if (sb.length >= maxChars) break
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(maxChars)
    }

    fun inlinesToPlain(inlines: List<Inline>): String = buildString {
        for (i in inlines) {
            when (i) {
                is Inline.Text -> append(i.value)
                is Inline.Bold -> append(i.value)
                is Inline.Italic -> append(i.value)
                is Inline.BoldItalic -> append(i.value)
                is Inline.Strike -> append(i.value)
                is Inline.Code -> append(i.value)
                is Inline.Link -> append(i.label)
            }
        }
    }

    /** Parsea texto Markdown + bloques `nexo-chart` / `nexo-card` a bloques estructurados. */
    fun parse(raw: String): List<Block> {
        val cleaned = stripInternalBlocks(raw).replace("\r\n", "\n").replace('\r', '\n')
        if (cleaned.isBlank()) return emptyList()
        val lines = cleaned.split('\n')
        val blocks = ArrayList<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Bloques delimitados ```lang ... ```
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim().lowercase()
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != "```") {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                if (i < lines.size && lines[i].trim() == "```") i++
                val content = body.toString().trim()
                when (lang) {
                    in INTERNAL_FENCES -> Unit
                    "nexo-chart" -> {
                        val chart = parseChartSpec(content)
                        if (chart != null) blocks += Block.Chart(chart)
                        else if (content.isNotBlank()) blocks += Block.CodeBlock("json", content)
                    }
                    "nexo-card" -> {
                        val card = parseCardSpec(content)
                        if (card != null) blocks += Block.Card(card)
                        else if (content.isNotBlank()) blocks += Block.CodeBlock("json", content)
                    }
                    else -> {
                        if (content.isNotEmpty()) blocks += Block.CodeBlock(lang, content)
                    }
                }
                continue
            }

            // 2. Divisores horizontales --- / *** / ___
            if (DIVIDER_REGEX.matches(trimmed)) {
                blocks += Block.Divider
                i++
                continue
            }

            // 3. Encabezados # .. ####
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 4)
                val title = headingMatch.groupValues[2].trim().trimEnd('#').trim()
                blocks += Block.Heading(level, parseInlines(title))
                i++
                continue
            }

            // 4. Tablas Markdown (línea con | seguida de separador |---|---|)
            if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP_REGEX.matches(lines[i + 1].trim())) {
                val headers = splitTableRow(trimmed).map { parseInlines(it) }
                i += 2
                val rows = ArrayList<List<List<Inline>>>()
                while (i < lines.size) {
                    val rowTrimmed = lines[i].trim()
                    if (rowTrimmed.isEmpty() || !rowTrimmed.contains('|')) break
                    val cells = splitTableRow(rowTrimmed)
                    if (cells.isNotEmpty()) {
                        val padded = List(headers.size) { idx -> parseInlines(cells.getOrElse(idx) { "" }) }
                        rows += padded
                    }
                    i++
                }
                if (headers.isNotEmpty()) {
                    blocks += Block.Table(headers, rows)
                }
                continue
            }

            // 5. Citas > ...
            if (trimmed.startsWith(">")) {
                val quoteLines = ArrayList<String>()
                var callout: String? = null
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    val q = lines[i].trim().removePrefix(">").trim()
                    val calloutMatch = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]$", RegexOption.IGNORE_CASE).matchEntire(q)
                    if (calloutMatch != null && quoteLines.isEmpty()) {
                        callout = calloutMatch.groupValues[1].uppercase()
                    } else if (q.isNotEmpty()) {
                        quoteLines += q
                    }
                    i++
                }
                if (quoteLines.isNotEmpty()) {
                    blocks += Block.Blockquote(parseInlines(quoteLines.joinToString("\n")), callout)
                }
                continue
            }

            // 6. Lista con viñetas
            if (BULLET_REGEX.matches(line)) {
                val items = ArrayList<List<Inline>>()
                while (i < lines.size) {
                    val m = BULLET_REGEX.matchEntire(lines[i]) ?: break
                    items += parseInlines(m.groupValues[1].trim())
                    i++
                }
                if (items.isNotEmpty()) blocks += Block.BulletList(items)
                continue
            }

            // 7. Lista numerada
            if (ORDERED_REGEX.matches(line)) {
                val items = ArrayList<Pair<Int, List<Inline>>>()
                while (i < lines.size) {
                    val m = ORDERED_REGEX.matchEntire(lines[i]) ?: break
                    val num = m.groupValues[1].toIntOrNull() ?: (items.size + 1)
                    items += num to parseInlines(m.groupValues[2].trim())
                    i++
                }
                if (items.isNotEmpty()) blocks += Block.OrderedList(items)
                continue
            }

            // 8. Párrafo continuo hasta línea en blanco o inicio de bloque especial
            val paraLines = ArrayList<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    curTrim.startsWith("```") ||
                    DIVIDER_REGEX.matches(curTrim) ||
                    HEADING_REGEX.matches(curTrim) ||
                    curTrim.startsWith(">") ||
                    BULLET_REGEX.matches(cur) ||
                    ORDERED_REGEX.matches(cur) ||
                    (curTrim.contains('|') && i + 1 < lines.size && TABLE_SEP_REGEX.matches(lines[i + 1].trim()))
                ) break
                paraLines += curTrim
                i++
            }
            if (paraLines.isNotEmpty()) {
                val joined = paraLines.joinToString("\n")
                val inlines = parseInlines(joined)
                if (inlines.isNotEmpty()) blocks += Block.Paragraph(inlines)
            }
        }
        return blocks
    }

    private fun splitTableRow(line: String): List<String> {
        val core = line.trim().removePrefix("|").removeSuffix("|")
        return core.split('|').map { it.trim() }
    }

    /**
     * Parsea formato en línea (`***`, `**`, `__`, `*`, `_`, `~~`, `` ` ``, `[texto](url)`)
     * y limpia cualquier `**` sobrante/no emparejado para que nunca aparezcan asteriscos crudos.
     */
    fun parseInlines(input: String): List<Inline> {
        if (input.isEmpty()) return emptyList()
        val out = ArrayList<Inline>()
        val plain = StringBuilder()
        var i = 0
        val n = input.length

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                // Limpiar posibles marcadores `**` huérfanos en streaming parcial o respuestas mal cerradas
                val cleaned = plain.toString().replace("**", "")
                if (cleaned.isNotEmpty()) out += Inline.Text(cleaned)
                plain.setLength(0)
            }
        }

        while (i < n) {
            // Escape \* \_ \`
            if (input[i] == '\\' && i + 1 < n && input[i + 1] in charArrayOf('*', '_', '`', '~', '[', ']')) {
                plain.append(input[i + 1])
                i += 2
                continue
            }

            // Código inline `...`
            if (input[i] == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    out += Inline.Code(input.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // Enlace [label](url)
            if (input[i] == '[') {
                val closeBracket = input.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && input[closeBracket + 1] == '(') {
                    val closeParen = input.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = input.substring(i + 1, closeBracket).trim()
                        val rawUrl = input.substring(closeBracket + 2, closeParen).trim()
                        val safeUrl = safeHttpUrl(rawUrl)
                        if (label.isNotEmpty() && safeUrl != null) {
                            flushPlain()
                            out += Inline.Link(label.replace("**", ""), safeUrl)
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }

            // Negrita + cursiva ***...***
            if (i + 2 < n && input[i] == '*' && input[i + 1] == '*' && input[i + 2] == '*') {
                val end = input.indexOf("***", i + 3)
                if (end > i + 3) {
                    flushPlain()
                    out += Inline.BoldItalic(input.substring(i + 3, end).trim())
                    i = end + 3
                    continue
                }
            }

            // Negrita **...** o __...__
            if (i + 1 < n && ((input[i] == '*' && input[i + 1] == '*') || (input[i] == '_' && input[i + 1] == '_'))) {
                val delim = input.substring(i, i + 2)
                val end = input.indexOf(delim, i + 2)
                if (end > i + 2) {
                    val inner = input.substring(i + 2, end)
                    if (inner.isNotBlank()) {
                        flushPlain()
                        out += Inline.Bold(inner)
                        i = end + 2
                        continue
                    }
                } else {
                    // Marcador `**` abierto sin cierre (p. ej. streaming en curso o error del modelo):
                    // renderizar el resto del segmento en negrita si empieza una palabra, o saltar el `**`.
                    val rest = input.substring(i + 2)
                    if (rest.isNotBlank() && !rest.startsWith(" ")) {
                        flushPlain()
                        out += Inline.Bold(rest.replace("**", ""))
                        i = n
                        continue
                    } else {
                        i += 2
                        continue
                    }
                }
            }

            // Tachado ~~...~~
            if (i + 1 < n && input[i] == '~' && input[i + 1] == '~') {
                val end = input.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out += Inline.Strike(input.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // Cursiva *...* (solo si no sigue espacio y tiene cierre antes de salto de línea)
            if (input[i] == '*' && i + 1 < n && !input[i + 1].isWhitespace()) {
                val end = findClosingSingle(input, '*', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    out += Inline.Italic(input.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // Cursiva _..._ (respetando bordes de palabra para no dañar snake_case)
            if (input[i] == '_' && i + 1 < n && !input[i + 1].isWhitespace() &&
                (i == 0 || !input[i - 1].isLetterOrDigit())
            ) {
                val end = findClosingSingle(input, '_', i + 1)
                if (end > i + 1 && (end + 1 == n || !input[end + 1].isLetterOrDigit())) {
                    flushPlain()
                    out += Inline.Italic(input.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            plain.append(input[i])
            i++
        }
        flushPlain()
        return out
    }

    private fun findClosingSingle(s: String, ch: Char, start: Int): Int {
        var j = start
        while (j < s.length) {
            if (s[j] == '\n') return -1
            if (s[j] == ch && (j + 1 >= s.length || s[j + 1] != ch) && !s[j - 1].isWhitespace()) {
                return j
            }
            j++
        }
        return -1
    }

    private fun safeHttpUrl(raw: String): String? {
        if (raw.length > 512) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.userInfo.isNullOrEmpty() || uri.host.isNullOrBlank()) return null
        return raw
    }

    /**
     * Parsea un bloque ```nexo-chart JSON a [ChartSpec].
     * Acepta dos formatos ergonómicos:
     * 1) `{"type":"bar|line|area|pie|donut|horizontal_bar|comparison","title":"...","labels":[...],"series":[{"name":"...","values":[...]}]}`
     * 2) `{"type":"bar","title":"...","data":[{"label":"A","value":42},{"label":"B","value":58}]}`
     */
    fun parseChartSpec(rawJson: String): ChartSpec? = runCatching {
        val obj = JSONObject(rawJson.trim())
        val rawType = obj.optString("type", "bar").trim().lowercase()
        val type = when (rawType) {
            "bar", "bars", "column" -> "bar"
            "horizontal_bar", "hbar", "horizontal" -> "horizontal_bar"
            "line", "lines", "trend" -> "line"
            "area" -> "area"
            "pie" -> "pie"
            "donut", "doughnut" -> "donut"
            "comparison", "compare" -> "comparison"
            else -> "bar"
        }
        val title = obj.optString("title").trim().take(90).ifBlank { "Gráfico" }
        val subtitle = obj.optString("subtitle").trim().take(120)
        val unit = obj.optString("unit").trim().take(20)

        val labels = ArrayList<String>()
        val series = ArrayList<ChartSeries>()

        val dataArr = obj.optJSONArray("data")
        if (dataArr != null && dataArr.length() > 0) {
            val values = ArrayList<Double>()
            for (k in 0 until minOf(dataArr.length(), 16)) {
                val pt = dataArr.optJSONObject(k) ?: continue
                val lbl = pt.optString("label").ifBlank { pt.optString("name") }.trim().take(28)
                val v = pt.optDouble("value", Double.NaN)
                if (lbl.isNotEmpty() && !v.isNaN() && v.isFinite()) {
                    labels += lbl
                    values += v
                }
            }
            if (labels.isNotEmpty()) {
                series += ChartSeries(obj.optString("seriesName").trim().take(32), values)
            }
        } else {
            val labelsArr = obj.optJSONArray("labels") ?: JSONArray()
            for (k in 0 until minOf(labelsArr.length(), 16)) {
                labels += labelsArr.optString(k).trim().take(28)
            }
            val seriesArr = obj.optJSONArray("series")
            if (seriesArr != null) {
                for (sIdx in 0 until minOf(seriesArr.length(), 5)) {
                    val sObj = seriesArr.optJSONObject(sIdx) ?: continue
                    val name = sObj.optString("name").trim().take(32)
                    val valsArr = sObj.optJSONArray("values") ?: continue
                    val vals = ArrayList<Double>()
                    for (vIdx in 0 until minOf(valsArr.length(), 16)) {
                        val v = valsArr.optDouble(vIdx, Double.NaN)
                        vals += if (v.isNaN() || !v.isFinite()) 0.0 else v
                    }
                    if (vals.isNotEmpty()) series += ChartSeries(name, vals)
                }
            } else {
                val valsArr = obj.optJSONArray("values")
                if (valsArr != null) {
                    val vals = ArrayList<Double>()
                    for (vIdx in 0 until minOf(valsArr.length(), 16)) {
                        val v = valsArr.optDouble(vIdx, Double.NaN)
                        vals += if (v.isNaN() || !v.isFinite()) 0.0 else v
                    }
                    if (vals.isNotEmpty()) series += ChartSeries("", vals)
                }
            }
        }

        if (series.isEmpty() || series.all { it.values.isEmpty() }) return null
        val pointCount = maxOf(labels.size, series.maxOf { it.values.size }).coerceAtMost(16)
        if (pointCount == 0) return null
        val normalizedLabels = List(pointCount) { idx -> labels.getOrElse(idx) { "${idx + 1}" }.ifBlank { "${idx + 1}" } }
        val normalizedSeries = series.map { s ->
            ChartSeries(s.name, List(pointCount) { idx -> s.values.getOrElse(idx) { 0.0 } })
        }
        ChartSpec(
            type = type,
            title = title,
            subtitle = subtitle,
            unit = unit,
            labels = normalizedLabels,
            series = normalizedSeries
        )
    }.getOrNull()

    /** Parsea un bloque ```nexo-card JSON a [CardSpec] para KPIs o resúmenes estructurados. */
    fun parseCardSpec(rawJson: String): CardSpec? = runCatching {
        val obj = JSONObject(rawJson.trim())
        val title = obj.optString("title").trim().take(90)
        val subtitle = obj.optString("subtitle").trim().take(140)
        val badge = obj.optString("badge").trim().take(28)
        val metrics = ArrayList<CardMetric>()
        obj.optJSONArray("metrics")?.let { arr ->
            for (k in 0 until minOf(arr.length(), 6)) {
                val m = arr.optJSONObject(k) ?: continue
                val label = m.optString("label").trim().take(40)
                val value = m.optString("value").trim().take(36)
                if (label.isNotEmpty() && value.isNotEmpty()) {
                    val tone = m.optString("tone", "neutral").trim().lowercase()
                        .takeIf { it in setOf("neutral", "positive", "negative", "accent") } ?: "neutral"
                    metrics += CardMetric(label, value, m.optString("delta").trim().take(24), tone)
                }
            }
        }
        val items = ArrayList<String>()
        obj.optJSONArray("items")?.let { arr ->
            for (k in 0 until minOf(arr.length(), 8)) {
                val it = arr.optString(k).trim().take(160)
                if (it.isNotEmpty()) items += it
            }
        }
        if (title.isBlank() && metrics.isEmpty() && items.isEmpty()) return null
        CardSpec(title = title.ifBlank { "Resumen" }, subtitle = subtitle, badge = badge, metrics = metrics, items = items)
    }.getOrNull()
}
