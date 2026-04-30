package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlErdIntegrationTest {
    @Test
    fun erd_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            entity Customer {
              *id : uuid
              name : text
            }
            entity Order { *id : uuid }
            Customer ||--o{ Order : places
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
    fun final_render_embeds_attributes() {
        val snapshot = run(
            """
            @startuml
            entity Customer {
              *id : uuid
              +account_id : uuid
              name : text
            }
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(ir.nodes.any { it.id.value == "Customer::id" })
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun crowfoot_relationship_yields_route() {
        val snapshot = run(
            """
            @startuml
            entity Customer { *id }
            entity Order { *id }
            Customer ||--o{ Order : places
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(ir.edges.any { it.label != null })
        assertTrue(snapshot.laidOut!!.edgeRoutes.isNotEmpty())
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
