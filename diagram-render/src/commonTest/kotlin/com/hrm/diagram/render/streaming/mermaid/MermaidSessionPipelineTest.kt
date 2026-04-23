package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SequenceIR
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

    @Test
    fun shapes_and_edge_labels_stream_equivalent_and_pinned() {
        val src = "flowchart TD\nA((start)) -->|init| B{auth?}\nB -->|ok| C(home)\n"
        val oneShot = runSession(src, chunkSize = src.length)
        val tiny = runSession(src, chunkSize = 1)
        val irOne = oneShot.ir as GraphIR
        val irTiny = tiny.ir as GraphIR
        assertEquals(irOne.nodes.map { it.id to it.shape }, irTiny.nodes.map { it.id to it.shape })
        assertEquals(irOne.edges.map { Triple(it.from, it.to, it.label) },
            irTiny.edges.map { Triple(it.from, it.to, it.label) })
        // Pinning: same node positions byte-for-byte.
        assertEquals(oneShot.laidOut!!.nodePositions, tiny.laidOut!!.nodePositions)
        assertTrue(oneShot.drawCommands.size >= irOne.nodes.size)
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

    @Test
    fun final_reflow_yields_layered_layout() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            // Diamond shape A→B, A→C, B→D, C→D — Sugiyama must put B and C on the same y.
            s.append("flowchart TD\nA --> B\nA --> C\nB --> D\nC --> D\n")
            val snap = s.finish()
            val laid = assertNotNull(snap.laidOut, "finish must produce a layout")
            val ya = laid.nodePositions.getValue(NodeId("A")).top
            val yb = laid.nodePositions.getValue(NodeId("B")).top
            val yc = laid.nodePositions.getValue(NodeId("C")).top
            val yd = laid.nodePositions.getValue(NodeId("D")).top
            assertEquals(yb, yc, "B and C share a rank")
            assertTrue(ya < yb, "A above B/C")
            assertTrue(yb < yd, "D below B/C")
        } finally { s.close() }
    }

    @Test
    fun mermaid_sequence_smoke() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("sequenceDiagram\n")
            s.append("Alice ->> Bob: hi\n")
            s.append("Bob -->> Alice: bye\n")
            val snap = s.finish()
            val ir = assertNotNull(snap.ir as? SequenceIR, "ir must be SequenceIR")
            assertEquals(2, ir.participants.size)
            val laid = assertNotNull(snap.laidOut)
            assertTrue(laid.nodePositions.size >= 2)
            assertTrue(laid.edgeRoutes.size >= 2)
            assertTrue(snap.drawCommands.size >= 4)
        } finally { s.close() }
    }

    @Test
    fun mode_dispatch_sequence_vs_flowchart() {
        val flow = Diagram.session(language = SourceLanguage.MERMAID).also { s ->
            s.append("flowchart TD\nA --> B\n")
            s.finish()
        }
        val seq = Diagram.session(language = SourceLanguage.MERMAID).also { s ->
            s.append("sequenceDiagram\nA ->> B\n")
            s.finish()
        }
        try {
            assertTrue(flow.state.value.ir is GraphIR)
            assertTrue(seq.state.value.ir is SequenceIR)
        } finally {
            flow.close(); seq.close()
        }
    }

    @Test
    fun class_header_dispatches_to_class_sub_pipeline() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass Foo\n")
            val snap = s.finish()
            assertTrue(snap.ir is com.hrm.diagram.core.ir.ClassIR, "expected ClassIR, got ${snap.ir?.let { it::class.simpleName }}")
            assertTrue(snap.drawCommands.isNotEmpty())
        } finally { s.close() }
    }

    @Test
    fun class_and_sequence_isolated_sessions() {
        val cls = Diagram.session(language = SourceLanguage.MERMAID).also { s ->
            s.append("classDiagram\nA <|-- B\n")
            s.finish()
        }
        val seq = Diagram.session(language = SourceLanguage.MERMAID).also { s ->
            s.append("sequenceDiagram\nA ->> B\n")
            s.finish()
        }
        try {
            assertTrue(cls.state.value.ir is com.hrm.diagram.core.ir.ClassIR)
            assertTrue(seq.state.value.ir is SequenceIR)
        } finally { cls.close(); seq.close() }
    }

    @Test
    fun state_header_dispatches_to_state_sub_pipeline() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\n[*] --> A\nA --> [*]\n")
            val snap = s.finish()
            assertTrue(snap.ir is com.hrm.diagram.core.ir.StateIR,
                "expected StateIR, got ${snap.ir?.let { it::class.simpleName }}")
            assertTrue(snap.drawCommands.isNotEmpty())
        } finally { s.close() }
    }

    @Test
    fun state_v1_header_also_dispatches() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram\nA --> B\n")
            val snap = s.finish()
            assertTrue(snap.ir is com.hrm.diagram.core.ir.StateIR)
        } finally { s.close() }
    }
}
