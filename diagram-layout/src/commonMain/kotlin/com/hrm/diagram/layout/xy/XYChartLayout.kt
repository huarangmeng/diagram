package com.hrm.diagram.layout.xy

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.abs
import kotlin.math.truncate

/**
 * Minimal deterministic layout for Mermaid XY charts.
 *
 * Layout stores only textual rect anchors and the plot area bounds:
 * - `xychart:title`
 * - `xychart:xTitle`
 * - `xychart:yTitle`
 * - `xychart:plot`
 * - `xychart:xLabel:<idx>`
 * - `xychart:yLabel:<idx>`
 *
 * Render will consume these rects plus the raw [XYChartIR] to draw geometry without measuring text.
 */
class XYChartLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val axisTitleFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val axisLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    fun layout(previous: LaidOutDiagram?, model: XYChartIR, options: LayoutOptions): LaidOutDiagram {
        val nodePositions = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Rect>()
        val pad = 18f
        val width = 760f
        val height = 520f
        var y = pad

        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont, maxWidth = width - 2 * pad)
            nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:title")] = Rect(Point(pad, y), Size(m.width, m.height))
            y += m.height + 10f
        }

        val plotLeft = 104f
        val plotTop = y + 12f
        val plotRight = width - 40f
        val plotBottom = height - 80f
        nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:plot")] = Rect.ltrb(plotLeft, plotTop, plotRight, plotBottom)

        val xTitle = (model.xAxis.title as? RichLabel.Plain)?.text
        if (!xTitle.isNullOrBlank()) {
            val m = textMeasurer.measure(xTitle, axisTitleFont, maxWidth = plotRight - plotLeft)
            nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:xTitle")] =
                Rect(Point((plotLeft + plotRight - m.width) / 2f, plotBottom + 46f), Size(m.width, m.height))
        }
        val yTitle = (model.yAxis.title as? RichLabel.Plain)?.text
        if (!yTitle.isNullOrBlank()) {
            val m = textMeasurer.measure(yTitle, axisTitleFont, maxWidth = 120f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:yTitle")] =
                Rect(Point(24f, (plotTop + plotBottom - m.height) / 2f), Size(m.width, m.height))
        }

        for ((idx, label) in model.xAxis.categories.withIndex()) {
            val m = textMeasurer.measure(label, axisLabelFont, maxWidth = 72f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:xLabel:$idx")] =
                Rect(Point(0f, plotBottom + 14f), Size(m.width, m.height))
        }
        val yTicks = 5
        val yMin = model.yAxis.min ?: 0.0
        val yMax = model.yAxis.max ?: 1.0
        for (i in 0..yTicks) {
            val v = yMin + (yMax - yMin) * (i.toDouble() / yTicks.toDouble())
            val txt = formatTick(v)
            val m = textMeasurer.measure(txt, axisLabelFont, maxWidth = 70f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("xychart:yLabel:$i")] =
                Rect(Point(plotLeft - m.width - 12f, 0f), Size(m.width, m.height))
        }

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, width, height),
        )
    }

    private fun formatTick(v: Double): String {
        val rounded = truncate(v + if (v >= 0.0) 0.5 else -0.5)
        if (abs(v - rounded) < 1e-4) return rounded.toInt().toString()
        val scaled = truncate(v * 100.0 + if (v >= 0.0) 0.5 else -0.5) / 100.0
        return scaled.toString()
    }
}
