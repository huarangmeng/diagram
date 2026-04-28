package com.hrm.diagram.layout.pie

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.max

/**
 * Minimal deterministic layout for [PieIR].
 *
 * Output uses synthetic [NodeId]s for legend rows and title:
 * - `pie:title`
 * - `pie:legend:<index>`
 *
 * The actual wedge geometry is computed in the renderer; layout only decides text rectangles
 * and overall bounds, so render/export never needs to measure text.
 */
class PieLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<PieIR> {

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val legendFont = FontSpec(family = "sans-serif", sizeSp = 12f)

    override fun layout(previous: LaidOutDiagram?, model: PieIR, options: LayoutOptions): LaidOutDiagram {
        val pad = 20f
        val gap = 18f
        val radius = 120f
        val diameter = radius * 2f

        val nodePositions = LinkedHashMap<NodeId, Rect>()

        var y0 = pad
        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont)
            val r = Rect(Point(pad, y0), Size(m.width, m.height))
            nodePositions[NodeId("pie:title")] = r
            y0 += m.height + 10f
        }

        val pieTop = y0
        val pieLeft = pad
        val pieRect = Rect(Point(pieLeft, pieTop), Size(diameter, diameter))

        val legendLeft = pieRect.right + gap
        var legendY = pieTop

        var maxLegendW = 0f
        for ((i, s) in model.slices.withIndex()) {
            val label = (s.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text ?: "slice$i"
            val valueText = formatValue(s.value)
            val m1 = textMeasurer.measure(label, legendFont)
            val m2 = textMeasurer.measure(valueText, legendFont)
            val rowH = max(m1.height, m2.height) + 6f
            val rowW = 14f + 8f + m1.width + 12f + m2.width
            maxLegendW = max(maxLegendW, rowW)
            nodePositions[NodeId("pie:legend:$i")] = Rect(Point(legendLeft, legendY), Size(rowW, rowH))
            legendY += rowH + 4f
        }

        val boundsRight = if (model.slices.isEmpty()) pieRect.right else (legendLeft + maxLegendW)
        val boundsBottom = max(pieRect.bottom, legendY)
        val bounds = Rect.ltrb(0f, 0f, boundsRight + pad, boundsBottom + pad)

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = bounds,
        )
    }

    private fun formatValue(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}

