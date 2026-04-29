package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.journey.JourneyLayout
import com.hrm.diagram.parser.mermaid.MermaidJourneyParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

internal class MermaidJourneySubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private val parser = MermaidJourneyParser()
    private val layout = JourneyLayout(textMeasurer)

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val stageFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val stepFont = FontSpec(family = "sans-serif", sizeSp = 11f, weight = 600)
    private val actorFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val axisFont = FontSpec(family = "sans-serif", sizeSp = 10f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (line in lines) parser.acceptLine(line)
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)
        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(snapshot = snap, patch = SessionPatch.empty(seq, isFinal))
    }

    private fun render(ir: JourneyIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val text = Color(0xFF263238.toInt())
        val axis = Color(0xFFD0D7DE.toInt())
        val line = Color(0xFF90A4AE.toInt())

        out += DrawCommand.FillRect(Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), Color(0xFFFFFFFF.toInt()), z = 0)
        val titleRect = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("journey:title")]
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(ir.title!!, Point(titleRect.left, titleRect.top), titleFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
        }

        val plotLeft = 20f
        val plotRight = bounds.right - 20f
        for (score in 1..5) {
            val y = 110f + (5 - score) * ((430f - 110f) / 4f)
            out += DrawCommand.StrokePath(
                path = PathCmd(listOf(PathOp.MoveTo(Point(plotLeft, y)), PathOp.LineTo(Point(plotRight, y)))),
                stroke = Stroke.Hairline,
                color = axis,
                z = 1,
            )
            out += DrawCommand.DrawText(score.toString(), Point(plotLeft + 2f, y - 4f), axisFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Bottom, z = 10)
        }

        val centers = ArrayList<Point>()
        for ((stageIndex, stage) in ir.stages.withIndex()) {
            val stageRect = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("journey:stage:$stageIndex")]
            val stageLabel = (stage.label as? RichLabel.Plain)?.text.orEmpty()
            if (stageRect != null) {
                out += DrawCommand.DrawText(stageLabel, Point(stageRect.left, stageRect.top), stageFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
            }
            for ((stepIndex, step) in stage.steps.withIndex()) {
                val rect = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("journey:step:$stageIndex:$stepIndex")] ?: continue
                val fill = scoreColor(step.score)
                out += DrawCommand.FillRect(rect = rect, color = fill, corner = 12f, z = 3)
                out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = 1f), color = line, corner = 12f, z = 4)
                val center = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
                centers += center
                val label = (step.label as? RichLabel.Plain)?.text.orEmpty()
                val actors = step.actors.mapNotNull { (it as? RichLabel.Plain)?.text }.joinToString(", ")
                out += DrawCommand.DrawText(label, Point(center.x, rect.top + 14f), stepFont, text, maxWidth = rect.size.width - 12f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Top, z = 10)
                out += DrawCommand.DrawText("score ${step.score}", Point(center.x, center.y), actorFont, text, maxWidth = rect.size.width - 12f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 10)
                if (actors.isNotBlank()) {
                    out += DrawCommand.DrawText(actors, Point(center.x, rect.bottom - 10f), actorFont, text, maxWidth = rect.size.width - 12f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Bottom, z = 10)
                }
            }
        }
        if (centers.size >= 2) {
            val ops = ArrayList<PathOp>()
            ops += PathOp.MoveTo(centers.first())
            for (pt in centers.drop(1)) ops += PathOp.LineTo(pt)
            out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = 2f), Color(0xFF5C6BC0.toInt()), z = 2)
        }
        return out
    }

    private fun scoreColor(score: Int): Color = when (score) {
        5 -> Color(0xFF81C784.toInt())
        4 -> Color(0xFFA5D6A7.toInt())
        3 -> Color(0xFFFFF59D.toInt())
        2 -> Color(0xFFFFCC80.toInt())
        else -> Color(0xFFEF9A9A.toInt())
    }
}
