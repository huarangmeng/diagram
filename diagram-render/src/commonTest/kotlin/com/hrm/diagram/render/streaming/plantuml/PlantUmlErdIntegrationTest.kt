package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlErdParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun anchored_note_renders_and_stays_bound_to_entity() {
        val snapshot = run(
            """
            @startuml
            entity Customer as C {
              *id : uuid
            }
            note right of C : VIP customers only
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        val note = ir.nodes.first { it.payload[PlantUmlErdParser.ER_KIND_KEY] == PlantUmlErdParser.ER_NOTE_KIND }
        val laidOut = assertNotNull(snapshot.laidOut)
        val entityRect = laidOut.nodePositions.getValue(com.hrm.diagram.core.ir.NodeId("C"))
        val noteRect = laidOut.nodePositions.getValue(note.id)
        assertTrue(noteRect.left >= entityRect.right, "note should be placed to the right of its target entity")
        assertTrue(laidOut.edgeRoutes.any { it.from == note.id && it.to == com.hrm.diagram.core.ir.NodeId("C") })
    }

    @Test
    fun note_first_chunk_is_dispatched_to_erd_instead_of_activity() {
        val snapshot = run(
            """
            @startuml
            note right of Customer : imported from CRM
            entity Customer { *id }
            @enduml
            """.trimIndent() + "\n",
            4,
        )
        assertIs<GraphIR>(snapshot.ir)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
    }

    @Test
    fun complex_alias_relationship_decorations_and_nonstandard_attributes_render_consistently() {
        val src =
            """
            @startuml
            entity "Customer Account" as CustomerAccount {
              *customer_id uuid
              "display name" text
            }
            entity InvoiceHeader as "Invoice Header" {
              decimal(10,2) total_amount
              status text
            }
            CustomerAccount }|..|{ InvoiceHeader : allocates
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(oneIr.nodes.any { it.id.value == "CustomerAccount::display_name" })
        assertTrue(oneIr.nodes.any { it.id.value == "InvoiceHeader::total_amount" })
        assertTrue(oneIr.edges.any { (it.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text == "}|..|{ allocates" })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
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
