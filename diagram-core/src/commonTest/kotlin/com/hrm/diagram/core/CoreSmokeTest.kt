package com.hrm.diagram.core

import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.theme.DiagramTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreSmokeTest {
    @Test
    fun packageIsAlive() {
        assertEquals(true, LayoutOptions().incremental)
        assertEquals(DiagramTheme.Default.background, DiagramTheme.Default.palette.surface)
    }
}
