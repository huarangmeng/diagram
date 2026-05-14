package com.hrm.diagram.render.streaming.dot

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DotIntegrationTest {
    @Test
    fun digraph_renders_and_is_streaming_consistent() {
        val src =
            """
            digraph G {
              graph [rankdir=LR, label="Build"];
              node [shape=box, style="rounded,filled", fillcolor="#E3F2FD"];
              checkout -> test [label="run"];
              test -> package [style=dashed];
            }
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(2, oneIr.edges.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "run" })
    }

    @Test
    fun graph_clusters_render_as_cluster_rects() {
        val src =
            """
            graph {
              subgraph cluster_api {
                label="API";
                color=gray;
                gateway -- service;
              }
              service -- db [label="reads"];
            }
            """.trimIndent() + "\n"

        val snapshot = run(src, 7)
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertEquals(1, ir.clusters.size)
        assertEquals(3, ir.nodes.size)
        assertTrue(snapshot.laidOut!!.clusterRects.isNotEmpty())
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().isNotEmpty())
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "API" })
    }

    @Test
    fun node_sets_ports_and_html_labels_render_consistently() {
        val src =
            """
            digraph {
              graph [rankdir=LR];
              { rank=same; a; b }
              { a b } -> { c d } [label=< <B>fanout</B> >];
              c:out:e -> sink:in:w;
            }
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "diagnostics: ${one.diagnostics}")
        assertEquals(5, oneIr.nodes.size)
        assertEquals(5, oneIr.edges.size)
        assertEquals("dot_a,dot_b", oneIr.styleHints.extras["dot.rank.0.nodes"])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "fanout" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().size >= 5)
    }

    @Test
    fun graph_node_and_edge_attributes_affect_rendering() {
        val src =
            """
            digraph {
              graph [rankdir=LR, nodesep=1.0, ranksep=1.2, bgcolor="#FEF3C7"];
              node [fontname="JetBrains Mono", fontsize=18, shape=box, URL="https://example.com/node"];
              a [label="Alpha"];
              b [label="Beta"];
              a -> b [
                label="main",
                headlabel="head",
                taillabel="tail",
                arrowhead=diamond,
                arrowtail=dot,
                constraint=false,
                weight=3
              ];
            }
            """.trimIndent() + "\n"

        val snapshot = run(src, 6)
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertEquals("1.0", ir.styleHints.extras["dot.graph.nodesep"])
        assertEquals("1.2", ir.styleHints.extras["dot.graph.ranksep"])
        assertEquals("#FEF3C7", ir.styleHints.extras["dot.graph.bgcolor"])
        assertEquals("3", ir.edges.single().payload["dot.edge.weight"])
        assertEquals("false", ir.edges.single().payload["dot.edge.constraint"])
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == 0xFFFEF3C7.toInt() })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.Hyperlink>().any { it.href == "https://example.com/node" })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Alpha" && it.font.family == "JetBrains Mono" && it.font.sizeSp == 18f })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "head" })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "tail" })
    }

    @Test
    fun rank_constraints_affect_final_sugiyama_layers() {
        val src =
            """
            digraph {
              { rank=min; start }
              { rank=same; left; right }
              { rank=max; finish }
              start -> left;
              start -> right;
              left -> finish;
              right -> finish;
            }
            """.trimIndent() + "\n"

        val snapshot = run(src, 8)
        val ir = assertIs<GraphIR>(snapshot.ir)
        val positions = snapshot.laidOut!!.nodePositions
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertEquals("min", ir.styleHints.extras["dot.rank.0.kind"])
        assertEquals("same", ir.styleHints.extras["dot.rank.1.kind"])
        assertEquals("max", ir.styleHints.extras["dot.rank.2.kind"])
        val start = positions.getValue(com.hrm.diagram.core.ir.NodeId("dot_start"))
        val left = positions.getValue(com.hrm.diagram.core.ir.NodeId("dot_left"))
        val right = positions.getValue(com.hrm.diagram.core.ir.NodeId("dot_right"))
        val finish = positions.getValue(com.hrm.diagram.core.ir.NodeId("dot_finish"))
        assertEquals(left.top, right.top)
        assertTrue(start.top < left.top)
        assertTrue(finish.top > left.top)
    }

    @Test
    fun html_label_font_hints_affect_rendered_text() {
        val src =
            """
            digraph {
              a [label=< <FONT FACE="serif" POINT-SIZE="18" COLOR="#336699"><B><I>Rich</I></B></FONT> >];
              b [label="Plain"];
              a -> b [label=< <I>edge</I> >, headlabel=< <FONT COLOR="red"><B>head</B></FONT> >];
            }
            """.trimIndent() + "\n"

        val snapshot = run(src, 9)
        val rich = snapshot.drawCommands
            .filterIsInstance<DrawCommand.DrawText>()
            .single { it.text == "Rich" }
        val edge = snapshot.drawCommands
            .filterIsInstance<DrawCommand.DrawText>()
            .single { it.text == "edge" }
        val head = snapshot.drawCommands
            .filterIsInstance<DrawCommand.DrawText>()
            .single { it.text == "head" }
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertEquals("serif", rich.font.family)
        assertEquals(18f, rich.font.sizeSp)
        assertEquals(700, rich.font.weight)
        assertTrue(rich.font.italic)
        assertEquals(0xFF336699.toInt(), rich.color.argb)
        assertTrue(edge.font.italic)
        assertEquals(700, head.font.weight)
        assertEquals(0xFFE53935.toInt(), head.color.argb)
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.DOT).let { session ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                session.append(src.substring(i, end))
                i = end
            }
            session.finish()
        } finally {
            session.close()
        }
    }
}
