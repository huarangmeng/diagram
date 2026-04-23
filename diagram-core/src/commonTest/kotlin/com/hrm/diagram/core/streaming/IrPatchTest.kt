package com.hrm.diagram.core.streaming

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeStyle
import com.hrm.diagram.core.ir.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrPatchTest {

    @Test
    fun add_node_round_trip() {
        val n = Node(NodeIds.explicit("a"))
        val p: IrPatch = IrPatch.AddNode(n)
        assertTrue(p is IrPatch.AddNode)
        assertEquals(n, p.node)
    }

    @Test
    fun update_attr_targets_existing_node() {
        val id = NodeIds.explicit("x")
        val style = NodeStyle.Default
        val p = IrPatch.UpdateAttr(id, style)
        assertEquals(id, p.target)
        assertEquals(style, p.style)
    }

    @Test
    fun batch_records_seq() {
        val b = IrPatchBatch(
            seq = 7L,
            patches = listOf(
                IrPatch.AddNode(Node(NodeIds.explicit("a"))),
                IrPatch.AddEdge(Edge(NodeIds.explicit("a"), NodeIds.explicit("b"))),
                IrPatch.AddDiagnostic(Diagnostic(Severity.WARNING, "unresolved", "MMD-W001")),
            ),
        )
        assertEquals(7L, b.seq)
        assertEquals(3, b.patches.size)
        assertEquals(false, b.isEmpty)
    }

    @Test
    fun empty_batch_reports_empty() {
        assertTrue(IrPatchBatch(0L, emptyList()).isEmpty)
    }
}
