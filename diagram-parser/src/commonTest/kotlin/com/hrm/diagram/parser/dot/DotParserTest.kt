package com.hrm.diagram.parser.dot

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotParserTest {
    @Test
    fun parses_digraph_nodes_edges_attrs_and_rankdir() {
        val result = DotParser().parse(
            """
            digraph G {
              graph [rankdir=LR, label="Pipeline"];
              node [shape=box, style="rounded,filled", fillcolor="#E3F2FD"];
              edge [color=blue, penwidth=2];
              a [label="Start"];
              b [shape=diamond, label="Ready?"];
              a -> b [label="check", style=dashed];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        val ir = result.ir
        assertEquals("Pipeline", ir.title)
        assertEquals(Direction.LR, ir.styleHints.direction)
        assertEquals(2, ir.nodes.size)
        assertEquals(NodeShape.RoundedBox, ir.nodes.single { it.id.value == "dot_a" }.shape)
        assertEquals(NodeShape.Diamond, ir.nodes.single { it.id.value == "dot_b" }.shape)
        assertEquals(1, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges.single().arrow)
        assertEquals(EdgeKind.Dashed, ir.edges.single().kind)
        assertEquals("check", labelOf(ir.edges.single().label!!))
    }

    @Test
    fun parses_undirected_graph_and_cluster() {
        val result = DotParser().parse(
            """
            graph {
              subgraph cluster_api {
                label="API";
                color=gray;
                gateway -- service;
              }
              service -- db [label="reads"];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        val ir = result.ir
        assertEquals(3, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertTrue(ir.edges.all { it.arrow == ArrowEnds.None })
        assertEquals(1, ir.clusters.size)
        assertEquals("dot_cluster_api", ir.clusters.single().id.value)
        assertEquals(listOf("dot_gateway", "dot_service"), ir.clusters.single().children.map { it.value })
    }

    @Test
    fun parses_edge_chain_and_html_like_label_as_text() {
        val result = DotParser().parse(
            """
            strict digraph {
              a -> b -> c;
              c [label=< <B>Done</B> >, shape=ellipse];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        val ir = result.ir
        assertEquals(3, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertEquals(NodeShape.Ellipse, ir.nodes.single { it.id.value == "dot_c" }.shape)
        assertEquals("Done", labelOf(ir.nodes.single { it.id.value == "dot_c" }.label))
    }

    @Test
    fun expands_node_sets_ports_and_rank_groups() {
        val result = DotParser().parse(
            """
            digraph {
              { rank=same; a; b }
              { a b } -> { c d } [label="fanout"];
              c:out:e -> sink:in:w [arrowtail=dot, arrowhead=vee];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        val ir = result.ir
        assertEquals(5, ir.nodes.size)
        assertEquals(5, ir.edges.size)
        assertEquals(
            setOf("dot_a->dot_c", "dot_a->dot_d", "dot_b->dot_c", "dot_b->dot_d"),
            ir.edges.filter { it.label?.let(::labelOf) == "fanout" }.map { "${it.from.value}->${it.to.value}" }.toSet(),
        )
        val portEdge = ir.edges.single { it.from.value == "dot_c" && it.to.value == "dot_sink" }
        assertEquals("out", portEdge.payload["dot.edge.fromPort"])
        assertEquals("e", portEdge.payload["dot.edge.fromCompass"])
        assertEquals("in", portEdge.payload["dot.edge.toPort"])
        assertEquals("w", portEdge.payload["dot.edge.toCompass"])
        assertEquals(ArrowEnds.Both, portEdge.arrow)
        assertEquals("same", ir.styleHints.extras["dot.rank.0.kind"])
        assertEquals("dot_a,dot_b", ir.styleHints.extras["dot.rank.0.nodes"])
    }

    @Test
    fun preserves_html_table_label_as_multiline_text() {
        val result = DotParser().parse(
            """
            digraph {
              table [label=<
                <TABLE>
                  <TR><TD>Key</TD><TD>Value</TD></TR>
                  <TR><TD>a&amp;b</TD><TD>&lt;ok&gt;</TD></TR>
                </TABLE>
              >];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        assertEquals("Key Value\na&b <ok>", labelOf(result.ir.nodes.single().label))
    }

    @Test
    fun extracts_html_label_font_hints_to_payload() {
        val result = DotParser().parse(
            """
            digraph {
              node [label=< <FONT FACE="serif" POINT-SIZE="18" COLOR="#336699"><B><I>Rich</I></B></FONT> >];
              a -> b [label=< <I>edge</I> >, headlabel=< <FONT COLOR="red">head</FONT> >];
            }
            """.trimIndent(),
        )

        assertTrue(result.diagnostics.isEmpty(), "diagnostics: ${result.diagnostics}")
        val node = result.ir.nodes.single { it.id.value == "dot_a" }
        assertEquals("Rich", labelOf(node.label))
        assertEquals("serif", node.payload["dot.node.html.fontname"])
        assertEquals("18", node.payload["dot.node.html.fontsize"])
        assertEquals("#336699", node.payload["dot.node.html.fontcolor"])
        assertEquals("true", node.payload["dot.node.html.bold"])
        assertEquals("true", node.payload["dot.node.html.italic"])
        val edge = result.ir.edges.single()
        assertEquals("edge", labelOf(edge.label!!))
        assertEquals("true", edge.payload["dot.edge.html.italic"])
        assertEquals("red", edge.payload["dot.edge.head.html.fontcolor"])
    }

    @Test
    fun warns_for_native_graphviz_layout_engines_but_preserves_graph() {
        val result = DotParser().parse(
            """
            graph {
              graph [layout=neato, overlap=false, root=a];
              a -- b;
            }
            """.trimIndent(),
        )

        assertEquals(2, result.ir.nodes.size)
        assertEquals(1, result.ir.edges.size)
        assertEquals("neato", result.ir.styleHints.extras["dot.graph.layout"])
        assertTrue(result.diagnostics.all { it.severity == Severity.WARNING }, "diagnostics: ${result.diagnostics}")
        assertTrue(result.diagnostics.any { it.code == "DOT-W001" && it.message.contains("layout=neato") })
        assertTrue(result.diagnostics.any { it.code == "DOT-W001" && it.message.contains("overlap") })
    }

    @Test
    fun incremental_session_matches_one_shot_parse_for_single_char_chunks() {
        val src =
            """
            digraph {
              graph [rankdir=LR];
              { rank=same; a; b }
              { a b } -> { c d } [label=< <B>fanout</B> >];
              c:out:e -> sink:in:w [arrowtail=dot, arrowhead=vee];
            }
            """.trimIndent()
        val expected = DotParser().parse(src)
        val incremental = DotParser().incrementalSession()
        var actual = incremental.feed("", eos = false)
        for (ch in src) {
            actual = incremental.feed(ch.toString(), eos = false)
        }
        actual = incremental.feed("", eos = true)

        assertEquals(expected.ir, actual.ir)
        assertEquals(expected.diagnostics, actual.diagnostics)
    }

    private fun labelOf(label: com.hrm.diagram.core.ir.RichLabel): String =
        when (label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}
