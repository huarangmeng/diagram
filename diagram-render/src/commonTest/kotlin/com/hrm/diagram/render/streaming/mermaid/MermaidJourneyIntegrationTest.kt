package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidJourneyIntegrationTest {
    @Test
    fun journey_renders_and_is_streaming_consistent() {
        val src =
            """
            journey
              title My working day
              section Go to work
                Make tea: 5: Me
                Do work: 1: Me, Cat
              section Go home
                Sit down: 5: Me
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<JourneyIR>(one.ir)
        val chunkedIr = assertIs<JourneyIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.drawCommands.any { it is DrawCommand.StrokePath })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun journey_step_text_bands_do_not_overlap() {
        val src =
            """
            journey
              title My day
              section Morning
                Wake up: 3: Me
                Coffee : 5: Me
            """.trimIndent() + "\n"

        val snap = run(src, chunkSize = src.length)
        val ir = assertIs<JourneyIR>(snap.ir)
        assertTrue(snap.diagnostics.isEmpty(), "diagnostics: ${snap.diagnostics}")

        val measurer = HeuristicTextMeasurer()
        val stepFont = FontSpec(family = "sans-serif", sizeSp = 11f, weight = 600)
        val actorFont = FontSpec(family = "sans-serif", sizeSp = 10f)
        for ((stageIndex, stage) in ir.stages.withIndex()) {
            for ((stepIndex, step) in stage.steps.withIndex()) {
                val rect = snap.laidOut!!.nodePositions[NodeId("journey:step:$stageIndex:$stepIndex")]!!
                val label = (step.label as RichLabel.Plain).text
                val actors = step.actors.map { (it as RichLabel.Plain).text }.joinToString(", ")
                val labelBottom = rect.top + 12f + measurer.measure(label, stepFont, rect.size.width - 12f).height
                val scoreMetrics = measurer.measure("score ${step.score}", actorFont, rect.size.width - 12f)
                val scoreTop = (rect.top + rect.bottom) / 2f - scoreMetrics.height / 2f
                val scoreBottom = (rect.top + rect.bottom) / 2f + scoreMetrics.height / 2f
                val actorTop = rect.bottom - 12f - measurer.measure(actors, actorFont, rect.size.width - 12f).height

                assertTrue(labelBottom + 4f <= scoreTop, "label overlaps score for step $stageIndex:$stepIndex")
                assertTrue(scoreBottom + 4f <= actorTop, "score overlaps actor for step $stageIndex:$stepIndex")
            }
        }
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.MERMAID).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }

    private fun drawSignature(cmds: List<DrawCommand>): List<DrawSig> = cmds.map { it.toSig() }

    private sealed interface DrawSig {
        val z: Int
        data class FillRectSig(val colorArgb: Int, val corner: Float, override val z: Int) : DrawSig
        data class StrokeRectSig(val colorArgb: Int, val stroke: StrokeSig, val corner: Float, override val z: Int) : DrawSig
        data class FillPathSig(val colorArgb: Int, override val z: Int) : DrawSig
        data class StrokePathSig(val colorArgb: Int, val stroke: StrokeSig, override val z: Int) : DrawSig
        data class DrawTextSig(val text: String, val font: FontSig, val colorArgb: Int, val maxWidth: Float?, val anchorX: TextAnchorX, val anchorY: TextAnchorY, override val z: Int) : DrawSig
    }

    private data class StrokeSig(val width: Float, val dash: List<Float>?)
    private data class FontSig(val family: String, val sizeSp: Float, val weight: Int, val italic: Boolean)

    private fun Stroke.toSig(): StrokeSig = StrokeSig(width = width, dash = dash)
    private fun FontSpec.toSig(): FontSig = FontSig(family = family, sizeSp = sizeSp, weight = weight, italic = italic)

    private fun DrawCommand.toSig(): DrawSig =
        when (this) {
            is DrawCommand.FillRect -> DrawSig.FillRectSig(colorArgb = color.argb, corner = corner, z = z)
            is DrawCommand.StrokeRect -> DrawSig.StrokeRectSig(colorArgb = color.argb, stroke = stroke.toSig(), corner = corner, z = z)
            is DrawCommand.FillPath -> DrawSig.FillPathSig(colorArgb = color.argb, z = z)
            is DrawCommand.StrokePath -> DrawSig.StrokePathSig(colorArgb = color.argb, stroke = stroke.toSig(), z = z)
            is DrawCommand.DrawText -> DrawSig.DrawTextSig(text, font.toSig(), color.argb, maxWidth, anchorX, anchorY, z)
            else -> DrawSig.DrawTextSig(this::class.simpleName ?: "Unknown", FontSig("", 0f, 0, false), 0, null, TextAnchorX.Start, TextAnchorY.Top, z)
        }
}
