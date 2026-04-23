package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-corpus tests for the Mermaid Phase 1 flowchart subset.
 *
 * Each case feeds the canonical (one-shot) source through the full lexer→parser pipeline and
 * asserts the resulting `GraphIR`. New corpus entries belong here — keep `parseAll` minimal so
 * additions stay declarative.
 */
class MermaidFlowchartParserTest {

    private fun parseAll(src: String): MermaidFlowchartParser {
        val lexer = MermaidLexer()
        val step = lexer.feed(lexer.initialState(), src, 0, eos = true)
        val parser = MermaidFlowchartParser()
        var lineStart = 0
        for (i in step.tokens.indices) {
            if (step.tokens[i].kind == MermaidTokenKind.NEWLINE) {
                if (i > lineStart) parser.acceptLine(step.tokens.subList(lineStart, i).toList())
                lineStart = i + 1
            }
        }
        if (lineStart < step.tokens.size) {
            parser.acceptLine(step.tokens.subList(lineStart, step.tokens.size).toList())
        }
        return parser
    }

    @Test
    fun golden_1_minimal_two_nodes() {
        val ir = parseAll("flowchart TD\nA --> B\n").snapshot()
        assertEquals(SourceLanguage.MERMAID, ir.sourceLanguage)
        assertEquals(Direction.TB, ir.styleHints.direction)
        assertEquals(listOf("A", "B"), ir.nodes.map { it.id.value })
        assertEquals(1, ir.edges.size)
        assertEquals(NodeId("A") to NodeId("B"), ir.edges[0].from to ir.edges[0].to)
    }

    @Test
    fun golden_2_labels_and_lr_direction() {
        val ir = parseAll("flowchart LR\nA[Start] --> B[End]\n").snapshot()
        assertEquals(Direction.LR, ir.styleHints.direction)
        val byId = ir.nodes.associateBy { it.id.value }
        assertEquals("Start", (byId["A"]!!.label as RichLabel.Plain).text)
        assertEquals("End", (byId["B"]!!.label as RichLabel.Plain).text)
    }

    @Test
    fun golden_3_diamond_three_edges() {
        val ir = parseAll(
            """
            flowchart TD
            A --> B
            B --> C
            A --> C
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(setOf("A", "B", "C"), ir.nodes.map { it.id.value }.toSet())
        assertEquals(3, ir.edges.size)
    }

    @Test
    fun golden_4_node_decl_then_use() {
        val ir = parseAll(
            """
            flowchart TD
            A[Standalone]
            B
            A --> B
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(listOf("A", "B"), ir.nodes.map { it.id.value })
        assertEquals(1, ir.edges.size)
    }

    @Test
    fun golden_5_comments_and_blank_lines_are_skipped() {
        val ir = parseAll(
            """
            flowchart TD
            %% top comment

            A --> B
            %% trailing
            B --> C
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(listOf("A", "B", "C"), ir.nodes.map { it.id.value })
        assertEquals(2, ir.edges.size)
        assertTrue(parseAllDiagnosticsAreEmpty("flowchart TD\nA --> B\n"))
    }

    private fun parseAllDiagnosticsAreEmpty(src: String): Boolean =
        parseAll(src).diagnosticsSnapshot().isEmpty()

    @Test
    fun bad_header_reports_diagnostic() {
        val p = parseAll("nonsense\n")
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        assertTrue(!p.headerSeen)
    }

    @Test
    fun bad_statement_does_not_kill_parser() {
        val p = parseAll(
            """
            flowchart TD
            A -->
            B --> C
            """.trimIndent() + "\n",
        )
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        // C and B still made it in via the second valid line.
        assertTrue(p.snapshot().nodes.any { it.id.value == "C" })
    }

    @Test
    fun forward_reference_node_is_added_then_label_is_kept_on_first_mention() {
        val ir = parseAll(
            """
            flowchart TD
            A --> B
            A[Labeled later]
            """.trimIndent() + "\n",
        ).snapshot()
        // First mention of A had no label; the later [Labeled later] is currently dropped (subset rule).
        val a = ir.nodes.first { it.id.value == "A" }
        assertEquals(RichLabel.Empty, a.label)
    }

    // ----- Phase 1 v2: shape aliases + edge labels -----

    @Test
    fun golden_v2_1_rounded_box_to_box() {
        val ir = parseAll("flowchart TD\nA(round) --> B[box]\n").snapshot()
        val a = ir.nodes.first { it.id.value == "A" }
        val b = ir.nodes.first { it.id.value == "B" }
        assertEquals(com.hrm.diagram.core.ir.NodeShape.RoundedBox, a.shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Box, b.shape)
        assertEquals(RichLabel.Plain("round"), a.label)
        assertEquals(RichLabel.Plain("box"), b.label)
    }

    @Test
    fun golden_v2_2_circle_default_target() {
        val ir = parseAll("flowchart TD\nA((circle)) --> B\n").snapshot()
        val a = ir.nodes.first { it.id.value == "A" }
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Circle, a.shape)
        assertEquals(RichLabel.Plain("circle"), a.label)
    }

    @Test
    fun golden_v2_3_diamond_with_yes_no_edge_labels() {
        val p = parseAll(
            """
            flowchart TD
            A{decision} -->|yes| B
            A -->|no| C
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val a = ir.nodes.first { it.id.value == "A" }
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Diamond, a.shape)
        assertEquals(RichLabel.Plain("decision"), a.label)
        val edges = p.snapshot().edges
        assertEquals(2, edges.size)
        assertEquals(RichLabel.Plain("yes"), edges[0].label)
        assertEquals(RichLabel.Plain("no"), edges[1].label)
        assertEquals(NodeId("B"), edges[0].to)
        assertEquals(NodeId("C"), edges[1].to)
    }

    @Test
    fun golden_v2_4_lr_chain_mixing_three_shapes() {
        val p = parseAll(
            """
            flowchart LR
            A(R) --> B((C))
            B --> D{D}
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertEquals(Direction.LR, ir.styleHints.direction)
        val byId = ir.nodes.associateBy { it.id.value }
        assertEquals(com.hrm.diagram.core.ir.NodeShape.RoundedBox, byId.getValue("A").shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Circle, byId.getValue("B").shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Diamond, byId.getValue("D").shape)
        // Two edges, neither labeled.
        assertEquals(2, p.snapshot().edges.size)
        assertTrue(p.snapshot().edges.all { it.label == null })
    }

    @Test
    fun golden_v2_5_kitchen_sink_comments_shapes_labels() {
        val p = parseAll(
            """
            flowchart TD
            %% top-level comment line
            Start((start)) -->|init| Auth{auth?}
            Auth -->|ok| Home[Home page]
            Auth -->|fail| Login(login form)
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val byId = ir.nodes.associateBy { it.id.value }
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Circle, byId.getValue("Start").shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Diamond, byId.getValue("Auth").shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.Box, byId.getValue("Home").shape)
        assertEquals(com.hrm.diagram.core.ir.NodeShape.RoundedBox, byId.getValue("Login").shape)
        val edges = p.snapshot().edges
        assertEquals(3, edges.size)
        assertEquals(listOf("init", "ok", "fail"), edges.map { (it.label as RichLabel.Plain).text })
        assertTrue(p.diagnosticsSnapshot().isEmpty(), "no diagnostics expected")
    }
}
