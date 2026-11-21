package com.nexo.browser

internal object NexoTokens {
    const val OBSIDIAN = 0xFF09090B.toInt()
    const val OLED = 0xFF000000.toInt()
    const val PAPER = 0xFFF5F4F0.toInt()
    const val COBALT = 0xFF2B59FF.toInt()
    const val VERMILION = 0xFFE84E24.toInt()
    const val SAGE = 0xFF1F9D63.toInt()

    const val WINDOW_RADIUS = 44f
    const val SHEET_RADIUS = 34f
    const val CARD_RADIUS = 26f
    const val CONTROL_RADIUS = 18f
    const val PILL_RADIUS = 999f
}

internal enum class GlassIntensity(val radiusDp: Float, val label: String) {
    SUBTLE(16f, "Sutil"),
    BALANCED(28f, "Equilibrado"),
    MAXIMUM(36f, "Máximo Cristal")
}
