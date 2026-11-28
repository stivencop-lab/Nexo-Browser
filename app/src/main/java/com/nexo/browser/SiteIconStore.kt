package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logos de sitios para accesos rápidos y pestañas. Se piden de forma anónima
 * (sin cookies ni referer, solo HTTPS) al propio sitio: primero los iconos que
 * declara su página (`apple-touch-icon`, `icon`), después `/apple-touch-icon.png`
 * y `/favicon.ico`. Sin servicios de terceros y nunca desde pestañas privadas.
 */
object SiteIconStore {
    private const val MAX_ICON_BYTES = 262_144
    private const val MAX_PAGE_BYTES = 160_000
    private const val ICON_PX = 96

    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val attempted = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private class Lookup {
        val budget = NativeImageNetwork.Budget(9_000)
        val listeners = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()
    }

    private class Subscriber(val deliver: (Bitmap) -> Unit) {
        @Volatile var cancelled = false
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Lookup>()
    private val memory = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val LETTER_COLORS = intArrayOf(
        0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFDC2626.toInt(),
        0xFFEA580C.toInt(), 0xFF16A34A.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(),
        0xFF0D9488.toInt(), 0xFF9333EA.toInt()
    )

    /** Color estable por sitio para la inicial de respaldo. */
    fun letterColor(host: String): Int {
        val key = host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        var hash = 0
        for (ch in key) hash = hash * 31 + ch.code
        return LETTER_COLORS[Math.floorMod(hash, LETTER_COLORS.size)]
    }

    fun letter(host: String): String =
        host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.").firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "•"

    private fun key(origin: String): String? {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return URI("https", null, uri.host.lowercase(Locale.ROOT), uri.port, "/", null, null).toASCIIString()
    }

    /** Icono ya cargado en memoria (para pintar sin esperas al reciclar tarjetas). */
    fun cached(origin: String): Bitmap? = key(origin)?.let { memory.get(it) }

    fun load(context: Context, origin: String, onIcon: (Bitmap) -> Unit): () -> Unit {
        val page = key(origin) ?: return {}
        memory.get(page)?.let { onIcon(it); return {} }
        val listener = Subscriber(onIcon)
        var first = false
        val flight = inFlight.compute(page) { _, existing ->
            (existing ?: Lookup().also { first = true }).apply { listeners.add(listener) }
        } ?: return {}
        if (first) {
            val dir = File(context.applicationContext.cacheDir, "site-icons")
            worker.execute {
                val icon = runCatching { find(dir, page, flight.budget) }.getOrNull()
                if (!flight.budget.cancelled) {
                    if (icon != null) memory.put(page, icon)
                }
                val listeners = if (inFlight.remove(page, flight)) flight.listeners.toList() else emptyList()
                if (icon != null) main.post {
                    listeners.forEach { if (!it.cancelled) runCatching { it.deliver(icon) } }
                }
            }
        }
        return {
            listener.cancelled = true
            inFlight.computeIfPresent(page) { _, current ->
                if (current !== flight || !current.listeners.remove(listener) || current.listeners.isNotEmpty()) current
                else {
                    current.budget.cancel()
                    null
                }
            }
        }
    }

    private fun find(dir: File, page: String, budget: NativeImageNetwork.Budget): Bitmap? {
        if (budget.exhausted) return null
        dir.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(page.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(dir, "$name.png")
        if (cached.isFile && cached.length() <= MAX_ICON_BYTES) {
            runCatching { decode(cached.readBytes()) }.getOrNull()?.let { return it }
        }
        val now = System.currentTimeMillis()
        if (now - (attempted[page] ?: 0L) < 3_600_000L) return null
        if (attempted.size > 250) attempted.entries.removeIf { now - it.value > 3_600_000L }

        val resource = runCatching {
            NativeImageNetwork.fetch(page, MAX_PAGE_BYTES, "text/html",
                sameSiteOnly = true, truncate = true, budget = budget)
        }.getOrNull()
        val finalUrl = resource?.url ?: page
        val html = resource?.bytes?.let { String(it, Charsets.UTF_8) }.orEmpty()
        for (candidate in SiteIconDiscovery.candidates(html, finalUrl)) {
            if (budget.exhausted) break
            val icon = runCatching {
                NativeImageNetwork.fetch(candidate, MAX_ICON_BYTES, "image/*", budget = budget)?.let { decode(it.bytes) }
            }.getOrNull() ?: continue
            if (icon.width < 8 || icon.height < 8) continue
            val scaled = Bitmap.createScaledBitmap(icon, ICON_PX, ICON_PX, true)
            runCatching { cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
            return scaled
        }
        if (!budget.cancelled) attempted[page] = now
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val payload = IconPayload.pngOrImage(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1) }
        return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }

    fun trimMemory(level: Int = 80) {
        when (MemoryPressure.cacheAction(level)) {
            MemoryPressure.CacheAction.CLEAR -> memory.evictAll()
            MemoryPressure.CacheAction.REDUCE -> memory.trimToSize(memory.maxSize() / 2)
            MemoryPressure.CacheAction.KEEP -> Unit
        }
    }
}
