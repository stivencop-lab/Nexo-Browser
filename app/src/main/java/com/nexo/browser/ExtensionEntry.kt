package com.nexo.browser

/** Extensión instalada tal como la muestra la interfaz, sin tipos del motor. */
data class ExtensionEntry(
    val id: String,
    val name: String?,
    val enabled: Boolean,
    /** Página de opciones (para mostrar su nombre en la barra de direcciones). */
    val optionsPageUrl: String?
)
