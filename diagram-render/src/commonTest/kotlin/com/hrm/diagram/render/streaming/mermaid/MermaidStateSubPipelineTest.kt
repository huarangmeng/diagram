package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidStateSubPipelineTest {

    @Test fun simple_state_diagram_produces_ir_and_drawcommands() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\nstate A\n")
            val snap = s.finish()
            val ir = assertNotNull(snap.ir as? StateIR)
            assertEquals(1, ir.states.size)
            assertTrue(snap.drawCommands.isNotEmpty())
        } finally { s.close() }
    }

    @Test fun transition_yields_edge_route() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\nA --> B\n")
            val snap = s.finish()
            val ir = snap.ir as StateIR
            assertEquals(1, ir.transitions.size)
            assertEquals(1, snap.laidOut!!.edgeRoutes.size)
        } finally { s.close() }
    }

    @Test fun initial_and_final_pseudo_states_render() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\n[*] --> A\nA --> [*]\n")
            val snap = s.finish()
            val ir = snap.ir as StateIR
            assertTrue(ir.states.any { it.kind == StateKind.Initial })
            assertTrue(ir.states.any { it.kind == StateKind.Final })
            assertTrue(snap.drawCommands.size >= 4)
        } finally { s.close() }
    }

    @Test fun composite_state_renders_with_children() {
        val src = """
            stateDiagram-v2
            state Active {
              A --> B
            }
        """.trimIndent() + "\n"
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append(src); val snap = s.finish()
            val ir = snap.ir as StateIR
            val outer = ir.states.first { it.id == NodeId("Active") }
            assertEquals(StateKind.Composite, outer.kind)
            assertTrue(snap.drawCommands.isNotEmpty())
        } finally { s.close() }
    }

    @Test fun choice_fork_join_render() {
        val src = """
            stateDiagram-v2
            state C <<choice>>
            state F <<fork>>
            state J <<join>>
            A --> C
            C --> F
            F --> J
        """.trimIndent() + "\n"
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append(src); val snap = s.finish()
            val ir = snap.ir as StateIR
            assertTrue(ir.states.any { it.kind == StateKind.Choice })
            assertTrue(ir.states.any { it.kind == StateKind.Fork })
            assertTrue(ir.states.any { it.kind == StateKind.Join })
            assertTrue(snap.drawCommands.size >= 6)
        } finally { s.close() }
    }

    @Test fun history_renders_with_letter() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\nstate H <<history>>\nA --> H\n")
            val snap = s.finish()
            val ir = snap.ir as StateIR
            assertEquals(StateKind.History, ir.states.first { it.id == NodeId("H") }.kind)
        } finally { s.close() }
    }

    @Test fun note_added_to_drawcommands() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("stateDiagram-v2\nstate A\nnote right of A : hi\n")
            val snap = s.finish()
            val ir = snap.ir as StateIR
            assertEquals(1, ir.notes.size)
            assertTrue(snap.laidOut!!.clusterRects.keys.any { it.value.startsWith("note#") })
        } finally { s.close() }
    }
}
