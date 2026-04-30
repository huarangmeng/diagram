package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlStateIntegrationTest {
    @Test
    fun state_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            state Active {
              [*] --> Loading
              Loading --> Ready : load [ok] / log
            }
            note right of Active : lifecycle
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 3)
        val oneIr = one.ir as? StateIR
        val chunkedIr = chunked.ir as? StateIR
        assertNotNull(oneIr)
        assertNotNull(chunkedIr)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun state_pseudo_nodes_render() {
        val snap = run(
            """
            @startuml
            [*] --> A
            A --> [*]
            @enduml
            """.trimIndent() + "\n",
            4,
        )
        val ir = snap.ir as? StateIR
        assertNotNull(ir)
        assertTrue(ir.states.any { it.kind == StateKind.Initial })
        assertTrue(ir.states.any { it.kind == StateKind.Final })
        assertEquals(2, snap.laidOut?.edgeRoutes?.size)
    }

    @Test
    fun history_and_note_generate_layout_artifacts() {
        val snap = run(
            """
            @startuml
            state Parent {
              [H] --> Ready
              --
              Ready --> [H*]
            }
            note top of Parent : regions
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = snap.ir as? StateIR
        assertNotNull(ir)
        assertTrue(ir.states.any { it.kind == StateKind.History })
        assertTrue(ir.states.any { it.kind == StateKind.DeepHistory })
        assertNotNull(snap.laidOut?.clusterRects?.get(NodeId("note#0")))
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(SourceLanguage.PLANTUML).let { s ->
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
