package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlC4IntegrationTest {
    @Test
    fun c4_plantuml_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            !include <C4/C4_Container>
            C4Container
            Person(u, "User", "Customer")
            Container_Boundary(app, "Application") {
              Container(api, "API", "Ktor", "Backend")
              ContainerDb(db, "Database", "PostgreSQL", "Stores data")
            }
            Rel(u, api, "uses", "HTTPS")
            Rel_Back(db, api, "reads")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(2, oneIr.edges.size)
        assertEquals(1, oneIr.clusters.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("API") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Application") })
    }

    @Test
    fun c4_style_macros_render_and_are_streaming_consistent() {
        val src =
            """
            @startuml
            C4Container
            AddRelTag("async", ${'$'}lineColor="#8E24AA", ${'$'}lineStyle="DashedLine()")
            Person(user, "User")
            Container(api, "API", "Ktor", "Backend")
            Rel(user, api, "Uses", "HTTPS", ${'$'}tags="async")
            UpdateElementStyle(api, ${'$'}bgColor="#FFF3E0", ${'$'}fontColor="#123456", ${'$'}borderColor="#333333")
            UpdateRelStyle(user, api, ${'$'}lineColor="#111111", ${'$'}lineStyle="BoldLine()")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(0xFFFFF3E0.toInt(), oneIr.nodes.single { it.id.value == "api" }.style.fill?.argb)
        assertEquals(0xFF111111.toInt(), oneIr.edges.single().style.color?.argb)
        assertEquals(2.5f, oneIr.edges.single().style.width)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Uses") })
    }

    @Test
    fun c4_deployment_legend_and_links_render_with_streaming_consistency() {
        val src =
            """
            @startuml
            C4Deployment
            Deployment_Node(node, "Kubernetes", "cluster", "prod") {
              Container(api, "API", "Ktor", "Backend", ${'$'}link="https://example.com/api")
            }
            System_Ext(ext, "External")
            Rel(api, ext, "calls")
            SHOW_LEGEND()
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 8)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(1, oneIr.clusters.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("[Legend]") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "L" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().isNotEmpty())
    }

    @Test
    fun c4_full_macro_helpers_render_and_are_streaming_consistent() {
        val src =
            """
            @startuml
            C4Container
            UpdateLayoutConfig(${ '$' }c4ShapeInRow="3", ${ '$' }layout="TB")
            AddElementTag("octa", ${ '$' }bgColor="#f96", ${ '$' }shape="EightSidedShape()", ${ '$' }legendText="Octagon Element")
            AddRelTag("async", ${ '$' }textColor="blue", ${ '$' }lineColor="#8E24AA", ${ '$' }lineStyle="DashedLine()", ${ '$' }legendText="Async Link")
            System_Boundary(sys, "System", ${ '$' }link="https://example.com/boundary") {
              Person_Ext(user, "External User", "Customer")
              ContainerQueue(q, "Queue", "Kafka", "Events", ${ '$' }tags="octa", ${ '$' }link="https://example.com/q")
            }
            BiRel_R(user, q, "publishes", "HTTPS", ${ '$' }tags="async", ${ '$' }link="https://example.com/rel")
            UpdateRelStyle(user, q, ${ '$' }textColor="#123456", ${ '$' }lineColor="#111111", ${ '$' }lineStyle="BoldLine()", ${ '$' }offsetX="12", ${ '$' }offsetY="-4")
            Lay_D(user, q)
            SHOW_LEGEND()
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(2, oneIr.edges.size)
        assertEquals(1, oneIr.clusters.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillPath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.Hyperlink>().size >= 2)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Octagon Element") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("publishes") })
    }

    @Test
    fun c4_edge_labels_avoid_node_overlap() {
        val src =
            """
            @startuml
            !include <C4/C4_Container>
            C4Container
            UpdateLayoutConfig(${ '$' }c4ShapeInRow="3", ${ '$' }layout="TB")
            AddElementTag("critical", ${ '$' }bgColor="#f96", ${ '$' }layout="TB")
            AddElementTag("async", ${ '$' }textColor="blue", ${ '$' }lineColor="#8E24AA", ${ '$' }lineStyle="DashedLine()", ${ '$' }legendText="Async")
            System_Boundary(sys, "Ordering", ${ '$' }link="https://example.com/system") {
              Person_Ext(u, "Customer", "Buyer")
              Container(api, "API", "Ktor", "Backend", ${ '$' }tags="critical", ${ '$' }link="https://example.com/api")
              ContainerQueue(q, "Events", "Kafka", "Async events")
            }
            BiRel_R(u, api, "uses", "HTTPS")
            Rel(api, q, "publishes", "JSON", ${ '$' }tags="async", ${ '$' }link="https://example.com/events")
            Lay_D(api, q)
            SHOW_LEGEND()
            @enduml
            """.trimIndent() + "\n"

        val state = run(src, src.length)
        val laidOut = assertNotNull(state.laidOut)
        val api = assertNotNull(laidOut.nodePositions[NodeId("api")])
        val queue = assertNotNull(laidOut.nodePositions[NodeId("q")])
        val labelText = state.drawCommands
            .filterIsInstance<DrawCommand.DrawText>()
            .single { it.text.contains("publishes") }
        val labelRect = state.drawCommands
            .filterIsInstance<DrawCommand.FillRect>()
            .filter { it.z == 3 }
            .minBy { distanceSquared(centerOf(it.rect), labelText.origin) }
            .rect

        assertTrue(!overlaps(labelRect, inflate(api, 4f)), "publishes label must not overlap api node")
        assertTrue(!overlaps(labelRect, inflate(queue, 4f)), "publishes label must not overlap queue node")
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

    private fun inflate(rect: Rect, delta: Float): Rect =
        Rect.ltrb(rect.left - delta, rect.top - delta, rect.right + delta, rect.bottom + delta)

    private fun overlaps(a: Rect, b: Rect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun centerOf(rect: Rect) =
        com.hrm.diagram.core.draw.Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

    private fun distanceSquared(a: com.hrm.diagram.core.draw.Point, b: com.hrm.diagram.core.draw.Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
