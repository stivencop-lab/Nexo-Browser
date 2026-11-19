package com.nexo.browser

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Modelo puro (comprobable en JVM) para Nexo Tabs Pro:
 * - Modos de vista (Cuadrícula, Compacta, Lista).
 * - Búsqueda instantánea sin acentos.
 * - Ordenación con pestañas fijadas primero (sin bloquear su descarga de memoria en segundo plano).
 * - Detección de URLs duplicadas sin cerrar ninguna automáticamente.
 */
object TabProModel {
    enum class ViewMode(val id: String, val label: String) {
        GRID("grid", "Cuadrícula"),
        COMPACT("compact", "Compacta"),
        LIST("list", "Lista");

        companion object {
            fun fromId(raw: String?): ViewMode =
                entries.firstOrNull { it.id == raw } ?: GRID
        }
    }

    data class TabSummary(
        val id: String,
        val title: String,
        val url: String,
        val groupName: String?,
        val isPrivate: Boolean,
        val pinned: Boolean
    )

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
    }

    /**
     * Normaliza una URL web para detectar duplicados reales (ignora mayúsculas en esquema/host,
     * barra final redundante y fragmento `#...`). Devuelve null para inicio/internas.
     */
    fun canonicalUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) return null
        if (trimmed.startsWith("nexo://", ignoreCase = true) || trimmed.startsWith("resource://", ignoreCase = true)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val port = if (uri.port == -1 || (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)) {
            ""
        } else ":${uri.port}"
        val rawPath = uri.rawPath.orEmpty()
        val path = if (rawPath.length > 1) rawPath.trimEnd('/') else ""
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$path$query"
    }

    /**
     * Cuenta ocurrencias por URL canónica y devuelve un mapa `tabId -> totalCopias`
     * solo para pestañas que comparten URL con al menos otra pestaña del mismo conjunto.
     * Nunca cierra ni elimina pestañas automáticamente.
     */
    fun duplicateCounts(tabs: List<TabSummary>): Map<String, Int> {
        val grouped = HashMap<String, MutableList<String>>()
        for (tab in tabs) {
            val key = canonicalUrl(tab.url) ?: continue
            grouped.getOrPut(key) { ArrayList() }.add(tab.id)
        }
        val result = HashMap<String, Int>()
        for ((_, ids) in grouped) {
            if (ids.size >= 2) {
                for (id in ids) result[id] = ids.size
            }
        }
        return result
    }

    /** Número total de pestañas que tienen al menos un duplicado en la lista visible. */
    fun duplicateGroupCount(tabs: List<TabSummary>): Int {
        val counts = HashMap<String, Int>()
        for (tab in tabs) {
            val key = canonicalUrl(tab.url) ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.values.count { it >= 2 }
    }

    /**
     * Filtra por modo (normal/privado), grupo, búsqueda instantánea y filtro opcional de duplicadas,
     * colocando las pestañas fijadas primero preservando el orden relativo de cada bloque.
     */
    fun <T> organize(
        tabs: List<T>,
        showPrivate: Boolean,
        selectedGroup: String?,
        query: String,
        onlyDuplicates: Boolean,
        toSummary: (T) -> TabSummary
    ): Pair<List<T>, Map<String, Int>> {
        val sameMode = tabs.filter { toSummary(it).isPrivate == showPrivate }
        val dupes = duplicateCounts(sameMode.map(toSummary))
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }

        val filtered = sameMode.filter { item ->
            val s = toSummary(item)
            val groupOk = selectedGroup == null || s.groupName.orEmpty() == selectedGroup
            val dupOk = !onlyDuplicates || (dupes[s.id] ?: 0) >= 2
            val queryOk = if (tokens.isEmpty()) true else {
                val haystack = normalize("${s.title} ${s.url} ${s.groupName.orEmpty()}")
                tokens.all { haystack.contains(it) }
            }
            groupOk && dupOk && queryOk
        }

        val pinned = filtered.filter { toSummary(it).pinned }
        val unpinned = filtered.filterNot { toSummary(it).pinned }
        return (pinned + unpinned) to dupes
    }

    /** Indica si conviene precargar miniaturas pesadas según el modo de vista. */
    fun shouldPrefetchPreviews(mode: ViewMode): Boolean = mode == ViewMode.GRID
}
