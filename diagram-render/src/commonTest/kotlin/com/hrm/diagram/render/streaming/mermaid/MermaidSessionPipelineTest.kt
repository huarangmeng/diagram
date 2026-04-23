package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidSessionPipelineTest {

    @Test
    fun diagram_session_dispatches_to_mermaid_pipeline() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("flowchart TD\nA --> B\n")
            val snap = s.finish()
            val ir = assertNotNull(snap.ir as? GraphIR, "ir must be GraphIR")
            assertEquals(2, ir.nodes.size)
            assertEquals(1, ir.edges.size)
            assertTrue(snap.drawCommands.isNotEmpty(), "mermaid pipeline must emit DrawCommands")
        } finally { s.close() }
    }

    @Test
    fun stream_in_tiny_chunks_yields_same_ir_as_one_shot() {
        val src = "flowchart LR\nA[Start] --> B[End]\nB --> C\n"

        val one = runSession(src, chunkSize = src.length)
        val streamed = runSession(src, chunkSize = 3)

        val oneIr = one.ir as GraphIR
        val streamedIr = streamed.ir as GraphIR
        assertEquals(oneIr.nodes.map { it.id }, streamedIr.nodes.map { it.id })
        assertEquals(oneIr.edges.map { it.from to it.to }, streamedIr.edges.map { it.from to it.to })
    }

    @Test
    fun pinning_invariant_node_positions_never_move() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("flowchart TD\n")
            s.append("A --> B\n")
            val mid = s.state.value
            val midPositions = (mid.laidOut?.nodePositions ?: emptyMap()).toMap()
            assertTrue(midPositions.isNotEmpty())

            s.append("B --> C\n")
            s.append("C --> D\n")
            val later = s.finish()
            val laterPositions = later.laidOut?.nodePositions ?: emptyMap()
            for ((id, rect) in midPositions) {
                assertEquals(rect, laterPositions[id], "node $id moved after later append (pinning violated)")
            }
        } finally { s.close() }
    }

    @Test
    fun seq_is_strictly_increasing_and_isFinal_only_on_finish() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("flowchart TD\n"); val a = s.state.value.seq
            s.append("A --> B\n");      val b = s.state.value.seq
            assertTrue(b > a)
            assertTrue(!s.state.value.isFinal)
            val final = s.finish()
            assertTrue(final.seq > b)
            assertTrue(final.isFinal)
        } finally { s.close() }
    }

    private fun runSession(src: String, chunkSize: Int): DiagramSnapshot {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            return s.finish()
        } finally { s.close() }
    }
}
