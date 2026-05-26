package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidBlockParserTest {
    private fun feedAll(src: String): MermaidBlockParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) {
                    lines += cur
                    cur = ArrayList()
                }
            } else {
                cur += t
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return MermaidBlockParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_columns_rows_nested_blocks_and_edges() {
        val parser = feedAll(
            """
            block-beta
              columns 3
              A["A label"] B:2
              block:group:2
                columns 2
                db[("DB")] sub[["Sub"]]
              end
              space C
              A -- "x" --> C
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(5, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertEquals(1, ir.clusters.size)
        assertEquals("group", ir.clusters.single().id.value)
        assertEquals(setOf("db", "sub"), ir.clusters.single().children.map { it.value }.toSet())
        assertTrue(ir.nodes.any { it.id.value == "db" && it.shape == NodeShape.Cylinder })
        assertTrue(ir.nodes.any { it.id.value == "sub" && it.shape == NodeShape.Subroutine })
        assertTrue(ir.edges.any { it.from.value == "A" && it.to.value == "C" })
        val placements = parser.placementSnapshot()
        assertEquals(0, placements.getValue(com.hrm.diagram.core.ir.NodeId("A")).row)
        assertEquals(0, placements.getValue(com.hrm.diagram.core.ir.NodeId("A")).col)
        assertEquals(2, placements.getValue(com.hrm.diagram.core.ir.NodeId("B")).span)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_arrow_blocks_and_space_span() {
        val parser = feedAll(
            """
            block-beta
              columns 3
              a space:2 down<["Go"]>(down)
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        val arrow = ir.nodes.first { it.payload[MermaidBlockParser.KIND_KEY] == "arrow" }
        assertEquals(null, arrow.style.fill)
        assertEquals(null, arrow.style.stroke)
        assertEquals(null, arrow.style.textColor)
        assertEquals(1.5f, arrow.style.strokeWidth)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parser_keeps_block_default_colors_out_of_ir() {
        val parser = feedAll(
            """
            block-beta
              columns 2
              A B
              block:group:2
                C D
              end
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val node = ir.nodes.first { it.id.value == "A" }
        assertEquals(null, node.style.fill)
        assertEquals(null, node.style.stroke)
        assertEquals(null, node.style.textColor)
        assertEquals(1.5f, node.style.strokeWidth)
        val cluster = ir.clusters.single()
        assertEquals(null, cluster.style.fill)
        assertEquals(null, cluster.style.stroke)
        assertEquals(1.5f, cluster.style.strokeWidth)
    }

    @Test
    fun supports_block_header_alias_and_extra_shapes() {
        val parser = feedAll(
            """
            block
              columns 4
              asym>"Asym"] trap1[/"Christmas"\] trap2[\"Go shopping"/] endCircle((("End")))
              xdir<["X"]>(x) ydir<["Y"]>(y) combo<["C"]>(x, down)
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertTrue(ir.nodes.any { it.id.value == "asym" && it.shape == NodeShape.Custom("asymmetric") })
        assertTrue(ir.nodes.any { it.id.value == "trap1" && it.shape == NodeShape.Trapezoid })
        assertTrue(ir.nodes.any { it.id.value == "trap2" && it.shape == NodeShape.Trapezoid })
        assertTrue(ir.nodes.any { it.id.value == "endCircle" && it.shape == NodeShape.EndCircle })
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun supports_plain_links_and_unquoted_edge_labels() {
        val parser = feedAll(
            """
            block
              columns 2
              A B C
              A --- B
              B -- sync --> C
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.edges.size)
        assertEquals(com.hrm.diagram.core.ir.ArrowEnds.None, ir.edges.first().arrow)
        assertEquals("sync", (ir.edges.last().label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }
}
