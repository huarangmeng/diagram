package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlObjectParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlObjectIntegrationTest {
    @Test
    fun object_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            object Order {
              id = 1
              total = 99
            }
            object Customer
            Order --> Customer : owner
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun object_members_affect_rendering() {
        val snapshot = run(
            """
            @startuml
            object Order {
              id = 1
              total = 99
            }
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.nodes.size)
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun object_relations_yield_routes() {
        val snapshot = run(
            """
            @startuml
            object A
            object B
            A ..> B : ref
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.edges.size)
        assertEquals(1, snapshot.laidOut!!.edgeRoutes.size)
    }

    @Test
    fun object_note_package_and_map_json_render_consistently() {
        val src =
            """
            @startuml
            package "Domain" {
              namespace "Orders" {
                object Order {
                  id = 1
                }
                map Cache {
                  key => value
                }
                json Payload {
                  orderId: 1
                }
                note right of Order
                  aggregate
                  root
                end note
              }
            }
            Order --> Cache
            Payload ..> Order
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(oneIr.clusters.isNotEmpty())
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.laidOut!!.clusterRects.isNotEmpty())
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "note" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "map" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "json" })
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
