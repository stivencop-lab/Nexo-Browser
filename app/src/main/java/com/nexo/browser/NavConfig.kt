package com.nexo.browser

import android.content.Context

/**
 * Configuración adaptable de la barra de navegación principal de Nexo:
 * - [Mode.CLASSIC]: barra de direcciones + barra inferior completa de 5 acciones.
 * - [Mode.COMPACT]: barra única con botón Atrás integrado para maximizar el contenido web.
 * - [Mode.CUSTOM]: selección y orden personalizados (entre 3 y 6 acciones) en la barra inferior.
 *
 * Toda la lógica de validación, reordenamiento y decisión de auto-ocultación es pura
 * para poder verificarse directamente en pruebas JVM.
 */
object NavConfig {
    enum class Mode(val id: String, val label: String, val description: String) {
        CLASSIC("classic", "Clásico", "Barra de direcciones y controles inferiores completos"),
        COMPACT("compact", "Compacto", "Una sola barra con más espacio vertical para la web"),
        CUSTOM("custom", "Personalizado", "Elige y ordena entre 3 y 6 acciones en la barra inferior");

        companion object {
            fun fromId(raw: String?): Mode =
                entries.firstOrNull { it.id == raw } ?: CLASSIC
        }
    }

    enum class Action(val id: String, val label: String) {
        BACK("back", "Atrás"),
        FORWARD("forward", "Adelante"),
        HOME("home", "Inicio"),
        RELOAD("reload", "Recargar"),
        NEW_TAB("new_tab", "Nueva pestaña"),
        HISTORY("history", "Historial"),
        DOWNLOADS("downloads", "Descargas"),
        AI("ai", "Nexo AI"),
        SHIELD("shield", "PhantomShield"),
        EXTENSIONS("extensions", "Extensiones");

        companion object {
            fun fromId(raw: String?): Action? =
                entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
        }
    }

    const val MIN_ACTIONS = 3
    const val MAX_ACTIONS = 6
    const val SCROLL_HIDE_THRESHOLD_PX = 120

    val DEFAULT_ACTIONS: List<Action> = listOf(
        Action.BACK,
        Action.FORWARD,
        Action.HOME,
        Action.RELOAD,
        Action.NEW_TAB
    )

    fun parseActions(raw: String?): List<Action> {
        if (raw.isNullOrBlank()) return DEFAULT_ACTIONS
        val parsed = raw.split(',')
            .mapNotNull { Action.fromId(it) }
            .distinct()
            .take(MAX_ACTIONS)
        return if (parsed.size >= MIN_ACTIONS) parsed else DEFAULT_ACTIONS
    }

    fun serializeActions(actions: List<Action>): String {
        val valid = actions.distinct().take(MAX_ACTIONS)
        val final = if (valid.size >= MIN_ACTIONS) valid else DEFAULT_ACTIONS
        return final.joinToString(",") { it.id }
    }

    fun toggle(current: List<Action>, action: Action): List<Action> {
        val clean = current.distinct()
        return if (action in clean) {
            if (clean.size <= MIN_ACTIONS) clean else clean.filterNot { it == action }
        } else {
            if (clean.size >= MAX_ACTIONS) clean else clean + action
        }
    }

    fun move(current: List<Action>, action: Action, delta: Int): List<Action> {
        val list = current.distinct().toMutableList()
        val index = list.indexOf(action)
        if (index < 0) return list
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return list
        list.removeAt(index)
        list.add(target, action)
        return list
    }

    /**
     * Regla pura para decidir si la barra inferior debe ocultarse durante el desplazamiento.
     * Nunca oculta controles esenciales al editar la URL, buscar en página, con teclado abierto,
     * en la página de inicio, pantalla completa o PiP.
     */
    fun shouldAutoHideDock(
        mode: Mode,
        autoHideEnabled: Boolean,
        scrollingDown: Boolean,
        scrollY: Int,
        isStartPage: Boolean,
        omniboxFocused: Boolean,
        findBarVisible: Boolean,
        imeVisible: Boolean,
        fullScreen: Boolean,
        isPip: Boolean
    ): Boolean {
        if (!autoHideEnabled || mode == Mode.COMPACT) return false
        if (isStartPage || omniboxFocused || findBarVisible || imeVisible || fullScreen || isPip) return false
        return scrollingDown && scrollY >= SCROLL_HIDE_THRESHOLD_PX
    }

    fun mode(context: Context): Mode = Mode.fromId(BrowserPrefs.navMode(context))
    fun setMode(context: Context, mode: Mode) = BrowserPrefs.setNavMode(context, mode.id)

    fun actions(context: Context): List<Action> = parseActions(BrowserPrefs.navActions(context))
    fun setActions(context: Context, actions: List<Action>) =
        BrowserPrefs.setNavActions(context, serializeActions(actions))

    fun iconFor(action: Action): Int = when (action) {
        Action.BACK -> R.drawable.ic_nav_back
        Action.FORWARD -> R.drawable.ic_nav_forward
        Action.HOME -> R.drawable.ic_nav_home
        Action.RELOAD -> R.drawable.ic_nav_reload
        Action.NEW_TAB -> R.drawable.ic_nav_add
        Action.HISTORY -> R.drawable.ic_menu_history
        Action.DOWNLOADS -> R.drawable.ic_menu_download
        Action.AI -> R.drawable.ic_menu_ai
        Action.SHIELD -> R.drawable.ic_menu_shield
        Action.EXTENSIONS -> R.drawable.ic_menu_extensions
    }
}
