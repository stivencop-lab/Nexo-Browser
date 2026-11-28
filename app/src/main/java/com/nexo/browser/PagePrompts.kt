package com.nexo.browser

import android.app.AlertDialog
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumFileChooserCallback
import com.cefrium.CefriumFileChooserParams
import com.nexo.browser.engine.EngineSession
import java.util.WeakHashMap

/**
 * Diálogos que piden las páginas: alert/confirm/prompt, "¿salir de la
 * página?", archivos y autenticación HTTP. Los `<select>`, fechas y colores
 * los dibuja Chromium.
 *
 * Se muestra el sitio real (nunca el título que pone la
 * página), solo la pestaña visible puede abrir diálogos y tras varios
 * seguidos se ofrece bloquearlos hasta que cambie la página.
 */
object PagePrompts {
    private const val DIALOG_BURST = 3
    private const val DIALOG_BURST_WINDOW_MS = 15_000L

    private val blockedDialogs = WeakHashMap<EngineSession, Boolean>()
    private val recentDialogs = WeakHashMap<EngineSession, ArrayDeque<Long>>()
    private val popupNotified = WeakHashMap<EngineSession, Boolean>()

    /** Una navegación nueva restablece los bloqueos de diálogos. */
    fun onPageStart(session: EngineSession) {
        blockedDialogs.remove(session)
        recentDialogs.remove(session)
        popupNotified.remove(session)
    }

    fun popupBlocked(session: EngineSession) {
        if (popupNotified.put(session, true) != true) TabManager.notifyMessage("Nexo bloqueó una ventana emergente")
    }

    private fun activityFor(session: EngineSession): android.app.Activity? =
        BrowserHosts.current()?.hostActivity?.takeIf { TabManager.activeSession() === session }

    private fun origin(session: EngineSession): String {
        val url = TabManager.tabForSession(session)?.url.orEmpty()
        return runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: "Esta página"
    }

    /** Respuesta única para un diálogo de la página: la primera acción gana. */
    private class Once(private val result: CefriumBrowser.JsDialogCallback) {
        private var done = false
        fun confirm(value: String? = null) {
            if (done) return
            done = true
            runCatching { result.continueDialog(true, value.orEmpty()) }
        }

        fun cancel() {
            if (done) return
            done = true
            runCatching { result.continueDialog(false, "") }
        }
    }

