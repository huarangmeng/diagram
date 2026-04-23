package com.hrm.diagram.layout.sugiyama

import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.sugiyama.internal.CoordinateAssignment
import com.hrm.diagram.layout.sugiyama.internal.CrossingMinimization
import com.hrm.diagram.layout.sugiyama.internal.CycleRemoval
import com.hrm.diagram.layout.sugiyama.internal.LayerAssignment
import com.hrm.diagram.layout.sugiyama.internal.LayeredGraph
import com.hrm.diagram.layout.sugiyama.routing.BezierEdgeRouter

/**
 * Sugiyama-style layered layout for [GraphIR], with streaming-friendly pinning.
 *
 * Two operating modes selected by [LayoutOptions]:
 *
 * - **Incremental** (`incremental = true`, `allowGlobalReflow = false`): every node already
 *   placed in the previous snapshot keeps its [Rect] byte-for-byte. New nodes are slotted into
 *   `layer = max(predLayer) + 1` (0 if no placed predecessor) and appended to the right (or
 *   bottom for LR/RL) of that layer. Edge routes are recomputed for any edge whose endpoint
 *   moved or appeared.
 *
 * - **Full reflow** (`allowGlobalReflow = true`, used at `finish()`): runs the canonical four
 *   stages — cycle removal → longest-path layering → barycenter crossing minimisation →
 *   equal-spacing coordinate assignment — and re-routes every edge. Pinning is dropped because
 *   the result is final.
 */
internal class SugiyamaIncrementalLayout(
    private val defaultNodeSize: Size = Size(120f, 48f),
    private val nodeSizeOf: (com.hrm.diagram.core.ir.NodeId) -> Size = { defaultNodeSize },
) : IncrementalLayout<GraphIR> {

    private val cache = LayeredGraph()
    private val rectCache: LinkedHashMap<NodeId, Rect> = LinkedHashMap()

    override fun layout(
        previous: LaidOutDiagram?,
        model: GraphIR,
        options: LayoutOptions,
    ): LaidOutDiagram {
        val direction = options.direction ?: model.styleHints.direction
        val effectiveOptions = if (options.direction == null) options.copy(direction = direction) else options
        return if (effectiveOptions.allowGlobalReflow) fullReflow(model, effectiveOptions)
        else incremental(model, effectiveOptions)
    }

    private fun incremental(model: GraphIR, options: LayoutOptions): LaidOutDiagram {
        // Slot every not-yet-placed node into a layer using its already-placed predecessors.
        val predsByNode: Map<NodeId, List<NodeId>> = model.edges.groupBy({ it.to }, { it.from })
        for (n in model.nodes) {
            if (n.id in cache.layer) continue
            val preds = predsByNode[n.id]?.filter { it in cache.layer }.orEmpty()
            val layerIdx = if (preds.isEmpty()) 0 else (preds.maxOf { cache.layer.getValue(it) } + 1)
            cache.appendToLayer(n.id, layerIdx)
        }
        // Place new nodes; existing rects stay frozen.
        val placement = CoordinateAssignment.assign(
            graph = cache,
            nodeSizeOf = nodeSizeOf,
            nodeSpacing = options.nodeSpacing,
            rankSpacing = options.rankSpacing,
            direction = options.direction,
        )
        for ((id, rect) in placement) {
            if (id !in rectCache) rectCache[id] = rect
        }
        return assemble(model, options)
    }

    private fun fullReflow(model: GraphIR, options: LayoutOptions): LaidOutDiagram {
        cache.reset()
        rectCache.clear()
        val reversed = CycleRemoval.reversedEdges(model.nodes, model.edges)
        cache.reversedEdges += reversed
        LayerAssignment.assign(model.nodes, model.edges, reversed, cache)
        CrossingMinimization.minimize(model.edges, reversed, cache)
        val placement = CoordinateAssignment.assign(
            graph = cache,
            nodeSizeOf = nodeSizeOf,
            nodeSpacing = options.nodeSpacing,
            rankSpacing = options.rankSpacing,
            direction = options.direction,
        )
        rectCache.putAll(placement)
        return assemble(model, options)
    }

    private fun assemble(model: GraphIR, options: LayoutOptions): LaidOutDiagram {
        val routes: List<EdgeRoute> = model.edges.mapNotNull { e ->
            val a = rectCache[e.from] ?: return@mapNotNull null
            val b = rectCache[e.to] ?: return@mapNotNull null
            BezierEdgeRouter.route(e.from, a, e.to, b, options.direction)
        }
        val pad = options.padding
        val maxRight = (rectCache.values.maxOfOrNull { it.right } ?: 0f) + pad.right
        val maxBottom = (rectCache.values.maxOfOrNull { it.bottom } ?: 0f) + pad.bottom
        return LaidOutDiagram(
            source = model,
            nodePositions = LinkedHashMap(rectCache),
            edgeRoutes = routes,
            bounds = Rect.ltrb(0f, 0f, maxRight, maxBottom),
            seq = 0L,
        )
    }
}
