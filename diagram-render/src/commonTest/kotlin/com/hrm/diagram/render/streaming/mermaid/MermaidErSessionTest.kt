package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidErSessionTest {
    @Test
    fun facade_session_parses_erDiagram() {
        val s = com.hrm.diagram.render.Diagram.session(SourceLanguage.MERMAID)
        s.append(
            """
            erDiagram
            CAR {
              string make PK
            }
            CAR ||--o{ PERSON : allows
            """.trimIndent() + "\n",
        )
        s.finish()
        val snap = s.state.value
        val ir = assertIs<GraphIR>(snap.ir)
        assertTrue(ir.nodes.isNotEmpty())
        // We should have at least some draw commands after finish/layout.
        assertTrue(snap.drawCommands.isNotEmpty())
    }
}
