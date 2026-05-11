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
    fun unsupported_sequence_skinparam_still_warns() {
        val src =
            """
            @startuml
            skinparam ResponseMessageBelowArrow true
            Alice -> Bob: hi
            @enduml
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.any { it.code == "PLANTUML-W001" })
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun sequence_skinparam_font_line_and_shadow_render_consistently() {
        val src =
            """
            @startuml
            skinparam sequence {
              BackgroundColor LightGray
              BorderColor Peru
              FontColor Navy
              FontSize 15
              FontName serif
              LineThickness 2.5
              Shadowing true
            }
            skinparam participant {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 16
              FontName monospace
              LineThickness 2
              Shadowing true
            }
            skinparam actor {
              BackgroundColor Ivory
              BorderColor SaddleBrown
              FontColor Navy
              FontSize 18
              FontName fantasy
              LineThickness 3
              Shadowing true
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Orange
              FontColor SaddleBrown
              FontSize 14
              FontName cursive
              LineThickness 1.75
              Shadowing true
            }
            skinparam box {
              BackgroundColor PaleGreen
              BorderColor Green
              FontColor Navy
              FontSize 13
              FontName system-ui
              LineThickness 1.5
              Shadowing true
            }
            skinparam ArrowColor Red
            box "Clients" LightBlue
            actor Alice
            participant Bob
            end box
            Alice -> Bob: request
            note over Alice,Bob: handshake
            Bob --> Alice: response
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<SequenceIR>(one.ir)
        val chunkedIr = assertIs<SequenceIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")

        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        val fillColors = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        val strokeRectColors = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.color.argb }
        val strokePathColors = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.color.argb }
        val textColors = texts.map { it.color.argb }
        val strokeWidths = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.stroke.width } +
            one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.stroke.width }
        val shadowRects = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }

        assertTrue(fillColors.contains(0xFFD3D3D3.toInt()), "expected sequence BackgroundColor LightGray")
        assertTrue(fillColors.contains(0xFFFFFFF0.toInt()), "expected actor/note BackgroundColor Ivory")
        assertTrue(fillColors.contains(0xFFFFFFE0.toInt()), "expected participant BackgroundColor LightYellow")
        assertTrue(fillColors.any { (it and 0x00FFFFFF) == 0x0098FB98 }, "expected box BackgroundColor PaleGreen")
        assertTrue(strokeRectColors.contains(0xFF8B4513.toInt()), "expected actor BorderColor SaddleBrown")
        assertTrue(strokeRectColors.contains(0xFFFFA500.toInt()), "expected participant/note BorderColor Orange")
        assertTrue(strokeRectColors.contains(0xFF008000.toInt()), "expected box BorderColor Green")
        assertTrue(strokePathColors.contains(0xFFFF0000.toInt()), "expected ArrowColor Red")
        assertTrue(textColors.contains(0xFF000080.toInt()), "expected participant/actor/box FontColor Navy")
        assertTrue(textColors.contains(0xFF8B4513.toInt()), "expected note FontColor SaddleBrown")
        assertTrue(texts.any { it.font.family == "fantasy" && it.font.sizeSp == 18f && it.text == "Alice" })
        assertTrue(texts.any { it.font.family == "monospace" && it.font.sizeSp == 16f && it.text == "Bob" })
        assertTrue(texts.any { it.font.family == "serif" && it.font.sizeSp == 15f && it.text.contains("request") })
        assertTrue(texts.any { it.font.family == "cursive" && it.font.sizeSp == 14f && it.text == "handshake" })
        assertTrue(texts.any { it.font.family == "system-ui" && it.font.sizeSp == 13f && it.text == "Clients" })
        assertTrue(strokeWidths.any { it == 3f })
        assertTrue(strokeWidths.any { it == 2.5f })
        assertTrue(strokeWidths.any { it == 1.75f })
        assertTrue(strokeWidths.any { it == 1.5f })
        assertTrue(shadowRects.contains(0x26000000))
    }

    @Test
    fun sequence_create_destroy_ref_box_and_autonumber_render_consistently() {
        val src =
            """
            @startuml
            box "Clients" LightBlue
            actor Alice
            participant Bob
            end box
            autonumber 10 5
            Alice -> Bob: first
            autonumber stop
            create Carol
            Alice -> Carol: born
            ref over Alice,Carol : Shared flow
            autonumber resume 30 3
            destroy Bob
            Carol -> Bob: bye
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 5)
        val oneIr = assertIs<SequenceIR>(one.ir)
        val chunkedIr = assertIs<SequenceIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(oneIr.messages.any { it.kind == com.hrm.diagram.core.ir.MessageKind.Create })
        assertTrue(oneIr.messages.any { it.kind == com.hrm.diagram.core.ir.MessageKind.Destroy })
        assertTrue(
            one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any {
                (it.color.argb and 0x00FFFFFF) == 0x00ADD8E6 && (it.color.argb ushr 24) > 0
            },
            "expected translucent box background",
        )
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().any { it.color.argb == 0xFFADD8E6.toInt() }, "expected box border color")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().size >= 3, "expected message and marker strokes")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("10 first") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("bye") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Shared flow" })
    }

    @Test
    fun sequence_complex_arrow_decorations_render_consistently() {
        val src =
            """
            @startuml
            participant Alice
            participant Bob
            participant Carol
            Alice o-> Bob: tail
            Bob -->>o Carol: stream
            Alice x<- Carol: reject
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<SequenceIR>(one.ir)
        val chunkedIr = assertIs<SequenceIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(
            one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().any {
                it.corner == 5f && it.rect.size.width <= 12f && it.rect.size.height <= 12f
            },
            "expected open-circle marker",
        )
        assertTrue(
            one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.path.ops.size == 4 },
            "expected cross marker path",
        )
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "stream" })
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
