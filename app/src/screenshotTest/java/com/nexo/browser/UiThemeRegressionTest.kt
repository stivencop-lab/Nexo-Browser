package com.nexo.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Real View measurement and insets, independent of Gecko native startup or network. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class UiThemeRegressionTest {
    private fun measure(view: View, widthDp: Int, heightDp: Int = 800) {
        val d = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * d).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun capture(view: View, name: String) {
        val output = System.getProperty("nexo.shots") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output).mkdirs()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun settingsRespectAllModesAndTwoHundredPercentFont() {
        RuntimeEnvironment.setFontScale(2f)
        val app = RuntimeEnvironment.getApplication()
        for (mode in listOf(BrowserPrefs.THEME_LIGHT, BrowserPrefs.THEME_MIDNIGHT, BrowserPrefs.THEME_OLED, BrowserPrefs.THEME_SYSTEM)) {
            BrowserPrefs.setTheme(app, mode)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.findViewById<View>(R.id.settingsRoot)
            measure(root, 360)
            val spinner = activity.findViewById<Spinner>(R.id.themeSpinner)
            val selected = spinner.selectedView
            assertNotNull(selected)
            assertTrue("Spinner must grow with its text", spinner.height >= selected.measuredHeight)
            assertTrue(spinner.height >= 48 * activity.resources.displayMetrics.density)
            val save = activity.findViewById<Button>(R.id.saveSettingsButton)
            assertTrue("Multiline action must not clip", save.height >= save.layout.height + save.compoundPaddingTop + save.compoundPaddingBottom)
            assertTrue(ThemeManager.palette(activity).isDark == (mode != BrowserPrefs.THEME_LIGHT &&
                (mode != BrowserPrefs.THEME_SYSTEM || app.resources.configuration.uiMode and 0x30 == 0x20)))
            capture(root, "settings-$mode-font200")
            controller.pause().stop().destroy()
        }
    }

    @Test fun safeAreasCombineKeyboardAndCutoutOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        ThemeManager.applyWindow(a)
        a.setContentView(FrameLayout(a))
        val content = a.findViewById<View>(android.R.id.content)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 20))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(30, 0, 10, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200)).build()
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(content, insets) }
        assertEquals(30, content.paddingLeft)
        assertEquals(10, content.paddingRight)
        assertEquals(24, content.paddingTop)
        assertEquals(200, content.paddingBottom)
        val closed = WindowInsetsCompat.Builder(insets).setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE).build()
        ViewCompat.dispatchApplyWindowInsets(content, closed)
        assertEquals(20, content.paddingBottom)
        controller.pause().stop().destroy()
    }

    @Test fun tabletColumnAndSheetHaveBoundedWidths() {
        RuntimeEnvironment.setQualifiers("w1000dp-h900dp-xhdpi")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        ThemeManager.applyWindow(a)
        val column = ContentColumn(a)
        measure(column, 1000)
        assertEquals((720 * a.resources.displayMetrics.density).toInt(), column.measuredWidth)
        val content = NexoUi.column(a).apply { addView(NexoUi.chip(a, "Portugués de Brasil", true) {}) }
        val sheet = NexoSheets.show(a, content)
        ShadowLooper.idleMainLooper()
        assertEquals((640 * a.resources.displayMetrics.density).toInt(), sheet.window!!.attributes.width)
        capture(sheet.window!!.decorView, "sheet-tablet")
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [30, 31]) fun dynamicColorHasFallbackAndDoesNotRetainWrongTheme() {
        val app = RuntimeEnvironment.getApplication()
        BrowserPrefs.setTheme(app, BrowserPrefs.THEME_LIGHT)
        BrowserPrefs.setAccent(app, BrowserPrefs.ACCENT_DYNAMIC)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        val seed = if (Build.VERSION.SDK_INT >= 31) a.getColor(android.R.color.system_accent1_500) else BrowserColors.COBALT
        assertEquals(BrowserColors.create(seed, false), ThemeManager.palette(a))
        assertSame(ThemeManager.palette(a), ThemeManager.palette(a))
        val key = ThemeManager.themeKey(a)
        BrowserPrefs.setTheme(app, BrowserPrefs.THEME_OLED)
        assertNotEquals(key, ThemeManager.themeKey(a))
        assertTrue(ThemeManager.palette(a).isAmoled)
        controller.pause().stop().destroy()
    }

    @Test fun miniPlayerHasItsOwnAccessibleAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        val mini = Button(a).apply { id = R.id.miniPlayerButton }
        CaptionsUi.styleChip(a, mini, false)
        assertEquals("Abrir mini reproductor", mini.contentDescription)
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [26, 35]) fun nativeDialogAccentMatchesViewTokens() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        for (mode in listOf(BrowserPrefs.THEME_LIGHT, BrowserPrefs.THEME_MIDNIGHT)) {
            BrowserPrefs.setTheme(app, mode)
            for (accent in listOf(BrowserPrefs.ACCENT_COBALT, BrowserPrefs.ACCENT_VIOLET, BrowserPrefs.ACCENT_CYAN,
                BrowserPrefs.ACCENT_LIME, BrowserPrefs.ACCENT_ORANGE, BrowserPrefs.ACCENT_PINK, BrowserPrefs.ACCENT_RED)) {
                BrowserPrefs.setAccent(app, accent)
                ThemeManager.applyWindow(a)
                val value = android.util.TypedValue()
                assertTrue(a.theme.resolveAttribute(android.R.attr.colorAccent, value, true))
                val color = if (value.resourceId == 0) value.data else a.getColor(value.resourceId)
                assertEquals(ThemeManager.palette(a).accent, color)
            }
        }
        controller.pause().stop().destroy()
    }

    @Test fun systemModeFollowsAndroidWithoutReplacingTheSavedChoice() {
        val app = RuntimeEnvironment.getApplication()
        BrowserPrefs.setTheme(app, BrowserPrefs.THEME_SYSTEM)
        for (dark in listOf(true, false)) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-" + (if (dark) "night" else "notnight") + "-xhdpi")
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            assertEquals(dark, ThemeManager.palette(controller.get()).isDark)
            assertEquals(BrowserPrefs.THEME_SYSTEM, BrowserPrefs.theme(app))
            controller.pause().stop().destroy()
        }
    }
}
