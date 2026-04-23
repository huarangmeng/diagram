package com.hrm.diagram.layout.classd

import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassNamespace
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassNote
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.layout.LayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassDiagramLayoutTest {

    private fun ir(
        classes: List<ClassNode>,
        relations: List<ClassRelation> = emptyList(),
        namespaces: List<ClassNamespace> = emptyList(),
        notes: List<ClassNote> = emptyList(),
        direction: Direction? = null,
    ) = ClassIR(
        classes = classes,
        relations = relations,
        namespaces = namespaces,
        notes = notes,
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    @Test fun single_class_gets_a_rect() {
        val model = ir(listOf(ClassNode(NodeId("Foo"), "Foo")))
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        assertEquals(1, out.nodePositions.size)
        val r = out.nodePositions.getValue(NodeId("Foo"))
        assertTrue(r.right > r.left && r.bottom > r.top)
    }

    @Test fun two_classes_with_relation_have_edge_route() {
        val model = ir(
            listOf(ClassNode(NodeId("A"), "A"), ClassNode(NodeId("B"), "B")),
            relations = listOf(ClassRelation(NodeId("A"), NodeId("B"), ClassRelationKind.Inheritance)),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        assertEquals(2, out.nodePositions.size)
        assertEquals(1, out.edgeRoutes.size)
        assertEquals(NodeId("A"), out.edgeRoutes[0].from)
    }

    @Test fun edge_route_is_cubic_bezier() {
        val model = ir(
            listOf(ClassNode(NodeId("A"), "A"), ClassNode(NodeId("B"), "B")),
            relations = listOf(ClassRelation(NodeId("A"), NodeId("B"), ClassRelationKind.Association)),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val route = out.edgeRoutes[0]
        assertEquals(com.hrm.diagram.layout.RouteKind.Bezier, route.kind)
        assertEquals(4, route.points.size, "cubic bezier needs 4 points (M + C triplet)")
        // Control points must lie inside the bbox spanned by from..to (vertical-bias case
        // for a typical TB layout: same x as endpoints, y between them).
        val (p0, c1, c2, p3) = listOf(route.points[0], route.points[1], route.points[2], route.points[3])
        val minY = kotlin.math.min(p0.y, p3.y)
        val maxY = kotlin.math.max(p0.y, p3.y)
        assertTrue(c1.y in minY..maxY, "c1.y=${c1.y} not in $minY..$maxY")
        assertTrue(c2.y in minY..maxY, "c2.y=${c2.y} not in $minY..$maxY")
    }

    @Test fun namespace_creates_cluster_rect() {
        val a = ClassNode(NodeId("A"), "A")
        val b = ClassNode(NodeId("B"), "B")
        val model = ir(
            listOf(a, b),
            namespaces = listOf(ClassNamespace("Pkg", listOf(NodeId("A"), NodeId("B")))),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val ns = out.clusterRects[NodeId("ns#Pkg")]
        assertNotNull(ns)
        val ra = out.nodePositions.getValue(NodeId("A"))
        // Cluster rect must enclose member.
        assertTrue(ns.left <= ra.left && ns.right >= ra.right)
    }

    @Test fun note_for_class_placed_to_the_right() {
        val a = ClassNode(NodeId("A"), "A")
        val model = ir(
            listOf(a),
            notes = listOf(ClassNote(text = RichLabel.Plain("hi"), targetClass = NodeId("A"))),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val noteRect = out.clusterRects[NodeId("note#0")]
        assertNotNull(noteRect)
        val a_r = out.nodePositions.getValue(NodeId("A"))
        assertTrue(noteRect.left >= a_r.right, "note should be to the right of class")
    }

    @Test fun note_left_placement_puts_rect_to_the_left() {
        val a = ClassNode(NodeId("A"), "A")
        val model = ir(
            listOf(a),
            notes = listOf(
                ClassNote(
                    text = RichLabel.Plain("hi"),
                    targetClass = NodeId("A"),
                    placement = com.hrm.diagram.core.ir.NotePlacement.LeftOf,
                ),
            ),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val noteRect = assertNotNull(out.clusterRects[NodeId("note#0")])
        val ar = out.nodePositions.getValue(NodeId("A"))
        assertTrue(noteRect.right <= ar.left + 0.5f, "note should be to the left; note.right=${noteRect.right} a.left=${ar.left}")
    }

    @Test fun note_top_placement_puts_rect_above() {
        val a = ClassNode(NodeId("A"), "A")
        val model = ir(
            listOf(a),
            notes = listOf(
                ClassNote(
                    text = RichLabel.Plain("hi"),
                    targetClass = NodeId("A"),
                    placement = com.hrm.diagram.core.ir.NotePlacement.TopOf,
                ),
            ),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val noteRect = assertNotNull(out.clusterRects[NodeId("note#0")])
        val ar = out.nodePositions.getValue(NodeId("A"))
        assertTrue(noteRect.bottom <= ar.top + 0.5f, "note should be above; note.bottom=${noteRect.bottom} a.top=${ar.top}")
    }

    @Test fun note_bottom_placement_puts_rect_below() {
        val a = ClassNode(NodeId("A"), "A")
        val model = ir(
            listOf(a),
            notes = listOf(
                ClassNote(
                    text = RichLabel.Plain("hi"),
                    targetClass = NodeId("A"),
                    placement = com.hrm.diagram.core.ir.NotePlacement.BottomOf,
                ),
            ),
        )
        val out = ClassDiagramLayout().layout(model, LayoutOptions())
        val noteRect = assertNotNull(out.clusterRects[NodeId("note#0")])
        val ar = out.nodePositions.getValue(NodeId("A"))
        assertTrue(noteRect.top >= ar.bottom - 0.5f, "note should be below; note.top=${noteRect.top} a.bottom=${ar.bottom}")
    }

    @Test fun direction_lr_lays_out_horizontally() {
        val classes = (0 until 4).map { ClassNode(NodeId("C$it"), "C$it") }
        val out = ClassDiagramLayout().layout(ir(classes, direction = Direction.LR), LayoutOptions(direction = Direction.LR))
        // For LR with 4 classes, columns should outnumber rows.
        val xs = classes.map { out.nodePositions.getValue(it.id).left }.distinct()
        val ys = classes.map { out.nodePositions.getValue(it.id).top }.distinct()
        assertTrue(xs.size >= ys.size, "LR layout should spread horizontally; xs=$xs ys=$ys")
    }

    @Test fun direction_bt_mirrors_grid_along_y() {
        val classes = (0 until 4).map { ClassNode(NodeId("C$it"), "C$it") }
        val tb = ClassDiagramLayout().layout(ir(classes, direction = Direction.TB), LayoutOptions(direction = Direction.TB))
        val bt = ClassDiagramLayout().layout(ir(classes, direction = Direction.BT), LayoutOptions(direction = Direction.BT))
        // First and last class should swap relative Y order between TB and BT.
        val tbFirst = tb.nodePositions.getValue(NodeId("C0")).top
        val tbLast = tb.nodePositions.getValue(NodeId("C3")).top
        val btFirst = bt.nodePositions.getValue(NodeId("C0")).top
        val btLast = bt.nodePositions.getValue(NodeId("C3")).top
        // C0 sits above C3 in TB; C0 sits below C3 in BT.
        assertTrue(tbFirst <= tbLast)
        assertTrue(btFirst >= btLast)
    }

    @Test fun direction_rl_mirrors_grid_along_x() {
        val classes = (0 until 4).map { ClassNode(NodeId("C$it"), "C$it") }
        val lr = ClassDiagramLayout().layout(ir(classes, direction = Direction.LR), LayoutOptions(direction = Direction.LR))
        val rl = ClassDiagramLayout().layout(ir(classes, direction = Direction.RL), LayoutOptions(direction = Direction.RL))
        val lrFirst = lr.nodePositions.getValue(NodeId("C0")).left
        val lrLast = lr.nodePositions.getValue(NodeId("C3")).left
        val rlFirst = rl.nodePositions.getValue(NodeId("C0")).left
        val rlLast = rl.nodePositions.getValue(NodeId("C3")).left
        assertTrue(lrFirst <= lrLast)
        assertTrue(rlFirst >= rlLast)
    }

    @Test fun layout_is_deterministic_across_runs() {
        val classes = (0 until 3).map { ClassNode(NodeId("C$it"), "C$it") }
        val model = ir(classes)
        val a = ClassDiagramLayout().layout(model, LayoutOptions())
        val b = ClassDiagramLayout().layout(model, LayoutOptions())
        assertEquals(a.nodePositions, b.nodePositions)
    }
}
