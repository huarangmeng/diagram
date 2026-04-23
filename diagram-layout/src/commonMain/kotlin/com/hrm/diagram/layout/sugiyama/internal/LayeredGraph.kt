package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.ir.NodeId

internal class LayeredGraph {
    val layer: MutableMap<NodeId, Int> = HashMap()
    val orderInLayer: MutableMap<Int, MutableList<NodeId>> = HashMap()
    val reversedEdges: MutableSet<Pair<NodeId, NodeId>> = HashSet()

    fun maxLayer(): Int = layer.values.maxOrNull() ?: -1

    fun appendToLayer(id: NodeId, layerIndex: Int) {
        layer[id] = layerIndex
        orderInLayer.getOrPut(layerIndex) { ArrayList() } += id
    }

    fun reset() {
        layer.clear()
        orderInLayer.clear()
        reversedEdges.clear()
    }
}
