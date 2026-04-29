package com.hrm.diagram.layout.journey

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram

class JourneyLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<JourneyIR> {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val stageFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val stepFont = FontSpec(family = "sans-serif", sizeSp = 11f, weight = 600)
    private val actorFont = FontSpec(family = "sans-serif", sizeSp = 10f)

    override fun layout(previous: LaidOutDiagram?, model: JourneyIR, options: LayoutOptions): LaidOutDiagram {
        val nodePositions = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Rect>()
        val pad = 20f
        val titleGap = 18f
        val headerGap = 20f
        val stageWidth = 220f
        val stageGap = 28f
        val cardMinWidth = 110f
        val cardMinHeight = 52f
        val scoreTop = 110f
        val scoreBottom = 430f
        val scoreStep = (scoreBottom - scoreTop) / 4f
        var top = pad

        model.title?.takeIf { it.isNotBlank() }?.let { title ->
            val m = textMeasurer.measure(title, titleFont, maxWidth = 900f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("journey:title")] = Rect(Point(pad, top), Size(m.width, m.height))
            top += m.height + titleGap
        }

        val stageTop = top
        for ((stageIndex, stage) in model.stages.withIndex()) {
            val stageLeft = pad + stageIndex * (stageWidth + stageGap)
            val stageLabel = (stage.label as? RichLabel.Plain)?.text.orEmpty()
            val stageMeasure = textMeasurer.measure(stageLabel, stageFont, maxWidth = stageWidth)
            nodePositions[com.hrm.diagram.core.ir.NodeId("journey:stage:$stageIndex")] =
                Rect(Point(stageLeft, stageTop), Size(stageMeasure.width, stageMeasure.height))

            val plotTop = stageTop + stageMeasure.height + headerGap
            val stepCount = stage.steps.size.coerceAtLeast(1)
            val stepSlot = stageWidth / stepCount.toFloat()
            for ((stepIndex, step) in stage.steps.withIndex()) {
                val label = (step.label as? RichLabel.Plain)?.text.orEmpty()
                val actors = step.actors.mapNotNull { (it as? RichLabel.Plain)?.text }.joinToString(", ")
                val labelMeasure = textMeasurer.measure(label, stepFont, maxWidth = stepSlot - 12f)
                val actorMeasure = textMeasurer.measure(actors, actorFont, maxWidth = stepSlot - 12f)
                val cardWidth = maxOf(cardMinWidth, maxOf(labelMeasure.width, actorMeasure.width) + 16f)
                val cardHeight = maxOf(cardMinHeight, labelMeasure.height + actorMeasure.height + 20f)
                val centerX = stageLeft + stepSlot * (stepIndex + 0.5f)
                val centerY = plotTop + (5 - step.score) * scoreStep
                nodePositions[com.hrm.diagram.core.ir.NodeId("journey:step:$stageIndex:$stepIndex")] =
                    Rect(
                        Point(centerX - cardWidth / 2f, centerY - cardHeight / 2f),
                        Size(cardWidth, cardHeight),
                    )
            }
        }

        val width = if (model.stages.isEmpty()) 480f else pad * 2f + model.stages.size * stageWidth + (model.stages.size - 1) * stageGap
        val height = 500f
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, width, height),
        )
    }
}
