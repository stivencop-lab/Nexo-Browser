package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Los temas nativos (diálogos, cursor, interruptores del sistema) deben usar exactamente los
 * mismos tokens que las vistas. Regenerar: `NEXO_WRITE_TOKENS=true ./gradlew :app:testDebugUnitTest --tests "*NativeThemeTokensTest"`.
 */
class NativeThemeTokensTest {
    private val accents = listOf(
        "mono" to BrowserColors.MONOCHROME,
        "signal" to BrowserColors.SIGNAL,
        "cobalt" to BrowserColors.COBALT,
        "orange" to BrowserColors.ORANGE,
        "sage" to BrowserColors.SAGE
    )

    private fun expected(): String {
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            .append("<!-- Nexo UI 7.0 «Liquid Craft». Generado desde BrowserColors.kt por NativeThemeTokensTest. -->\n<resources>\n")
        for ((name, seed) in accents) for (dark in listOf(false, true)) {
            val p = BrowserColors.create(seed, dark)
            val roles = listOf(
                "primary" to p.accent, "background" to p.background, "elevated" to p.surfaceContainerHigh,
                "text" to p.text, "muted" to p.muted, "outline" to p.outlineVariant,
                "primary_container" to p.accentSoft, "on_primary_container" to p.onAccentSoft
            )
            for ((role, color) in roles) {
                xml.append(String.format("  <color name=\"nexo_%s_%s_%s\">#%08X</color>\n", if (dark) "dark" else "light", name, role, color))
            }
        }
        for (mode in listOf("light", "dark")) {
            for ((alias, role) in listOf("background" to "background", "surface" to "elevated", "text" to "text", "outline" to "outline")) {
                xml.append("  <color name=\"nexo_${mode}_$alias\">@color/nexo_${mode}_mono_$role</color>\n")
            }
        }
        return xml.append("</resources>\n").toString()
    }

    @Test fun nativeColorsMatchViewTokens() {
        val file = listOf(File("src/main/res/values/colors.xml"), File("app/src/main/res/values/colors.xml")).first { it.parentFile.exists() }
        val want = expected()
        if (System.getenv("NEXO_WRITE_TOKENS") == "true") file.writeText(want)
        assertEquals("Regenera con NEXO_WRITE_TOKENS=true", want, file.readText())
    }
}
