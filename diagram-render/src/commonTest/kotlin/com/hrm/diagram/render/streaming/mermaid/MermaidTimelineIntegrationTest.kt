package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidTimelineIntegrationTest {

    @Test
    fun timeline_official_basic_sample_renders_and_is_streaming_consistent() {
        val src =
            """
            timeline
                title History of Social Media Platform
                2002 : LinkedIn
                2004 : Facebook
                     : Google
                2005 : YouTube
                2006 : Twitter
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 5)

        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)

        assertTrue(one.drawCommands.any { it is DrawCommand.DrawText })
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.diagnostics.isEmpty())
        assertTrue(chunked.diagnostics.isEmpty())

        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun timeline_td_direction_renders_and_is_streaming_consistent() {
        val src =
            """
            timeline TD
              title MermaidChart 2023 Timeline
              section 2023 Q1
                Bullet 1 : sub-point 1a : sub-point 1b
                     : sub-point 1c
              section 2023 Q2
                Bullet 2 : sub-point 2a
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 3)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun timeline_disable_multicolor_uses_single_card_color() {
        val src =
            """
            ---
            config:
              timeline:
                disableMulticolor: true
            ---
            timeline
              2002 : LinkedIn
              2004 : Facebook
              2005 : YouTube
            """.trimIndent() + "\n"
        val snap = run(src, chunkSize = src.length)
        val fillRects = snap.drawCommands.filterIsInstance<DrawCommand.FillRect>().drop(1) // skip background
        val colors = fillRects.map { it.color.argb }.distinct()
        assertTrue(colors.size <= 3) // marker + card colors, with cards staying single-color
        assertTrue(snap.diagnostics.isEmpty(), "diagnostics: ${snap.diagnostics}")
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
