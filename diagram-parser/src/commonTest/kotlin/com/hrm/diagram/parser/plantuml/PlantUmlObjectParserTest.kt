package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlObjectParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlObjectParser {
        val parser = PlantUmlObjectParser()
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
    fun object_declarations_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                object "Customer #1" as Customer
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(RichLabel.Plain("Order"), ir.nodes.first { it.id == NodeId("Order") }.label)
        assertEquals(RichLabel.Plain("Customer #1"), ir.nodes.first { it.id == NodeId("Customer") }.label)
    }

    @Test
    fun object_member_block_is_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order {
                  id = 1
                  total = 99
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val payload = ir.nodes.single().payload[PlantUmlObjectParser.MEMBERS_KEY]
        assertEquals("id = 1\ntotal = 99", payload)
    }

    @Test
    fun dotted_member_is_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                Order : id = 1
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("id = 1", ir.nodes.single().payload[PlantUmlObjectParser.MEMBERS_KEY])
    }

    @Test
    fun relations_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                object Customer
                Order --> Customer : owner
                Customer .. Order
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(2, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[1].kind)
    }

    @Test
    fun direction_supported() {
        val ir = parse("top to bottom direction\nobject Order\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            object Order {
              id = 1
            }
            object Customer
            Order --> Customer
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
