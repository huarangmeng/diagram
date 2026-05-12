package com.hrm.diagram.parser.plantuml

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
}
