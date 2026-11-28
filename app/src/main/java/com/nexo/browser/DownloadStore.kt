package com.nexo.browser

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import org.json.JSONArray
import org.json.JSONObject
import com.nexo.browser.engine.DownloadResponse
import com.nexo.browser.engine.EngineDownload
import com.nexo.browser.engine.Engines
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DownloadEntry(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sourceUrl: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long
)

/** Descarga en curso: progreso para la pantalla Descargas y la notificación. */
class ActiveDownload internal constructor(
    val id: String, val fileName: String, val total: Long, internal val privateScope: String? = null
) {
    val received = AtomicLong(0)
    val startedAt: Long = SystemClock.elapsedRealtime()
    @Volatile var connections = 1
        internal set
    @Volatile var bytesPerSecond = 0L
        internal set
    @Volatile var cancelled = false
        internal set
    /** Respuestas HTTP activas; se cierran todas al cancelar, incluida cada parte paralela. */
    internal val activeResponses = CopyOnWriteArrayList<DownloadResponse>()
    internal val workerFutures = CopyOnWriteArrayList<Future<*>>()
    internal val terminal = AtomicBoolean(false)
    internal var cancelNative: (() -> Unit)? = null
    internal var sampleBytes = 0L
    internal var sampleAt = startedAt

    val percent: Int
        get() = if (total > 0) ((received.get() * 100) / total).toInt().coerceIn(0, 100) else -1
}

/**
 * Datos para pedir partes del archivo con la misma sesión (cookies y modo
 * privado). Solo se pasa cuando la pestaña no usa proxy ni Burner: esas
 * descargas nunca repiten la petición fuera de su ruta.
 */
data class DownloadSource(
    val isPrivate: Boolean,
    val referrer: String?,
    /** false: una sola conexión (Ajustes); la reanudación con rangos sigue activa. */
    val split: Boolean = true,
    val burnerId: String? = null
)

object DownloadStore {
    private const val PREF = "nexo_downloads"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 250

    /** Margen libre que se deja siempre en el almacenamiento. */
    private const val FREE_SPACE_MARGIN = 64L * 1024L * 1024L
    private const val BUFFER_BYTES = 512 * 1024
    private const val MIN_WRITE_BYTES = 64 * 1024
    private const val MAX_PART_RETRIES = 3

    private const val CHANNEL = "nexo_downloads"
    private const val NOTIFY_PROGRESS = 7100
    private const val NOTIFY_DONE = 7101

