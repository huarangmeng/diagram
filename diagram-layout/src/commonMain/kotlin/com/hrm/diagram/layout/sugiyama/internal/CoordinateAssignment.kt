package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.NodeId

/**
 * Equal-spacing coordinate assignment. Per layer, nodes flow left-to-right (or top-to-bottom for
 * LR/RL) in the order produced by [CrossingMinimization]; layers themselves are spaced by
 * [rankSpacing]. A lightweight barycenter alignment pass then centers narrower ranks over their
 * already-placed neighbours, following the same readability goal as Graphviz/dagre layered
 * layouts without introducing dummy-node state into the streaming contract.
 */
internal object CoordinateAssignment {
    fun assign(
        graph: LayeredGraph,
        edges: List<Edge> = emptyList(),
        reversed: Set<Pair<NodeId, NodeId>> = emptySet(),
        nodeSizeOf: (NodeId) -> Size,
        nodeSpacing: Float,
        rankSpacing: Float,
        direction: Direction?,
    ): MutableMap<NodeId, Rect> {
        val rects = LinkedHashMap<NodeId, Rect>()
        val horizontal = direction == Direction.LR || direction == Direction.RL
        val sortedLayers = graph.orderInLayer.keys.sorted()
        // Per-layer "thickness" = max of the cross-axis size of any node in the layer.
        val layerThickness = HashMap<Int, Float>(sortedLayers.size)
        for (l in sortedLayers) {
            val ids = graph.orderInLayer[l] ?: continue
            layerThickness[l] = ids.maxOf {
                val s = nodeSizeOf(it)
                if (horizontal) s.width else s.height
            }
        }
        // Layer offset along the rank axis: cumulative sum of thicknesses + rankSpacing gaps.
        val layerOffset = HashMap<Int, Float>(sortedLayers.size)
        var acc = 0f
        for (l in sortedLayers) {
            layerOffset[l] = acc
            acc += (layerThickness[l] ?: 0f) + rankSpacing
        }
        for (layerIndex in sortedLayers) {
            val ids = graph.orderInLayer[layerIndex] ?: continue
            val thickness = layerThickness.getValue(layerIndex)
            var cursor = 0f
            for (id in ids) {
                val s = nodeSizeOf(id)
                val rect = if (horizontal) {
                    val x = layerOffset.getValue(layerIndex) + (thickness - s.width) / 2f
                    val y = cursor
                    cursor += s.height + nodeSpacing
                    Rect.ltrb(x, y, x + s.width, y + s.height)
                } else {
                    val x = cursor
                    val y = layerOffset.getValue(layerIndex) + (thickness - s.height) / 2f
                    cursor += s.width + nodeSpacing
                    Rect.ltrb(x, y, x + s.width, y + s.height)
                }
                rects[id] = rect
            }
        }
        alignRanksByBarycenter(rects, graph, edges, reversed, nodeSizeOf, nodeSpacing, horizontal, sortedLayers)
        // Mirror across the rank axis so BT places layer-0 at the bottom and RL at the right.
        // Cross-axis ordering is preserved; BezierEdgeRouter's per-direction anchor flip already
        // matches the mirrored geometry.
        if (direction == Direction.BT || direction == Direction.RL) {
            val maxX = rects.values.maxOfOrNull { it.right } ?: 0f
            val maxY = rects.values.maxOfOrNull { it.bottom } ?: 0f
            for ((id, r) in rects.entries.toList()) {
                rects[id] = if (direction == Direction.RL) {
                    Rect.ltrb(maxX - r.right, r.top, maxX - r.left, r.bottom)
                } else {
                    Rect.ltrb(r.left, maxY - r.bottom, r.right, maxY - r.top)
                }
            }
        }
        return rects
    }

