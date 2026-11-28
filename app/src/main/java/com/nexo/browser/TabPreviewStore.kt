package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Miniaturas de pestañas: WebP en disco y una caché en memoria.
 *
 * - Solo se guarda la parte superior de la página (lo que muestra la
 *   tarjeta) en RGB_565: caben cuatro veces más miniaturas en memoria.
 * - Una captura completamente en blanco o negro (superficie aún sin pintar
 *   o ya destruida) jamás se guarda ni sustituye a una miniatura válida.
 * - Lectura y escritura usan colas separadas para que precargar tarjetas
 *   al abrir el selector nunca bloquee el guardado inmediato de la pestaña activa.
 */
object TabPreviewStore {
    private const val TAG = "TabPreviewStore"
    private const val MAX_WIDTH = 440
    /** Alto máximo respecto al ancho: la tarjeta solo enseña la parte de arriba. */
    private const val MAX_ASPECT = 1.45f

    private fun dir(context: Context): File =
        File(context.filesDir, "tab_previews").apply { mkdirs() }

    private fun file(context: Context, id: String) = File(dir(context), "$id.webp")
    private fun legacyFile(context: Context, id: String) = File(dir(context), "$id.jpg")

    private val webpFormat = if (Build.VERSION.SDK_INT >= 30) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }

    private val writeIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoPreviewsWrite").apply { isDaemon = true }
    }
    private val readIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoPreviewsRead").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var generation = 0L

    private val memoryBytes: Int =
        (Runtime.getRuntime().maxMemory() / 10L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Avisos en el hilo principal con el id de la pestaña cuya miniatura cambió. */
    fun addListener(listener: (String) -> Unit) { listeners += listener }
    fun removeListener(listener: (String) -> Unit) { listeners -= listener }

    @Synchronized
    fun save(context: Context, id: String, bitmap: Bitmap) {
        val app = context.applicationContext
        val requested = generation
        writeIo.execute { saveNow(app, id, bitmap, requested) }
    }

    /** Vuelve a poner una miniatura conocida (p. ej. al deshacer el cierre de una pestaña). */
    @Synchronized
    fun restore(context: Context, id: String, bitmap: Bitmap) {
        memory.put(id, bitmap)
        val app = context.applicationContext
        writeIo.execute {
            runCatching { write(app, id, bitmap) }
        }
        notify(id)
    }

    private fun saveNow(context: Context, id: String, bitmap: Bitmap, requested: Long) {
        var working: Bitmap = bitmap
        try {
            if (working.isRecycled) return
            if (Build.VERSION.SDK_INT >= 26 && working.config == Bitmap.Config.HARDWARE) {
                val copy = working.copy(Bitmap.Config.ARGB_8888, false)
                working.recycle()
                if (copy == null) return
                working = copy
            }
            // Jamás guardar una captura completamente uniforme (negra/blanca), ni siquiera la primera vez.
            if (isBlank(working)) {
                working.recycle()
                return
            }
            val thumbnail = thumbnail(working)
            if (thumbnail !== working) working.recycle()
            // Publicar en memoria y notificar a la UI de inmediato, antes de comprimir WebP a disco.
            synchronized(this) {
                if (requested != generation) {
                    thumbnail.recycle()
                    return
                }
                memory.put(id, thumbnail)
                notify(id)
            }
            write(context, id, thumbnail)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save tab preview for $id", t)
            if (!working.isRecycled && memory.get(id) !== working) {
                runCatching { working.recycle() }
            }
        }
    }

    private fun write(context: Context, id: String, bitmap: Bitmap) {
        val file = android.util.AtomicFile(file(context, id))
        val output = file.startWrite()
        try {
            check(bitmap.compress(webpFormat, 82, output))
            file.finishWrite(output)
            legacyFile(context, id).delete()
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    /** Recorta la parte de arriba y la escala a [MAX_WIDTH] en RGB_565. */
    private fun thumbnail(source: Bitmap): Bitmap {
        val width = minOf(MAX_WIDTH, source.width).coerceAtLeast(1)
        val scale = width.toFloat() / source.width.coerceAtLeast(1)
        val sourceHeight = minOf(source.height, (source.width * MAX_ASPECT).toInt()).coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(output).drawBitmap(
            source,
            Rect(0, 0, source.width, sourceHeight),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        return output
    }

    /**
     * Una cuadrícula de 9×9 puntos del mismo color = captura vacía (superficie
     * aún sin pintar o ya destruida).
     */
    fun isBlank(bitmap: Bitmap): Boolean = runCatching {
        if (bitmap.width < 2 || bitmap.height < 2) return true
        val first = bitmap.getPixel(0, 0)
        for (y in 0..8) for (x in 0..8) {
            val pixel = bitmap.getPixel(
                (x * (bitmap.width - 1)) / 8,
                (y * (bitmap.height - 1)) / 8
            )
            if (pixel != first) return false
        }
        true
    }.getOrDefault(false)

    private fun notify(id: String) {
        main.post { listeners.forEach { runCatching { it(id) } } }
    }

    /** Miniatura ya decodificada, si está en memoria. */
    fun cached(id: String): Bitmap? = memory.get(id)?.takeUnless { it.isRecycled }

    /** Decodifica la miniatura fuera del hilo principal. */
    fun loadAsync(context: Context, id: String, onLoaded: (Bitmap?) -> Unit) {
        cached(id)?.let {
            onLoaded(it)
            return
        }
        val app = context.applicationContext
        val requested = synchronized(this) { generation }
        readIo.execute {
            val bitmap = load(app, id, requested)
            main.post { onLoaded(if (synchronized(this) { requested == generation }) bitmap else null) }
        }
    }

    /** Decodifica de antemano las miniaturas que el selector va a mostrar. */
    fun prefetch(context: Context, ids: List<String>) {
        val app = context.applicationContext
        val missing = ids.filter { cached(it) == null }.take(12)
        if (missing.isEmpty()) return
        val requested = synchronized(this) { generation }
        readIo.execute { missing.forEach { if (cached(it) == null) load(app, it, requested) } }
    }

    private fun load(context: Context, id: String, requested: Long): Bitmap? =
        runCatching {
            (file(context, id).takeIf { it.exists() } ?: legacyFile(context, id).takeIf { it.exists() })?.let {
                BitmapFactory.decodeFile(
                    it.absolutePath,
                    // Miniaturas sin transparencia: RGB_565 usa la mitad de memoria.
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                )
            }
        }.getOrNull()?.let { bitmap ->
            synchronized(this) {
                if (requested != generation) {
                    bitmap.recycle()
                    null
                } else {
                    memory.put(id, bitmap)
                    bitmap
                }
            }
        }

    fun remove(context: Context, id: String) {
        memory.remove(id)
        val app = context.applicationContext
        writeIo.execute {
            memory.remove(id)
            runCatching {
                android.util.AtomicFile(file(app, id)).delete()
                legacyFile(app, id).delete()
            }
        }
    }

    @Synchronized
    fun clear(context: Context, onDone: (Boolean) -> Unit) {
        generation++
        memory.evictAll()
        val app = context.applicationContext
        writeIo.execute {
            onDone(runCatching { dir(app).deleteRecursively() }.getOrDefault(false))
        }
    }

    /**
     * Compatible con Android 14/15 (`TRIM_MEMORY_UI_HIDDEN = 20`, `TRIM_MEMORY_BACKGROUND = 40`):
     * libera la caché al pasar a segundo plano sin saturar RAM.
     */
    fun trimMemory(level: Int = 80 /* COMPLETE */) {
        when {
            level >= 40 /* BACKGROUND / MODERATE / COMPLETE */ || level == 15 /* RUNNING_CRITICAL */ -> memory.evictAll()
            level >= 20 /* UI_HIDDEN */ || level == 10 /* RUNNING_LOW */ -> memory.trimToSize(memoryBytes / 3)
            else -> Unit
        }
    }
}
