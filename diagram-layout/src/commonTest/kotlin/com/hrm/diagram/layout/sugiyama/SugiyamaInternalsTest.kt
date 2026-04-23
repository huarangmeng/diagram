package com.hrm.diagram.layout.sugiyama

import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.internal.CoordinateAssignment
import com.hrm.diagram.layout.sugiyama.internal.CrossingMinimization
import com.hrm.diagram.layout.sugiyama.internal.CycleRemoval
import com.hrm.diagram.layout.sugiyama.internal.LayerAssignment
import com.hrm.diagram.layout.sugiyama.internal.LayeredGraph
import com.hrm.diagram.layout.sugiyama.routing.BezierEdgeRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SugiyamaInternalsTest {

    private fun n(id: String) = Node(NodeId(id))
    private fun e(a: String, b: String) = Edge(NodeId(a), NodeId(b))

    @Test
    fun cycleRemoval_dag_yields_empty() {
        val nodes = listOf(n("A"), n("B"), n("C"), n("D"))
        val edges = listOf(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D"))
        assertTrue(CycleRemoval.reversedEdges(nodes, edges).isEmpty())
    }

    @Test
    fun cycleRemoval_three_cycle_picks_at_least_one() {
        val nodes = listOf(n("A"), n("B"), n("C"))
        val edges = listOf(e("A", "B"), e("B", "C"), e("C", "A"))
        val reversed = CycleRemoval.reversedEdges(nodes, edges)
        assertTrue(reversed.isNotEmpty(), "cycle requires at least one reversed edge")
    }

    @Test
    fun layerAssignment_chain_is_consecutive() {
        val nodes = listOf(n("A"), n("B"), n("C"), n("D"))
        val edges = listOf(e("A", "B"), e("B", "C"), e("C", "D"))
        val g = LayeredGraph()
        LayerAssignment.assign(nodes, edges, emptySet(), g)
        assertEquals(0, g.layer[NodeId("A")])
        assertEquals(1, g.layer[NodeId("B")])
        assertEquals(2, g.layer[NodeId("C")])
        assertEquals(3, g.layer[NodeId("D")])
    }

    @Test
    fun layerAssignment_diamond_collapses_middle_layer() {
        val nodes = listOf(n("A"), n("B"), n("C"), n("D"))
        val edges = listOf(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D"))
        val g = LayeredGraph()
        LayerAssignment.assign(nodes, edges, emptySet(), g)
        assertEquals(0, g.layer[NodeId("A")])
        assertEquals(1, g.layer[NodeId("B")])
        assertEquals(1, g.layer[NodeId("C")])
        assertEquals(2, g.layer[NodeId("D")])
    }

    @Test
    fun crossingMinimization_resolves_swap() {
        // Edges: A→D, B→C with initial order A,B / D,C (obvious crossing).
        val g = LayeredGraph().apply {
            appendToLayer(NodeId("A"), 0); appendToLayer(NodeId("B"), 0)
            appendToLayer(NodeId("D"), 1); appendToLayer(NodeId("C"), 1)
        }
        val edges = listOf(e("A", "C"), e("B", "D"))
        CrossingMinimization.minimize(edges, emptySet(), g)
        val lower = g.orderInLayer.getValue(1)
        // Now C should be on the left of D (under A) for zero crossings.
        assertEquals(listOf(NodeId("C"), NodeId("D")), lower)
    }

    @Test
    fun coordinateAssignment_x_strictly_increases_per_layer() {
        val g = LayeredGraph().apply {
            appendToLayer(NodeId("A"), 0)
            appendToLayer(NodeId("B"), 1); appendToLayer(NodeId("C"), 1)
            appendToLayer(NodeId("D"), 2)
        }
        val rects = CoordinateAssignment.assign(
            graph = g, nodeSizeOf = { Size(120f, 48f) },
            nodeSpacing = 24f, rankSpacing = 48f, direction = Direction.TB,
        )
        val b = rects.getValue(NodeId("B")); val c = rects.getValue(NodeId("C"))
        assertTrue(c.left > b.left)
        assertEquals(120f + 24f, c.left - b.left)
    }

    @Test
    fun coordinateAssignment_bt_mirrors_layers_along_y() {
        val g = LayeredGraph().apply {
            appendToLayer(NodeId("A"), 0)
            appendToLayer(NodeId("B"), 1)
            appendToLayer(NodeId("C"), 2)
        }
        val tb = CoordinateAssignment.assign(
            graph = g, nodeSizeOf = { Size(100f, 40f) },
            nodeSpacing = 16f, rankSpacing = 40f, direction = Direction.TB,
        )
        val bt = CoordinateAssignment.assign(
            graph = g, nodeSizeOf = { Size(100f, 40f) },
            nodeSpacing = 16f, rankSpacing = 40f, direction = Direction.BT,
        )
        // Layer-0 in TB sits above layer-2; in BT it must sit below.
        assertTrue(tb.getValue(NodeId("A")).top < tb.getValue(NodeId("C")).top)
        assertTrue(bt.getValue(NodeId("A")).top > bt.getValue(NodeId("C")).top)
    }

    @Test
    fun coordinateAssignment_rl_mirrors_layers_along_x() {
        val g = LayeredGraph().apply {
            appendToLayer(NodeId("A"), 0)
            appendToLayer(NodeId("B"), 1)
            appendToLayer(NodeId("C"), 2)
        }
        val lr = CoordinateAssignment.assign(
            graph = g, nodeSizeOf = { Size(100f, 40f) },
            nodeSpacing = 16f, rankSpacing = 40f, direction = Direction.LR,
        )
        val rl = CoordinateAssignment.assign(
            graph = g, nodeSizeOf = { Size(100f, 40f) },
            nodeSpacing = 16f, rankSpacing = 40f, direction = Direction.RL,
        )
        assertTrue(lr.getValue(NodeId("A")).left < lr.getValue(NodeId("C")).left)
        assertTrue(rl.getValue(NodeId("A")).left > rl.getValue(NodeId("C")).left)
    }

    @Test
    fun bezierRouter_emits_four_points() {
        val a = com.hrm.diagram.core.draw.Rect.ltrb(0f, 0f, 120f, 48f)
        val b = com.hrm.diagram.core.draw.Rect.ltrb(0f, 96f, 120f, 144f)
        val r = BezierEdgeRouter.route(NodeId("A"), a, NodeId("B"), b, Direction.TB)
        assertEquals(4, r.points.size)
        assertEquals(RouteKind.Bezier, r.kind)
        // Control y values bracket the midpoint.
        val midY = (r.points.first().y + r.points.last().y) / 2f
        assertEquals(midY, r.points[1].y)
        assertEquals(midY, r.points[2].y)
    }

    @Test
    fun incremental_pinning_preserves_existing_rects_byte_for_byte() {
        val layout = SugiyamaLayouts.forGraph()
        val opts = LayoutOptions(direction = Direction.TB, incremental = true, allowGlobalReflow = false)
        val ir1 = GraphIR(
            nodes = listOf(n("A"), n("B")),
            edges = listOf(e("A", "B")),
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(direction = Direction.TB),
        )
        val l1 = layout.layout(null, ir1, opts)
        val ir2 = ir1.copy(nodes = ir1.nodes + n("C"), edges = ir1.edges + e("B", "C"))
        val l2 = layout.layout(l1, ir2, opts)
        assertEquals(l1.nodePositions.getValue(NodeId("A")), l2.nodePositions.getValue(NodeId("A")))
        assertEquals(l1.nodePositions.getValue(NodeId("B")), l2.nodePositions.getValue(NodeId("B")))
        assertTrue(NodeId("C") in l2.nodePositions)
    }

    @Test
    fun final_reflow_groups_nodes_into_layers() {
        val layout = SugiyamaLayouts.forGraph()
        val ir = GraphIR(
            nodes = listOf(n("A"), n("B"), n("C"), n("D")),
            edges = listOf(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")),
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(direction = Direction.TB),
        )
        val opts = LayoutOptions(direction = Direction.TB, incremental = false, allowGlobalReflow = true)
        val l = layout.layout(null, ir, opts)
        val ya = l.nodePositions.getValue(NodeId("A")).top
        val yb = l.nodePositions.getValue(NodeId("B")).top
        val yc = l.nodePositions.getValue(NodeId("C")).top
        val yd = l.nodePositions.getValue(NodeId("D")).top
        assertEquals(yb, yc, "B and C should share a rank y")
        assertTrue(ya < yb, "A is above B/C")
        assertTrue(yb < yd, "D is below B/C")
    }
}
