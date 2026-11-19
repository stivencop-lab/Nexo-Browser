package com.nexo.browser.dark

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.ExtensionManager
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.Toastish
import com.nexo.browser.engine.Engines
import org.json.JSONObject

/**
 * Nexo Dark Engine: modo oscuro web desde el inicio de carga.
 *
 * Capas coordinadas:
 * 1. Superficie Chromium (`setBackgroundColor` + `setDarkMode`) antes de la primera carga.
 * 2. Script `document_start` (`assets/nexo/dark-engine.js`) que anuncia `color-scheme: dark`
 *    y aplica un lienzo base oscuro para minimizar el destello blanco inicial.
 * 3. Motor híbrido:
 *    - Detecta sitios con modo oscuro nativo (`color-scheme: dark` o fondo con luminancia baja)
 *      y evita doble inversión o sobrecoste.
 *    - Aplica hoja CSS ligera de Nexo protegiendo medios, canvas, SVG, editores, PDF, formularios
 *      y contenedores de pago/autenticación.
 *    - Opcionalmente delega a Dark Reader (bajo demanda) en sitios complejos si está habilitado.
 * 4. Modos globales (`auto`, `light`, `dark`, `oled`) y excepciones / modos por dominio.
 */
object DarkEngine {
    private const val PREFS = "nexo_dark_engine"
    private const val KEY_MODE = "mode"
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_SITE_MODES = "site_modes"
    private const val MAX_SITE_RULES = 300

    const val MODE_AUTO = "auto"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_OLED = "oled"

    const val STRATEGY_HYBRID = "hybrid"
    const val STRATEGY_CSS_FAST = "css"
    const val STRATEGY_DARK_READER = "dark_reader"

    const val COLOR_DARK_BG = "#0c1222"
    const val COLOR_OLED_BG = "#000000"
    const val COLOR_LIGHT_BG = "#ffffff"
    const val COLOR_DARK_TEXT = "#e8e6e3"
    const val COLOR_DARK_SURFACE = "#151d30"
    const val COLOR_OLED_SURFACE = "#0a0a0a"

    data class Policy(
        val enabled: Boolean,
        val oled: Boolean,
        val backgroundHex: String,
        val surfaceHex: String,
        val textHex: String,
        val strategy: String,
        val excluded: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_AUTO)?.takeIf { it in VALID_MODES } ?: MODE_AUTO

    fun setMode(context: Context, value: String) {
        val clean = value.takeIf { it in VALID_MODES } ?: MODE_AUTO
        prefs(context).edit().putString(KEY_MODE, clean).apply()
        notifyApplied(context)
    }

    fun strategy(context: Context): String =
        prefs(context).getString(KEY_STRATEGY, STRATEGY_HYBRID)?.takeIf { it in VALID_STRATEGIES } ?: STRATEGY_HYBRID

    fun setStrategy(context: Context, value: String) {
        val clean = value.takeIf { it in VALID_STRATEGIES } ?: STRATEGY_HYBRID
        prefs(context).edit().putString(KEY_STRATEGY, clean).apply()
        notifyApplied(context)
    }

    fun normalizeHost(hostOrUrl: String?): String {
        if (hostOrUrl.isNullOrBlank()) return ""
        val raw = hostOrUrl.trim()
        val parsed = if ("://" in raw) runCatching { java.net.URI(raw).host }.getOrNull().orEmpty() else raw.substringBefore('/').substringBefore(':')
        return parsed.lowercase().removePrefix("www.").take(253)
    }

