package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
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