    private fun offerBlock(session: EngineSession, builder: AlertDialog.Builder, once: Once) {
        val now = SystemClock.elapsedRealtime()
        val times = recentDialogs.getOrPut(session) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > DIALOG_BURST_WINDOW_MS) times.removeFirst()
        times.addLast(now)
        if (times.size >= DIALOG_BURST) {
            builder.setNeutralButton("Bloquear diálogos") { _, _ ->
                blockedDialogs[session] = true
                once.cancel()
            }
        }
    }

    private fun show(session: EngineSession, dialog: AlertDialog, once: Once): Boolean {
        WindowPrivacy.protect(dialog.window, TabManager.tabForSession(session)?.isPrivate == true)
        dialog.setOnDismissListener { once.cancel() }
        return runCatching {
            dialog.show()
            dialog.window?.decorView?.clipToOutline = true
        }.isSuccess.also { if (!it) once.cancel() }
    }

    fun alert(session: EngineSession, message: String, result: CefriumBrowser.JsDialogCallback): Boolean {
        val once = Once(result)
        val activity = activityFor(session)
        if (activity == null || blockedDialogs[session] == true) { once.cancel(); return true }
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(message)
            .setPositiveButton("Aceptar") { _, _ -> once.confirm() }
        offerBlock(session, builder, once)
        show(session, builder.create(), once)
        return true
    }

    fun confirm(session: EngineSession, message: String, result: CefriumBrowser.JsDialogCallback): Boolean {
        val once = Once(result)
        val activity = activityFor(session)
        if (activity == null || blockedDialogs[session] == true) { once.cancel(); return true }
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(message)
            .setNegativeButton("Cancelar") { _, _ -> once.cancel() }
            .setPositiveButton("Aceptar") { _, _ -> once.confirm() }
        offerBlock(session, builder, once)
        show(session, builder.create(), once)
        return true
    }

    fun prompt(session: EngineSession, message: String, defaultValue: String?, result: CefriumBrowser.JsDialogCallback): Boolean {
        val once = Once(result)
        val activity = activityFor(session)
        if (activity == null || blockedDialogs[session] == true) { once.cancel(); return true }
        val input = field(activity, defaultValue, "")
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(message)
            .setView(container(activity, input))
            .setNegativeButton("Cancelar") { _, _ -> once.cancel() }
            .setPositiveButton("Aceptar") { _, _ -> once.confirm(input.text?.toString().orEmpty()) }
        offerBlock(session, builder, once)
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.selectAll()
        show(session, dialog, once)
        return true
    }

    fun beforeUnload(session: EngineSession, result: CefriumBrowser.JsDialogCallback): Boolean {
        val once = Once(result)
        // Sin pantalla visible, salir es lo que pidió el usuario.
        val activity = activityFor(session) ?: run { once.confirm(); return true }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("¿Salir de esta página?")
            .setMessage("Es posible que no se guarden los cambios que hiciste.")
            .setNegativeButton("Quedarme") { _, _ -> once.cancel() }
            .setPositiveButton("Salir") { _, _ -> once.confirm() }
            .create()
        show(session, dialog, once)
        return true
    }

    fun httpAuth(session: EngineSession, handler: CefriumBrowser.HttpAuthCallback, host: String, realm: String) {
        val activity = activityFor(session) ?: run { handler.cancel(); return }
        var answered = false
        val user = field(activity, null, "Usuario")
        val pass = field(activity, null, "Contraseña", password = true)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Iniciar sesión")
            .setMessage("${host.removePrefix("www.")} pide un usuario y una contraseña" + (realm.takeIf { it.isNotBlank() }?.let { " ($it)." } ?: "."))
            .setView(container(activity, user, pass))
            .setNegativeButton("Cancelar") { _, _ -> answered = true; handler.cancel() }
            .setPositiveButton("Entrar") { _, _ ->
                answered = true
                handler.proceed(user.text?.toString().orEmpty(), pass.text?.toString().orEmpty())
            }
            .setOnDismissListener { if (!answered) { answered = true; handler.cancel() } }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        WindowPrivacy.protect(dialog.window, TabManager.tabForSession(session)?.isPrivate == true)
        runCatching { dialog.show() }.onFailure { if (!answered) { answered = true; handler.cancel() } }
    }

    /**
     * Subir archivos: solo la pestaña visible puede abrir el selector.
     * Desde Cefrium 0.22.0, devolver `false` delega al selector nativo del motor
     * (respeta `accept`, `capture`, cámara vía `CefriumFileProvider`, selección
     * múltiple y carpetas).
     */
    fun chooseFiles(
        session: EngineSession,
        callback: CefriumFileChooserCallback,
        @Suppress("UNUSED_PARAMETER") params: CefriumFileChooserParams
    ): Boolean {
        val host = BrowserHosts.current()
        if (host == null || TabManager.activeSession() !== session) {
            callback.cancel()
            return true
        }
        host.suppressNextAutoPip()
        return false
    }

    private fun field(activity: android.app.Activity, value: String?, hint: String, password: Boolean = false): EditText =
        EditText(activity).apply {
            setText(value.orEmpty())
            this.hint = hint
            setSingleLine(true)
            textSize = 15f
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
            ThemeManager.styleEdit(activity, this)
        }

    private fun container(activity: android.app.Activity, vararg fields: EditText): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val d = activity.resources.displayMetrics.density
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            fields.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * d).toInt()).apply {
                    if (index > 0) topMargin = (10 * d).toInt()
                })
            }
        }
}
