package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlDeploymentIntegrationTest {
    @Test
    fun deployment_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            node Server {
              [App]
            }
            database MainDb
            App --> MainDb : jdbc
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
    fun cloud_and_database_generate_layout_artifacts() {
        val snapshot = run(
            """
            @startuml
            cloud Aws {
              node Runtime {
                [App]
              }
            }
            database MainDb
            App ..> MainDb : read
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(ir.clusters.isNotEmpty())
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun artifact_only_inside_node_uses_deployment_dispatcher() {
        val snapshot = run(
            """
            @startuml
            node Server {
              [App]
            }
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.clusters.size)
        assertEquals(1, ir.nodes.size)
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
