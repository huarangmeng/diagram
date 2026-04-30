package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlUsecaseIntegrationTest {
    @Test
    fun usecase_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            rectangle Checkout {
              actor User
              (Pay) as PayUc
            }
            User --> PayUc : starts
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun usecase_clusters_produce_cluster_rects() {
        val snapshot = run(
            """
            @startuml
            package Portal {
              rectangle Auth {
                actor User
                usecase Login
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
    fun actor_and_usecase_edges_yield_routes() {
        val snapshot = run(
            """
            @startuml
            actor User
            (Checkout) as CheckoutUc
            User ..> CheckoutUc : <<include>>
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.edges.size)
        assertEquals(1, snapshot.laidOut!!.edgeRoutes.size)
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
