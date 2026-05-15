package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Rect
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
    fun bracket_component_relations_render_with_visible_nodes_and_edges() {
        val snapshot = run(
            """
            @startuml
            [Web] --> [API]
            [API] --> [DB]
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 4,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertTrue(ir.nodes.any { it.id == NodeId("Web") })
        assertTrue(ir.nodes.any { it.id == NodeId("API") })
        assertTrue(ir.nodes.any { it.id == NodeId("DB") })
        assertEquals(2, ir.edges.size)
        assertEquals(2, snapshot.laidOut!!.edgeRoutes.size)
        assertTrue(snapshot.drawCommands.isNotEmpty())
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

    @Test
    fun component_skinparam_styles_render_consistently() {
        val src =
            """
            @startuml
            skinparam component {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 17
              FontName monospace
              LineThickness 2.5
              Shadowing true
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Peru
              FontColor Red
              FontSize 15
              FontName serif
              LineThickness 2
              Shadowing true
            }
            skinparam package {
              BackgroundColor LightGray
              BorderColor Silver
              FontColor Green
              FontSize 16
              FontName sans-serif
              LineThickness 2.25
              Shadowing true
            }
            skinparam ArrowColor Blue
            package Backend {
              component Api
              note right of Api : ingress
            }
            Api --> Api : self
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        val fillRects = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        val strokeRects = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val strokePaths = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        val textColors = texts.map { it.color.argb }
        val textFamilies = texts.map { it.font.family }
        val textSizes = texts.map { it.font.sizeSp }
        assertTrue(fillRects.contains(0xFFD3D3D3.toInt()))
        assertTrue(fillRects.contains(0xFFFFFFE0.toInt()))
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()))
        assertTrue(fillRects.contains(0x26000000))
        assertTrue(strokeRects.any { it.color.argb == 0xFFC0C0C0.toInt() && it.stroke.width == 2.25f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFFFA500.toInt() && it.stroke.width == 2.5f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFCD853F.toInt() && it.stroke.width == 2f })
        assertTrue(strokePaths.any { it.color.argb == 0xFF0000FF.toInt() })
        assertTrue(textColors.contains(0xFF008000.toInt()))
        assertTrue(textColors.contains(0xFF000080.toInt()))
        assertTrue(textColors.contains(0xFFFF0000.toInt()))
        assertTrue(textFamilies.contains("monospace"))
        assertTrue(textFamilies.contains("serif"))
        assertTrue(textFamilies.contains("sans-serif"))
        assertTrue(textSizes.contains(17f))
        assertTrue(textSizes.contains(15f))
        assertTrue(textSizes.contains(16f))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun component_ports_packages_and_notes_do_not_overlap() {
        val snapshot = run(
            """
            @startuml
            skinparam component {
              BackgroundColor #EFF6FF
              BorderColor #2563EB
              FontColor #1E3A8A
            }
            package Backend {
              component "Order API" as Api {
                portin HttpIn
                portout EventsOut
              }
              queue Jobs
              database Orders
            }
            interface "HTTP" as Http
            Http --> HttpIn : REST
            EventsOut --> Jobs : publish
            Api --> Orders : persist
            note right of Api
              port + package + queue/database preview
            end note
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 5,
        )
        val laidOut = assertNotNull(snapshot.laidOut)
        val api = laidOut.nodePositions.getValue(NodeId("Api"))
        val httpIn = laidOut.nodePositions.getValue(NodeId("HttpIn"))
        val eventsOut = laidOut.nodePositions.getValue(NodeId("EventsOut"))
        val jobs = laidOut.nodePositions.getValue(NodeId("Jobs"))
        val orders = laidOut.nodePositions.getValue(NodeId("Orders"))
        val noteId = laidOut.nodePositions.keys.first { it.value.startsWith("Api__note_") }
        val note = laidOut.nodePositions.getValue(noteId)
        val componentCluster = laidOut.clusterRects.getValue(NodeId("Api__cluster"))
        val packageCluster = laidOut.clusterRects.getValue(NodeId("Backend"))

        assertTrue(kotlin.math.abs(httpIn.right - api.left) <= httpIn.size.width / 2f + 1f, "HttpIn should anchor to Api left border")
        assertTrue(kotlin.math.abs(eventsOut.left - api.right) <= eventsOut.size.width / 2f + 1f, "EventsOut should anchor to Api right border")
        assertTrue(!note.overlaps(jobs), "note should not overlap Jobs: note=$note jobs=$jobs")
        assertTrue(!note.overlaps(orders), "note should not overlap Orders: note=$note orders=$orders")
        assertTrue(!api.overlaps(jobs), "Api should not overlap Jobs: api=$api jobs=$jobs")
        assertTrue(!api.overlaps(orders), "Api should not overlap Orders: api=$api orders=$orders")
        assertTrue(!jobs.overlaps(orders), "Jobs should not overlap Orders: jobs=$jobs orders=$orders")
        assertTrue(api.right + 30f <= jobs.left || jobs.right + 30f <= api.left, "Api and Jobs should keep horizontal visual gap")
        assertTrue(api.right + 30f <= orders.left || orders.right + 30f <= api.left, "Api and Orders should keep horizontal visual gap")
        assertTrue(componentCluster.contains(api), "component cluster should include Api host: cluster=$componentCluster api=$api")
        assertTrue(componentCluster.contains(httpIn), "component cluster should include HttpIn: cluster=$componentCluster port=$httpIn")
        assertTrue(componentCluster.contains(eventsOut), "component cluster should include EventsOut: cluster=$componentCluster port=$eventsOut")
        assertTrue(packageCluster.contains(componentCluster), "package should include nested component cluster: package=$packageCluster component=$componentCluster")
        assertTrue(packageCluster.contains(jobs), "package should include Jobs: package=$packageCluster jobs=$jobs")
        assertTrue(packageCluster.contains(orders), "package should include Orders: package=$packageCluster orders=$orders")
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
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

    private fun Rect.overlaps(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun Rect.contains(other: Rect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

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
