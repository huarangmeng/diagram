package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlErdParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlErdParser {
        val parser = PlantUmlErdParser()
        if (chunkSize == null) {
            src.lines().forEach { parser.acceptLine(it) }
        } else {
            var pending = ""
            var index = 0
            while (index < src.length) {
                val end = (index + chunkSize).coerceAtMost(src.length)
                val merged = pending + src.substring(index, end)
                var start = 0
                for (i in merged.indices) {
                    if (merged[i] == '\n') {
                        parser.acceptLine(merged.substring(start, i))
                        start = i + 1
                    }
                }
                pending = if (start < merged.length) merged.substring(start) else ""
                index = end
            }
            if (pending.isNotEmpty()) parser.acceptLine(pending)
        }
        parser.finish(blockClosed = true)
        return parser
    }

    @Test
    fun parses_entity_with_inline_attributes() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity Customer { *id : uuid; name : text }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.nodes.any { it.id == NodeId("Customer") })
        assertTrue(ir.nodes.any { it.id == NodeId("Customer::id") })
        assertEquals("PK", ir.nodes.first { it.id == NodeId("Customer::id") }.payload[PlantUmlErdParser.ER_ATTRIBUTE_FLAGS_KEY])
    }

    @Test
    fun parses_multiline_entity_block() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity Order {
                  *id : uuid
                  +customer_id : uuid
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.nodes.any { it.id == NodeId("Order::customer_id") })
    }

    @Test
    fun parses_relationship_and_label() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity Customer { *id }
                entity Order { *id }
                Customer ||--o{ Order : places
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(3, ir.edges.size)
        val relation = ir.edges.first { it.label != null }
        assertEquals(RichLabel.Plain("||--o{ places"), relation.label)
    }

    @Test
    fun parses_anchored_note_and_simple_alias() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity Customer as C { *id }
                note right of C : VIP customers only
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.nodes.any { it.id == NodeId("C") && it.payload[PlantUmlErdParser.ER_KIND_KEY] == PlantUmlErdParser.ER_ENTITY_KIND })
        val note = ir.nodes.first { it.payload[PlantUmlErdParser.ER_KIND_KEY] == PlantUmlErdParser.ER_NOTE_KIND }
        assertEquals("C", note.payload[PlantUmlErdParser.ER_NOTE_TARGET_KEY])
        assertEquals("right", note.payload[PlantUmlErdParser.ER_NOTE_PLACEMENT_KEY])
        assertTrue(ir.edges.any { it.from == note.id && it.to == NodeId("C") })
    }

    @Test
    fun parses_complex_alias_and_nonstandard_attributes() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity "Customer Account" as CustomerAccount {
                  *customer_id uuid
                  "display name" text
                }
                entity InvoiceHeader as "Invoice Header" {
                  decimal(10,2) total_amount
                  status text
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(RichLabel.Plain("Customer Account"), ir.nodes.first { it.id == NodeId("CustomerAccount") }.label)
        assertEquals(RichLabel.Plain("Invoice Header"), ir.nodes.first { it.id == NodeId("InvoiceHeader") }.label)
        val customerId = ir.nodes.first { it.id == NodeId("CustomerAccount::customer_id") }
        assertEquals("customer_id", customerId.payload[PlantUmlErdParser.ER_ATTRIBUTE_NAME_KEY])
        assertEquals("uuid", customerId.payload[PlantUmlErdParser.ER_ATTRIBUTE_TYPE_KEY])
        val displayName = ir.nodes.first { it.id == NodeId("CustomerAccount::display_name") }
        assertEquals("display name", displayName.payload[PlantUmlErdParser.ER_ATTRIBUTE_NAME_KEY])
        assertEquals("text", displayName.payload[PlantUmlErdParser.ER_ATTRIBUTE_TYPE_KEY])
        val totalAmount = ir.nodes.first { it.id == NodeId("InvoiceHeader::total_amount") }
        assertEquals("total_amount", totalAmount.payload[PlantUmlErdParser.ER_ATTRIBUTE_NAME_KEY])
        assertEquals("decimal(10,2)", totalAmount.payload[PlantUmlErdParser.ER_ATTRIBUTE_TYPE_KEY])
    }

    @Test
    fun parses_more_relationship_decorations() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                entity CustomerAccount { *id }
                entity InvoiceHeader { *id }
                CustomerAccount }|..|{ InvoiceHeader : allocates
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val relation = ir.edges.first { it.label != null }
        assertEquals(RichLabel.Plain("}|..|{ allocates"), relation.label)
        assertTrue(relation.style.dash != null)
    }

    @Test
    fun supports_direction() {
        val ir = parse("top to bottom direction\nentity Customer { *id }\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            entity Customer {
              *id : uuid
            }
            entity Order { *id }
            Customer ||--o{ Order : places
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
