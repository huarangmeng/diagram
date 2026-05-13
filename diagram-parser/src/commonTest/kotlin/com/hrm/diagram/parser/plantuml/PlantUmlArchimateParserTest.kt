package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.EdgeKind
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
        assertEquals("business-service", ir.nodes.single { it.id.value == "S" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertTrue(ir.edges.any { labelOf(it.label) == "uses" })
        assertTrue(ir.edges.any { labelOf(it.label) == "serves" })
    }

    @Test
    fun preserves_archimate_element_types_and_relationship_semantics() {
        val parser = PlantUmlArchimateParser()
        """
        archimate business-actor #LightYellow "Customer" as C
        archimate application-component "Portal" as P
        archimate technology-node "K8s" as K
        archimate physical-equipment "Router" as R
        archimate motivation-goal "Reduce Cost" as G
        archimate strategy-capability "Self Service" as Cap
        archimate implementation-event "Launch" as E
        Rel_Composition(C, P, "owns")
        Rel_Aggregation(P, K, "groups")
        Rel_Assignment(K, R, "runs")
        Rel_Realization(Cap, G, "realizes")
        Rel_Access(P, E, "reads")
        Rel_Flow(R, C, "traffic")
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(7, ir.nodes.size)
        assertEquals("business-actor", ir.nodes.single { it.id.value == "C" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("application-component", ir.nodes.single { it.id.value == "P" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("technology-node", ir.nodes.single { it.id.value == "K" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("physical-equipment", ir.nodes.single { it.id.value == "R" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("motivation-goal", ir.nodes.single { it.id.value == "G" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("strategy-capability", ir.nodes.single { it.id.value == "Cap" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals("implementation-event", ir.nodes.single { it.id.value == "E" }.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY])
        assertEquals(listOf("composition", "aggregation", "assignment", "realization", "access", "flow"), parser.relationTypesSnapshot())
        assertEquals(ArrowEnds.None, ir.edges[0].arrow)
        assertEquals(ArrowEnds.None, ir.edges[1].arrow)
        assertEquals(ArrowEnds.ToOnly, ir.edges[2].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[3].kind)
        assertEquals(EdgeKind.Dashed, ir.edges[4].kind)
        assertEquals(EdgeKind.Dashed, ir.edges[5].kind)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
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
