package com.nexo.browser

/** Partition the existing actions without hiding or duplicating any command. */
object MenuSections {
    data class Group(val title: String, val actions: List<SheetAction>)
    data class Layout(val quick: List<SheetAction>, val groups: List<Group>)

    fun arrange(actions: List<SheetAction>): Layout {
        val quickIds = setOf(R.drawable.ic_menu_download, R.drawable.ic_menu_desktop,
            R.drawable.ic_menu_find, R.drawable.ic_menu_translate)
        val pageIds = setOf(R.drawable.ic_menu_copy, R.drawable.ic_menu_share, R.drawable.ic_menu_pin,
            R.drawable.ic_menu_screenshot, R.drawable.ic_menu_pdf)
        val libraryIds = setOf(R.drawable.ic_menu_history, R.drawable.ic_menu_reopen, R.drawable.ic_tab_tools)
        val prefIds = setOf(R.drawable.ic_menu_settings, R.drawable.ic_menu_update)
        val quick = actions.filter { it.icon in quickIds }
        val remainder = actions.filterNot { it.icon in quickIds }
        val groups = listOf(
            Group("Esta página", remainder.filter { it.icon in pageIds }),
            Group("Pestañas e historial", remainder.filter { it.icon in libraryIds }),
            Group("Preferencias", remainder.filter { it.icon in prefIds }),
            Group("Más opciones", remainder.filterNot { it.icon in pageIds || it.icon in libraryIds || it.icon in prefIds })
        ).flatMap { group -> group.actions.chunked(5).map { Group(group.title, it) } }
        return Layout(quick, groups)
    }
}
