package com.hrm.diagram.layout.gauge

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram

/**
 * Minimal deterministic layout for [GaugeIR].
 *
 * Layout is intentionally mostly fixed-size to keep incremental updates stable:
 * - `gauge:title`
 * - `gauge:value`
 * - `gauge:min`
 * - `gauge:max`
 *
 * Text measurement is not required for positioning since DrawCommand supports anchors.
 * Render/export will not re-measure text.
 */
class GaugeLayout : IncrementalLayout<GaugeIR> {
    override fun layout(previous: LaidOutDiagram?, model: GaugeIR, options: LayoutOptions): LaidOutDiagram {
        val pad = 20f
        val w = 420f
        val h = 260f
        val bounds = Rect.ltrb(0f, 0f, w, h)

        val titleY = pad
        val center = Point(w / 2f, 150f)
        val nodePositions = linkedMapOf(
            NodeId("gauge:title") to Rect(Point(pad, titleY), Size(w - 2 * pad, 24f)),
            NodeId("gauge:value") to Rect(Point(center.x - 60f, center.y - 12f), Size(120f, 24f)),
            NodeId("gauge:min") to Rect(Point(pad, center.y + 70f), Size(80f, 18f)),
            NodeId("gauge:max") to Rect(Point(w - pad - 80f, center.y + 70f), Size(80f, 18f)),
        )

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = bounds,
        )
    }
}

