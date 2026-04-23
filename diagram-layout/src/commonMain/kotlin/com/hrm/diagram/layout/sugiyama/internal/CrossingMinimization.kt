package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.NodeId

/**
 * Iterated barycenter heuristic for crossing reduction.
 *
 * For each adjacent layer pair, sort the lower (or upper) layer by the average position of
 * each node's neighbours in the other layer. Repeat down then up until either crossings stop
 * decreasing or [maxPasses] passes have run.
 */
internal object CrossingMinimization {
    private const val MAX_PASSES_DEFAULT = 24

    fun minimize(
        edges: List<Edge>,
        reversed: Set<Pair<NodeId, NodeId>>,
        graph: LayeredGraph,
        maxPasses: Int = MAX_PASSES_DEFAULT,
    ) {
        val maxLayer = graph.maxLayer()
        if (maxLayer < 1) return
        val effective: List<Pair<NodeId, NodeId>> = edges.map { e ->
            if ((e.from to e.to) in reversed) e.to to e.from else e.from to e.to
        }

        var bestCrossings = countCrossings(effective, graph, maxLayer)
        for (pass in 0 until maxPasses) {
            for (l in 1..maxLayer) reorderLayer(l, neighbourLayer = l - 1, effective, graph)
            for (l in maxLayer - 1 downTo 0) reorderLayer(l, neighbourLayer = l + 1, effective, graph)
            val now = countCrossings(effective, graph, maxLayer)
            if (now >= bestCrossings) break
            bestCrossings = now
        }
    }

    private fun reorderLayer(
        target: Int,
        neighbourLayer: Int,
        effective: List<Pair<NodeId, NodeId>>,
        graph: LayeredGraph,
    ) {
        val targetOrder = graph.orderInLayer[target] ?: return
        val neighbourOrder = graph.orderInLayer[neighbourLayer] ?: return
        val neighbourPos = HashMap<NodeId, Int>(neighbourOrder.size).apply {
            neighbourOrder.forEachIndexed { i, id -> put(id, i) }
        }
        val sumPos = HashMap<NodeId, Double>(targetOrder.size)
        val deg = HashMap<NodeId, Int>(targetOrder.size)
        for (id in targetOrder) { sumPos[id] = 0.0; deg[id] = 0 }
        for ((from, to) in effective) {
            // For target layer above the neighbour: edges go target → neighbour.
            // For target below: edges go neighbour → target. Both contribute to barycenter of target.
            val (selfId, otherId) = when {
                target < neighbourLayer -> from to to
                else -> to to from
            }
            if (selfId !in sumPos) continue
            val np = neighbourPos[otherId] ?: continue
            sumPos[selfId] = sumPos.getValue(selfId) + np
            deg[selfId] = deg.getValue(selfId) + 1
        }
        val origIndex = HashMap<NodeId, Int>(targetOrder.size).apply {
            targetOrder.forEachIndexed { i, id -> put(id, i) }
        }
        val sorted = targetOrder.sortedWith(Comparator { a, b ->
            val da = deg.getValue(a); val db = deg.getValue(b)
            // Nodes with no neighbour in the reference layer keep their original index.
            if (da == 0 && db == 0) return@Comparator origIndex.getValue(a).compareTo(origIndex.getValue(b))
            if (da == 0) return@Comparator origIndex.getValue(a).compareTo(origIndex.getValue(b))
            if (db == 0) return@Comparator origIndex.getValue(a).compareTo(origIndex.getValue(b))
            val ba = sumPos.getValue(a) / da
            val bb = sumPos.getValue(b) / db
            val c = ba.compareTo(bb)
            if (c != 0) c else origIndex.getValue(a).compareTo(origIndex.getValue(b))
        })
        targetOrder.clear()
        targetOrder.addAll(sorted)
    }

    private fun countCrossings(
        effective: List<Pair<NodeId, NodeId>>,
        graph: LayeredGraph,
        maxLayer: Int,
    ): Int {
        var total = 0
        for (l in 0 until maxLayer) {
            val upper = graph.orderInLayer[l] ?: continue
            val lower = graph.orderInLayer[l + 1] ?: continue
            val upperPos = HashMap<NodeId, Int>(upper.size).apply { upper.forEachIndexed { i, id -> put(id, i) } }
            val lowerPos = HashMap<NodeId, Int>(lower.size).apply { lower.forEachIndexed { i, id -> put(id, i) } }
            val pairs = ArrayList<IntArray>()
            for ((from, to) in effective) {
                val u = upperPos[from]; val v = lowerPos[to]
                if (u != null && v != null) pairs += intArrayOf(u, v)
            }
            for (i in pairs.indices) for (j in i + 1 until pairs.size) {
                val a = pairs[i]; val b = pairs[j]
                if ((a[0] < b[0] && a[1] > b[1]) || (a[0] > b[0] && a[1] < b[1])) total++
            }
        }
        return total
    }
}
