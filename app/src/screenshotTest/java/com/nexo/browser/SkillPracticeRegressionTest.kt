package com.nexo.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Switch
import androidx.core.view.ViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SkillPracticeRegressionTest {
    @Test @Config(sdk = [29, 33, 35])
    fun privateClipboardMarksSensitivityAndPreservesTheCopiedText() {
        val privateClip = ClipboardPrivacy.plainText("Enlace", "https://example.com/private", sensitive = true)
        assertEquals("https://example.com/private", privateClip.getItemAt(0).text.toString())
        assertEquals("Enlace", privateClip.description.label.toString())
        assertTrue(privateClip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        val normalClip = ClipboardPrivacy.plainText("Enlace", "https://example.com/", sensitive = false)
        assertNull(normalClip.description.extras)
        assertEquals("https://example.com/", normalClip.getItemAt(0).text.toString())
    }

    @Test fun externalIntentCannotCarryActionsPermissionsSelectorsOrArbitraryExtras() {
        val app = RuntimeEnvironment.getApplication()
        val raw = "intent://pay/#Intent;scheme=demo;action=android.intent.action.SEND;" +
            "category=android.intent.category.DEFAULT;component=other.app/.Secret;package=other.app;" +
            "launchFlags=0x000000c3;S.android.intent.extra.TEXT=untrusted;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2F;end"
        val intent = ExternalApps.buildIntent(app, raw)!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.component); assertNull(intent.selector); assertNull(intent.clipData)
        assertEquals(setOf("browser_fallback_url"), intent.extras!!.keySet())
        assertEquals("other.app", intent.`package`)
        assertNull(ExternalApps.buildIntent(app, "intent://x/#Intent;scheme=file;end"))
        assertNull(ExternalApps.buildIntent(app, "intent://x/#Intent;scheme=demo;package=${app.packageName};end"))
        assertNotNull(ExternalApps.buildIntent(app, "mailto:hello@example.com"))
    }

    @Test fun privateProtectionPropagatesToSheetsAndClearsWithoutRemovingOtherFlags() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowPrivacy.apply(activity.window, true)
        val sheet = NexoSheets.show(activity, NexoUi.note(activity, "Privado"))
        assertTrue(sheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        sheet.dismiss()
        WindowPrivacy.apply(activity.window, false)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        controller.pause().stop().destroy()
    }

    @Test fun groupedOptionRowsKeepKeylinesAndExposeToggleStateAtLargeFonts() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        ThemeManager.applyWindow(activity)
        var clicks = 0
        val row = NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Configuración avanzada", "Valor actual") { clicks++ }
        val d = activity.resources.displayMetrics.density
        row.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * d).toInt(), View.MeasureSpec.AT_MOST))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        assertEquals((16 * d).toInt(), row.getChildAt(0).left)
        assertEquals((68 * d).toInt(), row.getChildAt(1).left)
        assertTrue(row.height >= 64 * d)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.getChildAt(0).importantForAccessibility)
        row.performClick(); assertEquals(1, clicks)
        val grid = NexoSheets.grid(activity, listOf(SheetAction(R.drawable.ic_menu_desktop, "Escritorio", checked = true) {})) {} as ViewGroup
        val cell = (grid.getChildAt(0) as ViewGroup).getChildAt(0)
        val info = cell.createAccessibilityNodeInfo()
        assertTrue(info.isCheckable); assertTrue(info.isChecked); assertTrue(cell.isSelected)
        val chevron = row.getChildAt(2) as ImageView
        assertTrue(chevron.drawable.isAutoMirrored)
        controller.pause().stop().destroy()
    }

    @Test fun dependentSettingsReflectMasterStateWithoutErasingTheChildPreference() {
        val app = RuntimeEnvironment.getApplication()
        BrowserPrefs.setTrackingProtection(app, false)
        BrowserPrefs.setStrictTracking(app, true)
        BrowserPrefs.setCaptionsEnabled(app, false)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val child = activity.findViewById<Switch>(R.id.strictTrackingSwitch)
        assertFalse(child.isEnabled)
        assertTrue(child.isChecked)
        assertFalse(activity.findViewById<View>(R.id.configureCaptionsButton).isEnabled)
        activity.findViewById<Switch>(R.id.trackingSwitch).isChecked = true
        activity.findViewById<Switch>(R.id.captionsEnabledSwitch).isChecked = true
        assertTrue(child.isEnabled)
        assertTrue(activity.findViewById<View>(R.id.configureCaptionsButton).isEnabled)
        assertTrue(BrowserPrefs.strictTracking(app))
        controller.pause().stop().destroy()
    }

    @Test fun realImageCacheRespondsToForegroundCriticalMemory() {
        val field = RemoteImageLoader::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as android.util.LruCache<String, Bitmap>
        cache.evictAll()
        val images = List(2) { Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888) }
        images.forEachIndexed { i, image -> cache.put("test-$i", image) }
        assertTrue(cache.size() > cache.maxSize() / 2)
        RemoteImageLoader.trimMemory(10)
        assertTrue(cache.size() <= cache.maxSize() / 2)
        RemoteImageLoader.trimMemory(15)
        assertEquals(0, cache.size())
        images.forEach { it.recycle() }
    }
}
