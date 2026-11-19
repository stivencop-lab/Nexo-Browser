package com.nexo.browser.reader

import android.content.Context
import com.nexo.browser.UrlSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Almacén local acotado de artículos saneados de Nexo Reader para lectura sin conexión.
 *
 * Límites estrictos:
 * - Máximo [MAX_ARTICLES] artículos (50).
 * - Máximo [MAX_TOTAL_BYTES] en disco (10 MB).
 * - Solo almacena bloques de texto plano saneados y enlaces HTTP/HTTPS validados (nunca HTML/JS crudo).
 * - Escritura atómica (.tmp -> rename) y expulsión FIFO de los más antiguos al superar cuota.
 *
 * Puro sobre [File]: verificable directamente en pruebas JVM.
 */
object OfflineArticleStore {
    const val FOLDER = "nexo_reader"
    const val MAX_ARTICLES = 50
    const val MAX_TOTAL_BYTES = 10L * 1024L * 1024L
    const val MAX_SINGLE_ARTICLE_BYTES = 512L * 1024L

    data class ReaderLink(
        val label: String,
        val href: String
    )

    data class ReaderBlock(
        val type: String, // "h2" | "h3" | "p" | "quote" | "li" | "code"
        val text: String,
        val links: List<ReaderLink> = emptyList()
    )

    data class ReaderArticle(
        val id: String,
        val url: String,
        val title: String,
        val byline: String,
        val siteName: String,
        val lang: String,
        val wordCount: Int,
        val readingMinutes: Int,
        val savedAtMs: Long,
        val blocks: List<ReaderBlock>
    ) {
        fun plainText(maxChars: Int = 12_000): String = buildString {
            appendLine(title)
            if (byline.isNotBlank()) appendLine(byline)
            appendLine()
            for (b in blocks) {
                if (length >= maxChars) break
                appendLine(b.text)
                appendLine()
            }
        }.take(maxChars).trim()
    }

