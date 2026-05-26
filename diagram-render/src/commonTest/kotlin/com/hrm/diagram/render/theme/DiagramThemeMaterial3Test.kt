package com.hrm.diagram.render.theme

import androidx.compose.material3.lightColorScheme
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.theme.DiagramTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DiagramThemeMaterial3Test {
    @Test
    fun material3MapsColorSchemeIntoTheme() {
        val scheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
            secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
            tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260),
            surface = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
            onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
            outline = androidx.compose.ui.graphics.Color(0xFF79747E),
            error = androidx.compose.ui.graphics.Color(0xFFB3261E),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8DEF8),
            errorContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC),
            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE6E0E9),
        )

        val theme = DiagramTheme.material3(scheme)

        assertEquals(Color(0xFF6750A4.toInt()), theme.colors.accent)
        assertEquals(Color(0xFFFFFBFE.toInt()), theme.background)
        assertEquals(Color(0xFF1C1B1F.toInt()), theme.graphColors.nodeText)
        assertEquals(Color(0xFFE8DEF8.toInt()), theme.colors.selection)
        assertEquals(Color(0xFFF9DEDC.toInt()), theme.colors.diagnostic)
    }

    @Test
    fun material3OverridesDefaultPaletteButKeepsStructure() {
        val scheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF005AC1),
            secondary = androidx.compose.ui.graphics.Color(0xFF5B5F97),
            tertiary = androidx.compose.ui.graphics.Color(0xFF7C4D00),
            surface = androidx.compose.ui.graphics.Color(0xFFF8F9FF),
            onSurface = androidx.compose.ui.graphics.Color(0xFF171C22),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF5C6470),
            outline = androidx.compose.ui.graphics.Color(0xFF747C89),
            error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFDDE1FF),
            errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFDFE2EB),
        )

        val theme = DiagramTheme.material3(scheme, base = DiagramTheme.Dark)
        val resolved = ThemeResolver.resolveSequence(theme)

        assertNotEquals(DiagramTheme.Dark.colors.accent, theme.colors.accent)
        assertEquals(theme.colors.accent, resolved.headerStroke)
        assertEquals(theme.colors.warning, resolved.noteStroke)
        assertEquals(theme.colors.surface, resolved.activationFill)
    }
}
