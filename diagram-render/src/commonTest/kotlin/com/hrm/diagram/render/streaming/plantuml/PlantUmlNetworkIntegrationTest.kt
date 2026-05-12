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

class PlantUmlNetworkIntegrationTest {
    @Test
    fun nwdiag_inside_startuml_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            nwdiag {
              network dmz {
                address = "210.x.x.x/24"
                web [address = "210.x.x.10", description = "frontend"];
              }
              network internal {
                address = "172.16.x.x/24"
                web;
                db [address = "172.16.x.20"];
              }
            }
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(2, oneIr.clusters.size)
        assertEquals(1, oneIr.edges.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("frontend") })
    }

    @Test
    fun startnwdiag_special_block_is_supported() {
        val snapshot = run(
            """
            @startnwdiag
            nwdiag {
              network lan {
                address = "10.0.0.0/24"
                app;
                db;
              }
            }
            @endnwdiag
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.clusters.size)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
    }

    @Test
    fun nwdiag_group_renders_as_nested_cluster_with_streaming_consistency() {
        val src =
            """
            @startnwdiag
            nwdiag {
              network office {
                address = "10.0.0.0/24"
                group servers {
                  app;
                  db [address = "10.0.0.20"];
                }
                laptop;
              }
            }
            @endnwdiag
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val network = oneIr.clusters.single()
        assertEquals(1, network.nestedClusters.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().size >= 2)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("servers") })
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
