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

internal enum class GlassIntensity(
    val radiusDp: Float,
    val label: String,
    val darkAlpha: Int,
    val oledAlpha: Int,
    val lightAlpha: Int,
    val fallbackDarkAlpha: Int,
    val fallbackLightAlpha: Int,
    val sheenStrength: Float,
    val sheetAlphaDark: Int,
    val sheetAlphaLight: Int
) {
    SUBTLE(
        radiusDp = 16f,
        label = "Sutil",
        darkAlpha = 186,
        oledAlpha = 178,
        lightAlpha = 204,
        fallbackDarkAlpha = 216,
        fallbackLightAlpha = 224,
        sheenStrength = 0.65f,
        sheetAlphaDark = 236,
        sheetAlphaLight = 238
    ),
    BALANCED(
        radiusDp = 28f,
        label = "Equilibrado",
        darkAlpha = 138,
        oledAlpha = 132,
        lightAlpha = 166,
        fallbackDarkAlpha = 194,
        fallbackLightAlpha = 208,
        sheenStrength = 1.0f,
        sheetAlphaDark = 216,
        sheetAlphaLight = 222
    ),
    MAXIMUM(
        radiusDp = 36f,
        label = "Máximo Cristal",
        darkAlpha = 96,
        oledAlpha = 94,
        lightAlpha = 128,
        fallbackDarkAlpha = 172,
        fallbackLightAlpha = 188,
        sheenStrength = 1.35f,
        sheetAlphaDark = 190,
        sheetAlphaLight = 200
    )
}
