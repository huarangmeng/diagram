package com.hrm.diagram.render.compose

import com.hrm.diagram.core.draw.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagramSurfacePresentationTest {
    @Test
    fun auto_mode_prefers_embedded_for_width_only_hosts() {
        assertEquals(
            DiagramPresentationMode.Embedded,
            resolveDiagramPresentationMode(
                requested = DiagramPresentationMode.Auto,
                hasBoundedWidth = true,
                hasBoundedHeight = false,
            ),
        )
    }

    @Test
    fun auto_mode_prefers_viewport_for_fully_bounded_hosts() {
        assertEquals(
            DiagramPresentationMode.Viewport,
            resolveDiagramPresentationMode(
                requested = DiagramPresentationMode.Auto,
                hasBoundedWidth = true,
                hasBoundedHeight = true,
            ),
        )
    }

    @Test
    fun embedded_mode_uses_natural_bounds_plus_padding_when_space_is_available() {
        val layout = resolveDiagramPresentationLayout(
            bounds = Rect.ltrb(10f, 20f, 210f, 120f),
            mode = DiagramPresentationMode.Embedded,
            maxWidth = 480f,
            maxHeight = 320f,
        )

        assertEquals(224f, layout.width)
        assertEquals(124f, layout.height)
    }

    @Test
    fun embedded_mode_scales_down_to_parent_width() {
        val layout = resolveDiagramPresentationLayout(
            bounds = Rect.ltrb(0f, 0f, 300f, 150f),
            mode = DiagramPresentationMode.Embedded,
            maxWidth = 162f,
        )

        assertEquals(162f, layout.width)
        assertEquals(87f, layout.height)
    }

    @Test
    fun viewport_mode_consumes_available_space_when_fully_bounded() {
        val layout = resolveDiagramPresentationLayout(
            bounds = Rect.ltrb(0f, 0f, 300f, 150f),
            mode = DiagramPresentationMode.Viewport,
            maxWidth = 640f,
            maxHeight = 360f,
        )

        assertEquals(640f, layout.width)
        assertEquals(360f, layout.height)
    }

    @Test
    fun viewport_mode_falls_back_to_embedded_when_height_is_unbounded() {
        val layout = resolveDiagramPresentationLayout(
            bounds = Rect.ltrb(0f, 0f, 300f, 150f),
            mode = DiagramPresentationMode.Viewport,
            maxWidth = 162f,
            maxHeight = null,
        )

        assertEquals(162f, layout.width)
        assertEquals(87f, layout.height)
    }
}
