package com.hrm.diagram.layout.journey

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.JourneyStep
import com.hrm.diagram.core.ir.NodeId
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

    private data class StepBox(
        val step: JourneyStep,
        val width: Float,
        val height: Float,
    )

    override fun layout(previous: LaidOutDiagram?, model: JourneyIR, options: LayoutOptions): LaidOutDiagram {
        val nodePositions = LinkedHashMap<NodeId, Rect>()
        val pad = 20f
        val titleGap = 18f
        val headerGap = 20f
        val stageMinWidth = 220f
        val stageGap = 32f
        val stepGap = 24f
        val cardMinWidth = 110f
        val cardMinHeight = 72f
        val cardHorizontalPad = 16f
        val cardVerticalPad = 12f
        val cardTextGap = 6f
        var top = pad

        model.title?.takeIf { it.isNotBlank() }?.let { title ->
            val m = textMeasurer.measure(title, titleFont, maxWidth = 900f)
            nodePositions[NodeId("journey:title")] = Rect(Point(pad, top), Size(m.width, m.height))
            top += m.height + titleGap
        }

        val stageTop = top
        val stagePlans = model.stages.map { stage ->
            val stageLabel = (stage.label as? RichLabel.Plain)?.text.orEmpty()
            val stageMeasure = textMeasurer.measure(stageLabel, stageFont, maxWidth = stageMinWidth)
            val boxes = stage.steps.map { step ->
                val label = (step.label as? RichLabel.Plain)?.text.orEmpty()
                val actors = step.actors.mapNotNull { (it as? RichLabel.Plain)?.text }.joinToString(", ")
                val labelMeasure = textMeasurer.measure(label, stepFont, maxWidth = cardMinWidth - cardHorizontalPad * 2f)
                val scoreMeasure = textMeasurer.measure("score ${step.score}", actorFont, maxWidth = cardMinWidth - cardHorizontalPad * 2f)
                val actorMeasure = textMeasurer.measure(actors, actorFont, maxWidth = cardMinWidth - cardHorizontalPad * 2f)
                val contentWidth = maxOf(labelMeasure.width, scoreMeasure.width, actorMeasure.width)
                val cardWidth = maxOf(cardMinWidth, contentWidth + cardHorizontalPad * 2f)
                val cardHeight = maxOf(
                    cardMinHeight,
                    labelMeasure.height + scoreMeasure.height + actorMeasure.height + cardVerticalPad * 2f + cardTextGap * 2f,
                )
                StepBox(step, cardWidth, cardHeight)
            }
            val stepsWidth = boxes.sumOf { it.width.toDouble() }.toFloat() + stepGap * (boxes.size - 1).coerceAtLeast(0)
            val stageWidth = maxOf(stageMinWidth, stageMeasure.width, stepsWidth)
            Triple(stageMeasure, boxes, stageWidth)
        }
        val maxStageLabelHeight = stagePlans.maxOfOrNull { it.first.height } ?: 0f
        val maxCardHeight = stagePlans.flatMap { it.second }.maxOfOrNull { it.height } ?: cardMinHeight
        val scoreTop = stageTop + maxStageLabelHeight + headerGap + maxCardHeight / 2f
        val scoreStep = maxOf(72f, maxCardHeight + 24f)

        var stageLeft = pad
        for (stageIndex in model.stages.indices) {
            val (stageMeasure, boxes, stageWidth) = stagePlans[stageIndex]
            nodePositions[NodeId("journey:stage:$stageIndex")] =
                Rect(Point(stageLeft, stageTop), Size(stageMeasure.width, stageMeasure.height))

            val stepsWidth = boxes.sumOf { it.width.toDouble() }.toFloat() + stepGap * (boxes.size - 1).coerceAtLeast(0)
            var stepLeft = stageLeft + (stageWidth - stepsWidth).coerceAtLeast(0f) / 2f
            for ((stepIndex, box) in boxes.withIndex()) {
                val centerX = stepLeft + box.width / 2f
                val centerY = scoreTop + (5 - box.step.score) * scoreStep
                nodePositions[NodeId("journey:step:$stageIndex:$stepIndex")] =
                    Rect(Point(centerX - box.width / 2f, centerY - box.height / 2f), Size(box.width, box.height))
                stepLeft += box.width + stepGap
            }
            stageLeft += stageWidth + stageGap
        }

        val contentWidth = if (model.stages.isEmpty()) 480f else stageLeft - stageGap + pad
        val height = maxOf(500f, scoreTop + scoreStep * 4f + maxCardHeight / 2f + pad)
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, contentWidth, height),
        )
    }
}
