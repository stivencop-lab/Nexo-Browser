package com.nexo.browser.workspace

import android.content.Context
import com.nexo.browser.NexoCommandEngine
import com.nexo.browser.UrlSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Almacén local de favoritos de Nexo Workspace con carpetas, búsqueda insensible a tildes,
 * escritura atómica e importación/exportación estándar (Netscape HTML y JSON).
 *
 * Seguridad:
 * - Solo admite direcciones web HTTP/HTTPS ([UrlSafety.isWebUrl]); rechaza `javascript:`, `data:` o `file:`.
 * - Escapa entidades HTML al exportar y sanea texto de control al importar.
 * - Puro sobre [File]: verificable directamente en pruebas JVM.
 */
object BookmarkStore {
    const val FILE_NAME = "nexo_bookmarks.json"
    const val DEFAULT_FOLDER = "General"
    const val MAX_BOOKMARKS = 2000

    data class Bookmark(
        val id: String,
        val title: String,
        val url: String,
        val folder: String = DEFAULT_FOLDER,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val lock = Any()

    fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun list(context: Context): List<Bookmark> = list(file(context))

    fun list(file: File): List<Bookmark> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        val arr = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until minOf(arr.length(), MAX_BOOKMARKS)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (!UrlSafety.isWebUrl(url)) continue
            val id = o.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
            val title = sanitize(o.optString("title"), 180).ifBlank { url }
            val folder = sanitizeFolder(o.optString("folder"))
            val created = o.optLong("createdAtMs", 0L)
            out.add(Bookmark(id, title, url, folder, created))
        }
        out.sortedByDescending { it.createdAtMs }
    }

    fun folders(file: File): List<String> {
        val set = linkedSetOf(DEFAULT_FOLDER)
        list(file).forEach { set.add(it.folder) }
        return set.toList()
    }

    fun folders(context: Context): List<String> = folders(file(context))

    fun findByUrl(file: File, url: String?): Bookmark? {
        val target = normalizeUrl(url) ?: return null
        return list(file).firstOrNull { normalizeUrl(it.url) == target }
    }

    fun isBookmarked(context: Context, url: String?): Boolean = findByUrl(file(context), url) != null

    fun search(file: File, query: String, folder: String? = null): List<Bookmark> =
        search(list(file), query, folder)

    fun search(bookmarks: List<Bookmark>, query: String, folder: String? = null): List<Bookmark> {
        val items = bookmarks.let { all ->
            if (folder.isNullOrBlank()) all else all.filter { it.folder.equals(folder, ignoreCase = true) }
        }
        val terms = normalizeSearch(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return items
        return items.filter { bm ->
            val hay = normalizeSearch("${bm.title} ${bm.url} ${bm.folder}")
            terms.all { hay.contains(it) }
        }
    }

    fun add(
        file: File,
        title: String,
        url: String,
        folder: String = DEFAULT_FOLDER,
        nowMs: Long = System.currentTimeMillis()
    ): Bookmark? = synchronized(lock) {
        val cleanUrl = url.trim().take(4096)
        if (!UrlSafety.isWebUrl(cleanUrl)) return null
        val cleanTitle = sanitize(title, 180).ifBlank { cleanUrl }
        val cleanFolder = sanitizeFolder(folder)
        val current = list(file).toMutableList()
        val key = normalizeUrl(cleanUrl)
        val existingIdx = current.indexOfFirst { normalizeUrl(it.url) == key }
        val saved = if (existingIdx >= 0) {
            current[existingIdx].copy(title = cleanTitle, url = cleanUrl, folder = cleanFolder)
                .also { current[existingIdx] = it }
        } else {
            val created = Bookmark(UUID.randomUUID().toString(), cleanTitle, cleanUrl, cleanFolder, nowMs)
            current.add(0, created)
            created
        }
        persist(file, current.take(MAX_BOOKMARKS))
        saved
    }

    fun add(context: Context, title: String, url: String, folder: String = DEFAULT_FOLDER): Bookmark? =
        add(file(context), title, url, folder)

    fun update(file: File, id: String, title: String, url: String, folder: String): Boolean = synchronized(lock) {
        val cleanUrl = url.trim().take(4096)
        if (!UrlSafety.isWebUrl(cleanUrl)) return false
        val current = list(file).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return false
        current[idx] = current[idx].copy(
            title = sanitize(title, 180).ifBlank { cleanUrl },
            url = cleanUrl,
            folder = sanitizeFolder(folder)
        )
        persist(file, current)
        true
    }

    fun delete(file: File, id: String): Boolean = synchronized(lock) {
        val current = list(file).toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) persist(file, current)
        removed
    }

    fun delete(context: Context, id: String): Boolean = delete(file(context), id)

    /** Alterna el estado de favorito para [url]. Devuelve `true` si se añadió, `false` si se quitó. */
    fun toggle(file: File, title: String, url: String, folder: String = DEFAULT_FOLDER): Boolean = synchronized(lock) {
        val existing = findByUrl(file, url)
        if (existing != null) {
            delete(file, existing.id)
            false
        } else {
            add(file, title, url, folder) != null
        }
    }

    fun toggle(context: Context, title: String, url: String, folder: String = DEFAULT_FOLDER): Boolean =
        toggle(file(context), title, url, folder)

    fun toCommandCandidates(context: Context): List<NexoCommandEngine.BookmarkCandidate> =
        list(context).map { NexoCommandEngine.BookmarkCandidate(it.id, it.title, it.url, it.folder) }

    // -------------------------------------------------------------------------
    // Exportación / Importación estándar (JSON + Netscape Bookmark HTML)
    // -------------------------------------------------------------------------

    fun exportJson(bookmarks: List<Bookmark>): String {
        val root = JSONObject()
            .put("format", "nexo-bookmarks-v1")
            .put("exportedAtMs", System.currentTimeMillis())
        val arr = JSONArray()
        bookmarks.forEach { bm ->
            arr.put(JSONObject()
                .put("title", bm.title)
                .put("url", bm.url)
                .put("folder", bm.folder)
                .put("createdAtMs", bm.createdAtMs))
        }
        root.put("bookmarks", arr)
        return root.toString(2)
    }

    fun importJson(file: File, rawJson: String): Int = synchronized(lock) {
        val trimmed = rawJson.trim()
        val arr = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("bookmarks")
        }.getOrNull() ?: return 0
        val current = list(file).toMutableList()
        val existingUrls = current.mapNotNull { normalizeUrl(it.url) }.toMutableSet()
        var added = 0
        val baseTime = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            if (current.size >= MAX_BOOKMARKS) break
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim().take(4096)
            if (!UrlSafety.isWebUrl(url)) continue
            val key = normalizeUrl(url) ?: continue
            if (!existingUrls.add(key)) continue
            val title = sanitize(o.optString("title"), 180).ifBlank { url }
            val folder = sanitizeFolder(o.optString("folder"))
            val created = o.optLong("createdAtMs", baseTime - i)
            current.add(Bookmark(UUID.randomUUID().toString(), title, url, folder, created))
            added++
        }
        if (added > 0) persist(file, current)
        added
    }

    fun exportNetscapeHtml(bookmarks: List<Bookmark>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<!-- Exportado por Nexo Browser -->")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Favoritos de Nexo Browser</TITLE>")
        appendLine("<H1>Favoritos</H1>")
        appendLine("<DL><p>")
        val grouped = bookmarks.groupBy { it.folder }
        for ((folder, items) in grouped) {
            appendLine("    <DT><H3>${escapeHtml(folder)}</H3>")
            appendLine("    <DL><p>")
            for (bm in items) {
                val addDate = (bm.createdAtMs / 1000L).coerceAtLeast(0L)
                appendLine("        <DT><A HREF=\"${escapeHtml(bm.url)}\" ADD_DATE=\"$addDate\">${escapeHtml(bm.title)}</A>")
            }
            appendLine("    </DL><p>")
        }
        appendLine("</DL><p>")
    }

    private val HTML_TOKEN_RE = Regex(
        """<H3\b[^>]*>(.*?)</H3>|</DL>|<A\b([^>]*)>(.*?)</A>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HREF_ATTR_RE = Regex("""\bHREF\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    fun importNetscapeHtml(file: File, html: String): Int = synchronized(lock) {
        val current = list(file).toMutableList()
        val existingUrls = current.mapNotNull { normalizeUrl(it.url) }.toMutableSet()
        val folderStack = ArrayDeque<String>()
        var pendingFolder: String? = null
        var added = 0
        val baseTime = System.currentTimeMillis()

        for (match in HTML_TOKEN_RE.findAll(html)) {
            if (current.size >= MAX_BOOKMARKS) break
            val full = match.value
            when {
                full.startsWith("<H3", ignoreCase = true) -> {
                    val name = sanitizeFolder(unescapeHtml(match.groupValues[1]))
                    pendingFolder = name
                    folderStack.addLast(name)
                }
                full.startsWith("</DL", ignoreCase = true) -> {
                    if (folderStack.isNotEmpty()) folderStack.removeLast()
                    pendingFolder = folderStack.lastOrNull()
                }
                full.startsWith("<A", ignoreCase = true) -> {
                    val attrs = match.groupValues[2]
                    val hrefMatch = HREF_ATTR_RE.find(attrs) ?: continue
                    val rawHref = (hrefMatch.groupValues[1].ifEmpty { hrefMatch.groupValues[2] }.ifEmpty { hrefMatch.groupValues[3] }).trim()
                    val url = unescapeHtml(rawHref).take(4096)
                    if (!UrlSafety.isWebUrl(url)) continue
                    val key = normalizeUrl(url) ?: continue
                    if (!existingUrls.add(key)) continue
                    val title = sanitize(unescapeHtml(match.groupValues[3]), 180).ifBlank { url }
                    val folder = pendingFolder ?: DEFAULT_FOLDER
                    current.add(Bookmark(UUID.randomUUID().toString(), title, url, folder, baseTime - added))
                    added++
                }
            }
        }
        if (added > 0) persist(file, current)
        added
    }

    private fun persist(file: File, items: List<Bookmark>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        items.forEach { bm ->
            arr.put(JSONObject()
                .put("id", bm.id)
                .put("title", bm.title)
                .put("url", bm.url)
                .put("folder", bm.folder)
                .put("createdAtMs", bm.createdAtMs))
        }
        com.nexo.browser.writeAtomicFile(file, arr.toString().toByteArray(Charsets.UTF_8))
    }

    private fun normalizeUrl(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return UrlSafety.navigationKey(trimmed)
    }

    private fun sanitize(raw: String?, maxLen: Int): String =
        raw.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLen)

    private fun sanitizeFolder(raw: String?): String =
        sanitize(raw, 48).ifBlank { DEFAULT_FOLDER }

    private fun normalizeSearch(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun unescapeHtml(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("<[^>]+>"), "")
}
