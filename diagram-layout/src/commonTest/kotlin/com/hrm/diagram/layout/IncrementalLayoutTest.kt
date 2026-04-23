package com.hrm.diagram.layout

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun graph(vararg ids: String): GraphIR =
    GraphIR(nodes = ids.map { Node(NodeId(it)) }, sourceLanguage = SourceLanguage.MERMAID)

/** Trivial layout used to exercise the contract: pins prior coords, appends new ones in a row. */
private class StubAppendLayout : IncrementalLayout<GraphIR> {
    var newIds: List<NodeId> = emptyList()
    override fun layout(previous: LaidOutDiagram?, model: GraphIR, options: LayoutOptions): LaidOutDiagram {
        val pinned = previous?.nodePositions ?: emptyMap()
        val xStart = pinned.values.maxOfOrNull { it.right } ?: 0f
        val appended = newIds.mapIndexed { i, id ->
            id to Rect(Point(xStart + i * 100f, 0f), Size(80f, 40f))
        }.toMap()
        val all = pinned + appended
        val maxR = all.values.maxOfOrNull { it.right } ?: 0f
        val maxB = all.values.maxOfOrNull { it.bottom } ?: 0f
        return LaidOutDiagram(
            source = model,
            nodePositions = all,
            edgeRoutes = emptyList(),
            bounds = Rect.ltrb(0f, 0f, maxR, maxB),
            seq = (previous?.seq ?: -1L) + 1,
        )
    }
}

class IncrementalLayoutTest {

    @Test
    fun first_run_with_null_previous_is_full_layout() {
        val l = StubAppendLayout().apply { newIds = listOf(NodeId("a"), NodeId("b")) }
        val out = l.layout(graph("a", "b"), LayoutOptions())
        assertEquals(2, out.nodePositions.size)
        assertEquals(0L, out.seq)
    }

    @Test
    fun incremental_run_pins_previous_coordinates_byte_for_byte() {
        val l = StubAppendLayout()
        l.newIds = listOf(NodeId("a"), NodeId("b"))
        val first = l.layout(graph("a", "b"), LayoutOptions())
        val a0 = first.nodePositions[NodeId("a")]
        val b0 = first.nodePositions[NodeId("b")]
        assertNotNull(a0); assertNotNull(b0)

        l.newIds = listOf(NodeId("c"))
        val second = l.layout(previous = first, model = graph("a", "b", "c"), options = LayoutOptions())
        // The pinning contract: previously laid-out nodes MUST equal byte-for-byte.
        assertEquals(a0, second.nodePositions[NodeId("a")])
        assertEquals(b0, second.nodePositions[NodeId("b")])
        assertNotNull(second.nodePositions[NodeId("c")])
        assertEquals(1L, second.seq)
    }

    @Test
    fun edge_route_requires_at_least_two_points() {
        assertFailsWith<IllegalArgumentException> {
            EdgeRoute(NodeId("a"), NodeId("b"), points = listOf(Point.Zero))
        }
    }

    @Test
    fun layout_options_streaming_defaults_match_contract() {
        val o = LayoutOptions()
        assertTrue(o.incremental, "incremental MUST default true (streaming-first)")
        assertEquals(false, o.allowGlobalReflow, "global reflow MUST default false (no node jumping)")
        assertEquals(50_000, o.drawCommandBudget)
    }

    @Test
    fun rejects_zero_command_budget() {
        assertFailsWith<IllegalArgumentException> { LayoutOptions(drawCommandBudget = 0) }
    }

    @Test
    fun bounds_present_even_for_empty_layout() {
        val l = StubAppendLayout() // no newIds
        val out = l.layout(graph(), LayoutOptions())
        assertEquals(0f, out.bounds.right)
        assertNull(out.clusterRects[NodeId("anything")])
    }
}
