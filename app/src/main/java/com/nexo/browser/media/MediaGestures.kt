package com.nexo.browser.media

import kotlin.math.abs

/**
 * Reglas de los gestos del reproductor en pantalla completa. Puras (sin Android) para
 * probarlas en la JVM; la vista solo traduce toques a estas llamadas.
 *
 * - Laterales (35 %): doble toque salta ∓/± el paso del sitio; toques seguidos acumulan.
 * - Arrastre vertical en un lateral: brillo (izquierda) o volumen (derecha).
 * - Arrastre horizontal: buscar con vista previa del tiempo.
 * - Mantener pulsado: velocidad 2× mientras dure.
 * - Franja superior e inferior: siempre para la página (sus barras de control).
 */
object MediaGestures {
    enum class Zone { LEFT, CENTER, RIGHT, PAGE }
    enum class Drag { NONE, BRIGHTNESS, VOLUME, SCRUB }

    const val DOUBLE_TAP_MS = 300L
    const val SEEK_CHAIN_MS = 700L
    const val LONG_PRESS_MS = 450L
    const val HOLD_SPEED = 2.0f

    /** Fracciones de alto reservadas a los controles propios de la página. */
    private const val TOP_RESERVED = 0.10f
    private const val BOTTOM_RESERVED = 0.18f
    private const val SIDE = 0.35f

    fun zone(x: Float, y: Float, width: Int, height: Int): Zone {
        if (width <= 0 || height <= 0) return Zone.PAGE
        if (y < height * TOP_RESERVED || y > height * (1f - BOTTOM_RESERVED)) return Zone.PAGE
        return when {
            x < width * SIDE -> Zone.LEFT
            x > width * (1f - SIDE) -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    /** Qué arrastre empieza (o NONE si aún no supera el umbral o no es claro). */
    fun drag(zone: Zone, dx: Float, dy: Float, slop: Float): Drag {
        if (zone == Zone.PAGE) return Drag.NONE
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay > slop * 2 && ay > ax * 1.6f) {
            return when (zone) {
                Zone.LEFT -> Drag.BRIGHTNESS
                Zone.RIGHT -> Drag.VOLUME
                else -> Drag.NONE
            }
        }
        if (ax > slop * 2 && ax > ay * 1.6f) return Drag.SCRUB
        return Drag.NONE
    }

    /**
     * Segundos que representa un arrastre horizontal: todo el ancho equivale a la mitad del
     * video (entre 30 s y 5 min); sin duración conocida, 90 s.
     */
    fun scrubSeconds(dx: Float, width: Int, durationSec: Double): Int {
        if (width <= 0 || !dx.isFinite()) return 0
        val span = if (durationSec.isFinite() && durationSec > 0) (durationSec * 0.5).coerceIn(30.0, 300.0) else 90.0
        return (dx / width * span).toInt()
    }

    /** Destino de la búsqueda acotado a [0, duración]. */
    fun scrubTarget(currentSec: Double, deltaSec: Int, durationSec: Double): Double {
        val base = if (currentSec.isFinite() && currentSec > 0) currentSec else 0.0
        val target = (base + deltaSec).coerceAtLeast(0.0)
        return if (durationSec.isFinite() && durationSec > 0) target.coerceAtMost(durationSec) else target
    }

    /** ¿Este toque continúa una cadena de saltos (doble toque o toques seguidos tras saltar)? */
    fun continuesSeek(
        zone: Zone,
        nowMs: Long,
        lastTapUpMs: Long,
        lastTapZone: Zone?,
        chainUntilMs: Long,
        chainZone: Zone?
    ): Boolean {
        if (zone != Zone.LEFT && zone != Zone.RIGHT) return false
        if (nowMs < chainUntilMs && zone == chainZone) return true
        return lastTapUpMs > 0 && nowMs - lastTapUpMs <= DOUBLE_TAP_MS && zone == lastTapZone
    }

    /** Fracción 0..1 → nivel de brillo de ventana (nunca a oscuras del todo). */
    fun brightness(start: Float, fraction: Float): Float = (start + fraction).coerceIn(0.02f, 1f)

    fun volume(start: Int, fraction: Float, max: Int): Int =
        (start + fraction * max).let { if (it.isFinite()) Math.round(it) else start }.coerceIn(0, max.coerceAtLeast(0))

    enum class Fit(val css: String, val label: String) {
        CONTAIN("contain", "Ajustar"),
        COVER("cover", "Rellenar"),
        FILL("fill", "Estirar");

        fun next(): Fit = entries[(ordinal + 1) % entries.size]
    }
}
