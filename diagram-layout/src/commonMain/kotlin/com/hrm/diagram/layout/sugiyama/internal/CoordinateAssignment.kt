package com.hrm.diagram.layout.sugiyama.internal

import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId

/**
 * Equal-spacing coordinate assignment. Per layer, nodes flow left-to-right (or top-to-bottom for
 * LR/RL) in the order produced by [CrossingMinimization]; layers themselves are spaced by
 * [rankSpacing]. Not Brandes-Köpf — see plan.md for the upgrade path.
 */
internal object CoordinateAssignment {
    fun assign(
        graph: LayeredGraph,
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
}