    private val io = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "NexoDownloadIO").apply { isDaemon = true }
    }
    // Tramos en paralelo de las descargas grandes.
    private val parts = Executors.newCachedThreadPool { task ->
        Thread(task, "NexoDownloadPart").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val fileLock = Any()
    private val running = ConcurrentHashMap<String, ActiveDownload>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val channelReady = AtomicBoolean(false)
    private var appContext: Context? = null
    private const val PART_PREFIX = ".nexo-incomplete-"
    private val processPrefix = PART_PREFIX + UUID.randomUUID() + "-"
    private val privateLists = mutableMapOf<String, MutableList<DownloadEntry>>()

    @Synchronized
    private fun privateList(scope: String?): MutableList<DownloadEntry>? =
        scope?.let { privateLists.getOrPut(it) { mutableListOf() } }

    @Synchronized
    internal fun closePrivateScope(scope: String) {
        privateLists.remove(scope)?.clear()
    }

    @Synchronized
    private fun rememberPrivate(scope: String, list: MutableList<DownloadEntry>, entry: DownloadEntry) {
        if (privateLists[scope] !== list) return
        list.add(0, entry)
        if (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
    }

    // ---------------------------------------------------------------
    // Descargas en curso
    // ---------------------------------------------------------------

    fun activeDownloads(scope: String? = null): List<ActiveDownload> =
        running.values.filter { it.privateScope == scope }.sortedBy { it.startedAt }

    /** Al arrancar: el progreso de una descarga de un proceso que murió no debe quedarse en la barra. */
    fun clearStaleProgress(context: Context) {
        if (running.isNotEmpty()) return
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_PROGRESS) }
        val app = context.applicationContext
        io.execute {
            com.nexo.browser.engine.cefrium.CefriumFileDownload.clearOrphaned(app)
            downloadsFolder(app).listFiles()?.forEach { file ->
                if (file.name.startsWith(PART_PREFIX) && !file.name.startsWith(processPrefix) &&
                    file.name.endsWith(".part")) file.delete()
            }
        }
    }

    fun cancel(id: String) {
        val job = running[id] ?: return
        synchronized(job) {
            if (job.terminal.get()) return
            job.cancelled = true
        }
        job.cancelNative?.let { cancel -> main.post(cancel) }
        job.activeResponses.forEach { response -> response.close() }
        job.workerFutures.forEach { future -> future.cancel(true) }
    }

    internal fun startNative(
        context: Context, fileName: String?, total: Long, scope: String?, cancel: () -> Unit
    ): ActiveDownload = ActiveDownload(
        UUID.randomUUID().toString(), FileNames.sanitize(fileName.orEmpty()), total, scope
    ).also { job ->
        job.cancelNative = cancel
        running[job.id] = job
        startTicker(context)
    }

    internal fun finishNative(job: ActiveDownload) {
        job.terminal.set(true)
        running.remove(job.id, job)
        main.post {
            appContext?.let(::showProgress)
            listeners.forEach { runCatching { it() } }
        }
    }

    /** Aviso periódico (hilo principal) mientras hay descargas en curso. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun progressText(context: Context, job: ActiveDownload): String {
        val received = Formatter.formatShortFileSize(context, job.received.get())
        val size = if (job.total > 0) "$received de ${Formatter.formatShortFileSize(context, job.total)}" else received
        val speed = if (job.bytesPerSecond > 0) Formatter.formatShortFileSize(context, job.bytesPerSecond) + "/s" else null
        val connections = if (job.connections > 1) "${job.connections} conexiones" else null
        return listOfNotNull(job.percent.takeIf { it >= 0 }?.let { "$it %" }, size, speed, connections).joinToString(" · ")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            running.values.forEach { job ->
                val elapsed = (now - job.sampleAt).coerceAtLeast(1)
                val bytes = job.received.get()
                val instant = (bytes - job.sampleBytes) * 1000 / elapsed
                // Media suavizada: la cifra no salta en cada muestra.
                job.bytesPerSecond = if (job.bytesPerSecond == 0L) instant else (job.bytesPerSecond * 2 + instant) / 3
                job.sampleBytes = bytes
                job.sampleAt = now
            }
            appContext?.let { showProgress(it) }
            listeners.forEach { runCatching { it() } }
            if (running.isNotEmpty()) main.postDelayed(this, 800L)
        }
    }

    private fun startTicker(context: Context) {
        appContext = context.applicationContext
        main.post {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 400L)
        }
    }

    // ---------------------------------------------------------------
    // Iniciar descargas
    // ---------------------------------------------------------------

    /**
     * Descarga una URL (menú contextual) a través del motor: envía las cookies
     * de la sesión, respeta el modo privado y guarda en la misma carpeta que
     * el resto de descargas, de modo que aparece en la pantalla Descargas.
     * [parallel]: permite repartir archivos grandes en varias conexiones.
     */
    fun download(
        context: Context,
        url: String,
        isPrivate: Boolean,
        referrer: String?,
        parallel: Boolean = true,
        split: Boolean = true,
        burnerId: String? = null,
        callback: (Boolean, String) -> Unit
    ) {
        if (!UrlSafety.isWebUrl(url)) {
            callback(false, "Este recurso no se puede descargar directamente")
            return
        }

        val app = context.applicationContext
        val source = if (parallel) DownloadSource(isPrivate, referrer, split, burnerId) else null
        val scope = SitePermissionStore.scope(isPrivate, burnerId)
        val privateEntries = privateList(scope)
        io.execute {
            val response = try {
                Engines.current.downloadFetcher.fetch(url, emptyMap(), referrer?.takeIf { UrlSafety.isWebUrl(it) }, isPrivate, burnerId)
            } catch (error: Throwable) {
                main.post { callback(false, "Descarga fallida: ${error.message ?: "error de red"}") }
                return@execute
            }
            if (response.statusCode !in 200..299) {
                response.close()
                main.post { callback(false, "Descarga fallida: HTTP ${response.statusCode}") }
            } else {
                receive(app, response, source, scope, privateEntries, callback)
            }
        }
    }

    /** Respuesta que el motor no puede mostrar (`Content-Disposition`, tipos binarios…). */
    fun saveDownload(
        context: Context,
        download: EngineDownload,
        scope: String?,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext
        val privateEntries = privateList(scope)
        io.execute {
            val response = try {
                download.open()
            } catch (error: Throwable) {
                download.discard()
                main.post { callback(false, "Descarga fallida: ${error.message ?: "error de red"}") }
                return@execute
            }
            if (response.statusCode !in 200..299) {
                response.close()
                main.post { callback(false, "Descarga fallida: HTTP ${response.statusCode}") }
            } else {
                receive(app, response, null, scope, privateEntries, callback)
            }
        }
    }

    /** Captura de la página como PNG en Descargas. */
    fun saveBitmap(
        context: Context, bitmap: Bitmap, title: String, scope: String?, callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext
        val privateEntries = privateList(scope)
        io.execute {
            var file: File? = null
            try {
                val partial = createPartial(downloadsFolder(app))
                file = partial
                partial.outputStream().buffered().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "La captura no se pudo codificar" }
                }
                val target = publishFile(partial, FileNames.sanitize("$title.png", "captura.png"))
                record(app, target, "", "image/png", scope, privateEntries)
                main.post { callback(true, if (scope == null) "Captura guardada en Descargas: ${target.name}"
                    else "Captura guardada en Descargas privadas") }
            } catch (error: Throwable) {
                file?.let { partial -> runCatching { partial.delete() } }
                main.post { callback(false, "No se pudo guardar la captura: ${error.message}") }
            } finally {
                if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
            }
        }
    }

    /** Guarda un flujo ya abierto (p. ej. la página como PDF). */
    fun saveStream(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        sourceUrl: String,
        scope: String?,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext
        val privateEntries = privateList(scope)
        io.execute {
            var file: File? = null
            runCatching {
                val folder = downloadsFolder(app)
                input.use { stream ->
                    val partial = createPartial(folder)
                    file = partial
                    val job = ActiveDownload(UUID.randomUUID().toString(), fileName, -1, scope)
                    RandomAccessFile(partial, "rw").use { raf -> copy(stream, raf.channel, 0, { Long.MAX_VALUE }, job, folder) }
                }
                val target = publishFile(requireNotNull(file), fileName)
                record(app, target, sourceUrl, mime, scope, privateEntries)
                main.post { callback(true, if (scope == null) "Guardado en Descargas: ${target.name}"
                    else "Archivo guardado en Descargas privadas") }
            }.onFailure {
                file?.let { partial -> runCatching { partial.delete() } }
                main.post { callback(false, "No se pudo guardar: ${it.message}") }
            }
        }
    }

    // ---------------------------------------------------------------
    // Recepción
    // ---------------------------------------------------------------

    private fun receive(
        app: Context,
        response: DownloadResponse,
        source: DownloadSource?,
        scope: String?,
        privateEntries: MutableList<DownloadEntry>?,
        callback: (Boolean, String) -> Unit
    ) {
        var file: File? = null
        var job: ActiveDownload? = null
        try {
            val body = response.body ?: error("Sin cuerpo descargable")
            val headers = response.headers
            val mime = header(headers, "Content-Type")
                ?.substringBefore(";")
                ?.trim()
                .orEmpty()
                .ifBlank { "application/octet-stream" }

            val requested = resolveFileName(response.uri, header(headers, "Content-Disposition"))
            val folder = downloadsFolder(app)
            val declared = header(headers, "Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
            // Con compresión de transporte el tamaño declarado no es el del archivo.
            val encoding = header(headers, "Content-Encoding")?.trim()?.lowercase()
            val total = if (encoding.isNullOrEmpty() || encoding == "identity") declared else -1L

            if (declared > 0 && declared > folder.usableSpace - FREE_SPACE_MARGIN) {
                runCatching { body.close() }
                error("No hay espacio suficiente para este archivo")
            }

            val partial = createPartial(folder)
            file = partial
            val active = ActiveDownload(UUID.randomUUID().toString(), requested, total, scope)
            job = active
            active.activeResponses += response
            running[active.id] = active
            startTicker(app)

            RandomAccessFile(partial, "rw").use { raf ->
                if (total > 0) preallocate(raf, total)
                val channel = raf.channel
                val rangeable = source != null && total > 0 && UrlSafety.isWebUrl(response.uri) &&
                    DownloadPlan.strongEtag(headers) != null &&
                    header(headers, "Accept-Ranges")?.trim()?.equals("bytes", ignoreCase = true) == true
                if (source != null && rangeable && source.split && DownloadPlan.canSplit(headers, total)) {
                    receiveSplit(app, active, response, body, channel, folder, source)
                } else {
                    receiveSequential(app, active, response, body, channel, folder, if (rangeable) source else null)
                }
                if (total > 0 && raf.length() != total) raf.setLength(total)
            }

            val target = synchronized(active) {
                if (active.terminal.get() || active.cancelled) throw CancellationException("cancelada")
                publishFile(partial, requested).also { active.terminal.set(true) }
            }
            record(app, target, response.uri, mime, scope, privateEntries)
            if (scope == null) showDone(app, target.name)
            main.post { callback(true, if (scope == null) "Descargado: ${target.name}"
                else "Archivo guardado en Descargas privadas") }
        } catch (error: Throwable) {
            // No dejar archivos a medio descargar.
            job?.cancelled = true
            file?.let { partial -> runCatching { partial.delete() } }
            val cancelled = error is CancellationException
            main.post { callback(false, if (cancelled) "Descarga cancelada" else "Descarga fallida: ${error.message ?: "error de red"}") }
        } finally {
            runCatching { response.close() }
            job?.let { running.remove(it.id) }
            main.post {
                appContext?.let { showProgress(it) }
                listeners.forEach { runCatching { it() } }
            }
        }
    }

    /**
     * Una conexión. Si se corta y el servidor admite rangos ([source] no
     * nulo), se reanuda desde el último byte guardado en vez de fallar.
     */
    private fun receiveSequential(
        app: Context,
        job: ActiveDownload,
        response: DownloadResponse,
        body: InputStream,
        channel: FileChannel,
        folder: File,
        source: DownloadSource?
    ) {
        var position = 0L
        try {
            body.use { position = copy(it, channel, 0, { Long.MAX_VALUE }, job, folder) }
            if (job.total > 0 && position < job.total) throw IOException("conexión interrumpida")
        } catch (error: IOException) {
            if (source == null || job.total <= 0 || job.cancelled) {
                throw if (job.total > 0 && position < job.total) IOException("Descarga incompleta (${error.message})", error) else error
            }
            position = job.received.get()
            fetchPart(app, job, response, channel, folder, source, position, job.total, null)
        }
        if (job.total > 0 && job.received.get() != job.total) throw IOException("Descarga incompleta")
    }

    /**
     * Varias conexiones: la respuesta original descarga el primer tramo
     * mientras se pide el último para comprobar que el servidor entrega
     * rangos del mismo archivo; si es así, el hueco se reparte entre más
     * conexiones. Si no, la original sigue sola hasta el final.
     */
    private fun receiveSplit(
        app: Context,
        job: ActiveDownload,
        response: DownloadResponse,
        body: InputStream,
        channel: FileChannel,
        folder: File,
        source: DownloadSource
    ) {
        val total = job.total
        val connections = DownloadPlan.connectionsFor(total)
        val tail = DownloadPlan.tailStart(total, connections)
        val lock = Any()
        var position = 0L
        var limit = total
        val tasks = CopyOnWriteArrayList<Future<*>>()

        val probe = parts.submit {
            val first = runCatching { openRange(app, job, response, source, tail, total) }.getOrNull() ?: return@submit
            if (job.cancelled) { first.close(); return@submit }
            val plan = synchronized(lock) {
                if (job.cancelled) null else DownloadPlan.split(position, tail, connections - 2)?.also { limit = it.first }
            }
            if (plan == null) {
                first.close()
                return@submit
            }
            job.connections = 2 + plan.second.size
            plan.second.forEach { range ->
                parts.submit {
                    fetchPart(app, job, response, channel, folder, source, range.first, range.last + 1, null)
                }.also {
                    tasks += it
                    job.workerFutures += it
                }
            }
            fetchPart(app, job, response, channel, folder, source, tail, total, first)
        }.also { job.workerFutures += it }

        try {
            var end = 0L
            try {
                body.use { input ->
                    end = copy(input, channel, 0, { synchronized(lock) { limit } }, job, folder) { advanced ->
                        synchronized(lock) { position = advanced }
                    }
                }
            } catch (error: IOException) {
                if (job.cancelled) throw error
                end = synchronized(lock) { position }
            }
            // Esperar la decisión: hasta entonces el límite puede cambiar.
            probe.get()
            val own = synchronized(lock) { limit }
            // La conexión original se cortó antes de su límite: se completa con rangos.
            if (end < own) fetchPart(app, job, response, channel, folder, source, end, own, null)
            tasks.forEach { it.get() }
        } catch (error: Throwable) {
            job.cancelled = true
            job.activeResponses.forEach { runCatching { it.close() } }
            job.workerFutures.forEach { it.cancel(true) }
            throw (error as? java.util.concurrent.ExecutionException)?.cause ?: error
        }
        if (job.received.get() != total) throw IOException("Descarga incompleta")
    }

    /** Descarga [start, end) con reintentos y reanudación desde lo ya escrito. */
    private fun fetchPart(
        app: Context,
        job: ActiveDownload,
        original: DownloadResponse,
        channel: FileChannel,
        folder: File,
        source: DownloadSource,
        start: Long,
        end: Long,
        first: DownloadResponse?
    ) {
        var position = start
        var attempt = 0
        var pending = first
        while (position < end) {
            if (job.cancelled) {
                pending?.close()
                throw CancellationException("cancelada")
            }
            try {
                val response = pending ?: openRange(app, job, original, source, position, end)
                pending = null
                job.activeResponses += response
                try {
                    val input = response.body ?: throw IOException("sin cuerpo")
                    copy(input, channel, position, { end }, job, folder) { position = it }
                    if (position < end) throw IOException("tramo incompleto")
                } finally {
                    response.close()
                    job.activeResponses -= response
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (job.cancelled) throw CancellationException("cancelada")
                if (++attempt > MAX_PART_RETRIES) throw IOException("No se pudo completar la descarga (${error.message})", error)
                try {
                    Thread.sleep(700L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("cancelada")
                }
            }
        }
    }

    /** Pide [start, end) y comprueba que es el tramo correcto del mismo archivo. */
    private fun openRange(app: Context, job: ActiveDownload, original: DownloadResponse, source: DownloadSource, start: Long, end: Long): DownloadResponse {
        val validator = DownloadPlan.strongEtag(original.headers) ?: throw IOException("sin ETag fuerte")
        val response = Engines.current.downloadFetcher.fetch(
            original.uri,
            mapOf("Range" to "bytes=$start-${end - 1}", "If-Range" to validator),
            source.referrer?.takeIf { UrlSafety.isWebUrl(it) },
            source.isPrivate,
            source.burnerId
        )
        val valid = response.statusCode == 206 && response.uri == original.uri &&
            DownloadPlan.matchesRange(original.headers, response.headers, start, end, job.total)
        if (!valid) {
            response.close()
            throw IOException("el servidor no entregó el tramo pedido")
        }
        return response
    }

    /**
     * Copia hasta [limit] (puede bajar mientras se copia) escribiendo en
     * posiciones absolutas. Llena el búfer antes de escribir: menos escrituras
     * al disco que con cada trozo pequeño que entrega la red.
     */
    private fun copy(
        input: InputStream,
        channel: FileChannel,
        start: Long,
        limit: () -> Long,
        job: ActiveDownload,
        folder: File,
        onAdvance: ((Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var position = start
        var sinceCheck = 0L
        var eof = false
        while (!eof) {
            if (job.cancelled) throw CancellationException("cancelada")
            val room = limit() - position
            if (room <= 0) break
            val want = minOf(buffer.size.toLong(), room).toInt()
            var filled = 0
            while (filled < want) {
                val count = input.read(buffer, filled, want - filled)
                if (count < 0) { eof = true; break }
                filled += count
                // Lo recibido se guarda si no hay más esperando: el progreso avanza fluido.
                if (filled >= MIN_WRITE_BYTES && input.available() <= 0) break
            }
            if (filled == 0) continue
            val chunk = ByteBuffer.wrap(buffer, 0, filled)
            while (chunk.hasRemaining()) {
                val written = channel.write(chunk, position).toLong()
                if (written == 0L) throw IOException("No se pudo escribir el archivo")
                position += written
                job.received.addAndGet(written)
                onAdvance?.invoke(position)
            }

            // Comprobar espacio libre cada ~32 MB (sin reserva previa del archivo).
            sinceCheck += filled
            if (sinceCheck >= 32L * 1024L * 1024L) {
                sinceCheck = 0L
                if (job.total <= 0 && folder.usableSpace < FREE_SPACE_MARGIN) {
                    throw IOException("Almacenamiento casi lleno")
                }
            }
        }
        return position
    }

    /** Reserva el espacio de una vez: menos fragmentación y error de espacio antes de empezar. */
    private fun preallocate(raf: RandomAccessFile, total: Long) {
        try {
            android.system.Os.posix_fallocate(raf.fd, 0, total)
        } catch (error: android.system.ErrnoException) {
            if (error.errno == android.system.OsConstants.ENOSPC) throw IOException("No hay espacio suficiente para este archivo")
            // Sistemas de archivos sin fallocate: se escribe sin reserva.
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------
    // Notificaciones
    // ---------------------------------------------------------------

    private fun notifications(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return null
        if (channelReady.compareAndSet(false, true)) {
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progreso de las descargas de Nexo"
                        setShowBadge(false)
                    }
                )
            }.onFailure { channelReady.set(false) }
        }
        return manager
    }

    private fun openDownloads(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7102,
            Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showProgress(context: Context) {
        val manager = notifications(context) ?: return
        val jobs = activeDownloads()
        if (jobs.isEmpty()) {
            manager.cancel(NOTIFY_PROGRESS)
            return
        }
        val job = jobs.first()
        // En la pantalla de bloqueo no se muestran nombres de archivo.
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle("Descarga en curso")
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle(if (jobs.size == 1) job.fileName else "${jobs.size} descargas en curso")
            .setContentText(progressText(context, job))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openDownloads(context))
            .apply {
                if (Build.VERSION.SDK_INT >= 36) {
                    // Android 16 «Live Updates»: la descarga sube a la barra de estado / isla
                    // (chip con el porcentaje) y a la pantalla de bloqueo.
                    style = Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .apply { if (job.total > 0) setProgress(job.percent.coerceIn(0, 100)) else setProgressIndeterminate(true) }
                    // La promoción a Live Update llegó en Android 16 QPR1 (API 36.1).
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                        setRequestPromotedOngoing(true)
                        if (job.total > 0) setShortCriticalText("${job.percent.coerceIn(0, 100)}%")
                    }
                } else if (job.total > 0) setProgress(100, job.percent, false) else setProgress(0, 0, true)
            }
            .build()
        runCatching { manager.notify(NOTIFY_PROGRESS, notification) }
    }

    private fun showDone(context: Context, name: String) {
        val manager = notifications(context) ?: return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle("Descarga completada")
            .setContentText(name)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openDownloads(context))
            .build()
        runCatching { manager.notify(NOTIFY_DONE, notification) }
    }

    // ---------------------------------------------------------------
    // Lista de descargas
    // ---------------------------------------------------------------

    @Synchronized
    fun list(context: Context, scope: String? = null): List<DownloadEntry> {
        if (scope != null) return privateLists[scope]?.toList().orEmpty()
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DownloadEntry(
                            o.optString("id"),
                            o.optString("fileName"),
                            o.optString("filePath"),
                            o.optString("sourceUrl"),
                            o.optString("mimeType"),
                            o.optLong("size"),
                            o.optLong("timestamp")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Devuelve el archivo solo si sigue dentro de la carpeta de descargas. */
    fun fileFor(context: Context, entry: DownloadEntry): File? =
        runCatching {
            val root = downloadsFolder(context).canonicalFile
            val file = File(entry.filePath).canonicalFile
            file.takeIf { isChildOf(root, it) && it.isFile }
        }.getOrNull()

    @Synchronized
    fun remove(context: Context, id: String, scope: String? = null) {
        val items = list(context, scope).toMutableList()
        val item = items.firstOrNull { it.id == id } ?: return

        fileFor(context, item)?.let { runCatching { it.delete() } }

        items.remove(item)
        if (scope == null) save(context, items) else privateLists[scope]?.remove(item)
    }

    @Synchronized
    fun clearList(context: Context, scope: String? = null) {
        if (scope != null) {
            privateLists[scope]?.clear()
            return
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    private fun record(
        app: Context, target: File, url: String, mime: String, scope: String?, privateEntries: MutableList<DownloadEntry>?
    ) {
        val entry = DownloadEntry(
            UUID.randomUUID().toString(), target.name, target.absolutePath,
            if (scope == null) redactSourceUrl(url) else "", mime, target.length(), System.currentTimeMillis()
        )
        if (scope == null) add(app, entry)
        else if (privateEntries != null) rememberPrivate(scope, privateEntries, entry)
    }

    @Synchronized
    private fun add(context: Context, entry: DownloadEntry) {
        val items = list(context).toMutableList()
        items.add(0, entry)
        save(context, items.take(MAX_ENTRIES))
    }

    private fun save(context: Context, items: List<DownloadEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("fileName", it.fileName)
                put("filePath", it.filePath)
                put("sourceUrl", it.sourceUrl)
                put("mimeType", it.mimeType)
                put("size", it.size)
                put("timestamp", it.timestamp)
            })
        }

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun downloadsFolder(context: Context): File {
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        folder.mkdirs()
        return folder
    }

    private fun header(headers: Map<String, String>, name: String): String? = DownloadPlan.header(headers, name)

    private fun resolveFileName(url: String, disposition: String?): String {
        val fromHeader = FileNames.fromContentDisposition(disposition)
        if (fromHeader != null) return FileNames.sanitize(fromHeader)

        return FileNames.sanitize(
            FileNames.fromUrl(url) ?: "descarga-${System.currentTimeMillis()}.bin"
        )
    }

    private fun createPartial(folder: File): File = File.createTempFile(processPrefix, ".part", folder)

    private fun publishFile(partial: File, requested: String): File {
        val root = requireNotNull(partial.parentFile).canonicalFile
        val safeRequested = FileNames.sanitize(requested).let { name ->
            if (name.startsWith(PART_PREFIX)) "archivo-$name" else name
        }
        val dot = safeRequested.lastIndexOf('.')
        val base = if (dot > 0) safeRequested.substring(0, dot) else safeRequested
        val ext = if (dot > 0) safeRequested.substring(dot) else ""

        fun candidate(name: String): File {
            val file = File(root, name).canonicalFile
            require(isChildOf(root, file)) { "Nombre de archivo inseguro" }
            return file
        }

        synchronized(fileLock) {
            var file = candidate(safeRequested)
            var n = 1
            while (java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (n > 999) error("Demasiados archivos con el mismo nombre")
                file = candidate("$base ($n)$ext")
                n++
            }
            java.nio.file.Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return file
        }
    }

    private fun isChildOf(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return file.canonicalPath.startsWith(rootPath)
    }

    private fun redactSourceUrl(url: String): String =
        runCatching {
            if (url.isBlank()) return@runCatching ""
            val clean = UrlSafety.stripCredentials(url)
            val uri = Uri.parse(clean)
            uri.buildUpon()
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
        }.getOrDefault("")
}
