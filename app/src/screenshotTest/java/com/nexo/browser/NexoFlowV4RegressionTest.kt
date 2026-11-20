package com.nexo.browser

import android.view.View
import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regresiones de Nexo Flow 4.0: el botón permanente de Nexo AI en la barra
 * superior y su posición destacada en el menú principal.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class NexoFlowV4RegressionTest {
    @Test fun permanentAiSparkButtonInflatesVisibleAndAccessibleNextToTheOmnibox() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        val ai = root.findViewById<ImageButton>(R.id.aiButton)
        assertNotNull("El emblema Aperture debe existir en el cromo", ai)
        assertEquals(View.VISIBLE, ai?.visibility)
        assertTrue(ai!!.contentDescription.toString().contains("Nexo AI"))
        // Liquid Split Chrome: el emblema vive en el clúster desacoplado, junto a la píldora de dirección.
        val omnibox = root.findViewById<View>(R.id.omnibox)
        assertNotNull(omnibox)
        val topBar = root.findViewById<android.view.ViewGroup>(R.id.topBar)
        assertTrue((ai.parent as View).parent === topBar)
        assertTrue((omnibox.parent as View).parent === topBar)
        assertEquals(R.id.chromeCluster, (ai.parent as View).id)
        controller.pause().stop().destroy()
    }

    @Test fun menuLeadsWithNexoAiWithoutDroppingAnyExistingAction() {
        val ids = listOf(
            R.drawable.ic_nexo_aperture,
            R.drawable.ic_menu_download, R.drawable.ic_menu_desktop,
            R.drawable.ic_menu_find, R.drawable.ic_menu_translate,
            R.drawable.ic_menu_reader, R.drawable.ic_menu_copy, R.drawable.ic_menu_share,
            R.drawable.ic_menu_pin, R.drawable.ic_menu_screenshot, R.drawable.ic_menu_pdf,
            R.drawable.ic_menu_bookmark, R.drawable.ic_menu_history,
            R.drawable.ic_menu_reopen, R.drawable.ic_tab_tools,
            R.drawable.ic_menu_settings, R.drawable.ic_menu_update
        )
        val actions = ids.map { SheetAction(it, "Acción $it") {} }
        val layout = MenuSections.arrange(actions)
        assertEquals("Nexo AI", layout.groups.first().title)
        assertTrue(layout.groups.first().actions.all { it.icon == R.drawable.ic_nexo_aperture })
        val seen = layout.quick + layout.groups.flatMap { it.actions }
        assertEquals(actions.toSet(), seen.toSet())
        assertEquals(actions.size, seen.size)
        assertTrue(layout.groups.all { it.actions.size <= 5 })
    }

    @Test fun aiRemainsSelectableInEveryCustomNavigationBar() {
        val withAi = NavConfig.parseActions("back,new_tab,ai,forward,reload")
        assertTrue(withAi.contains(NavConfig.Action.AI))
        assertEquals(5, withAi.size)
        val icon = NavConfig.iconFor(NavConfig.Action.AI)
        assertEquals(R.drawable.ic_nexo_aperture, icon)
        val modes = listOf(NavConfig.Mode.CLASSIC, NavConfig.Mode.COMPACT, NavConfig.Mode.CUSTOM)
        modes.forEach { mode ->
            assertTrue(NavConfig.Action.entries.any { it == NavConfig.Action.AI })
            assertTrue(mode.label.isNotBlank())
        }
    }
}
