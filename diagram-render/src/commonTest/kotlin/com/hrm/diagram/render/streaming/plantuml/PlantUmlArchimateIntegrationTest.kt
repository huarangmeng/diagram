package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlArchimateIntegrationTest {
    @Test
    fun archimate_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            archimate #LightBlue "Service" as S <<business-service>>
            archimate "Application" as App <<application-component>>
            S --> App : uses
            Rel(App, S, "serves")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(2, oneIr.nodes.size)
        assertEquals(2, oneIr.edges.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Service" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "<<business-service>>" })
    }

    @Test
    fun archimate_group_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            group "Business Layer" as BL {
              archimate "Customer" as C <<business-actor>>
              group "Application Layer" as AL {
                archimate "Portal" as P <<application-component>>
              }
            }
            C --> P : uses
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(1, oneIr.clusters.size)
        assertEquals(1, oneIr.clusters.single().nestedClusters.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().size >= 2)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Business Layer" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Application Layer" })
    }

    @Test
    fun archimate_full_icon_and_relation_semantics_are_streaming_consistent() {
        val src =
            """
            @startuml
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
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(7, oneIr.nodes.size)
        assertEquals(6, oneIr.edges.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Customer" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "B" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "A" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "T" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "P" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "M" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "S" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "I" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillPath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().size >= oneIr.edges.size)
    }

    @Test
    fun archimate_demo_layout_keeps_layer_headers_and_relation_labels_clear() {
        val src =
            """
            @startuml
            archimate business-actor #LightYellow "Customer" as C
            archimate application-component #LightBlue "Portal" as P
            archimate technology-node "K8s" as K
            archimate motivation-goal "Reduce Cost" as G
            group "Application Layer" as AL {
              archimate implementation-event "Launch" as E
            }
            Rel_Serving(P, C, "serves")
            Rel_Assignment(K, P, "hosts")
            Rel_Realization(P, G, "realizes")
            Rel_Flow(C, E, "starts")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val laid = assertNotNull(one.laidOut)
        val nodes = laid.nodePositions
        val portal = assertNotNull(nodes[NodeId("P")])
        val k8s = assertNotNull(nodes[NodeId("K")])
        val customer = assertNotNull(nodes[NodeId("C")])
        val launch = assertNotNull(nodes[NodeId("E")])
        val goal = assertNotNull(nodes[NodeId("G")])
        val applicationLayer = assertNotNull(laid.clusterRects[NodeId("AL")])
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>()

        assertTrue(portal.top - k8s.bottom >= 100f, "K8s and Portal should leave room for the hosts label")
        assertTrue(launch.top - customer.bottom >= 100f, "Customer and Launch should leave room for the starts label and layer header")
        assertTrue(applicationLayer.top >= customer.bottom + 24f, "Application Layer header should not overlap the business row")

        val starts = texts.single { it.text == "starts" }
        assertTrue(
            starts.origin.y + 12f <= applicationLayer.top + 10f || starts.origin.y - 12f >= applicationLayer.top + 34f,
            "starts label should not overlap the Application Layer title band: starts=${starts.origin}, layer=$applicationLayer",
        )

        val implementation = texts.single { it.text == "<<implementation-event>>" }
        val launchLabel = texts.single { it.text == "Launch" }
        assertTrue(
            implementation.origin.y + 12f <= launchLabel.origin.y,
            "implementation stereotype should be measured and rendered above the Launch label without internal overlap",
        )

        val realizes = texts.single { it.text == "realizes" }
        val portalCenterX = (portal.left + portal.right) / 2f
        val goalCenterX = (goal.left + goal.right) / 2f
        assertTrue(
            realizes.origin.x > portalCenterX && realizes.origin.x < goalCenterX,
            "realizes label should sit on the Bezier midpoint, not on the target-side control point: ${realizes.origin}",
        )
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
}
