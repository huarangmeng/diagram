package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlComponentParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlComponentIntegrationTest {
    @Test
    fun component_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            package Backend {
              component "API" as Api {
                portin In
                portout Out
              }
              interface "HTTP" as Http
              () "gRPC" as Grpc
            }
            In ..> Api : input
            Api --> Out : output
            Out --> Http : serves
            Out --> Grpc : streams
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun ports_anchor_to_component_border_and_rewrite_edge_geometry() {
        val snapshot = run(
            """
            @startuml
            left to right direction
            [Client]
            component Api {
              portin In
              portout Out
            }
            [Worker]
            Client --> In
            In --> Api
            Api --> Out
            Out --> Worker
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        val laidOut = assertNotNull(snapshot.laidOut)
        val apiRect = laidOut.nodePositions.getValue(NodeId("Api"))
        val inRect = laidOut.nodePositions.getValue(NodeId("In"))
        val outRect = laidOut.nodePositions.getValue(NodeId("Out"))
        assertTrue(kotlin.math.abs(inRect.right - apiRect.left) <= inRect.size.width / 2f + 1f, "portin should hug Api left edge")
        assertTrue(kotlin.math.abs(outRect.left - apiRect.right) <= outRect.size.width / 2f + 1f, "portout should hug Api right edge")
        val inEdge = laidOut.edgeRoutes.first { it.from == NodeId("Client") && it.to == NodeId("In") }
        val outEdge = laidOut.edgeRoutes.first { it.from == NodeId("Out") && it.to == NodeId("Worker") }
        val inCenter = centerOf(inRect)
        val outCenter = centerOf(outRect)
        assertEquals(inCenter, inEdge.points.last())
        assertEquals(outCenter, outEdge.points.first())
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertTrue(ir.nodes.first { it.id == NodeId("In") }.payload[PlantUmlComponentParser.PORT_HOST_KEY] == "Api")
    }

    @Test
    fun component_clusters_produce_cluster_rects() {
        val snapshot = run(
            """
            @startuml
            cloud Aws {
              node Runtime {
                component Worker
              }
            }
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.clusters.size)
        assertTrue(snapshot.laidOut!!.clusterRects.isNotEmpty())
    }

    @Test
    fun component_relations_yield_edges() {
        val snapshot = run(
            """
            @startuml
            component A
            component B
            A --> B : link
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.edges.size)
        assertEquals(1, snapshot.laidOut!!.edgeRoutes.size)
    }

    @Test
    fun advanced_component_shapes_and_note_render_consistently() {
        val src =
            """
            @startuml
            frame Runtime {
              rectangle Services {
                component Api
                database Db
                queue Jobs
              }
            }
            note right of Api
              handles ingress
              and validation
            end note
            Api --> Db : reads
            Api ..> Jobs : enqueues
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val note = oneIr.nodes.first { it.payload[PlantUmlComponentParser.KIND_KEY] == "note" }
        val laidOut = assertNotNull(one.laidOut)
        val noteRect = laidOut.nodePositions.getValue(note.id)
        val apiRect = laidOut.nodePositions.getValue(NodeId("Api"))
        assertTrue(noteRect.left >= apiRect.right, "component note should stay to the right of Api")
        assertNotNull(laidOut.clusterRects[NodeId("Runtime")])
        assertNotNull(laidOut.clusterRects[NodeId("Services")])
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

    private fun centerOf(rect: com.hrm.diagram.core.draw.Rect) =
        com.hrm.diagram.core.draw.Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

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
