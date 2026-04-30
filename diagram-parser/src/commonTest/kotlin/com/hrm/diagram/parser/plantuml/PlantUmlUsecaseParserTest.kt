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

class PlantUmlUsecaseParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlUsecaseParser {
        val parser = PlantUmlUsecaseParser()
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
    fun actor_and_usecase_declarations_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                actor "Customer" as User
                usecase "Login" as LoginUc
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("actor", ir.nodes.first { it.id == NodeId("User") }.payload[PlantUmlUsecaseParser.KIND_KEY])
        assertEquals("usecase", ir.nodes.first { it.id == NodeId("LoginUc") }.payload[PlantUmlUsecaseParser.KIND_KEY])
    }

    @Test
    fun shorthand_forms_are_supported() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                :Visitor:
                (Checkout) as CheckoutUc
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.nodes.any { (it.label as? RichLabel.Plain)?.text == "Visitor" })
        assertEquals(RichLabel.Plain("Checkout"), ir.nodes.first { it.id == NodeId("CheckoutUc") }.label)
    }

    @Test
    fun rectangle_and_package_clusters_build_hierarchy() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                package System {
                  rectangle Checkout {
                    actor User
                    usecase Pay
                  }
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(1, ir.clusters.size)
        val pkg = ir.clusters.single()
        assertEquals("System", pkg.id.value)
        assertEquals(1, pkg.nestedClusters.size)
        assertEquals("Checkout", pkg.nestedClusters.single().id.value)
    }

    @Test
    fun relations_and_labels_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                actor User
                usecase Pay
                User --> Pay : starts
                (Pay) ..> (Notify) : <<include>>
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(2, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[1].kind)
        assertEquals(RichLabel.Plain("<<include>>"), ir.edges[1].label)
    }

    @Test
    fun direction_supported() {
        val ir = parse("top to bottom direction\nactor User\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            package Portal {
              actor User
              (Login) as LoginUc
            }
            User --> LoginUc : starts
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