    private val ALLOWED_BLOCK_TYPES = setOf("h2", "h3", "p", "quote", "li", "code")

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, FOLDER).apply { mkdirs() }

    fun idForUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Convierte la respuesta JSON de `assets/nexo/reader-mode.js` en un [ReaderArticle] saneado. */
    fun parseFromExtractorJson(json: JSONObject, fallbackUrl: String, nowMs: Long = System.currentTimeMillis()): ReaderArticle? {
        if (!json.optBoolean("ok", false)) return null
        val rawUrl = json.optString("url").trim().ifBlank { fallbackUrl.trim() }
        val url = if (UrlSafety.isWebUrl(rawUrl)) UrlSafety.redactSensitiveQuery(rawUrl) else ""
        val title = sanitizeText(json.optString("title"), 200).ifBlank { "Artículo sin título" }
        val byline = sanitizeText(json.optString("byline"), 140)
        val siteName = sanitizeText(json.optString("siteName"), 80)
        val lang = sanitizeText(json.optString("lang"), 16).ifBlank { "es" }
        val blocksJson = json.optJSONArray("blocks") ?: return null
        val blocks = parseBlocks(blocksJson)
        if (blocks.isEmpty()) return null
        val words = json.optInt("wordCount", 0).takeIf { it > 0 }
            ?: blocks.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        val mins = json.optInt("readingMinutes", 0).takeIf { it > 0 }
            ?: maxOf(1, (words + 100) / 200)
        val id = idForUrl(url.ifBlank { title })
        return ReaderArticle(
            id = id,
            url = url,
            title = title,
            byline = byline,
            siteName = siteName,
            lang = lang,
            wordCount = words,
            readingMinutes = mins,
            savedAtMs = nowMs,
            blocks = blocks
        )
    }

    fun save(
        dir: File,
        article: ReaderArticle,
        maxArticles: Int = MAX_ARTICLES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES
    ): Boolean {
        if (article.blocks.isEmpty()) return false
        dir.mkdirs()
        val payload = toJson(article).toString().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SINGLE_ARTICLE_BYTES || payload.size > maxTotalBytes) return false

        val safeId = article.id.filter { it.isLetterOrDigit() }.take(32).ifBlank { idForUrl(article.title) }
        val target = File(dir, "$safeId.json")
        val tmp = File(dir, "$safeId.json.tmp")
        tmp.writeBytes(payload)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(payload)
            tmp.delete()
        }
        enforceQuota(dir, maxArticles, maxTotalBytes, protectedName = target.name)
        return true
    }

    fun list(dir: File): List<ReaderArticle> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
            ?.sortedByDescending { it.savedAtMs }
            ?: emptyList()
    }

    fun get(dir: File, id: String): ReaderArticle? {
        val safeId = id.filter { it.isLetterOrDigit() }.take(32)
        if (safeId.isEmpty()) return null
        val file = File(dir, "$safeId.json")
        if (!file.isFile) return null
        return runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
    }

    fun findByUrl(dir: File, url: String): ReaderArticle? {
        if (url.isBlank()) return null
        val clean = if (UrlSafety.isWebUrl(url)) UrlSafety.redactSensitiveQuery(url) else url
        return get(dir, idForUrl(clean)) ?: list(dir).firstOrNull { it.url == clean }
    }

    fun delete(dir: File, id: String): Boolean {
        val safeId = id.filter { it.isLetterOrDigit() }.take(32)
        if (safeId.isEmpty()) return false
        return File(dir, "$safeId.json").delete()
    }

    fun clear(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".tmp")) }
            ?.forEach { it.delete() }
    }

    fun totalBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sumOf { it.length() } ?: 0L
    }

    private fun enforceQuota(dir: File, maxArticles: Int, maxTotalBytes: Long, protectedName: String) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toMutableList() ?: return
        // Ordenar por fecha savedAtMs ascendente (más antiguos primero) para expulsión determinista.
        val timestamps = files.associateWith { file ->
            runCatching { JSONObject(file.readText(Charsets.UTF_8)).optLong("savedAtMs", file.lastModified()) }
                .getOrDefault(file.lastModified())
        }
        files.sortBy { timestamps[it] ?: 0L }

        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= maxArticles && total <= maxTotalBytes) break
            if (file.name == protectedName && count > 1) continue
            val len = file.length()
            if (file.delete()) {
                total -= len
                count--
            }
        }
    }

    private fun toJson(a: ReaderArticle): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("url", a.url)
        put("title", a.title)
        put("byline", a.byline)
        put("siteName", a.siteName)
        put("lang", a.lang)
        put("wordCount", a.wordCount)
        put("readingMinutes", a.readingMinutes)
        put("savedAtMs", a.savedAtMs)
        put("blocks", JSONArray().apply {
            a.blocks.forEach { b ->
                put(JSONObject().apply {
                    put("type", b.type)
                    put("text", b.text)
                    if (b.links.isNotEmpty()) {
                        put("links", JSONArray().apply {
                            b.links.forEach { l ->
                                put(JSONObject().put("label", l.label).put("href", l.href))
                            }
                        })
                    }
                })
            }
        })
    }

    private fun fromJson(obj: JSONObject): ReaderArticle? {
        val id = obj.optString("id").trim()
        val title = sanitizeText(obj.optString("title"), 200)
        val rawUrl = obj.optString("url").trim()
        val url = if (UrlSafety.isWebUrl(rawUrl)) rawUrl else ""
        val blocks = parseBlocks(obj.optJSONArray("blocks") ?: return null)
        if (id.isEmpty() || title.isEmpty() || blocks.isEmpty()) return null
        return ReaderArticle(
            id = id,
            url = url,
            title = title,
            byline = sanitizeText(obj.optString("byline"), 140),
            siteName = sanitizeText(obj.optString("siteName"), 80),
            lang = sanitizeText(obj.optString("lang"), 16).ifBlank { "es" },
            wordCount = obj.optInt("wordCount", 0).coerceAtLeast(1),
            readingMinutes = obj.optInt("readingMinutes", 1).coerceAtLeast(1),
            savedAtMs = obj.optLong("savedAtMs", 0L),
            blocks = blocks
        )
    }

    private fun parseBlocks(arr: JSONArray): List<ReaderBlock> {
        val out = ArrayList<ReaderBlock>(minOf(arr.length(), 400))
        for (i in 0 until minOf(arr.length(), 400)) {
            val item = arr.optJSONObject(i) ?: continue
            val type = item.optString("type").trim().lowercase().takeIf { it in ALLOWED_BLOCK_TYPES } ?: "p"
            val text = sanitizeText(item.optString("text"), 8000)
            if (text.isEmpty()) continue
            val linksArr = item.optJSONArray("links")
            val links = if (linksArr != null) {
                (0 until minOf(linksArr.length(), 6)).mapNotNull { j ->
                    val lo = linksArr.optJSONObject(j) ?: return@mapNotNull null
                    val href = lo.optString("href").trim()
                    val label = sanitizeText(lo.optString("label"), 120)
                    if (label.isNotEmpty() && UrlSafety.isWebUrl(href)) ReaderLink(label, href) else null
                }
            } else emptyList()
            out.add(ReaderBlock(type, text, links))
        }
        return out
    }

    private fun sanitizeText(raw: String?, maxLen: Int): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .trim()
            .take(maxLen)
    }
}
