package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlSequenceIntegrationTest {
    @Test
    fun sequence_session_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            actor Alice
            participant Bob
            Alice -> Bob: request
            Bob --> Alice: response
            note over Alice,Bob: handshake
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 3)
        val oneIr = assertIs<SequenceIR>(one.ir)
        val chunkedIr = assertIs<SequenceIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun missing_enduml_reports_diagnostic() {
        val src =
            """
            @startuml
            Alice -> Bob: hi
            """.trimIndent()

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.any { it.code == "PLANTUML-E001" })
    }

    @Test
    fun skinparam_is_ignored_with_warning() {
        val src =
            """
            @startuml
            skinparam ArrowColor red
            Alice -> Bob: hi
            @enduml
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.any { it.code == "PLANTUML-W001" })
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.PLANTUML).let { s ->
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
        data class DrawArrowSig(val style: String, override val z: Int) : DrawSig
        data class DrawIconSig(val name: String, override val z: Int) : DrawSig
        data class GroupSig(val children: Int, override val z: Int) : DrawSig
        data class ClipSig(val children: Int, override val z: Int) : DrawSig
        data class HyperlinkSig(val href: String, override val z: Int) : DrawSig
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
            is DrawCommand.DrawText -> DrawSig.DrawTextSig(text = text, font = font.toSig(), colorArgb = color.argb, maxWidth = maxWidth, anchorX = anchorX, anchorY = anchorY, z = z)
            is DrawCommand.DrawArrow -> DrawSig.DrawArrowSig(style = style.toString(), z = z)
            is DrawCommand.DrawIcon -> DrawSig.DrawIconSig(name = name, z = z)
            is DrawCommand.Group -> DrawSig.GroupSig(children = children.size, z = z)
            is DrawCommand.Clip -> DrawSig.ClipSig(children = children.size, z = z)
            is DrawCommand.Hyperlink -> DrawSig.HyperlinkSig(href = href, z = z)
        }
}
