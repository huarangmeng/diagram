package com.hrm.diagram.layout.sugiyama.routing

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.RouteKind

/**
 * Cubic Bézier router: emits exactly four points (M + C1 + C2 + end) per edge — the smallest
 * span permitted by the `1 + 3·n` invariant in [EdgeRoute]. Endpoints sit on the rectangle
 * border so arrowheads do not vanish under the node fill; control points pull the curve into
 * the rank-spacing gap.
 */
internal object BezierEdgeRouter {
    fun route(
        from: NodeId,
        fromRect: Rect,
        to: NodeId,
        toRect: Rect,
        direction: Direction?,
    ): EdgeRoute {
        val horizontal = direction == Direction.LR || direction == Direction.RL
        return if (horizontal) routeHorizontal(from, fromRect, to, toRect, reverseDir = direction == Direction.RL)
        else routeVertical(from, fromRect, to, toRect, reverseDir = direction == Direction.BT)
    }

    private fun routeVertical(
        from: NodeId, a: Rect, to: NodeId, b: Rect, reverseDir: Boolean,
    ): EdgeRoute {
        val (start, end) = if (!reverseDir) {
            Point((a.left + a.right) / 2f, a.bottom) to Point((b.left + b.right) / 2f, b.top)
        } else {
            Point((a.left + a.right) / 2f, a.top) to Point((b.left + b.right) / 2f, b.bottom)
        }
        val midY = (start.y + end.y) / 2f
        return EdgeRoute(
            from = from,
            to = to,
            points = listOf(start, Point(start.x, midY), Point(end.x, midY), end),
            kind = RouteKind.Bezier,
        )
    }

    private fun routeHorizontal(
        from: NodeId, a: Rect, to: NodeId, b: Rect, reverseDir: Boolean,
    ): EdgeRoute {
        val (start, end) = if (!reverseDir) {
            Point(a.right, (a.top + a.bottom) / 2f) to Point(b.left, (b.top + b.bottom) / 2f)
        } else {
            Point(a.left, (a.top + a.bottom) / 2f) to Point(b.right, (b.top + b.bottom) / 2f)
        }
        val midX = (start.x + end.x) / 2f
        return EdgeRoute(
            from = from,
            to = to,
            points = listOf(start, Point(midX, start.y), Point(midX, end.y), end),
            kind = RouteKind.Bezier,
        )
    }
}