    private fun alignRanksByBarycenter(
        rects: MutableMap<NodeId, Rect>,
        graph: LayeredGraph,
        edges: List<Edge>,
        reversed: Set<Pair<NodeId, NodeId>>,
        nodeSizeOf: (NodeId) -> Size,
        nodeSpacing: Float,
        horizontal: Boolean,
        sortedLayers: List<Int>,
    ) {
        if (sortedLayers.size < 2) return
        val widestLayer = sortedLayers.maxByOrNull { layerSpan(graph.orderInLayer[it].orEmpty(), nodeSizeOf, nodeSpacing, horizontal) } ?: return
        val placed = HashSet<Int>()
        placed += widestLayer

        fun shiftLayer(layer: Int, desiredCenter: Float) {
            val ids = graph.orderInLayer[layer].orEmpty()
            if (ids.isEmpty()) return
            val span = layerSpan(ids, nodeSizeOf, nodeSpacing, horizontal)
            val delta = desiredCenter - span / 2f
            for (id in ids) rects[id]?.let { rects[id] = shiftCross(it, delta, horizontal) }
        }

        fun placeRange(range: Iterable<Int>) {
            for (layer in range) {
                val ids = graph.orderInLayer[layer].orEmpty()
                if (ids.isEmpty()) continue
                val desired = neighbourBarycenter(ids, graph, rects, edges, reversed, placed, horizontal)
                    ?: nearestPlacedLayerCenter(layer, graph, rects, placed, horizontal)
                    ?: continue
                shiftLayer(layer, desired)
                placed += layer
            }
        }

        placeRange(sortedLayers.filter { it > widestLayer })
        placeRange(sortedLayers.filter { it < widestLayer }.asReversed())
        normalizeCrossAxis(rects, horizontal)
    }

    private fun neighbourBarycenter(
        ids: List<NodeId>,
        graph: LayeredGraph,
        rects: Map<NodeId, Rect>,
        edges: List<Edge>,
        reversed: Set<Pair<NodeId, NodeId>>,
        placedLayers: Set<Int>,
        horizontal: Boolean,
    ): Float? {
        if (edges.isEmpty()) return null
        val idSet = ids.toSet()
        var sum = 0f
        var count = 0
        for (edge in edges) {
            val pair = edge.from to edge.to
            val (from, to) = if (pair in reversed) edge.to to edge.from else pair
            val other = when {
                from in idSet -> to
                to in idSet -> from
                else -> continue
            }
            val otherLayer = graph.layer[other] ?: continue
            if (otherLayer !in placedLayers) continue
            val otherRect = rects[other] ?: continue
            sum += crossCenter(otherRect, horizontal)
            count++
        }
        return if (count == 0) null else sum / count
    }

    private fun nearestPlacedLayerCenter(
        layer: Int,
        graph: LayeredGraph,
        rects: Map<NodeId, Rect>,
        placedLayers: Set<Int>,
        horizontal: Boolean,
    ): Float? {
        val nearest = placedLayers.minWithOrNull(compareBy<Int> { kotlin.math.abs(it - layer) }.thenBy { it }) ?: return null
        val ids = graph.orderInLayer[nearest].orEmpty()
        if (ids.isEmpty()) return null
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (id in ids) {
            val rect = rects[id] ?: continue
            min = kotlin.math.min(min, crossStart(rect, horizontal))
            max = kotlin.math.max(max, crossEnd(rect, horizontal))
        }
        return if (min.isFinite() && max.isFinite()) (min + max) / 2f else null
    }

    private fun layerSpan(
        ids: List<NodeId>,
        nodeSizeOf: (NodeId) -> Size,
        nodeSpacing: Float,
        horizontal: Boolean,
    ): Float {
        if (ids.isEmpty()) return 0f
        var span = 0f
        for ((index, id) in ids.withIndex()) {
            val size = nodeSizeOf(id)
            span += if (horizontal) size.height else size.width
            if (index < ids.lastIndex) span += nodeSpacing
        }
        return span
    }

    private fun normalizeCrossAxis(rects: MutableMap<NodeId, Rect>, horizontal: Boolean) {
        val minCross = rects.values.minOfOrNull { crossStart(it, horizontal) } ?: return
        if (minCross >= 0f) return
        val delta = -minCross
        for ((id, rect) in rects.entries.toList()) rects[id] = shiftCross(rect, delta, horizontal)
    }

    private fun shiftCross(rect: Rect, delta: Float, horizontal: Boolean): Rect =
        if (horizontal) Rect.ltrb(rect.left, rect.top + delta, rect.right, rect.bottom + delta)
        else Rect.ltrb(rect.left + delta, rect.top, rect.right + delta, rect.bottom)

    private fun crossStart(rect: Rect, horizontal: Boolean): Float = if (horizontal) rect.top else rect.left
    private fun crossEnd(rect: Rect, horizontal: Boolean): Float = if (horizontal) rect.bottom else rect.right
    private fun crossCenter(rect: Rect, horizontal: Boolean): Float = (crossStart(rect, horizontal) + crossEnd(rect, horizontal)) / 2f
}
