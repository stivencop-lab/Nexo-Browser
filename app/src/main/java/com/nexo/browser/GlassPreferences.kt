package com.nexo.browser

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.glassDataStore by preferencesDataStore("nexo_glass")

/** The current value is available to Views without waiting for disk. Mutations are stored in order. */
internal object GlassPreferences {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val key = stringPreferencesKey("intensity")
    private val listeners = LinkedHashSet<() -> Unit>()
    private var started = false
    private var revision = 0L
    var intensity = GlassIntensity.BALANCED
        private set

    fun initialize(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            try {
                val value = parse(app.glassDataStore.data.first()[key])
                if (revision == 0L) publish(value)
            } catch (error: IOException) {
                Log.w("NexoGlass", "Could not load the glass setting", error)
            }
        }
    }

    fun set(context: Context, value: GlassIntensity) {
        val expected = ++revision
        publish(value)
        val app = context.applicationContext
        scope.launch {
            try {
                app.glassDataStore.edit { it[key] = value.name }
            } catch (error: IOException) {
                Log.w("NexoGlass", "Could not save the glass setting", error)
                if (revision == expected) {
                    val stored = runCatching { app.glassDataStore.data.first()[key] }.getOrNull()
                    if (revision == expected) publish(parse(stored))
                }
            }
        }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun publish(value: GlassIntensity) {
        if (intensity == value) return
        intensity = value
        listeners.toList().forEach { it() }
    }

    private fun parse(value: String?): GlassIntensity =
        GlassIntensity.entries.firstOrNull { it.name == value } ?: GlassIntensity.BALANCED
}
