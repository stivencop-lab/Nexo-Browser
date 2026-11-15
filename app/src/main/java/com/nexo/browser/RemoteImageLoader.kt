package com.nexo.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors
import java.lang.ref.WeakReference

/** Carga iconos remotos (tienda de extensiones) con límites de tamaño. */
object RemoteImageLoader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_DIMENSION = 512

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "NexoImageLoader").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun trimMemory(level: Int) {
        when (MemoryPressure.cacheAction(level)) {
            MemoryPressure.CacheAction.CLEAR -> cache.evictAll()
            MemoryPressure.CacheAction.REDUCE -> cache.trimToSize(cache.maxSize() / 2)
            MemoryPressure.CacheAction.KEEP -> Unit
        }
    }

    fun load(url: String, target: ImageView) {
        showPlaceholder(target)
        target.tag = url
        if (url.isBlank() || UrlSafety.scheme(url) != "https") return

        cache.get(url)?.let {
            showBitmap(target, it)
            return
        }

        val reference = WeakReference(target)
        pool.execute {
            val bitmap = runCatching { fetch(url) }.getOrNull() ?: return@execute

            cache.put(url, bitmap)
            main.post {
                reference.get()?.takeIf { it.tag == url }?.let { showBitmap(it, bitmap) }
            }
        }
    }

    /** El icono genérico se tiñe con el color secundario del tema para que se vea. */
    private fun showPlaceholder(target: ImageView) {
        target.setImageResource(R.drawable.ic_extension_placeholder)
        val activity = target.context as? android.app.Activity
        target.imageTintList = activity?.let {
            android.content.res.ColorStateList.valueOf(ThemeManager.palette(it).muted)
        }
    }

    /** Un icono real no debe teñirse. */
    private fun showBitmap(target: ImageView, bitmap: Bitmap) {
        target.imageTintList = null
        target.setImageBitmap(bitmap)
    }

    private fun fetch(url: String): Bitmap? =
        NativeImageNetwork.fetch(url, MAX_BYTES, "image/*")?.let { decodeBounded(it.bytes) }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
