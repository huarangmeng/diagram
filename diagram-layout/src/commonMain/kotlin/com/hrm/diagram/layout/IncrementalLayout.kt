package com.hrm.diagram.layout

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.layout.LayoutOptions

/**
 * The primary contract every layout algorithm in `:diagram-layout` exposes.
 *
 * **Streaming-first**: the design assumes [layout] is called many times during one session, each
 * time with a slightly larger [model]. Implementations MUST honour [LayoutOptions.incremental]:
 *
 * - When `previous != null` and `options.incremental == true`: keep every coordinate already
 *   present in [previous] **byte-for-byte identical** in the result, and only assign coordinates
 *   to the genuinely new [DiagramModel] elements. See `docs/streaming.md` §3.4.
 * - When unable to do so without moving existing nodes:
 *     - if `options.allowGlobalReflow == true` → may re-layout from scratch.
 *     - else → MUST keep existing nodes pinned and append even if the result is sub-optimal
 *       (we trade aesthetics for stability — the LLM streaming UX MUST NOT have node jumping).
 *
 * Non-streaming callers simply pass `previous = null` and get a full layout.
 */
public interface IncrementalLayout<I : DiagramModel> {
    public fun layout(
        previous: LaidOutDiagram?,
        model: I,
        options: LayoutOptions,
    ): LaidOutDiagram

    /** Convenience for the common "first run" case. */
    public fun layout(model: I, options: LayoutOptions): LaidOutDiagram =
        layout(previous = null, model = model, options = options)
}

/**
 * Result of a layout pass. Coordinates are in the canvas coordinate space defined by
 * `:diagram-core/draw/Geometry.kt` (top-left origin, Y-axis down, units = px @ 1x DPI).
 *
 * **Pinning contract**: when an incremental layout consumes a previous [LaidOutDiagram], every
 * `(NodeId, Rect)` pair in [nodePositions] that already existed in the baseline MUST equal
 * (`==`) the baseline value. Tests in `:diagram-layout/commonTest` enforce this invariant via
 * a property-based check.
 */
public data class LaidOutDiagram(
    /** The model these positions were computed for. Held by reference to enable cheap diffing. */
    val source: DiagramModel,
    /** Absolute bounding box of every visible node, keyed by stable [NodeId]. */
    val nodePositions: Map<NodeId, Rect>,
    /** Routed edge polylines/curves; key is the (from, to) pair as it appears in [source]. */
    val edgeRoutes: List<EdgeRoute>,
    /** Cluster bounding boxes, parent-before-child topological order. */
    val clusterRects: Map<NodeId, Rect> = emptyMap(),
    /** Total drawing extent (max-x, max-y) including padding. */
    val bounds: Rect,
    /** Monotonically-increasing version, mirrors `DiagramSnapshot.seq`. */
    val seq: Long = 0L,
)

/**
 * One edge's geometry. The polyline form covers straight / orthogonal routings; bezier
 * algorithms encode their control points by interleaving them in [points] and exposing
 * [kind] = [RouteKind.Bezier] (downstream consumes via the `:diagram-render` mapper).
 */
public data class EdgeRoute(
    val from: NodeId,
    val to: NodeId,
    /** Ordered points; for bezier kind, length must be `1 + 3 * n` (M + n × C triplets). */
    val points: List<Point>,
    val kind: RouteKind = RouteKind.Polyline,
) {
    init { require(points.size >= 2) { "EdgeRoute must have at least 2 points" } }
}

public enum class RouteKind { Polyline, Bezier, Orthogonal }
