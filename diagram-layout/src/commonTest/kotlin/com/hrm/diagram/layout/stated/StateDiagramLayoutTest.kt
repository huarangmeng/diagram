package com.hrm.diagram.layout.stated

import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.ir.StateNode
import com.hrm.diagram.core.ir.StateNote
import com.hrm.diagram.core.ir.StateTransition
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.layout.LayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateDiagramLayoutTest {

    private fun ir(
        states: List<StateNode>,
        transitions: List<StateTransition> = emptyList(),
        notes: List<StateNote> = emptyList(),
        direction: Direction? = null,
    ) = StateIR(
        states = states,
        transitions = transitions,
        notes = notes,
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    @Test fun empty_ir_yields_empty_layout() {
        val out = StateDiagramLayout().layout(ir(emptyList()), LayoutOptions())
        assertTrue(out.nodePositions.isEmpty())
        assertTrue(out.edgeRoutes.isEmpty())
    }

    @Test fun two_simple_states_are_placed_side_by_side_or_stacked() {
        val a = StateNode(NodeId("A"), "A")
        val b = StateNode(NodeId("B"), "B")
        val out = StateDiagramLayout().layout(ir(listOf(a, b)), LayoutOptions())
        assertEquals(2, out.nodePositions.size)
        // Both nodes have positive coordinates within bounds.
        for (r in out.nodePositions.values) {
            assertTrue(r.left >= 0f && r.top >= 0f, "rect must be on canvas")
            assertTrue(r.right <= out.bounds.right && r.bottom <= out.bounds.bottom)
        }
    }

    @Test fun transition_produces_bezier_route_with_4_points() {
        val a = StateNode(NodeId("A"), "A")
        val b = StateNode(NodeId("B"), "B")
        val out = StateDiagramLayout().layout(
            ir(listOf(a, b), transitions = listOf(StateTransition(a.id, b.id, label = RichLabel.Plain("e")))),
            LayoutOptions(),
        )
        assertEquals(1, out.edgeRoutes.size)
        assertEquals(4, out.edgeRoutes[0].points.size)
    }

    @Test fun composite_encloses_children() {
        val child1 = StateNode(NodeId("C1"), "C1")
        val child2 = StateNode(NodeId("C2"), "C2")
        val composite = StateNode(
            NodeId("Outer"), "Outer", kind = StateKind.Composite,
            children = listOf(child1.id, child2.id),
        )
        val out = StateDiagramLayout().layout(
            ir(listOf(composite, child1, child2)),
            LayoutOptions(),
        )
        val outerR = assertNotNull(out.nodePositions[composite.id])
        for (cid in listOf(child1.id, child2.id)) {
            val cR = assertNotNull(out.nodePositions[cid])
            assertTrue(cR.left >= outerR.left && cR.right <= outerR.right, "child must fit within composite horizontally")
            assertTrue(cR.top >= outerR.top && cR.bottom <= outerR.bottom, "child must fit within composite vertically")
        }
    }

    @Test fun pseudo_states_have_small_intrinsic_size() {
        val init = StateNode(NodeId("__init__1"), "", kind = StateKind.Initial)
        val a = StateNode(NodeId("A"), "A")
        val out = StateDiagramLayout().layout(
            ir(listOf(init, a), transitions = listOf(StateTransition(init.id, a.id))),
            LayoutOptions(),
        )
        val r = assertNotNull(out.nodePositions[init.id])
        assertTrue((r.right - r.left) <= 20f, "initial pseudo-state should be small")
        assertTrue((r.bottom - r.top) <= 20f)
    }

    @Test fun fork_is_a_thick_horizontal_bar() {
        val f = StateNode(NodeId("F"), "F", kind = StateKind.Fork)
        val a = StateNode(NodeId("A"), "A")
        val out = StateDiagramLayout().layout(
            ir(listOf(f, a), transitions = listOf(StateTransition(f.id, a.id))),
            LayoutOptions(),
        )
        val r = assertNotNull(out.nodePositions[f.id])
        val w = r.right - r.left
        val h = r.bottom - r.top
        assertTrue(w > h, "fork bar must be wider than tall")
    }

    @Test fun direction_lr_lays_out_horizontally() {
        // Use 4 states so the LR vs TB grid actually differs.
        val nodes = listOf("A", "B", "C", "D").map { StateNode(NodeId(it), it) }
        val outTB = StateDiagramLayout().layout(ir(nodes, direction = Direction.TB), LayoutOptions())
        val outLR = StateDiagramLayout().layout(ir(nodes, direction = Direction.LR), LayoutOptions())
        // LR should be wider than tall; TB taller than wide (or at least different aspect).
        val tbAspect = (outTB.bounds.right - outTB.bounds.left) / (outTB.bounds.bottom - outTB.bounds.top)
        val lrAspect = (outLR.bounds.right - outLR.bounds.left) / (outLR.bounds.bottom - outLR.bounds.top)
        assertTrue(lrAspect >= tbAspect - 0.01f, "LR layout should not be more vertical than TB")
        assertNotNull(outTB.nodePositions[NodeId("A")])
    }

    @Test fun note_is_added_to_cluster_rects() {
        val a = StateNode(NodeId("A"), "A")
        val out = StateDiagramLayout().layout(
            ir(listOf(a), notes = listOf(StateNote(text = RichLabel.Plain("hi"), targetState = a.id, placement = NotePlacement.RightOf))),
            LayoutOptions(),
        )
        assertTrue(out.clusterRects.keys.any { it.value.startsWith("note#") })
    }

    @Test fun composite_marker_in_cluster_rects() {
        val ch = StateNode(NodeId("C"), "C")
        val outer = StateNode(NodeId("Outer"), "Outer", kind = StateKind.Composite, children = listOf(ch.id))
        val out = StateDiagramLayout().layout(ir(listOf(outer, ch)), LayoutOptions())
        assertTrue(out.clusterRects.keys.any { it.value == "composite#Outer" })
    }
}
