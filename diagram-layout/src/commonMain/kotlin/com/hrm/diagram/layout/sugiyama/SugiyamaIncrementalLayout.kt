package com.hrm.diagram.layout.sugiyama

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.internal.CoordinateAssignment
import com.hrm.diagram.layout.sugiyama.internal.CrossingMinimization
import com.hrm.diagram.layout.sugiyama.internal.CycleRemoval
import com.hrm.diagram.layout.sugiyama.internal.LayerAssignment
import com.hrm.diagram.layout.sugiyama.internal.LayeredGraph
import com.hrm.diagram.layout.sugiyama.routing.BezierEdgeRouter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        applyRankConstraints(options, model.nodes.map { it.id }.toSet(), cache)
        CrossingMinimization.minimize(model.edges, reversed, cache)
        val placement = CoordinateAssignment.assign(
            graph = cache,
            edges = model.edges,
            reversed = reversed,
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
            routeEdge(e, a, b, options)
        }
        val pad = options.padding
        val routePoints = routes.flatMap { it.points }
        val maxRight = max(
            rectCache.values.maxOfOrNull { it.right } ?: 0f,
            routePoints.maxOfOrNull { it.x } ?: 0f,
        ) + pad.right
        val maxBottom = max(
            rectCache.values.maxOfOrNull { it.bottom } ?: 0f,
            routePoints.maxOfOrNull { it.y } ?: 0f,
        ) + pad.bottom
        return LaidOutDiagram(
            source = model,
            nodePositions = LinkedHashMap(rectCache),
            edgeRoutes = routes,
            bounds = Rect.ltrb(0f, 0f, maxRight, maxBottom),
            seq = 0L,
        )
    }

    private fun routeEdge(
        edge: Edge,
        fromRect: Rect,
        toRect: Rect,
        options: LayoutOptions,
    ): EdgeRoute {
        val base = BezierEdgeRouter.route(edge.from, fromRect, edge.to, toRect, options.direction)
        val fromLayer = cache.layer[edge.from] ?: return base
        val toLayer = cache.layer[edge.to] ?: return base
        if (abs(toLayer - fromLayer) <= 1) return base
        val horizontal = options.direction == Direction.LR || options.direction == Direction.RL
        val blockers = intermediateBlockers(
            from = edge.from,
            to = edge.to,
            fromLayer = fromLayer,
            toLayer = toLayer,
            start = base.points.first(),
            end = base.points.last(),
            horizontal = horizontal,
            clearance = max(6f, options.nodeSpacing / 4f),
        )
        if (blockers.isEmpty()) return base
        return if (horizontal) routeAroundHorizontal(edge, base.points.first(), base.points.last(), blockers, options)
        else routeAroundVertical(edge, base.points.first(), base.points.last(), blockers, options)
    }

    private fun intermediateBlockers(
        from: NodeId,
        to: NodeId,
        fromLayer: Int,
        toLayer: Int,
        start: Point,
        end: Point,
        horizontal: Boolean,
        clearance: Float,
    ): List<Rect> {
        val lowLayer = min(fromLayer, toLayer)
        val highLayer = max(fromLayer, toLayer)
        val lowAxis = if (horizontal) min(start.x, end.x) else min(start.y, end.y)
        val highAxis = if (horizontal) max(start.x, end.x) else max(start.y, end.y)
        val cross = if (horizontal) start.y else start.x
        return rectCache.mapNotNull { (id, rect) ->
            if (id == from || id == to) return@mapNotNull null
            val layer = cache.layer[id] ?: return@mapNotNull null
            if (layer <= lowLayer || layer >= highLayer) return@mapNotNull null
            if (horizontal) {
                val crossesY = cross >= rect.top - clearance && cross <= rect.bottom + clearance
                val spansX = rect.right >= lowAxis && rect.left <= highAxis
                rect.takeIf { crossesY && spansX }
            } else {
                val crossesX = cross >= rect.left - clearance && cross <= rect.right + clearance
                val spansY = rect.bottom >= lowAxis && rect.top <= highAxis
                rect.takeIf { crossesX && spansY }
            }
        }
    }

    private fun routeAroundVertical(
        edge: Edge,
        start: Point,
        end: Point,
        blockers: List<Rect>,
        options: LayoutOptions,
    ): EdgeRoute {
        val laneX = blockers.maxOf { it.right } + max(18f, options.nodeSpacing / 2f)
        val sign = if (end.y >= start.y) 1f else -1f
        val elbow = min(abs(end.y - start.y) / 3f, options.rankSpacing / 2f).coerceAtLeast(12f)
        val y1 = start.y + sign * elbow
        val y2 = end.y - sign * elbow
        return EdgeRoute(
            from = edge.from,
            to = edge.to,
            points = listOf(start, Point(start.x, y1), Point(laneX, y1), Point(laneX, y2), Point(end.x, y2), end),
            kind = RouteKind.Orthogonal,
        )
    }

    private fun routeAroundHorizontal(
        edge: Edge,
        start: Point,
        end: Point,
        blockers: List<Rect>,
        options: LayoutOptions,
    ): EdgeRoute {
        val laneY = blockers.maxOf { it.bottom } + max(18f, options.nodeSpacing / 2f)
        val sign = if (end.x >= start.x) 1f else -1f
        val elbow = min(abs(end.x - start.x) / 3f, options.rankSpacing / 2f).coerceAtLeast(12f)
        val x1 = start.x + sign * elbow
        val x2 = end.x - sign * elbow
        return EdgeRoute(
            from = edge.from,
            to = edge.to,
            points = listOf(start, Point(x1, start.y), Point(x1, laneY), Point(x2, laneY), Point(x2, end.y), end),
            kind = RouteKind.Orthogonal,
        )
    }

    private fun applyRankConstraints(
        options: LayoutOptions,
        validNodeIds: Set<NodeId>,
        graph: LayeredGraph,
    ) {
        val constraints = rankConstraints(options.extras, validNodeIds)
        if (constraints.isEmpty()) return
        val maxBefore = graph.maxLayer().coerceAtLeast(0)
        for (constraint in constraints) {
            val ids = constraint.nodes.filter { it in graph.layer }
            if (ids.isEmpty()) continue
            val target = when (constraint.kind) {
                "same" -> ids.maxOf { graph.layer[it] ?: 0 }
                "min", "source" -> 0
                "max", "sink" -> maxBefore
                else -> continue
            }
            for (id in ids) graph.layer[id] = target
        }
        rebuildOrder(graph)
    }

    private data class RankConstraint(val kind: String, val nodes: List<NodeId>)

    private fun rankConstraints(extras: Map<String, String>, validNodeIds: Set<NodeId>): List<RankConstraint> {
        val out = ArrayList<RankConstraint>()
        var index = 0
        while (true) {
            val kind = extras["dot.rank.$index.kind"]?.lowercase() ?: break
            val nodes = extras["dot.rank.$index.nodes"]
                .orEmpty()
                .split(',')
                .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.let(::NodeId) }
                .filter { it in validNodeIds }
            if (nodes.isNotEmpty()) out += RankConstraint(kind, nodes)
            index++
        }
        return out
    }

    private fun rebuildOrder(graph: LayeredGraph) {
        val orderedNodes = graph.orderInLayer.keys.sorted().flatMap { graph.orderInLayer[it].orEmpty() }
        graph.orderInLayer.clear()
        for (id in orderedNodes) {
            val layer = graph.layer[id] ?: continue
            graph.orderInLayer.getOrPut(layer) { ArrayList() } += id
        }
    }
}
