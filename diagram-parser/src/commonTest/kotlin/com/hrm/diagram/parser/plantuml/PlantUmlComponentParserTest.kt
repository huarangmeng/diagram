package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlComponentParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlComponentParser {
        val parser = PlantUmlComponentParser()
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
    fun component_interface_and_port_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                component "API" as Api
                interface "HTTP" as Http
                portin In
                portout Out
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(NodeShape.Component, ir.nodes.first { it.id == NodeId("Api") }.shape)
        assertEquals(NodeShape.Circle, ir.nodes.first { it.id == NodeId("Http") }.shape)
        assertEquals("in", ir.nodes.first { it.id == NodeId("In") }.payload[PlantUmlComponentParser.PORT_DIR_KEY])
        assertEquals("out", ir.nodes.first { it.id == NodeId("Out") }.payload[PlantUmlComponentParser.PORT_DIR_KEY])
    }

    @Test
    fun clusters_build_hierarchy() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                package Backend {
                  cloud Aws {
                    component Api
                  }
                  node Runtime {
                    interface Http
                  }
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(1, ir.clusters.size)
        val backend = ir.clusters.single()
        assertEquals("Backend", backend.id.value)
        assertEquals(2, backend.nestedClusters.size)
        assertTrue(backend.nestedClusters.any { it.id.value == "Aws" })
        assertTrue(backend.nestedClusters.any { it.id.value == "Runtime" })
    }

    @Test
    fun bracket_form_and_implicit_nodes_supported() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                [Gateway] as Gw
                Gw --> Downstream
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(RichLabel.Plain("Gateway"), ir.nodes.first { it.id == NodeId("Gw") }.label)
        assertTrue(ir.nodes.any { it.id == NodeId("Downstream") })
    }

    @Test
    fun relations_parse_arrow_and_dash_variants() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                component A
                component B
                A --> B : uses
                B ..> A : callback
                A -- B
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(3, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[1].kind)
        assertEquals(ArrowEnds.None, ir.edges[2].arrow)
    }

    @Test
    fun direction_supported() {
        val ir = parse("top to bottom direction\ncomponent A\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            package Backend {
              component Api
              interface Http
            }
            Api --> Http : serves
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
