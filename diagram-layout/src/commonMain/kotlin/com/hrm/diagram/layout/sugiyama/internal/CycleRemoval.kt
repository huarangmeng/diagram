package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId

/**
 * Greedy DFS cycle removal. Marks back-edges (closing onto the current DFS stack) as reversed
 * so the remaining stages can treat the graph as a DAG. Determinism: input order in [nodes]
 * and adjacency list order in [edges] are preserved.
 */
internal object CycleRemoval {
    fun reversedEdges(nodes: List<Node>, edges: List<Edge>): Set<Pair<NodeId, NodeId>> {
        if (edges.isEmpty()) return emptySet()
        val adj: Map<NodeId, List<Edge>> = edges.groupBy { it.from }
        val state = HashMap<NodeId, Mark>(nodes.size)
        val reversed = HashSet<Pair<NodeId, NodeId>>()
        for (root in nodes) {
            if (state[root.id] == Mark.DONE) continue
            dfs(root.id, adj, state, reversed)
        }
        return reversed
    }

    private fun dfs(
        start: NodeId,
        adj: Map<NodeId, List<Edge>>,
        state: HashMap<NodeId, Mark>,
        reversed: HashSet<Pair<NodeId, NodeId>>,
    ) {
        val stack = ArrayDeque<Frame>()
        state[start] = Mark.OPEN
        stack += Frame(start, (adj[start] ?: emptyList()).iterator())
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.iter.hasNext()) {
                state[top.id] = Mark.DONE
                stack.removeLast()
                continue
            }
            val edge = top.iter.next()
            when (state[edge.to]) {
                null -> {
                    state[edge.to] = Mark.OPEN
                    stack += Frame(edge.to, (adj[edge.to] ?: emptyList()).iterator())
                }
                Mark.OPEN -> reversed += edge.from to edge.to
                Mark.DONE -> { /* forward / cross edge — leave alone */ }
            }
        }
    }

    private enum class Mark { OPEN, DONE }
    private data class Frame(val id: NodeId, val iter: Iterator<Edge>)
}
