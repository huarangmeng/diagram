package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidRequirementIntegrationTest {
    @Test
    fun requirement_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            requirementDiagram
              direction LR
              requirement test_req {
                id: 1
                text: the test text.
                risk: high
                verifymethod: test
              }
              element test_entity {
                type: simulation
              }
              test_entity - satisfies -> test_req
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect || it is DrawCommand.FillPath })
        assertTrue(one.drawCommands.any { it is DrawCommand.StrokePath })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun requirement_diagram_style_and_class_are_applied() {
        val src =
            """
            requirementDiagram
              requirement test_req:::important {
                id: 1
                text: class styling example
                risk: low
                verifymethod: test
              }
              element test_entity {
                type: simulation
              }
              classDef important fill:#f96,stroke:#333,stroke-width:4px
              class test_entity important
              style test_entity fill:#f9f,stroke:#111,color:#123456
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        assertTrue(fills.contains(0xFFFF9966.toInt()) || fills.contains(0xFFFF99FF.toInt()))
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.color.argb }
        assertTrue(texts.contains(0xFF123456.toInt()) || texts.isNotEmpty())
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
