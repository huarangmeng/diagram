package com.hrm.diagram.core.theme

import com.hrm.diagram.core.draw.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun defaultThemesProvideBackground() {
        assertEquals(Color(0xFFFFFFFF.toInt()), DiagramTheme.Default.background)
        assertEquals(Color(0xFF0D1117.toInt()), DiagramTheme.Dark.background)
    }

    @Test
    fun defaultsAreStable() {
        // Re-evaluating MUST yield equal value (companion-object backed).
        assertEquals(DiagramTheme.Default, DiagramTheme.Default)
    }

    @Test
    fun darkAndLightDiffer() {
        assertNotEquals(DiagramTheme.Default, DiagramTheme.Dark)
    }

    @Test
    fun nodeDefaultsInheritOnSurface() {
        val t = DiagramTheme.Default
        assertTrue(t.nodeDefaults.textColor!!.argb == t.palette.onSurface.argb)
    }
}
