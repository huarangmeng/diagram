package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidOfficialSampleTest {

    @Test
    fun official_flowchart_branching_sample_renders() {
        val snap = runSession(
            """
            flowchart LR
                A[Hard edge] -->|Link text| B(Round edge)
                B --> C{Decision}
                C -->|One| D[Result one]
                C -->|Two| E[Result two]
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(snap.ir)
        assertEquals(5, ir.nodes.size)
        assertEquals(4, ir.edges.size)
        assertTrue(ir.nodes.any { it.id.value == "B" && it.shape == NodeShape.RoundedBox })
        assertTrue(ir.nodes.any { it.id.value == "C" && it.shape == NodeShape.Diamond })
        assertTrue(ir.edges.any { (it.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text == "Link text" })
        assertTrue(ir.edges.any { (it.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text == "One" })
        assertTrue(ir.edges.any { (it.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text == "Two" })
        assertTrue(snap.drawCommands.isNotEmpty())
        assertTrue(snap.diagnostics.isEmpty(), "expected official sample to parse cleanly: ${snap.diagnostics}")
    }

    @Test
    fun official_flowchart_style_sample_applies_node_colors() {
        val snap = runSession(
            """
            flowchart LR
                id1(Start)-->id2(Stop)
                style id1 fill:#f9f,stroke:#333,stroke-width:4px
                style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
            """.trimIndent() + "\n",
        )

        val fills = snap.drawCommands
            .filterIsInstance<DrawCommand.FillRect>()
            .map { it.color.argb.toLong() and 0xFFFFFFFFL }
            .toSet()
        val strokes = snap.drawCommands
            .filterIsInstance<DrawCommand.StrokeRect>()
            .map { (it.color.argb.toLong() and 0xFFFFFFFFL) to it.stroke.width }
            .toSet()

        assertTrue(fills.contains(0xFFFF99FFL), "expected #f9f node fill (got: $fills)")
        assertTrue(fills.contains(0xFFBBBBFFL), "expected #bbf node fill (got: $fills)")
        assertTrue(strokes.contains(0xFF333333L to 4f), "expected #333 stroke width 4 (got: $strokes)")
        assertTrue(strokes.contains(0xFFFF6666L to 2f), "expected #f66 stroke width 2 (got: $strokes)")
    }

    @Test
    fun official_er_order_example_renders_relationship_and_attributes() {
        val snap = runSession(
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
                CUSTOMER {
                    string name
                    string custNumber
                    string sector
                }
                ORDER {
                    int orderNumber
                    string deliveryAddress
                }
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(snap.ir)
        val nodeIds = ir.nodes.map { it.id.value }.toSet()
        assertTrue("CUSTOMER" in nodeIds)
        assertTrue("ORDER" in nodeIds)
        assertTrue("CUSTOMER::name" in nodeIds)
        assertTrue("CUSTOMER::custNumber" in nodeIds)
        assertTrue("ORDER::orderNumber" in nodeIds)
        assertTrue(ir.edges.any { (it.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text == "||--o{ places" })
        val texts = snap.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.contains("CUSTOMER"))
        assertTrue(texts.contains("ORDER"))
        assertTrue(texts.contains("||--o{"))
        assertTrue(texts.contains("places"))
        assertTrue(snap.diagnostics.isEmpty(), "expected official ER sample to parse cleanly: ${snap.diagnostics}")
    }

    @Test
    fun official_er_style_sample_applies_entity_colors() {
        val snap = runSession(
            """
            erDiagram
                id1||--||id2 : label
                style id1 fill:#f9f,stroke:#333,stroke-width:4px
                style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
            """.trimIndent() + "\n",
        )

        val fills = snap.drawCommands
            .filterIsInstance<DrawCommand.FillRect>()
            .map { it.color.argb.toLong() and 0xFFFFFFFFL }
            .toSet()
        val strokes = snap.drawCommands
            .filterIsInstance<DrawCommand.StrokeRect>()
            .map { (it.color.argb.toLong() and 0xFFFFFFFFL) to it.stroke.width }
            .toSet()

        assertTrue(fills.contains(0xFFFF99FFL), "expected #f9f entity fill (got: $fills)")
        assertTrue(fills.contains(0xFFBBBBFFL), "expected #bbf entity fill (got: $fills)")
        assertTrue(strokes.contains(0xFF333333L to 4f), "expected #333 stroke width 4 (got: $strokes)")
        assertTrue(strokes.contains(0xFFFF6666L to 2f), "expected #f66 stroke width 2 (got: $strokes)")
    }

    @Test
    fun official_flowchart_branching_streaming_is_consistent() {
        val src =
            """
            flowchart LR
                A[Hard edge] -->|Link text| B(Round edge)
                B --> C{Decision}
                C -->|One| D[Result one]
                C -->|Two| E[Result two]
            """.trimIndent() + "\n"

        assertStreamingConsistent(src)
    }

    @Test
    fun official_flowchart_style_streaming_is_consistent() {
        val src =
            """
            flowchart LR
                id1(Start)-->id2(Stop)
                style id1 fill:#f9f,stroke:#333,stroke-width:4px
                style id2 fill:#bbf,stroke:#f66,stroke-width:2px,color:#fff,stroke-dasharray: 5 5
            """.trimIndent() + "\n"

        assertStreamingConsistent(src)
    }

    @Test
    fun official_er_order_example_streaming_is_consistent() {
        val src =
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
                CUSTOMER {
                    string name
                    string custNumber
                    string sector
                }
                ORDER {
                    int orderNumber
                    string deliveryAddress
                }
            """.trimIndent() + "\n"

        assertStreamingConsistent(src)
    }

    private fun runSession(src: String): DiagramSnapshot {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append(src)
            return s.finish()
        } finally {
            s.close()
        }
    }

    private fun runSessionChunked(src: String, chunkSize: Int): DiagramSnapshot {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            return s.finish()
        } finally {
            s.close()
        }
    }

    private fun assertStreamingConsistent(src: String) {
        val one = runSession(src)
        val c1 = runSessionChunked(src, chunkSize = 1)
        val c4 = runSessionChunked(src, chunkSize = 4)

        val oneIr = assertIs<GraphIR>(one.ir)
        val c1Ir = assertIs<GraphIR>(c1.ir)
        val c4Ir = assertIs<GraphIR>(c4.ir)

        assertEquals(graphSignature(oneIr), graphSignature(c1Ir), "one-shot vs chunkSize=1 GraphIR mismatch")
        assertEquals(graphSignature(oneIr), graphSignature(c4Ir), "one-shot vs chunkSize=4 GraphIR mismatch")
        assertEquals(drawSignature(one.drawCommands), drawSignature(c1.drawCommands), "one-shot vs chunkSize=1 DrawCommand mismatch")
        assertEquals(drawSignature(one.drawCommands), drawSignature(c4.drawCommands), "one-shot vs chunkSize=4 DrawCommand mismatch")
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(c1.diagnostics.isEmpty(), "chunkSize=1 diagnostics: ${c1.diagnostics}")
        assertTrue(c4.diagnostics.isEmpty(), "chunkSize=4 diagnostics: ${c4.diagnostics}")
    }

    private fun graphSignature(ir: GraphIR): Pair<List<NodeSig>, List<EdgeSig>> {
        // Order matters for edges (linkStyle index semantics), so keep edge order.
        val nodes = ir.nodes.map { it.toSig() }.sortedBy { it.id }
        val edges = ir.edges.map { it.toSig() }
        return nodes to edges
    }

    private data class NodeSig(
        val id: String,
        val shape: NodeShape,
        val label: String,
        val style: com.hrm.diagram.core.ir.NodeStyle,
        val payload: List<Pair<String, String>>,
    )

    private data class EdgeSig(
        val from: String,
        val to: String,
        val label: String?,
        val style: com.hrm.diagram.core.ir.EdgeStyle,
    )

    private fun Node.toSig(): NodeSig =
        NodeSig(
            id = id.value,
            shape = shape,
            label = (label as? RichLabel.Plain)?.text ?: "",
            style = style,
            payload = payload.entries.map { it.key to it.value }.sortedBy { it.first },
        )

    private fun Edge.toSig(): EdgeSig =
        EdgeSig(
            from = from.value,
            to = to.value,
            label = (label as? RichLabel.Plain)?.text,
            style = style,
        )

    private fun drawSignature(cmds: List<DrawCommand>): List<DrawSig> =
        cmds.map { it.toSig() }

    /**
     * Draw output signature that intentionally ignores geometry (coords/paths/rects) to avoid
     * brittle tests, but still locks "visual identity": paint colors, strokes/dashes, fonts,
     * text content, and Z ordering.
     */
    private sealed interface DrawSig {
        val z: Int

        data class FillRectSig(
            val colorArgb: Int,
            val corner: Float,
            override val z: Int,
        ) : DrawSig

        data class StrokeRectSig(
            val colorArgb: Int,
            val stroke: StrokeSig,
            val corner: Float,
            override val z: Int,
        ) : DrawSig

        data class FillPathSig(
            val colorArgb: Int,
            override val z: Int,
        ) : DrawSig

        data class StrokePathSig(
            val colorArgb: Int,
            val stroke: StrokeSig,
            override val z: Int,
        ) : DrawSig

        data class DrawTextSig(
            val text: String,
            val font: FontSig,
            val colorArgb: Int,
            val maxWidth: Float?,
            val anchorX: TextAnchorX,
            val anchorY: TextAnchorY,
            override val z: Int,
        ) : DrawSig

        data class DrawArrowSig(
            val style: String,
            override val z: Int,
        ) : DrawSig

        data class DrawIconSig(
            val name: String,
            override val z: Int,
        ) : DrawSig

        data class GroupSig(
            val children: List<DrawSig>,
            override val z: Int,
        ) : DrawSig

        data class ClipSig(
            val children: List<DrawSig>,
            override val z: Int,
        ) : DrawSig

        data class HyperlinkSig(
            val href: String,
            override val z: Int,
        ) : DrawSig
    }

    private data class StrokeSig(
        val width: Float,
        val dash: List<Float>?,
    )

    private data class FontSig(
        val family: String,
        val sizeSp: Float,
        val weight: Int,
        val italic: Boolean,
    )

    private fun Stroke.toSig(): StrokeSig = StrokeSig(width = width, dash = dash)

    private fun FontSpec.toSig(): FontSig =
        FontSig(
            family = family,
            sizeSp = sizeSp,
            weight = weight,
            italic = italic,
        )

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
            is DrawCommand.DrawArrow -> DrawSig.DrawArrowSig(style = style.toString(), z = z)
            is DrawCommand.DrawIcon -> DrawSig.DrawIconSig(name = name, z = z)
            is DrawCommand.Group -> DrawSig.GroupSig(children = children.map { it.toSig() }, z = z)
            is DrawCommand.Clip -> DrawSig.ClipSig(children = children.map { it.toSig() }, z = z)
            is DrawCommand.Hyperlink -> DrawSig.HyperlinkSig(href = href, z = z)
        }
}
