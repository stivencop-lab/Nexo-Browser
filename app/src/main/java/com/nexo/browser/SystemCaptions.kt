package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.nexo.browser.engine.EngineSession

/**
 * Opciones cuando un video o reproductor no trae subtítulos de texto.
 * Presenta una hoja moderna Nexo UI 5.0 con captura universal de Android (capta todo el audio),
 * transcripción directa desde la página y subtítulos instantáneos locales de Android.
 */
object SystemCaptions {
    fun showNoTextChoice(activity: MainActivity, session: EngineSession, onChange: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val hasKey = BrowserPrefs.deepgramKey(activity).isNotBlank()
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        body.addView(NexoUi.note(
            activity,
            "Este reproductor no expone subtítulos de texto propios. Elige cómo quieres capturar el audio:"
        ))

        if (Build.VERSION.SDK_INT >= 29) {
            body.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_play,
                "Capturar todo el audio con Android (Recomendado)",
                if (hasKey) "Capta videos protegidos, iframes, streams y audio sin depender de la página"
                else "Requiere clave API de Deepgram · tocar para configurar"
            ) {
                sheet.dismiss()
                if (hasKey) PlaybackAudioCapture.request(activity)
                else CaptionsUi.showSheet(activity, askKey = true) { onChange() }
            })
        }

        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_nav_reload,
            if (hasKey) "Transcribir desde la página" else "Configurar Deepgram",
            if (hasKey) "Intenta leer el audio directamente del elemento multimedia de la página"
            else "Añade tu clave de Deepgram para transcribir cualquier audio en vivo"
        ) {
            sheet.dismiss()
            if (hasKey) {
                LiveCaptions.allowAudio(activity, session)
                onChange()
            } else {
                CaptionsUi.showSheet(activity, askKey = true) { onChange() }
            }
        })

        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_settings,
            "Subtítulos instantáneos de Android (Offline)",
            "Generados localmente por Android para cualquier sonido, sin clave ni internet"
        ) {
            sheet.dismiss()
            LiveCaptions.stop(session)
            onChange()
            openSystemSettings(activity)
        })

        body.addView(NexoUi.button(activity, "Seguir esperando subtítulos del reproductor", primary = false) {
            sheet.dismiss()
        }, NexoUi.buttonParams(activity, 10f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Sin pista de subtítulos", "Elige el método de captura de audio"),
                body
            )
        )
    }

    /** Explica dónde se activa (varía por fabricante) y abre los ajustes de subtítulos del sistema. */
    fun openSystemSettings(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        body.addView(NexoUi.statusPill(activity, "En el dispositivo · Sin internet", NexoUi.Tone.SUCCESS))
        body.addView(NexoUi.note(
            activity,
            "Android puede generar subtítulos de cualquier sonido directamente en el teléfono, sin enviar nada a internet " +
                "y con audífonos igual que con altavoz.\n\n" +
                "Forma rápida: pulsa una tecla de volumen y toca el icono de subtítulos. " +
                "Si no aparece, búscalo en Ajustes › Accesibilidad como «Subtítulos instantáneos» o «Live Caption»."
        ))
        body.addView(NexoUi.button(activity, "Abrir ajustes de Android", primary = true) {
            sheet.dismiss()
            val opened = runCatching { activity.startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS)) }.isSuccess ||
                runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
            if (!opened) android.widget.Toast.makeText(activity, "Abre Ajustes › Accesibilidad en tu teléfono.", android.widget.Toast.LENGTH_LONG).show()
        }, NexoUi.buttonParams(activity, 10f))
        body.addView(NexoUi.button(activity, "Cerrar", primary = false) {
            sheet.dismiss()
        }, NexoUi.buttonParams(activity, 6f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Subtítulos instantáneos de Android", "Live Caption integrado en el sistema"),
                body
            )
        )
    }
}
