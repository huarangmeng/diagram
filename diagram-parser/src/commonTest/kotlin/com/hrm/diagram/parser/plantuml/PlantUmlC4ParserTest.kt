package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlC4ParserTest {
    @Test
    fun parses_context_elements_and_relation() {
        val parser = PlantUmlC4Parser()
        """
        !include <C4/C4_Container>
        C4Context
        Person(u, "User")
        System(s, "App")
        Rel(u, s, "uses")
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals("C4Context", ir.styleHints.extras["c4.diagramKind"])
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertEquals("u", ir.edges.single().from.value)
        assertEquals("s", ir.edges.single().to.value)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_boundaries_tags_and_relation_variants() {
        val parser = PlantUmlC4Parser()
        """
        C4Container
        AddElementTag("hot", ${'$'}bgColor="#FFF3E0", ${'$'}borderColor="#FB8C00", ${'$'}fontColor="#E65100")
        Container_Boundary(app, "Application") {
          Container(api, "API", "Ktor", "Backend", ${'$'}tags="hot")
          ContainerDb(db, "Database", "PostgreSQL", "Stores data")
        }
        BiRel(api, db, "syncs", "JDBC")
        Rel_Back(db, api, "reads")
        """.trimIndent().lines().forEach { parser.acceptLine(it) }

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertEquals(1, ir.clusters.size)
        assertEquals(listOf("api", "db"), ir.clusters.single().children.map { it.value })
        assertEquals(NodeShape.Cylinder, ir.nodes.single { it.id.value == "db" }.shape)
        val api = ir.nodes.single { it.id.value == "api" }
        assertEquals(0xFFFFF3E0.toInt(), api.style.fill?.argb)
        assertEquals(0xFFFB8C00.toInt(), api.style.stroke?.argb)
        assertEquals(0xFFE65100.toInt(), api.style.textColor?.argb)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_style_macros_for_elements_and_relationships() {
        val parser = PlantUmlC4Parser()
        """
        C4Container
        AddRelTag("async", ${'$'}lineColor="#8E24AA", ${'$'}lineStyle="DashedLine()")
        Person(user, "User")
        Container(api, "API", "Ktor", "Backend")
        Rel(user, api, "Uses", "HTTPS", ${'$'}tags="async")
        UpdateElementStyle(api, ${'$'}bgColor="#FFF3E0", ${'$'}fontColor="#123456", ${'$'}borderColor="#333333")
        UpdateRelStyle(user, api, ${'$'}lineColor="#111111", ${'$'}lineStyle="BoldLine()")
        """.trimIndent().lines().forEach { parser.acceptLine(it) }

        val ir = assertIs<GraphIR>(parser.snapshot())
        val api = ir.nodes.single { it.id.value == "api" }
        assertEquals(0xFFFFF3E0.toInt(), api.style.fill?.argb)
        assertEquals(0xFF333333.toInt(), api.style.stroke?.argb)
        assertEquals(0xFF123456.toInt(), api.style.textColor?.argb)
        val edge = ir.edges.single()
        assertEquals(0xFF111111.toInt(), edge.style.color?.argb)
        assertEquals(2.5f, edge.style.width)
        assertEquals(listOf(7f, 5f), edge.style.dash)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_deployment_nodes_links_and_legend() {
        val parser = PlantUmlC4Parser()
        """
        C4Deployment
        Deployment_Node(node, "Kubernetes", "cluster", "prod") {
          Container(api, "API", "Ktor", "Backend", ${'$'}link="https://example.com/api")
        }
        System_Ext(ext, "External")
        Rel(api, ext, "calls")
        SHOW_LEGEND()
        """.trimIndent().lines().forEach { parser.acceptLine(it) }

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(3, ir.nodes.size)
        assertEquals(1, ir.clusters.size)
        assertEquals("api", ir.clusters.single().children.single().value)
        val api = ir.nodes.single { it.id.value == "api" }
        assertEquals("https://example.com/api", api.payload[PlantUmlC4Parser.LINK_KEY])
        val legend = ir.nodes.single { it.id.value == "c4:legend" }
        assertEquals("true", legend.payload[PlantUmlC4Parser.LEGEND_KEY])
        assertTrue(labelOf(legend).contains("Relationship"))
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_full_c4_macro_helpers_layout_links_and_presentations() {
        val parser = PlantUmlC4Parser()
        """
        C4Container
        UpdateLayoutConfig(${ '$' }c4ShapeInRow="3", ${ '$' }c4BoundaryInRow="2", ${ '$' }layout="TB")
        AddElementTag("octa", ${ '$' }bgColor="#f96", ${ '$' }shape="EightSidedShape()", ${ '$' }legendText="Octagon Element")
        AddElementTag("rounded", ${ '$' }shape="RoundedBoxShape()")
        AddRelTag("async", ${ '$' }textColor="blue", ${ '$' }lineColor="#8E24AA", ${ '$' }lineStyle="DashedLine()", ${ '$' }legendText="Async Link")
        System_Boundary(sys, "System", ${ '$' }tags="rounded", ${ '$' }link="https://example.com/boundary") {
          Person_Ext(user, "External User", "Customer", ${ '$' }tags="rounded")
          ContainerQueue(q, "Queue", "Kafka", "Events", ${ '$' }tags="octa", ${ '$' }link="https://example.com/q")
          ComponentDb(repo, "Repo", "PostgreSQL", "Stores state")
        }
        BiRel_R(user, q, "publishes", "HTTPS", ${ '$' }tags="async", ${ '$' }link="https://example.com/rel")
        RelIndex(1, q, repo, "stores", ${ '$' }techn="JDBC")
        UpdateRelStyle(user, q, ${ '$' }textColor="#123456", ${ '$' }lineColor="#111111", ${ '$' }lineStyle="BoldLine()", ${ '$' }offsetX="12", ${ '$' }offsetY="-4")
        Lay_D(user, repo)
        SHOW_LEGEND()
        """.trimIndent().lines().forEach { parser.acceptLine(it) }

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(Direction.TB, ir.styleHints.direction)
        assertEquals("3", ir.styleHints.extras["c4.shapeInRow"])
        assertEquals("2", ir.styleHints.extras["c4.boundaryInRow"])
        assertEquals(4, ir.nodes.size)
        assertEquals(3, ir.edges.size)
        assertEquals(1, ir.edges.count { it.kind == EdgeKind.Invisible })
        assertEquals(ArrowEnds.Both, ir.edges[0].arrow)
        assertEquals("R", ir.edges[0].fromPort?.value)
        assertEquals("L", ir.edges[0].toPort?.value)
        assertEquals(NodeShape.Custom("octagon"), ir.nodes.single { it.id.value == "q" }.shape)
        assertEquals(NodeShape.Cylinder, ir.nodes.single { it.id.value == "repo" }.shape)
        assertEquals("true", ir.nodes.single { it.id.value == "user" }.payload[PlantUmlC4Parser.EXTERNAL_KEY])
        assertEquals("https://example.com/q", parser.nodeLinkSnapshot()[com.hrm.diagram.core.ir.NodeId("q")])
        assertEquals("https://example.com/boundary", parser.nodeLinkSnapshot()[com.hrm.diagram.core.ir.NodeId("sys")])
        assertEquals("https://example.com/rel", parser.edgeLinkSnapshot()[0])
        val presentation = parser.edgePresentationSnapshot()[0]
        assertEquals(0xFF123456.toInt(), presentation?.textColor?.argb)
        assertEquals(12f, presentation?.offsetX)
        assertEquals(-4f, presentation?.offsetY)
        assertEquals(0xFF111111.toInt(), ir.edges[0].style.color?.argb)
        assertEquals(3f, ir.edges[0].style.width)
        val legend = ir.nodes.single { it.id.value == "c4:legend" }
        assertTrue(labelOf(legend).contains("Octagon Element"))
        assertTrue(labelOf(legend).contains("Async Link"))
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    private fun labelOf(node: com.hrm.diagram.core.ir.Node): String =
        when (val label = node.label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}
