package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlDitaaIntegrationTest {
    @Test
    fun ditaa_renders_and_is_streaming_consistent() {
        val src =
            """
            @startditaa
            +-----+    +-----+
            | Foo |--->| Bar |
            +-----+    +-----+
            @endditaa
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(2, oneIr.nodes.size)
        assertEquals(1, oneIr.edges.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Foo") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Bar") })
    }

    @Test
    fun ditaa_color_markers_render_and_are_streaming_consistent() {
        val src =
            """
            @startditaa
            +----------+    +----------+
            | cGRE Ok  |--->| cF80 Warn|
            +----------+    +----------+
            @endditaa
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == 0xFF43A047.toInt() })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == 0xFFFF8800.toInt() })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Ok" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Warn" })
    }

    @Test
    fun ditaa_rounded_and_handwritten_render_and_are_streaming_consistent() {
        val src =
            """
            @startditaa
            skinparam handwritten true
            /-------\    /------\
            | Round |--->| Next |
            \-------/    \------/
            @endditaa
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.corner == 16f })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(8f, 2f, 2f, 2f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(7f, 2f, 2f, 2f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Round" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Next" })
    }

    @Test
    fun ditaa_shape_markers_and_rich_connectors_render_with_streaming_consistency() {
        val src =
            """
            @startditaa
            +---------+   +---------+   +---------+
            | {d} Doc |<=>| {s} DB  |:::| {io} IO |
            +---------+   +---------+   +---------+
                  ^             |
                  |             v
            +---------+   +---------+
            | {c} Dec |===| {o} End |
            +---------+   +---------+
            @endditaa
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(5, oneIr.nodes.size)
        assertTrue(oneIr.edges.size >= 3)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillPath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 2.4f })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(4f, 4f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Doc" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "IO" })
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
}
