package com.hrm.diagram.render.streaming.dot

import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.ArrowStyle
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.dot.DotParser
import com.hrm.diagram.render.cache.DrawCommandStore
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import com.hrm.diagram.render.streaming.SessionPipeline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class DotSessionPipeline(
    private val textMeasurer: TextMeasurer,
) : SessionPipeline {
    private val parser = DotParser()
    private val parserSession = parser.incrementalSession()
    private val drawStore = DrawCommandStore()
    private val nodeFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val edgeFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val clusterFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val nodeSizes: MutableMap<NodeId, Size> = LinkedHashMap()
    private var lastNodeIds: Set<NodeId> = emptySet()
    private var lastEdgeKeys: Set<String> = emptySet()
    private var lastDiagnosticCount: Int = 0
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(112f, 44f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(112f, 44f) },
    )

    override fun advance(
        previousSnapshot: DiagramSnapshot,
        chunk: CharSequence,
        absoluteOffset: Int,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val result = parserSession.feed(chunk, eos = isFinal)
        val ir = result.ir
        measureNodes(ir, remeasure = isFinal)
        val layoutIr = ir.copy(edges = ir.edges.filterNot { it.payload["dot.edge.constraint"].equals("false", ignoreCase = true) })
        val layoutOptions = LayoutOptions(
            direction = ir.styleHints.direction,
            nodeSpacing = dotSpacing(ir, "dot.graph.nodesep", defaultPx = 24f),
            rankSpacing = dotSpacing(ir, "dot.graph.ranksep", defaultPx = 48f),
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
            extras = ir.styleHints.extras,
        )
        val base = layout.layout(previousSnapshot.laidOut, layoutIr, layoutOptions).copy(source = ir, seq = seq)
        val laid = withClusterRects(ir, base)
        val drawDelta = drawStore.updateFullFrame(render(ir, laid))

        val nodeIds = ir.nodes.map { it.id }.toSet()
        val edgeKeys = ir.edges.mapIndexed { index, edge -> "${edge.from.value}->${edge.to.value}:$index:${labelOf(edge.label)}" }.toSet()
        val addedNodes = ir.nodes.filter { it.id !in lastNodeIds }.map { it.id }
        val addedEdges = ir.edges.filterIndexed { index, edge ->
            "${edge.from.value}->${edge.to.value}:$index:${labelOf(edge.label)}" !in lastEdgeKeys
        }
        val newDiagnostics = result.diagnostics.drop(lastDiagnosticCount)
        lastNodeIds = nodeIds
        lastEdgeKeys = edgeKeys
        lastDiagnosticCount = result.diagnostics.size

        val patches = buildList {
            addedNodes.forEach { id -> ir.nodes.firstOrNull { it.id == id }?.let { add(IrPatch.AddNode(it)) } }
            addedEdges.forEach { add(IrPatch.AddEdge(it)) }
            newDiagnostics.forEach { add(IrPatch.AddDiagnostic(it)) }
        }
        val snapshot = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = drawDelta.fullFrame,
            diagnostics = result.diagnostics,
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(
            snapshot = snapshot,
            patch = SessionPatch(
                seq = seq,
                addedNodes = addedNodes,
                addedEdges = addedEdges,
                addedDrawCommands = drawDelta.addedCommands,
                newDiagnostics = newDiagnostics,
                isFinal = isFinal,
            ),
            irBatch = IrPatchBatch(seq, patches),
        )
    }

    override fun dispose() {
        parserSession.reset()
        drawStore.clear()
        nodeSizes.clear()
        lastNodeIds = emptySet()
        lastEdgeKeys = emptySet()
        lastDiagnosticCount = 0
    }

    private fun measureNodes(ir: GraphIR, remeasure: Boolean) {
        for (node in ir.nodes) {
            if (!remeasure && node.id in nodeSizes) continue
            val text = labelOf(node.label).ifBlank { node.id.value }
            val metrics = textMeasurer.measure(text, fontOf(node), maxWidth = 200f)
            val padX = when (node.shape) {
                NodeShape.Diamond -> 30f
                NodeShape.Circle, NodeShape.Ellipse -> 24f
                else -> 18f
            }
            val padY = when (node.shape) {
                NodeShape.Diamond -> 18f
                else -> 12f
            }
            val width = (metrics.width + padX * 2f).coerceAtLeast(72f)
            val height = (metrics.height + padY * 2f).coerceAtLeast(38f)
            val side = max(width, height)
            nodeSizes[node.id] = when (node.shape) {
                NodeShape.Circle -> Size(side, side)
                NodeShape.Diamond -> Size(width * 1.35f, height * 1.35f)
                else -> Size(width, height)
            }
        }
    }

    private fun withClusterRects(ir: GraphIR, laid: LaidOutDiagram): LaidOutDiagram {
        val rects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, laid.nodePositions, rects)
        val bounds = computeBounds(laid.nodePositions.values + rects.values)
        return laid.copy(clusterRects = rects, bounds = bounds)
    }

    private fun computeClusterRect(
        cluster: Cluster,
        nodePositions: Map<NodeId, Rect>,
        out: MutableMap<NodeId, Rect>,
    ): Rect? {
        val rects = ArrayList<Rect>()
        cluster.children.mapNotNullTo(rects) { nodePositions[it] }
        cluster.nestedClusters.mapNotNullTo(rects) { computeClusterRect(it, nodePositions, out) }
        val base = rects.takeIf { it.isNotEmpty() }?.let { union(it) } ?: return null
        val labelHeight = labelOf(cluster.label).takeIf { it.isNotBlank() }?.let {
            textMeasurer.measure(it, clusterFont, maxWidth = 260f).height + 18f
        } ?: 18f
        val rect = Rect.ltrb(base.left - 24f, base.top - labelHeight - 16f, base.right + 24f, base.bottom + 20f)
        out[cluster.id] = rect
        return rect
    }

    private fun render(ir: GraphIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val routeByEndpoints = laid.edgeRoutes.associateBy { it.from to it.to }
        ir.styleHints.extras["dot.graph.bgcolor"]?.let(::colorOf)?.let { bg ->
            out += DrawCommand.FillRect(rect = laid.bounds, color = bg, z = -10)
        }
        renderClusters(ir.clusters, laid, out)
        for (edge in ir.edges) {
            if (edge.kind == EdgeKind.Invisible) continue
            val route = routeByEndpoints[edge.from to edge.to]
            val rawPoints = route?.points ?: fallbackRoute(edge.from, edge.to, laid.nodePositions) ?: continue
            val points = applyPortAnchors(edge, rawPoints, laid.nodePositions)
            val color = edge.style.color?.let { Color(it.argb) } ?: Color(0xFF4B5563.toInt())
            val stroke = Stroke(width = edge.style.width ?: if (edge.kind == EdgeKind.Thick) 2.2f else 1.2f, dash = edge.style.dash)
            val kind = route?.kind ?: RouteKind.Polyline
            out += DrawCommand.StrokePath(path = pathOf(points, kind), stroke = stroke, color = color, z = 3)
            renderArrowHeads(edge, points, kind, color, stroke, out)
            labelOf(edge.label).takeIf { it.isNotBlank() }?.let { label ->
                val mid = points[points.size / 2]
                out += DrawCommand.FillRect(
                    rect = Rect(Point(mid.x - 36f, mid.y - 10f), Size(72f, 20f)),
                    color = edge.style.labelBg?.let { Color(it.argb) } ?: Color(0xF0FFFFFF.toInt()),
                    corner = 4f,
                    z = 5,
                )
                out += DrawCommand.DrawText(
                    text = label,
                    origin = mid,
                    font = edgeLabelFont(edge, "dot.edge.html"),
                    color = edgeLabelColor(edge, "dot.edge.html") ?: color,
                    maxWidth = 160f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 6,
                )
            }
            renderEndpointLabel(edge.payload["dot.edge.headlabel"], points.last(), color, out, edge, "dot.edge.head.html")
            renderEndpointLabel(edge.payload["dot.edge.taillabel"], points.first(), color, out, edge, "dot.edge.tail.html")
        }
        for (node in ir.nodes) renderNode(node, laid.nodePositions[node.id] ?: continue, out)
        return out
    }

    private fun renderEndpointLabel(
        label: String?,
        point: Point,
        color: Color,
        out: MutableList<DrawCommand>,
        edge: Edge,
        prefix: String,
    ) {
        if (label.isNullOrBlank()) return
        out += DrawCommand.FillRect(
            rect = Rect(Point(point.x - 30f, point.y - 9f), Size(60f, 18f)),
            color = Color(0xF0FFFFFF.toInt()),
            corner = 4f,
            z = 5,
        )
        out += DrawCommand.DrawText(
            text = label,
            origin = point,
            font = edgeLabelFont(edge, prefix),
            color = edgeLabelColor(edge, prefix) ?: color,
            maxWidth = 120f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 6,
        )
    }

    private fun renderClusters(clusters: List<Cluster>, laid: LaidOutDiagram, out: MutableList<DrawCommand>) {
        for (cluster in clusters) {
            val rect = laid.clusterRects[cluster.id]
            if (rect != null) {
                out += DrawCommand.FillRect(
                    rect = rect,
                    color = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF8FAFC.toInt()),
                    corner = 12f,
                    z = 0,
                )
                out += DrawCommand.StrokeRect(
                    rect = rect,
                    stroke = Stroke(width = cluster.style.strokeWidth ?: 1.2f),
                    color = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF94A3B8.toInt()),
                    corner = 12f,
                    z = 1,
                )
                labelOf(cluster.label).takeIf { it.isNotBlank() }?.let {
                    out += DrawCommand.DrawText(
                        text = it,
                        origin = Point(rect.left + 12f, rect.top + 10f),
                        font = clusterFont,
                        color = Color(0xFF334155.toInt()),
                        maxWidth = rect.size.width - 24f,
                        anchorY = TextAnchorY.Top,
                        z = 2,
                    )
                }
            }
            renderClusters(cluster.nestedClusters, laid, out)
        }
    }

    private fun renderNode(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFF9FAFB.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF374151.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.2f)
        when (node.shape) {
            NodeShape.Circle, NodeShape.Ellipse -> {
                val path = ellipsePath(rect)
                out += DrawCommand.FillPath(path, fill, z = 7)
                out += DrawCommand.StrokePath(path, stroke, strokeColor, z = 8)
            }
            NodeShape.Diamond -> {
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                val path = PathCmd(listOf(
                    PathOp.MoveTo(Point(cx, rect.top)),
                    PathOp.LineTo(Point(rect.right, cy)),
                    PathOp.LineTo(Point(cx, rect.bottom)),
                    PathOp.LineTo(Point(rect.left, cy)),
                    PathOp.Close,
                ))
                out += DrawCommand.FillPath(path, fill, z = 7)
                out += DrawCommand.StrokePath(path, stroke, strokeColor, z = 8)
            }
            else -> {
                val corner = if (node.shape == NodeShape.RoundedBox) 10f else 4f
                out += DrawCommand.FillRect(rect, fill, corner = corner, z = 7)
                out += DrawCommand.StrokeRect(rect, stroke, strokeColor, corner = corner, z = 8)
            }
        }
        out += DrawCommand.DrawText(
            text = labelOf(node.label),
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
            font = fontOf(node),
            color = nodeHtmlColor(node) ?: node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF111827.toInt()),
            maxWidth = rect.size.width - 16f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 9,
        )
        (node.payload["dot.node.url"] ?: node.payload["dot.node.href"])?.takeIf { it.isNotBlank() }?.let { href ->
            out += DrawCommand.Hyperlink(href = href, rect = rect, z = 10)
        }
    }

    private fun fontOf(node: Node): FontSpec =
        FontSpec(
            family = node.payload["dot.node.html.fontname"]?.takeIf { it.isNotBlank() }
                ?: node.payload["dot.node.fontname"]?.takeIf { it.isNotBlank() }
                ?: nodeFont.family,
            sizeSp = node.payload["dot.node.html.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: node.payload["dot.node.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: nodeFont.sizeSp,
            weight = if (
                node.payload["dot.node.html.bold"].toBoolean()
                || node.payload["dot.node.style"]?.contains("bold", ignoreCase = true) == true
            ) 700 else nodeFont.weight,
            italic = node.payload["dot.node.html.italic"].toBoolean()
                || node.payload["dot.node.style"]?.contains("italic", ignoreCase = true) == true,
        )

    private fun nodeHtmlColor(node: Node): Color? =
        node.payload["dot.node.html.fontcolor"]?.let(::colorOf)

    private fun edgeLabelFont(edge: Edge, prefix: String): FontSpec =
        FontSpec(
            family = edge.payload["$prefix.fontname"]?.takeIf { it.isNotBlank() } ?: edgeFont.family,
            sizeSp = edge.payload["$prefix.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f) ?: edgeFont.sizeSp,
            weight = if (edge.payload["$prefix.bold"].toBoolean()) 700 else edgeFont.weight,
            italic = edge.payload["$prefix.italic"].toBoolean(),
        )

    private fun edgeLabelColor(edge: Edge, prefix: String): Color? =
        edge.payload["$prefix.fontcolor"]?.let(::colorOf)

    private fun fallbackRoute(from: NodeId, to: NodeId, nodes: Map<NodeId, Rect>): List<Point>? {
        val a = nodes[from] ?: return null
        val b = nodes[to] ?: return null
        return listOf(Point(a.right, (a.top + a.bottom) / 2f), Point(b.left, (b.top + b.bottom) / 2f))
    }

    private fun applyPortAnchors(edge: Edge, points: List<Point>, nodes: Map<NodeId, Rect>): List<Point> {
        if (points.size < 2) return points
        val out = points.toMutableList()
        nodes[edge.from]?.let { from ->
            out[0] = anchorFor(from, edge.payload["dot.edge.fromCompass"] ?: edge.payload["dot.edge.fromPort"]) ?: out[0]
        }
        nodes[edge.to]?.let { to ->
            out[out.lastIndex] = anchorFor(to, edge.payload["dot.edge.toCompass"] ?: edge.payload["dot.edge.toPort"]) ?: out.last()
        }
        return out
    }

    private fun anchorFor(rect: Rect, raw: String?): Point? {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        return when (raw?.lowercase()) {
            "n" -> Point(cx, rect.top)
            "ne" -> Point(rect.right, rect.top)
            "e" -> Point(rect.right, cy)
            "se" -> Point(rect.right, rect.bottom)
            "s" -> Point(cx, rect.bottom)
            "sw" -> Point(rect.left, rect.bottom)
            "w" -> Point(rect.left, cy)
            "nw" -> Point(rect.left, rect.top)
            "c", "_" -> Point(cx, cy)
            else -> null
        }
    }

    private fun arrowHeadOf(raw: String?, enabled: Boolean): ArrowHead {
        if (!enabled) return ArrowHead.None
        return when (raw?.lowercase()?.substringBefore(':')) {
            null, "", "normal", "vee" -> ArrowHead.Triangle
            "none" -> ArrowHead.None
            "empty" -> ArrowHead.OpenTriangle
            "diamond" -> ArrowHead.Diamond
            "odiamond" -> ArrowHead.OpenDiamond
            "dot" -> ArrowHead.Circle
            "odot" -> ArrowHead.OpenCircle
            "tee" -> ArrowHead.Bar
            "crow" -> ArrowHead.Cross
            else -> ArrowHead.Triangle
        }
    }

    private fun renderArrowHeads(
        edge: Edge,
        points: List<Point>,
        routeKind: RouteKind,
        color: Color,
        stroke: Stroke,
        out: MutableList<DrawCommand>,
    ) {
        if (points.size < 2) return
        val head = arrowHeadOf(edge.payload["dot.edge.arrowhead"], edge.arrow == ArrowEnds.ToOnly || edge.arrow == ArrowEnds.Both)
        val tail = arrowHeadOf(edge.payload["dot.edge.arrowtail"], edge.arrow == ArrowEnds.FromOnly || edge.arrow == ArrowEnds.Both)
        arrowCommand(head, tangentBeforeEnd(points, routeKind), points.last(), color, stroke)?.let { out += it }
        arrowCommand(tail, tangentAfterStart(points, routeKind), points.first(), color, stroke)?.let { out += it }
    }

    private fun arrowCommand(
        head: ArrowHead,
        direction: Point,
        tip: Point,
        color: Color,
        stroke: Stroke,
    ): DrawCommand? {
        if (head == ArrowHead.None) return null
        val len = sqrt(direction.x * direction.x + direction.y * direction.y)
        if (len <= 0.0001f) return null
        val ux = direction.x / len
        val uy = direction.y / len
        val size = 8f * stroke.width.coerceAtLeast(1f)
        val nx = -uy
        val ny = ux
        fun p(back: Float, side: Float) = Point(tip.x - ux * back + nx * side, tip.y - uy * back + ny * side)
        return when (head) {
            ArrowHead.Triangle -> DrawCommand.FillPath(
                PathCmd(listOf(PathOp.MoveTo(tip), PathOp.LineTo(p(size, -size / 2f)), PathOp.LineTo(p(size, size / 2f)), PathOp.Close)),
                color = color,
                z = 10,
            )
            ArrowHead.OpenTriangle -> DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(p(size, -size / 2f)), PathOp.LineTo(tip), PathOp.LineTo(p(size, size / 2f)))),
                stroke = stroke,
                color = color,
                z = 10,
            )
            ArrowHead.Bar -> DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(p(0f, -size / 2f)), PathOp.LineTo(p(0f, size / 2f)))),
                stroke = stroke,
                color = color,
                z = 10,
            )
            ArrowHead.Cross -> DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(p(size / 2f, -size / 2f)), PathOp.LineTo(p(-size / 2f, size / 2f)), PathOp.MoveTo(p(size / 2f, size / 2f)), PathOp.LineTo(p(-size / 2f, -size / 2f)))),
                stroke = stroke,
                color = color,
                z = 10,
            )
            ArrowHead.Diamond, ArrowHead.OpenDiamond -> {
                val path = PathCmd(listOf(PathOp.MoveTo(tip), PathOp.LineTo(p(size / 2f, -size / 3f)), PathOp.LineTo(p(size, 0f)), PathOp.LineTo(p(size / 2f, size / 3f)), PathOp.Close))
                if (head == ArrowHead.Diamond) DrawCommand.FillPath(path, color = color, z = 10) else DrawCommand.StrokePath(path, stroke = stroke, color = color, z = 10)
            }
            ArrowHead.Circle, ArrowHead.OpenCircle -> {
                val center = p(size / 2f, 0f)
                val path = circlePath(center, radius = size / 2f)
                if (head == ArrowHead.Circle) DrawCommand.FillPath(path, color = color, z = 10) else DrawCommand.StrokePath(path, stroke = stroke, color = color, z = 10)
            }
            ArrowHead.None -> null
        }
    }

    private fun tangentBeforeEnd(points: List<Point>, routeKind: RouteKind): Point =
        if (routeKind == RouteKind.Bezier && points.size >= 4) {
            val c2 = points[points.lastIndex - 1]
            val end = points.last()
            Point(end.x - c2.x, end.y - c2.y)
        } else {
            val prev = points[points.lastIndex - 1]
            val end = points.last()
            Point(end.x - prev.x, end.y - prev.y)
        }

    private fun tangentAfterStart(points: List<Point>, routeKind: RouteKind): Point =
        if (routeKind == RouteKind.Bezier && points.size >= 4) {
            val start = points.first()
            val c1 = points[1]
            Point(start.x - c1.x, start.y - c1.y)
        } else {
            val start = points.first()
            val next = points[1]
            Point(start.x - next.x, start.y - next.y)
        }

    private fun circlePath(center: Point, radius: Float): PathCmd {
        val k = radius * 0.5522848f
        return PathCmd(listOf(
            PathOp.MoveTo(Point(center.x + radius, center.y)),
            PathOp.CubicTo(Point(center.x + radius, center.y + k), Point(center.x + k, center.y + radius), Point(center.x, center.y + radius)),
            PathOp.CubicTo(Point(center.x - k, center.y + radius), Point(center.x - radius, center.y + k), Point(center.x - radius, center.y)),
            PathOp.CubicTo(Point(center.x - radius, center.y - k), Point(center.x - k, center.y - radius), Point(center.x, center.y - radius)),
            PathOp.CubicTo(Point(center.x + k, center.y - radius), Point(center.x + radius, center.y - k), Point(center.x + radius, center.y)),
            PathOp.Close,
        ))
    }

    private fun dotSpacing(ir: GraphIR, key: String, defaultPx: Float): Float {
        val inches = ir.styleHints.extras[key]?.toFloatOrNull() ?: return defaultPx
        return (inches * 72f).coerceIn(8f, 240f)
    }

    private fun colorOf(raw: String): Color? {
        val value = raw.trim().removeSurrounding("\"")
        val hex = value.removePrefix("#")
        if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return Color((0xFF000000 or hex.toLong(16)).toInt())
        }
        return when (value.lowercase()) {
            "black" -> Color.Black
            "white" -> Color.White
            "red" -> Color(0xFFE53935.toInt())
            "green" -> Color(0xFF43A047.toInt())
            "blue" -> Color(0xFF1E88E5.toInt())
            "yellow" -> Color(0xFFFDD835.toInt())
            "orange" -> Color(0xFFFF9800.toInt())
            "purple" -> Color(0xFF8E24AA.toInt())
            "gray", "grey" -> Color(0xFF9E9E9E.toInt())
            else -> null
        }
    }

    private fun pathOf(points: List<Point>, kind: RouteKind): PathCmd {
        val ops = ArrayList<PathOp>(points.size)
        ops += PathOp.MoveTo(points.first())
        if (kind == RouteKind.Bezier) {
            var i = 1
            while (i + 2 < points.size) {
                ops += PathOp.CubicTo(points[i], points[i + 1], points[i + 2])
                i += 3
            }
            while (i < points.size) {
                ops += PathOp.LineTo(points[i])
                i++
            }
        } else {
            points.drop(1).forEach { ops += PathOp.LineTo(it) }
        }
        return PathCmd(ops)
    }

    private fun ellipsePath(rect: Rect): PathCmd {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val rx = rect.size.width / 2f
        val ry = rect.size.height / 2f
        return PathCmd(listOf(
            PathOp.MoveTo(Point(cx, rect.top)),
            PathOp.CubicTo(Point(cx + rx * 0.552f, rect.top), Point(rect.right, cy - ry * 0.552f), Point(rect.right, cy)),
            PathOp.CubicTo(Point(rect.right, cy + ry * 0.552f), Point(cx + rx * 0.552f, rect.bottom), Point(cx, rect.bottom)),
            PathOp.CubicTo(Point(cx - rx * 0.552f, rect.bottom), Point(rect.left, cy + ry * 0.552f), Point(rect.left, cy)),
            PathOp.CubicTo(Point(rect.left, cy - ry * 0.552f), Point(cx - rx * 0.552f, rect.top), Point(cx, rect.top)),
            PathOp.Close,
        ))
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect(Point.Zero, Size.Zero)
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (rect in rects) {
            l = min(l, rect.left)
            t = min(t, rect.top)
            r = max(r, rect.right)
            b = max(b, rect.bottom)
        }
        return Rect.ltrb(l, t, r + 24f, b + 24f)
    }

    private fun union(rects: Collection<Rect>): Rect {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (rect in rects) {
            l = min(l, rect.left)
            t = min(t, rect.top)
            r = max(r, rect.right)
            b = max(b, rect.bottom)
        }
        return Rect.ltrb(l, t, r, b)
    }

    private fun labelOf(label: RichLabel?): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
            null -> ""
        }
}
