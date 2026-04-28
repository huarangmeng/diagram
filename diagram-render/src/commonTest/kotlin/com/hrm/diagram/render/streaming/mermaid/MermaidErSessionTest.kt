package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun erDiagram_finish_renders_entity_attribute_and_relationship_layers() {
        val s = com.hrm.diagram.render.Diagram.session(SourceLanguage.MERMAID)
        try {
            s.append(
                """
                erDiagram
                USER {
                  uuid id PK
                  uuid account_id FK
                  string nickname
                }
                USER ||--o{ ORDER : places
                """.trimIndent() + "\n",
            )
            val snap = s.finish()
            val ir = assertIs<GraphIR>(snap.ir)
            val laid = assertNotNull(snap.laidOut)

            assertTrue(NodeId("USER") in laid.nodePositions)
            assertTrue(NodeId("USER::id") in laid.nodePositions)
            assertTrue(NodeId("ORDER") in laid.nodePositions)

            val textCommands = snap.drawCommands.filterIsInstance<DrawCommand.DrawText>()
            assertTrue(textCommands.any { it.text == "USER" })
            assertTrue(textCommands.any { it.text.contains("id") && it.text.contains("uuid") })
            assertTrue(textCommands.any { it.text == "PK" })
            assertTrue(textCommands.any { it.text == "FK" })
            assertTrue(textCommands.any { it.text == "||--o{" })
            assertTrue(textCommands.any { it.text == "places" })

            val fillRects = snap.drawCommands.filterIsInstance<DrawCommand.FillRect>()
            // Final rendering may embed attribute rows into the entity box (so attribute-node fills may be skipped).
            val entityCount = ir.nodes.count { !it.id.value.contains("::") }
            assertTrue(fillRects.size >= entityCount + 3, "expected entity fills + attribute flag badges + relationship badge")
            // Relationship badge background uses a light gray chip (Mermaid-like).
            val fills = fillRects.map { it.color.argb.toLong() and 0xFFFFFFFFL }.toSet()
            assertTrue(fills.contains(0xFFF5F5F5L), "expected relationship badge chip fill (got: $fills)")
        } finally {
            s.close()
        }
    }

    @Test
    fun erDiagram_streaming_matches_one_shot_node_and_edge_shapes() {
        val src = """
            erDiagram
            USER {
              uuid id PK
              uuid account_id FK
            }
            USER ||--o{ ORDER : places
        """.trimIndent() + "\n"

        val one = run(src, src.length)
        val streamed = run(src, 4)

        val oneIr = assertIs<GraphIR>(one.ir)
        val streamedIr = assertIs<GraphIR>(streamed.ir)

        assertEquals(
            oneIr.nodes.map { Triple(it.id, it.shape, it.payload) },
            streamedIr.nodes.map { Triple(it.id, it.shape, it.payload) },
        )
        assertEquals(
            oneIr.edges.map { Triple(it.from, it.to, it.style) },
            streamedIr.edges.map { Triple(it.from, it.to, it.style) },
        )
    }

    private fun run(src: String, chunkSize: Int) = com.hrm.diagram.render.Diagram.session(SourceLanguage.MERMAID).let { s ->
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
