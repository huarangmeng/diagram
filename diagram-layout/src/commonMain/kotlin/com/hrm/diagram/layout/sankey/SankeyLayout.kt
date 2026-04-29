package com.hrm.diagram.layout.sankey

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram

class SankeyLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<SankeyIR> {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val nodeFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun layout(previous: LaidOutDiagram?, model: SankeyIR, options: LayoutOptions): LaidOutDiagram {
        val nodePositions = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Rect>()
        val pad = 20f
        val plotTopBase = 72f
        val plotHeight = 420f
        val nodeWidth = 24f
        val levelGap = 160f
        val nodeGap = 18f
        var top = pad

        model.title?.takeIf { it.isNotBlank() }?.let { title ->
            val m = textMeasurer.measure(title, titleFont, maxWidth = 960f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("sankey:title")] = Rect(Point(pad, top), Size(m.width, m.height))
            top += m.height + 12f
        }
        val plotTop = maxOf(plotTopBase, top)

        val levels = computeLevels(model)
        val values = computeNodeValues(model)
        val maxLevel = levels.values.maxOrNull() ?: 0
        val columns = (0..maxLevel).associateWith { level -> model.nodes.filter { levels[it.id] == level } }
        val maxSum = columns.values.maxOfOrNull { nodes ->
            nodes.sumOf { values[it.id] ?: 1.0 }
        } ?: 1.0
        val scale = ((plotHeight - (model.nodes.size.coerceAtLeast(1) - 1) * nodeGap) / maxSum.toFloat()).coerceAtLeast(1f)

        for ((level, nodes) in columns) {
            var y = plotTop
            val x = pad + level * levelGap
            for (node in nodes) {
                val value = (values[node.id] ?: 1.0).coerceAtLeast(1e-6)
                val height = maxOf(18f, value.toFloat() * scale)
                nodePositions[node.id] = Rect(Point(x, y), Size(nodeWidth, height))
                val label = (node.label as? RichLabel.Plain)?.text.orEmpty()
                val m = textMeasurer.measure(label, nodeFont, maxWidth = 120f)
                nodePositions[com.hrm.diagram.core.ir.NodeId("sankey:label:${node.id.value}")] =
                    Rect(Point(x + nodeWidth + 8f, y + (height - m.height) / 2f), Size(m.width, m.height))
                y += height + nodeGap
            }
        }

        val width = pad * 2f + (maxLevel + 1) * levelGap
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, width, plotTop + plotHeight + 30f),
        )
    }

    private fun computeLevels(model: SankeyIR): Map<com.hrm.diagram.core.ir.NodeId, Int> {
        val levels = model.nodes.associate { it.id to 0 }.toMutableMap()
        repeat(model.nodes.size.coerceAtLeast(1)) {
            for (flow in model.flows) {
                val next = (levels[flow.from] ?: 0) + 1
                if (next > (levels[flow.to] ?: 0)) levels[flow.to] = next
            }
        }
        return levels
    }

    private fun computeNodeValues(model: SankeyIR): Map<com.hrm.diagram.core.ir.NodeId, Double> {
        val incoming = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Double>()
        val outgoing = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Double>()
        for (flow in model.flows) {
            outgoing[flow.from] = (outgoing[flow.from] ?: 0.0) + flow.value
            incoming[flow.to] = (incoming[flow.to] ?: 0.0) + flow.value
        }
        return model.nodes.associate { node ->
            val value = maxOf(incoming[node.id] ?: 0.0, outgoing[node.id] ?: 0.0, 1.0)
            node.id to value
        }
    }
}
