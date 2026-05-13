package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlDitaaParserTest {
    @Test
    fun parses_ascii_boxes_and_horizontal_arrow() {
        val parser = PlantUmlDitaaParser()
        """
        +-----+    +-----+
        | Foo |--->| Bar |
        +-----+    +-----+
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertTrue(ir.nodes.any { labelOf(it.label).contains("Foo") })
        assertTrue(ir.nodes.any { labelOf(it.label).contains("Bar") })
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_ditaa_color_markers_as_node_fill() {
        val parser = PlantUmlDitaaParser()
        """
        +----------+    +----------+
        | cGRE Ok  |--->| cF80 Warn|
        +----------+    +----------+
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        val ok = ir.nodes.single { labelOf(it.label) == "Ok" }
        val warn = ir.nodes.single { labelOf(it.label) == "Warn" }
        assertEquals(0xFF43A047.toInt(), ok.style.fill?.argb)
        assertEquals(0xFFFF8800.toInt(), warn.style.fill?.argb)
        assertEquals(1, ir.edges.size)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_rounded_boxes_and_handwritten_skinparam() {
        val parser = PlantUmlDitaaParser()
        """
        skinparam handwritten true
        /-------\
        | Round |
        \-------/
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        val round = ir.nodes.single { labelOf(it.label) == "Round" }
        assertEquals("true", round.payload[PlantUmlDitaaParser.ROUNDED_KEY])
        assertEquals("true", round.payload[PlantUmlDitaaParser.HANDWRITTEN_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlDitaaParser.HANDWRITTEN_KEY])
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_ditaa_shape_markers_and_rich_connectors() {
        val parser = PlantUmlDitaaParser()
        """
        +---------+   +---------+   +---------+
        | {d} Doc |<=>| {s} DB  |:::| {io} IO |
        +---------+   +---------+   +---------+
              ^             |
              |             v
        +---------+   +---------+
        | {c} Dec |===| {o} End |
        +---------+   +---------+
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(NodeShape.Note, ir.nodes.single { labelOf(it.label) == "Doc" }.shape)
        assertEquals(NodeShape.Cylinder, ir.nodes.single { labelOf(it.label) == "DB" }.shape)
        assertEquals(NodeShape.Parallelogram, ir.nodes.single { labelOf(it.label) == "IO" }.shape)
        assertEquals(NodeShape.Diamond, ir.nodes.single { labelOf(it.label) == "Dec" }.shape)
        assertEquals(NodeShape.Ellipse, ir.nodes.single { labelOf(it.label) == "End" }.shape)
        assertTrue(ir.edges.any { it.arrow == ArrowEnds.Both })
        assertTrue(ir.edges.any { it.kind == EdgeKind.Dashed && it.style.dash == listOf(4f, 4f) })
        assertTrue(ir.edges.any { it.style.width == 2.4f })
        assertTrue(ir.edges.any { it.arrow == ArrowEnds.FromOnly || it.arrow == ArrowEnds.ToOnly })
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun reports_missing_closing_delimiter() {
        val parser = PlantUmlDitaaParser()
        parser.acceptLine("+---+")
        parser.finish(blockClosed = false)
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E020" })
    }

    private fun labelOf(label: com.hrm.diagram.core.ir.RichLabel): String =
        when (label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}
