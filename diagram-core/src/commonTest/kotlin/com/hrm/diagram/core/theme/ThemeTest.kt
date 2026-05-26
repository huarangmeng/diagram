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
        assertTrue(t.nodeDefaults.textColor!!.argb == t.colors.textPrimary.argb)
    }

    @Test
    fun graphScopesTrackPaletteDefaults() {
        val t = DiagramTheme.Default
        assertEquals(t.colors.surface, t.graphColors.nodeFill)
        assertEquals(t.colors.border, t.graphColors.nodeStroke)
        assertEquals(t.colors.warning, t.sequenceColors.noteStroke)
        assertEquals(t.colors.accent, t.treeColors.rootStroke)
    }

    @Test
    fun semanticColorsExposeSecondarySlots() {
        val t = DiagramTheme.Dark
        assertNotEquals(t.colors.surface, t.colors.surfaceAlt)
        assertEquals(t.colors.textSecondary.argb, DiagramTheme.Dark.colors.textSecondary.argb)
        assertEquals(t.colors.diagnostic, t.colors.danger)
    }
}
