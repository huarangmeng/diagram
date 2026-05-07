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
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlDeploymentParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class PlantUmlDeploymentSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlDeploymentParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(176f, 76f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(176f, 76f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)
    private val groupFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

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
        val laidOutWithClusters = baseLaid.copy(
            clusterRects = clusterRects,
            bounds = computeBounds(baseLaid.nodePositions.values + clusterRects.values),
            seq = seq,
        )
        val laidOut = applyAnchoredNotes(ir, laidOutWithClusters)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = render(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val label = labelTextOf(node)
            val kind = node.payload[PlantUmlDeploymentParser.KIND_KEY]
            val metrics = textMeasurer.measure(label, labelFont, maxWidth = 180f)
            nodeSizes[node.id] = when (kind) {
                "actor" -> Size((metrics.width + 36f).coerceAtLeast(120f), (metrics.height + 68f).coerceAtLeast(92f))
                "database" -> Size((metrics.width + 42f).coerceAtLeast(136f), (metrics.height + 34f).coerceAtLeast(64f))
                "storage" -> Size((metrics.width + 42f).coerceAtLeast(136f), (metrics.height + 34f).coerceAtLeast(64f))
                "cloud" -> Size((metrics.width + 48f).coerceAtLeast(152f), (metrics.height + 34f).coerceAtLeast(72f))
                "artifact", "queue", "note" -> Size((metrics.width + 34f).coerceAtLeast(124f), (metrics.height + 28f).coerceAtLeast(56f))
                else -> Size((metrics.width + 36f).coerceAtLeast(140f), (metrics.height + 28f).coerceAtLeast(60f))
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
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF1F8E9.toInt())
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF558B2F.toInt())
        val stroke = Stroke(width = cluster.style.strokeWidth ?: 1.5f, dash = listOf(7f, 5f))
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 14f, z = 0)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 14f, z = 1)
        val (kind, title) = parseClusterLabel(cluster)
        val chipWidth = min(160f, rect.size.width - 24f)
        val chipRect = Rect.ltrb(rect.left + 12f, rect.top + 10f, rect.left + chipWidth, rect.top + 34f)
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
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE8F5E9.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF2E7D32.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF1B5E20.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.5f)
        when (node.payload[PlantUmlDeploymentParser.KIND_KEY]) {
            "actor" -> {
                drawActor(node, rect, out)
                return
            }
            "note" -> {
                drawNote(node, rect, out)
                return
            }
        }
        when (node.shape) {
            NodeShape.Cylinder -> drawCylinder(rect, fill, strokeColor, stroke, out)
            NodeShape.Cloud -> drawCloud(rect, fill, strokeColor, stroke, out)
            NodeShape.Note -> drawArtifact(rect, fill, strokeColor, stroke, out)
            NodeShape.Package -> drawPackage(rect, fill, strokeColor, stroke, out)
            else -> {
                out += DrawCommand.FillRect(rect = rect, color = fill, corner = 10f, z = 4)
                out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 10f, z = 5)
            }
        }
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 18f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 7,
        )
    }

    private fun drawPackage(rect: Rect, fill: Color, strokeColor: Color, stroke: Stroke, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 10f, z = 4)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 10f, z = 5)
        val tab = Rect.ltrb(rect.left + 10f, rect.top - 8f, rect.left + max(46f, rect.size.width * 0.34f), rect.top + 10f)
        out += DrawCommand.FillRect(rect = tab, color = fill, corner = 6f, z = 5)
        out += DrawCommand.StrokeRect(rect = tab, stroke = Stroke(width = 1.2f), color = strokeColor, corner = 6f, z = 6)
    }

    private fun drawArtifact(rect: Rect, fill: Color, strokeColor: Color, stroke: Stroke, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 8f, z = 4)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 8f, z = 5)
        val fold = 14f
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(Point(rect.right - fold, rect.top)),
                PathOp.LineTo(Point(rect.right - fold, rect.top + fold)),
                PathOp.LineTo(Point(rect.right, rect.top + fold)),
            ),
        )
        out += DrawCommand.StrokePath(path = path, stroke = Stroke(width = 1.2f), color = strokeColor, z = 6)
    }

    private fun drawCylinder(rect: Rect, fill: Color, strokeColor: Color, stroke: Stroke, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 10f, z = 4)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 10f, z = 5)
        out += DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(rect.left + 12f, rect.top + 10f)),
                    PathOp.CubicTo(
                        Point(rect.left + 30f, rect.top),
                        Point(rect.right - 30f, rect.top),
                        Point(rect.right - 12f, rect.top + 10f),
                    ),
                ),
            ),
            stroke = Stroke(width = 1f),
            color = strokeColor,
            z = 6,
        )
    }

    private fun drawCloud(rect: Rect, fill: Color, strokeColor: Color, stroke: Stroke, out: MutableList<DrawCommand>) {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val w = rect.size.width
        val h = rect.size.height
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(Point(l + w * 0.18f, b - h * 0.18f)),
                PathOp.CubicTo(Point(l - w * 0.02f, b - h * 0.26f), Point(l + w * 0.02f, t + h * 0.38f), Point(l + w * 0.20f, t + h * 0.38f)),
                PathOp.CubicTo(Point(l + w * 0.20f, t + h * 0.08f), Point(l + w * 0.42f, t + h * 0.02f), Point(l + w * 0.54f, t + h * 0.20f)),
                PathOp.CubicTo(Point(l + w * 0.64f, t - h * 0.02f), Point(r - w * 0.10f, t + h * 0.06f), Point(r - w * 0.12f, t + h * 0.32f)),
                PathOp.CubicTo(Point(r + w * 0.02f, t + h * 0.36f), Point(r + w * 0.02f, b - h * 0.12f), Point(r - w * 0.12f, b - h * 0.12f)),
                PathOp.CubicTo(Point(r - w * 0.18f, b + h * 0.04f), Point(l + w * 0.34f, b + h * 0.04f), Point(l + w * 0.18f, b - h * 0.18f)),
                PathOp.Close,
            ),
        )
        out += DrawCommand.FillPath(path = path, color = fill, z = 4)
        out += DrawCommand.StrokePath(path = path, stroke = stroke, color = strokeColor, z = 5)
    }

    private fun applyAnchoredNotes(ir: GraphIR, laidOut: LaidOutDiagram): LaidOutDiagram {
        val notes = ir.nodes.filter { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "note" && it.payload.containsKey(PlantUmlDeploymentParser.NOTE_TARGET_KEY) }
        if (notes.isEmpty()) return laidOut
        val nodePositions = LinkedHashMap(laidOut.nodePositions)
        val edgeRoutes = laidOut.edgeRoutes.toMutableList()
        for (note in notes) {
            val target = note.payload[PlantUmlDeploymentParser.NOTE_TARGET_KEY]?.let(::NodeId) ?: continue
            val placement = note.payload[PlantUmlDeploymentParser.NOTE_PLACEMENT_KEY].orEmpty()
            val targetRect = nodePositions[target] ?: continue
            val current = nodePositions[note.id] ?: continue
            val anchored = anchoredNoteRect(current.size, targetRect, placement)
            nodePositions[note.id] = anchored
            val edgeIndex = ir.edges.indexOfFirst { it.from == note.id && it.to == target }
            if (edgeIndex >= 0) {
                val route = com.hrm.diagram.layout.EdgeRoute(
                    from = note.id,
                    to = target,
                    points = anchoredNoteRoute(anchored, targetRect, placement),
                    kind = RouteKind.Polyline,
                )
                if (edgeIndex < edgeRoutes.size) edgeRoutes[edgeIndex] = route else edgeRoutes += route
            }
        }
        return laidOut.copy(
            nodePositions = nodePositions,
            edgeRoutes = edgeRoutes,
            bounds = computeBounds(nodePositions.values + laidOut.clusterRects.values),
        )
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

    private fun anchoredNoteRoute(noteRect: Rect, targetRect: Rect, placement: String): List<Point> {
        return when (placement.lowercase()) {
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
    }

    private fun drawEdge(edge: com.hrm.diagram.core.ir.Edge, route: com.hrm.diagram.layout.EdgeRoute, out: MutableList<DrawCommand>) {
        val pts = route.points
        if (pts.size < 2) return
        val ops = ArrayList<PathOp>()
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
        val text = (edge.label as? RichLabel.Plain)?.text.orEmpty()
        if (text.isNotEmpty()) {
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
    }

    private fun drawActor(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF455A64.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF263238.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.5f)
        val cx = (rect.left + rect.right) / 2f
        val top = rect.top + 8f
        val headRadius = 11f
        val headRect = Rect.ltrb(cx - headRadius, top, cx + headRadius, top + headRadius * 2f)
        out += DrawCommand.StrokeRect(rect = headRect, stroke = stroke, color = strokeColor, corner = headRadius, z = 5)
        val bodyTop = headRect.bottom
        val bodyBottom = rect.bottom - 28f
        out += DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(cx, bodyTop)),
                    PathOp.LineTo(Point(cx, bodyTop + 30f)),
                    PathOp.MoveTo(Point(cx - 16f, bodyTop + 12f)),
                    PathOp.LineTo(Point(cx + 16f, bodyTop + 12f)),
                    PathOp.MoveTo(Point(cx, bodyTop + 30f)),
                    PathOp.LineTo(Point(cx - 14f, bodyBottom)),
                    PathOp.MoveTo(Point(cx, bodyTop + 30f)),
                    PathOp.LineTo(Point(cx + 14f, bodyBottom)),
                ),
            ),
            stroke = stroke,
            color = strokeColor,
            z = 5,
        )
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point(cx, rect.bottom - 10f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 12f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 6,
        )
    }

    private fun drawNote(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFFFF8E1.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFFFFA000.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF5D4037.toInt())
        drawArtifact(rect, fill, strokeColor, Stroke(width = node.style.strokeWidth ?: 1.25f), out)
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point(rect.left + 12f, rect.top + 10f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 24f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Top,
            z = 7,
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

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Html -> label.html.takeIf { it.isNotEmpty() } ?: node.id.value
        }

    private fun parseClusterLabel(cluster: Cluster): Pair<String, String> {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: return "node" to cluster.id.value
        val parts = text.split('\n', limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "node" to text
    }
}
