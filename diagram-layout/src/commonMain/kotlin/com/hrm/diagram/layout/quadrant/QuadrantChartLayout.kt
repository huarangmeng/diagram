package com.hrm.diagram.layout.quadrant

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.QuadrantPoint
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram

/**
 * Fixed-size deterministic layout for Mermaid quadrantChart.
 *
 * Layout only places textual anchors and point label boxes:
 * - `quadrant:title`
 * - `quadrant:plot`
 * - axis/quadrant labels
 * - `quadrant:pointLabel:<id>`
 */
class QuadrantChartLayout {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val axisFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val quadrantFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val pointFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    fun layout(model: QuadrantChartIR): LaidOutDiagram {
        val nodePositions = LinkedHashMap<NodeId, Rect>()
        val width = 560f
        val height = 560f
        val pad = 18f
        val plot = Rect.ltrb(70f, 68f, width - 40f, height - 56f)
        nodePositions[NodeId("quadrant:plot")] = plot

        model.title?.takeIf { it.isNotBlank() }?.let {
            nodePositions[NodeId("quadrant:title")] = Rect(Point(pad, pad), Size(260f, 20f))
        }

        nodePositions[NodeId("quadrant:xMin")] = Rect(Point(plot.left + 8f, plot.bottom + 10f), Size(90f, 16f))
        nodePositions[NodeId("quadrant:xMax")] = Rect(Point(plot.right - 90f, plot.bottom + 10f), Size(90f, 16f))
        nodePositions[NodeId("quadrant:yMin")] = Rect(Point(plot.left - 56f, plot.bottom - 18f), Size(50f, 16f))
        nodePositions[NodeId("quadrant:yMax")] = Rect(Point(plot.left - 56f, plot.top + 2f), Size(50f, 16f))

        val midX = (plot.left + plot.right) / 2f
        val midY = (plot.top + plot.bottom) / 2f
        nodePositions[NodeId("quadrant:q1")] = Rect(Point(midX + 12f, plot.top + 10f), Size(120f, 18f))
        nodePositions[NodeId("quadrant:q2")] = Rect(Point(plot.left + 12f, plot.top + 10f), Size(120f, 18f))
        nodePositions[NodeId("quadrant:q3")] = Rect(Point(plot.left + 12f, midY + 10f), Size(120f, 18f))
        nodePositions[NodeId("quadrant:q4")] = Rect(Point(midX + 12f, midY + 10f), Size(120f, 18f))

        for (p in model.points) {
            val pt = pointAt(plot, p)
            nodePositions[NodeId("quadrant:pointLabel:${p.id.value}")] =
                Rect(Point(pt.x + 8f, pt.y - 6f), Size(120f, 28f))
        }

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, width, height),
        )
    }

    private fun pointAt(plot: Rect, p: QuadrantPoint): Point =
        Point(
            (plot.left + plot.size.width * p.x).toFloat(),
            (plot.bottom - plot.size.height * p.y).toFloat(),
        )
}

