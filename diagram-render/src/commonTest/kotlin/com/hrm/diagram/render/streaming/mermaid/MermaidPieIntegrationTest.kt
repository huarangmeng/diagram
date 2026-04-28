package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidPieIntegrationTest {

    @Test
    fun pie_basic_official_sample_renders_and_is_streaming_consistent() {
        val src =
            """
            pie
              title Pets adopted by volunteers
              "Dogs" : 386
              "Cats" : 85
              "Rats" : 15
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 3)

        val oneIr = assertIs<PieIR>(one.ir)
        val chunkedIr = assertIs<PieIR>(chunked.ir)
        assertEquals("Pets adopted by volunteers", oneIr.title)
        assertEquals(3, oneIr.slices.size)
        assertEquals(oneIr, chunkedIr)

        // Wedges are filled paths; legend uses fill rect swatches.
        assertTrue(one.drawCommands.any { it is DrawCommand.FillPath })
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.diagnostics.isEmpty())
        assertTrue(chunked.diagnostics.isEmpty())

        // DrawCommand signature compare (ignoring geometry) should match for one-shot vs chunked.
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
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

    private fun drawSignature(cmds: List<DrawCommand>): List<DrawSig> =
        cmds.map { it.toSig() }

    private sealed interface DrawSig {
        val z: Int

        data class FillRectSig(val colorArgb: Int, val corner: Float, override val z: Int) : DrawSig
        data class StrokeRectSig(val colorArgb: Int, val stroke: StrokeSig, val corner: Float, override val z: Int) : DrawSig
        data class FillPathSig(val colorArgb: Int, override val z: Int) : DrawSig
        data class StrokePathSig(val colorArgb: Int, val stroke: StrokeSig, override val z: Int) : DrawSig
        data class DrawTextSig(
            val text: String,
            val font: FontSig,
            val colorArgb: Int,
            val maxWidth: Float?,
            val anchorX: TextAnchorX,
            val anchorY: TextAnchorY,
            override val z: Int,
        ) : DrawSig
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
            is DrawCommand.DrawText ->
                DrawSig.DrawTextSig(
                    text = text,
                    font = font.toSig(),
                    colorArgb = color.argb,
                    maxWidth = maxWidth,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    z = z,
                )
            // Pie renderer shouldn't emit these; if it does, lock by class name + z for stability.
            else -> DrawSig.DrawTextSig(
                text = this::class.simpleName ?: "Unknown",
                font = FontSig("", 0f, 0, false),
                colorArgb = 0,
                maxWidth = null,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = z,
            )
        }
}
