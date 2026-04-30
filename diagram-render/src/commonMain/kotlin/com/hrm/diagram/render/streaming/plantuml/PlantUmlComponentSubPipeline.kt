package com.hrm.diagram.render.streaming.plantuml

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
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlComponentParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlComponentSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlComponentParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(172f, 72f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(172f, 72f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)
    private val groupFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val iconFallbackFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        measureNodes(ir)
        val baseLaid = layout.layout(
            previousSnapshot.laidOut,
            ir,
            LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, baseLaid.nodePositions, clusterRects)
        val bounds = computeBounds(baseLaid.nodePositions.values + clusterRects.values)
        val laidOut = applyAnchoredNotes(
            ir,
            applyPortAnchors(
                ir,
                baseLaid.copy(clusterRects = clusterRects, bounds = bounds, seq = seq),
            ),
        )
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = render(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val label = labelTextOf(node)
            val kind = node.payload[PlantUmlComponentParser.KIND_KEY]
            when (kind) {
                "interface", "port" -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 120f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 20f).coerceAtLeast(24f),
                        height = (metrics.height + 20f).coerceAtLeast(24f),
                    )
                }
                "note" -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 180f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 30f).coerceAtLeast(120f),
                        height = (metrics.height + 24f).coerceAtLeast(54f),
                    )
                }
                else -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 180f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 36f).coerceAtLeast(132f),
                        height = (metrics.height + 28f).coerceAtLeast(56f),
                    )
                }
            }
        }
    }

    private fun computeClusterRect(
        cluster: Cluster,
        nodePositions: Map<NodeId, Rect>,
        out: LinkedHashMap<NodeId, Rect>,
    ): Rect? {
        val childRects = cluster.children.mapNotNull { nodePositions[it] }.toMutableList()
        val nestedRects = cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        childRects += nestedRects
        if (childRects.isEmpty()) return null
        val title = parseClusterLabel(cluster).second
        val titleMetrics = textMeasurer.measure(title.ifBlank { cluster.id.value }, groupFont, maxWidth = 220f)
        val left = childRects.minOf { it.left } - 20f
        val top = childRects.minOf { it.top } - (titleMetrics.height + 26f)
        val right = childRects.maxOf { it.right } + 20f
        val bottom = childRects.maxOf { it.bottom } + 18f
        val rect = Rect.ltrb(left, top, right, bottom)
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 400f, 240f)
        val minLeft = rects.minOf { it.left }.coerceAtMost(0f)
        val minTop = rects.minOf { it.top }.coerceAtMost(0f)
        val maxRight = rects.maxOf { it.right }
        val maxBottom = rects.maxOf { it.bottom }
        return Rect.ltrb(minLeft, minTop, maxRight + 20f, maxBottom + 20f)
    }

    private fun render(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laidOut.bounds
        out += DrawCommand.FillRect(Rect(Point(bounds.left, bounds.top), Size(bounds.size.width, bounds.size.height)), Color(0xFFFFFFFF.toInt()), z = 0)
        for (cluster in ir.clusters) drawCluster(cluster, laidOut.clusterRects, out)
        for (node in ir.nodes) drawNode(node, laidOut, out)
        for ((index, route) in laidOut.edgeRoutes.withIndex()) {
            val edge = ir.edges.getOrNull(index) ?: continue
            drawEdge(edge, route, out)
        }
        return out
    }

    private fun drawCluster(cluster: Cluster, clusterRects: Map<NodeId, Rect>, out: MutableList<DrawCommand>) {
        val rect = clusterRects[cluster.id] ?: return
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF5F5F5.toInt())
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF78909C.toInt())
        val stroke = Stroke(width = cluster.style.strokeWidth ?: 1.5f, dash = listOf(7f, 5f))
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 14f, z = 0)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 14f, z = 1)

        val (kind, title) = parseClusterLabel(cluster)
        val chipRect = Rect.ltrb(rect.left + 12f, rect.top + 10f, rect.left + 132f.coerceAtMost(rect.size.width - 24f), rect.top + 34f)
        out += DrawCommand.FillRect(rect = chipRect, color = Color(0xFFFFFFFF.toInt()), corner = 12f, z = 2)
        out += DrawCommand.StrokeRect(rect = chipRect, stroke = Stroke(width = 1f), color = strokeColor, corner = 12f, z = 3)
        out += DrawCommand.DrawText(
            text = "${kind.uppercase()}  ${title.ifBlank { cluster.id.value }}",
            origin = Point(chipRect.left + 10f, (chipRect.top + chipRect.bottom) / 2f),
            font = groupFont,
            color = strokeColor,
            maxWidth = chipRect.size.width - 20f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Middle,
            z = 4,
        )
        for (nested in cluster.nestedClusters) drawCluster(nested, clusterRects, out)
    }

    private fun drawNode(node: Node, laidOut: LaidOutDiagram, out: MutableList<DrawCommand>) {
        val rect = laidOut.nodePositions[node.id] ?: return
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE8EAF6.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF3949AB.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF1A237E.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.5f)
        val label = labelTextOf(node)
        val kind = node.payload[PlantUmlComponentParser.KIND_KEY]
        when (kind) {
            "interface", "port" -> {
                val w = minOf(rect.size.width, rect.size.height) / 2f
                out += DrawCommand.FillRect(rect = rect, color = fill, corner = w, z = 4)
                out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = w, z = 5)
                val labelOrigin = portLabelOrigin(node, rect, laidOut)
                val labelAnchorX = portLabelAnchorX(node, laidOut)
                val labelAnchorY = portLabelAnchorY(node, laidOut)
                out += DrawCommand.DrawText(
                    text = label,
                    origin = labelOrigin,
                    font = if (kind == "port") iconFallbackFont else labelFont,
                    color = textColor,
                    maxWidth = 120f,
                    anchorX = labelAnchorX,
                    anchorY = labelAnchorY,
                    z = 6,
                )
                if (kind == "interface") {
                    out += DrawCommand.DrawText(
                        text = "I",
                        origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                        font = iconFallbackFont,
                        color = strokeColor,
                        anchorX = TextAnchorX.Center,
                        anchorY = TextAnchorY.Middle,
                        z = 6,
                    )
                }
            }
            "note" -> {
                drawNote(rect, fill, strokeColor, stroke, out)
                out += DrawCommand.DrawText(
                    text = label,
                    origin = Point(rect.left + 12f, rect.top + 10f),
                    font = labelFont,
                    color = textColor,
                    maxWidth = rect.size.width - 24f,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    z = 7,
                )
            }
            else -> {
                val corner = when (node.shape) {
                    NodeShape.Component -> 10f
                    else -> 8f
                }
                out += DrawCommand.FillRect(rect = rect, color = fill, corner = corner, z = 4)
                out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = corner, z = 5)
                if (kind == "component") {
                    val iconRect = Rect.ltrb(rect.right - 22f, rect.top + 8f, rect.right - 8f, rect.top + 22f)
                    out += DrawCommand.StrokeRect(rect = iconRect, stroke = Stroke(width = 1f), color = strokeColor, corner = 2f, z = 6)
                    out += DrawCommand.StrokePath(
                        path = PathCmd(
                            listOf(
                                PathOp.MoveTo(Point(iconRect.left + 3f, iconRect.top + 5f)),
                                PathOp.LineTo(Point(iconRect.right - 3f, iconRect.top + 5f)),
                                PathOp.MoveTo(Point(iconRect.left + 3f, iconRect.top + 9f)),
                                PathOp.LineTo(Point(iconRect.right - 3f, iconRect.top + 9f)),
                            ),
                        ),
                        stroke = Stroke(width = 1f),
                        color = strokeColor,
                        z = 6,
                    )
                }
                out += DrawCommand.DrawText(
                    text = label,
                    origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                    font = labelFont,
                    color = textColor,
                    maxWidth = rect.size.width - 20f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 7,
                )
                if (kind == "queue") {
                    out += DrawCommand.DrawText(
                        text = "Q",
                        origin = Point(rect.left + 12f, rect.top + 10f),
                        font = iconFallbackFont,
                        color = strokeColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 8,
                    )
                }
            }
        }
    }

    private fun applyPortAnchors(ir: GraphIR, laidOut: LaidOutDiagram): LaidOutDiagram {
        val portNodes = ir.nodes.filter { it.payload[PlantUmlComponentParser.KIND_KEY] == "port" }
        if (portNodes.isEmpty()) return laidOut
        val nodePositions = LinkedHashMap(laidOut.nodePositions)
        val edgeRoutes = laidOut.edgeRoutes.toMutableList()
        val portsByHost = LinkedHashMap<NodeId, MutableList<Node>>()
        for (port in portNodes) {
            val host = port.payload[PlantUmlComponentParser.PORT_HOST_KEY]?.let(::NodeId) ?: continue
            if (host !in nodePositions) continue
            portsByHost.getOrPut(host) { ArrayList() } += port
        }
        for ((hostId, ports) in portsByHost) {
            val hostRect = nodePositions[hostId] ?: continue
            val grouped = ports.groupBy { resolvedPortSide(it, ir) }
            for ((side, sidePorts) in grouped) {
                val ordered = sidePorts.sortedBy { portSortKey(it, ir, side) }
                ordered.forEachIndexed { index, port ->
                    val size = nodePositions[port.id]?.size ?: nodeSizes[port.id] ?: Size(24f, 24f)
                    nodePositions[port.id] = anchoredPortRect(hostRect, size, side, index, ordered.size)
                }
            }
        }
        ir.edges.forEachIndexed { index, edge ->
            val route = routedEdgeForPorts(edge, ir, nodePositions) ?: return@forEachIndexed
            if (index < edgeRoutes.size) edgeRoutes[index] = route else edgeRoutes += route
        }
        val bounds = computeBounds(nodePositions.values + laidOut.clusterRects.values)
        return laidOut.copy(nodePositions = nodePositions, edgeRoutes = edgeRoutes, bounds = bounds)
    }

    private fun applyAnchoredNotes(ir: GraphIR, laidOut: LaidOutDiagram): LaidOutDiagram {
        val noteNodes = ir.nodes.filter { it.payload[PlantUmlComponentParser.KIND_KEY] == "note" }
        if (noteNodes.isEmpty()) return laidOut
        val nodePositions = LinkedHashMap(laidOut.nodePositions)
        val edgeRoutes = laidOut.edgeRoutes.toMutableList()
        for (note in noteNodes) {
            val target = note.payload[PlantUmlComponentParser.NOTE_TARGET_KEY]?.let(::NodeId) ?: continue
            val placement = note.payload[PlantUmlComponentParser.NOTE_PLACEMENT_KEY].orEmpty()
            val targetRect = nodePositions[target] ?: continue
            val noteRect = nodePositions[note.id] ?: continue
            val anchored = anchoredNoteRect(noteRect.size, targetRect, placement)
            nodePositions[note.id] = anchored
            val edgeIndex = ir.edges.indexOfFirst { it.from == note.id && it.to == target }
            if (edgeIndex >= 0) {
                val route = EdgeRoute(
                    from = note.id,
                    to = target,
                    points = anchoredNoteRoute(anchored, targetRect, placement),
                    kind = RouteKind.Polyline,
                )
                if (edgeIndex < edgeRoutes.size) edgeRoutes[edgeIndex] = route else edgeRoutes += route
            }
        }
        val bounds = computeBounds(nodePositions.values + laidOut.clusterRects.values)
        return laidOut.copy(nodePositions = nodePositions, edgeRoutes = edgeRoutes, bounds = bounds)
    }

    private fun anchoredNoteRect(size: Size, targetRect: Rect, placement: String): Rect {
        val gap = 18f
        return when (placement.lowercase()) {
            "left" -> Rect(Point(targetRect.left - size.width - gap, targetRect.top + (targetRect.size.height - size.height) / 2f), size)
            "top" -> Rect(Point(targetRect.left + (targetRect.size.width - size.width) / 2f, targetRect.top - size.height - gap), size)
            "bottom" -> Rect(Point(targetRect.left + (targetRect.size.width - size.width) / 2f, targetRect.bottom + gap), size)
            else -> Rect(Point(targetRect.right + gap, targetRect.top + (targetRect.size.height - size.height) / 2f), size)
        }
    }

    private fun anchoredNoteRoute(noteRect: Rect, targetRect: Rect, placement: String): List<Point> =
        when (placement.lowercase()) {
            "left" -> listOf(
                Point(noteRect.right, (noteRect.top + noteRect.bottom) / 2f),
                Point(targetRect.left, (targetRect.top + targetRect.bottom) / 2f),
            )
            "top" -> listOf(
                Point((noteRect.left + noteRect.right) / 2f, noteRect.bottom),
                Point((targetRect.left + targetRect.right) / 2f, targetRect.top),
            )
            "bottom" -> listOf(
                Point((noteRect.left + noteRect.right) / 2f, noteRect.top),
                Point((targetRect.left + targetRect.right) / 2f, targetRect.bottom),
            )
            else -> listOf(
                Point(noteRect.left, (noteRect.top + noteRect.bottom) / 2f),
                Point(targetRect.right, (targetRect.top + targetRect.bottom) / 2f),
            )
        }

    private fun anchoredPortRect(hostRect: Rect, size: Size, side: PortSide, index: Int, total: Int): Rect {
        val center = portCenter(hostRect, side, index, total)
        return Rect(
            origin = Point(center.x - size.width / 2f, center.y - size.height / 2f),
            size = size,
        )
    }

    private fun portCenter(hostRect: Rect, side: PortSide, index: Int, total: Int): Point {
        val t = (index + 1f) / (total + 1f)
        return when (side) {
            PortSide.Left -> Point(hostRect.left, hostRect.top + hostRect.size.height * t)
            PortSide.Right -> Point(hostRect.right, hostRect.top + hostRect.size.height * t)
            PortSide.Top -> Point(hostRect.left + hostRect.size.width * t, hostRect.top)
            PortSide.Bottom -> Point(hostRect.left + hostRect.size.width * t, hostRect.bottom)
        }
    }

    private fun resolvedPortSide(node: Node, ir: GraphIR): PortSide {
        val explicit = node.payload[PlantUmlComponentParser.PORT_DIR_KEY]
        val direction = ir.styleHints.direction ?: Direction.LR
        if (explicit == "in") return inputSide(direction)
        if (explicit == "out") return outputSide(direction)
        val incoming = ir.edges.count { it.to == node.id }
        val outgoing = ir.edges.count { it.from == node.id }
        return when {
            outgoing > incoming -> outputSide(direction)
            incoming > outgoing -> inputSide(direction)
            else -> inputSide(direction)
        }
    }

    private fun inputSide(direction: Direction): PortSide = when (direction) {
        Direction.LR -> PortSide.Left
        Direction.RL -> PortSide.Right
        Direction.TB -> PortSide.Top
        Direction.BT -> PortSide.Bottom
        Direction.RADIAL -> PortSide.Left
    }

    private fun outputSide(direction: Direction): PortSide = when (direction) {
        Direction.LR -> PortSide.Right
        Direction.RL -> PortSide.Left
        Direction.TB -> PortSide.Bottom
        Direction.BT -> PortSide.Top
        Direction.RADIAL -> PortSide.Right
    }

    private fun portSortKey(node: Node, ir: GraphIR, side: PortSide): Int {
        val degree = ir.edges.count { it.from == node.id || it.to == node.id }
        val bias = when (side) {
            PortSide.Left, PortSide.Top -> 0
            PortSide.Right, PortSide.Bottom -> 1000
        }
        return bias + degree
    }

    private fun routedEdgeForPorts(edge: com.hrm.diagram.core.ir.Edge, ir: GraphIR, nodePositions: Map<NodeId, Rect>): EdgeRoute? {
        val fromNode = ir.nodes.firstOrNull { it.id == edge.from } ?: return null
        val toNode = ir.nodes.firstOrNull { it.id == edge.to } ?: return null
        val fromRect = nodePositions[edge.from] ?: return null
        val toRect = nodePositions[edge.to] ?: return null
        val fromIsPort = fromNode.payload[PlantUmlComponentParser.KIND_KEY] == "port"
        val toIsPort = toNode.payload[PlantUmlComponentParser.KIND_KEY] == "port"
        if (!fromIsPort && !toIsPort) return null
        val fromPoint = if (fromIsPort) centerOf(fromRect) else boundaryPoint(fromRect, centerOf(toRect))
        val toPoint = if (toIsPort) centerOf(toRect) else boundaryPoint(toRect, centerOf(fromRect))
        return EdgeRoute(
            from = edge.from,
            to = edge.to,
            points = orthogonalRoute(fromPoint, toPoint),
            kind = RouteKind.Polyline,
        )
    }

    private fun orthogonalRoute(from: Point, to: Point): List<Point> {
        if (kotlin.math.abs(from.x - to.x) < 1f || kotlin.math.abs(from.y - to.y) < 1f) {
            return listOf(from, to)
        }
        val midX = (from.x + to.x) / 2f
        return listOf(from, Point(midX, from.y), Point(midX, to.y), to)
    }

    private fun centerOf(rect: Rect): Point = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

    private fun boundaryPoint(rect: Rect, toward: Point): Point {
        val center = centerOf(rect)
        val dx = toward.x - center.x
        val dy = toward.y - center.y
        val halfW = rect.size.width / 2f
        val halfH = rect.size.height / 2f
        if (dx == 0f && dy == 0f) return center
        val scaleX = if (dx != 0f) halfW / kotlin.math.abs(dx) else Float.POSITIVE_INFINITY
        val scaleY = if (dy != 0f) halfH / kotlin.math.abs(dy) else Float.POSITIVE_INFINITY
        val scale = minOf(scaleX, scaleY)
        return Point(center.x + dx * scale, center.y + dy * scale)
    }

    private fun portLabelOrigin(node: Node, rect: Rect, laidOut: LaidOutDiagram): Point {
        val side = portSideForNode(node, laidOut) ?: return Point((rect.left + rect.right) / 2f, rect.bottom + 12f)
        return when (side) {
            PortSide.Left -> Point(rect.left - 8f, (rect.top + rect.bottom) / 2f)
            PortSide.Right -> Point(rect.right + 8f, (rect.top + rect.bottom) / 2f)
            PortSide.Top -> Point((rect.left + rect.right) / 2f, rect.top - 8f)
            PortSide.Bottom -> Point((rect.left + rect.right) / 2f, rect.bottom + 8f)
        }
    }

    private fun portLabelAnchorX(node: Node, laidOut: LaidOutDiagram): TextAnchorX =
        when (portSideForNode(node, laidOut)) {
            PortSide.Left -> TextAnchorX.End
            PortSide.Right -> TextAnchorX.Start
            else -> TextAnchorX.Center
        }

    private fun portLabelAnchorY(node: Node, laidOut: LaidOutDiagram): TextAnchorY =
        when (portSideForNode(node, laidOut)) {
            PortSide.Top -> TextAnchorY.Bottom
            PortSide.Bottom -> TextAnchorY.Top
            else -> TextAnchorY.Middle
        }

    private fun portSideForNode(node: Node, laidOut: LaidOutDiagram): PortSide? {
        val hostId = node.payload[PlantUmlComponentParser.PORT_HOST_KEY]?.let(::NodeId) ?: return null
        val rect = laidOut.nodePositions[node.id] ?: return null
        val hostRect = laidOut.nodePositions[hostId] ?: return null
        val center = centerOf(rect)
        val dxLeft = kotlin.math.abs(center.x - hostRect.left)
        val dxRight = kotlin.math.abs(center.x - hostRect.right)
        val dyTop = kotlin.math.abs(center.y - hostRect.top)
        val dyBottom = kotlin.math.abs(center.y - hostRect.bottom)
        val min = minOf(dxLeft, dxRight, dyTop, dyBottom)
        return when (min) {
            dxLeft -> PortSide.Left
            dxRight -> PortSide.Right
            dyTop -> PortSide.Top
            else -> PortSide.Bottom
        }
    }

    private fun drawEdge(edge: com.hrm.diagram.core.ir.Edge, route: com.hrm.diagram.layout.EdgeRoute, out: MutableList<DrawCommand>) {
        val pts = route.points
        if (pts.size < 2) return
        val ops = ArrayList<PathOp>(pts.size)
        ops += PathOp.MoveTo(pts[0])
        when (route.kind) {
            RouteKind.Bezier -> {
                var i = 1
                while (i + 2 < pts.size) {
                    ops += PathOp.CubicTo(pts[i], pts[i + 1], pts[i + 2])
                    i += 3
                }
                if (i < pts.size) ops += PathOp.LineTo(pts.last())
            }
            else -> for (k in 1 until pts.size) ops += PathOp.LineTo(pts[k])
        }
        val edgeColor = edge.style.color?.let { Color(it.argb) } ?: Color(0xFF546E7A.toInt())
        val stroke = Stroke(width = edge.style.width ?: 1.5f, dash = edge.style.dash)
        out += DrawCommand.StrokePath(path = PathCmd(ops), stroke = stroke, color = edgeColor, z = 2)
        val tail = pts[pts.size - 2]
        val head = pts.last()
        val startTail = pts[1]
        val startHead = pts[0]
        when (edge.arrow) {
            com.hrm.diagram.core.ir.ArrowEnds.None -> Unit
            com.hrm.diagram.core.ir.ArrowEnds.ToOnly -> out += arrowHead(tail, head, edgeColor)
            com.hrm.diagram.core.ir.ArrowEnds.FromOnly -> out += arrowHead(startTail, startHead, edgeColor)
            com.hrm.diagram.core.ir.ArrowEnds.Both -> {
                out += arrowHead(tail, head, edgeColor)
                out += arrowHead(startTail, startHead, edgeColor)
            }
        }
        val text = (edge.label as? RichLabel.Plain)?.text ?: return
        if (text.isEmpty()) return
        val mid = pts[pts.size / 2]
        out += DrawCommand.DrawText(
            text = text,
            origin = Point(mid.x, mid.y - 4f),
            font = edgeLabelFont,
            color = Color(0xFF263238.toInt()),
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 5,
        )
    }

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f),
            color = color,
            z = 4,
        )
        val ux = dx / len
        val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size
        val baseY = to.y - uy * size
        val nx = -uy
        val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = 1.5f),
            color = color,
            z = 4,
        )
    }

    private fun drawNote(rect: Rect, fill: Color, strokeColor: Color, stroke: Stroke, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 8f, z = 4)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 8f, z = 5)
        val fold = 14f
        out += DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(rect.right - fold, rect.top)),
                    PathOp.LineTo(Point(rect.right - fold, rect.top + fold)),
                    PathOp.LineTo(Point(rect.right, rect.top + fold)),
                ),
            ),
            stroke = Stroke(width = 1.2f),
            color = strokeColor,
            z = 6,
        )
    }

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Html -> label.html.takeIf { it.isNotEmpty() } ?: node.id.value
        }

    private fun parseClusterLabel(cluster: Cluster): Pair<String, String> {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: return "package" to cluster.id.value
        val parts = text.split('\n', limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "package" to text
    }

    override fun dispose() {
        nodeSizes.clear()
    }

    private enum class PortSide { Left, Right, Top, Bottom }
}
