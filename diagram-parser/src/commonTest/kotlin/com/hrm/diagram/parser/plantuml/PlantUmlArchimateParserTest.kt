package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.GraphIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlArchimateParserTest {
    @Test
    fun parses_elements_colors_stereotypes_and_relations() {
        val parser = PlantUmlArchimateParser()
        """
        archimate #LightBlue "Service" as S <<business-service>>
        archimate "Application" as App <<application-component>>
        S --> App : uses
        Rel(App, S, "serves")
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
        assertEquals("business-service", ir.nodes.single { it.id.value == "S" }.payload[PlantUmlArchimateParser.STEREOTYPE_KEY])
        assertTrue(ir.edges.any { labelOf(it.label) == "uses" })
        assertTrue(ir.edges.any { labelOf(it.label) == "serves" })
    }

    @Test
    fun creates_placeholders_for_relation_endpoints() {
        val parser = PlantUmlArchimateParser()
        parser.acceptLine("""Rel(A, B, "depends")""")
        val ir = parser.snapshot()
        assertEquals(listOf("A", "B"), ir.nodes.map { it.id.value })
        assertEquals(1, ir.edges.size)
    }

    @Test
    fun parses_group_blocks_as_clusters() {
        val parser = PlantUmlArchimateParser()
        """
        group "Business Layer" as BL {
          archimate "Customer" as C <<business-actor>>
          group "Application Layer" as AL {
            archimate "Portal" as P <<application-component>>
          }
        }
        C --> P : uses
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertEquals(1, ir.clusters.size)
        val business = ir.clusters.single()
        assertEquals("BL", business.id.value)
        assertEquals(listOf("C"), business.children.map { it.value })
        assertEquals(1, business.nestedClusters.size)
        val app = business.nestedClusters.single()
        assertEquals("AL", app.id.value)
        assertEquals(listOf("P"), app.children.map { it.value })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    private fun labelOf(label: com.hrm.diagram.core.ir.RichLabel?): String? =
        when (label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
            null -> null
        }
}