    fun siteModes(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_SITE_MODES, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val value = json.optString(key)
            if (key.isNotBlank() && value in VALID_MODES) out[key] = value
        }
        return out
    }

    fun siteMode(context: Context, hostOrUrl: String?): String? {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty()) return null
        return siteModes(context)[host]
    }

    fun setSiteMode(context: Context, hostOrUrl: String, modeValue: String?) {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty()) return
        val current = siteModes(context).toMutableMap()
        if (modeValue == null || modeValue == MODE_AUTO) {
            current.remove(host)
        } else if (modeValue in VALID_MODES) {
            current[host] = modeValue
        }
        val trimmed = current.entries.toList().takeLast(MAX_SITE_RULES)
        val out = JSONObject()
        trimmed.forEach { (k, v) -> out.put(k, v) }
        prefs(context).edit().putString(KEY_SITE_MODES, out.toString()).apply()
        if (modeValue == MODE_LIGHT) BrowserPrefs.setNightException(context, host, true)
        else if (modeValue == MODE_DARK || modeValue == MODE_OLED) BrowserPrefs.setNightException(context, host, false)
        notifyApplied(context)
    }

    /** Política pura (sin dependencias de Android) para pruebas unitarias en JVM. */
    fun resolvePolicy(
        globalMode: String,
        strategy: String,
        appTheme: String,
        systemDark: Boolean,
        siteExcluded: Boolean,
        siteOverride: String? = null
    ): Policy {
        val effectiveStrategy = strategy.takeIf { it in VALID_STRATEGIES } ?: STRATEGY_HYBRID
        val chosen = siteOverride?.takeIf { it in VALID_MODES && it != MODE_AUTO }
            ?: globalMode.takeIf { it in VALID_MODES }
            ?: MODE_AUTO

        if (siteExcluded && siteOverride != MODE_DARK && siteOverride != MODE_OLED) {
            return Policy(false, false, COLOR_LIGHT_BG, COLOR_LIGHT_BG, "#111418", effectiveStrategy, excluded = true)
        }

        val (enabled, oled) = when (chosen) {
            MODE_LIGHT -> false to false
            MODE_DARK -> true to false
            MODE_OLED -> true to true
            else -> when (appTheme) {
                BrowserPrefs.THEME_OLED -> true to true
                BrowserPrefs.THEME_MIDNIGHT -> true to false
                BrowserPrefs.THEME_LIGHT -> false to false
                else -> systemDark to false
            }
        }
        return if (!enabled) {
            Policy(false, false, COLOR_LIGHT_BG, COLOR_LIGHT_BG, "#111418", effectiveStrategy, excluded = false)
        } else if (oled) {
            Policy(true, true, COLOR_OLED_BG, COLOR_OLED_SURFACE, COLOR_DARK_TEXT, effectiveStrategy, excluded = false)
        } else {
            Policy(true, false, COLOR_DARK_BG, COLOR_DARK_SURFACE, COLOR_DARK_TEXT, effectiveStrategy, excluded = false)
        }
    }

    fun isSystemDark(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun policyFor(context: Context, hostOrUrl: String? = null): Policy {
        val host = normalizeHost(hostOrUrl)
        val excluded = host.isNotEmpty() && BrowserPrefs.isNightException(context, host)
        val override = if (host.isNotEmpty()) siteMode(context, host) else null
        return resolvePolicy(
            globalMode = mode(context),
            strategy = strategy(context),
            appTheme = BrowserPrefs.theme(context),
            systemDark = isSystemDark(context),
            siteExcluded = excluded,
            siteOverride = override
        )
    }

    /** Color de fondo hexadecimal si aplica modo oscuro global, o null si está desactivado. */
    fun effectiveBackground(context: Context, hostOrUrl: String? = null): String? =
        policyFor(context, hostOrUrl).takeIf { it.enabled }?.backgroundHex

    /** Color entero para `CefriumBrowser.setBackgroundColor` antes de la primera carga. */
    fun canvasColorInt(context: Context, hostOrUrl: String? = null): Int {
        val policy = policyFor(context, hostOrUrl)
        return runCatching { Color.parseColor(policy.backgroundHex) }
            .getOrDefault(if (policy.enabled) 0xFF0C1222.toInt() else Color.WHITE)
    }

    fun isDarkEnabled(context: Context, hostOrUrl: String? = null): Boolean =
        policyFor(context, hostOrUrl).enabled

    fun modeLabel(mode: String): String = when (mode) {
        MODE_LIGHT -> "Claro (desactivado)"
        MODE_DARK -> "Oscuro"
        MODE_OLED -> "OLED (negro puro)"
        else -> "Automático"
    }

    fun strategyLabel(strategy: String): String = when (strategy) {
        STRATEGY_CSS_FAST -> "CSS ligero (rápido)"
        STRATEGY_DARK_READER -> "Dark Reader dinámico"
        else -> "Híbrido inteligente"
    }

    fun summary(context: Context): String {
        val m = modeLabel(mode(context))
        val s = strategyLabel(strategy(context))
        val count = BrowserPrefs.nightExceptions(context).size
        return if (count > 0) "$m · $s · $count sitios excluidos" else "$m · $s"
    }

    private fun notifyApplied(context: Context) {
        runCatching { Engines.current.applySettings(context) }
        runCatching { ExtensionManager.sendBrowserState(context) }
    }

    /** Hoja nativa para configurar Nexo Dark Engine globalmente y para el sitio actual. */
    fun showSheet(activity: Activity, currentHost: String? = null, onChanged: () -> Unit = {}) {
        lateinit var sheet: Dialog
        val cleanHost = normalizeHost(currentHost)
        val body = NexoUi.column(activity)

        var selectedMode = mode(activity)
        var selectedStrategy = strategy(activity)
        val modeHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val strategyHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val modes = listOf(
            MODE_AUTO to "Auto",
            MODE_LIGHT to "Claro",
            MODE_DARK to "Oscuro",
            MODE_OLED to "OLED"
        )
        val strategies = listOf(
            STRATEGY_HYBRID to "Híbrido",
            STRATEGY_CSS_FAST to "CSS rápido",
            STRATEGY_DARK_READER to "Dark Reader"
        )

        fun renderSelectors() {
            modeHolder.removeAllViews()
            modeHolder.addView(NexoUi.chipRow(activity, modes.map { (id, label) ->
                NexoUi.chip(activity, label, selectedMode == id) {
                    selectedMode = id
                    setMode(activity, id)
                    renderSelectors()
                    onChanged()
                }
            }))
            strategyHolder.removeAllViews()
            strategyHolder.addView(NexoUi.chipRow(activity, strategies.map { (id, label) ->
                NexoUi.chip(activity, label, selectedStrategy == id) {
                    selectedStrategy = id
                    setStrategy(activity, id)
                    renderSelectors()
                    onChanged()
                }
            }))
        }

        body.addView(NexoUi.section(activity, "Modo oscuro web global"))
        body.addView(modeHolder)
        body.addView(NexoUi.note(activity, "Auto sigue el tema de Nexo y del sistema. OLED usa fondo #000000."))

        body.addView(NexoUi.section(activity, "Motor de adaptación"))
        body.addView(strategyHolder)
        body.addView(NexoUi.note(activity,
            "Híbrido respeta sitios que ya son oscuros, aplica CSS ligero seguro y usa Dark Reader bajo demanda. " +
                "Imágenes, videos, canvas, editores y formularios sensibles se protegen de inversiones forzadas."))

        if (cleanHost.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Este sitio ($cleanHost)"))
            val excluded = BrowserPrefs.isNightException(activity, cleanHost)
            body.addView(NexoUi.switchRow(
                activity,
                "Activar Dark Engine en $cleanHost",
                if (excluded) "Excluido: se muestra con los colores originales del sitio."
                else "Oscurecimiento inteligente activo sin recargar la página.",
                !excluded
            ) { enabled ->
                BrowserPrefs.setNightException(activity, cleanHost, !enabled)
                if (enabled && siteMode(activity, cleanHost) == MODE_LIGHT) setSiteMode(activity, cleanHost, null)
                notifyApplied(activity)
                onChanged()
            })
        }

        val exceptions = BrowserPrefs.nightExceptions(activity).sorted()
        if (exceptions.isNotEmpty()) {
            body.addView(NexoUi.section(activity, "Excepciones guardadas (${exceptions.size})"))
            body.addView(NexoUi.note(activity, exceptions.take(8).joinToString(", ") + if (exceptions.size > 8) "…" else ""))
            body.addView(NexoUi.button(activity, "Restablecer excepciones por dominio") {
                exceptions.forEach { BrowserPrefs.setNightException(activity, it, false) }
                prefs(activity).edit().remove(KEY_SITE_MODES).apply()
                notifyApplied(activity)
                onChanged()
                sheet.dismiss()
                Toastish.show(activity, "Excepciones de modo oscuro restablecidas.")
            }, NexoUi.buttonParams(activity, 8f))
        }

        renderSelectors()
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Nexo Dark Engine", summary(activity)),
                body
            )
        )
    }

    private val VALID_MODES = setOf(MODE_AUTO, MODE_LIGHT, MODE_DARK, MODE_OLED)
    private val VALID_STRATEGIES = setOf(STRATEGY_HYBRID, STRATEGY_CSS_FAST, STRATEGY_DARK_READER)
}
