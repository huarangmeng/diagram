package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId

/**
 * Longest-path layering. layer(n) = 1 + max(layer(p) for p in predecessors(n)); sources start
 * at 0. Reversed edges from [CycleRemoval] are interpreted in their flipped direction.
 *
 * Iterated relaxation, terminates as soon as a full pass doesn't change anything.
 */
internal object LayerAssignment {
    fun assign(
        nodes: List<Node>,
        edges: List<Edge>,
        reversed: Set<Pair<NodeId, NodeId>>,
        out: LayeredGraph,
    ) {
        val effective: List<Pair<NodeId, NodeId>> = edges.map { e ->
            val pair = e.from to e.to
            if (pair in reversed) e.to to e.from else pair
        }
        val preds: Map<NodeId, List<NodeId>> = effective.groupBy({ it.second }, { it.first })

        for (n in nodes) out.layer[n.id] = 0

        val maxPasses = nodes.size + 1
        var pass = 0
        var changed = true
        while (changed && pass < maxPasses) {
            changed = false
            for (n in nodes) {
                val ps = preds[n.id] ?: continue
                var maxL = -1
                for (p in ps) {
                    val pl = out.layer[p] ?: 0
                    if (pl > maxL) maxL = pl
                }
                val want = maxL + 1
                if (want > (out.layer[n.id] ?: 0)) {
                    out.layer[n.id] = want
                    changed = true
                }
            }
            pass++
        }

        out.orderInLayer.clear()
        for (n in nodes) {
            val l = out.layer.getValue(n.id)
            out.orderInLayer.getOrPut(l) { ArrayList() } += n.id
        }
    }
}
