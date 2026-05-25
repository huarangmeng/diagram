package com.hrm.diagram.render.compose

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagramViewportStateTest {
    @Test
    fun zoom_keeps_anchor_stable() {
        val state = DiagramViewportState()

        state.applyZoom(anchor = Offset(100f, 80f), zoomChange = 2f)

        assertEquals(2f, state.zoom)
        assertEquals(Offset(-100f, -80f), state.pan)
    }

    @Test
    fun gesture_applies_pan_before_anchor_zoom() {
        val state = DiagramViewportState()

        state.applyGesture(
            centroid = Offset(50f, 50f),
            panDelta = Offset(10f, 20f),
            zoomChange = 2f,
        )

        assertEquals(2f, state.zoom)
        assertEquals(Offset(-30f, -10f), state.pan)
    }
}
