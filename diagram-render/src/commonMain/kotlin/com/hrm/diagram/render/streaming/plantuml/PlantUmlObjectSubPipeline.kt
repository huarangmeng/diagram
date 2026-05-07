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
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlObjectParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlObjectSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlObjectParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(176f, 92f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(176f, 92f) },
    )
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)
    private val memberFont = FontSpec(family = "monospace", sizeSp = 11f)
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
        val bounds = computeBounds(baseLaid.nodePositions.values + clusterRects.values)
        val laidOut = applyAnchoredNotes(
            ir,
            baseLaid.copy(clusterRects = clusterRects, bounds = bounds, seq = seq),
        )
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
            val title = titleOf(node)
            val members = membersOf(node)
            val isNote = node.payload[PlantUmlObjectParser.KIND_KEY] == "note"
            val titleMetrics = textMeasurer.measure(title, if (isNote) edgeLabelFont else titleFont, maxWidth = 220f)
            val memberMetrics = if (members.isEmpty()) null else textMeasurer.measure(members.joinToString("\n"), memberFont, maxWidth = 220f)
            val width = maxOf(
                titleMetrics.width + 28f,
                (memberMetrics?.width ?: 0f) + 28f,
                if (isNote) 120f else 124f,
            )
            val height = titleMetrics.height + 22f + if (memberMetrics != null) memberMetrics.height + 18f else 0f
            nodeSizes[node.id] = Size(width, height.coerceAtLeast(if (isNote) 50f else 56f))
        }
    }

    private fun computeClusterRect(
        cluster: Cluster,
        nodePositions: Map<NodeId, Rect>,
        out: LinkedHashMap<NodeId, Rect>,
    ): Rect? {
        val childRects = cluster.children.mapNotNull { nodePositions[it] }.toMutableList()
        childRects += cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        if (childRects.isEmpty()) return null
        val (_, title) = parseClusterLabel(cluster)
        val titleMetrics = textMeasurer.measure(title.ifBlank { cluster.id.value }, titleFont, maxWidth = 220f)
        val rect = Rect.ltrb(
            childRects.minOf { it.left } - 20f,
            childRects.minOf { it.top } - (titleMetrics.height + 24f),
            childRects.maxOf { it.right } + 20f,
            childRects.maxOf { it.bottom } + 18f,
        )
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 400f, 240f)
        return Rect.ltrb(
            rects.minOf { it.left }.coerceAtMost(0f),
            rects.minOf { it.top }.coerceAtMost(0f),
            rects.maxOf { it.right } + 20f,
            rects.maxOf { it.bottom } + 20f,
        )
    }

    private fun render(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        for (cluster in ir.clusters) drawCluster(cluster, laidOut.clusterRects, out)
        for (node in ir.nodes) {
            val rect = laidOut.nodePositions[node.id] ?: continue
            drawNode(node, rect, out)
        }
        for ((index, route) in laidOut.edgeRoutes.withIndex()) {
            val edge = ir.edges.getOrNull(index) ?: continue
            drawEdge(edge, route, out)
        }
        return out
    }

    private fun drawCluster(cluster: Cluster, clusterRects: Map<NodeId, Rect>, out: MutableList<DrawCommand>) {
        val rect = clusterRects[cluster.id] ?: return
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF5F5F5.toInt())
        val stroke = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF90A4AE.toInt())
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 12f, z = 0)
        out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = cluster.style.strokeWidth ?: 1.5f, dash = listOf(7f, 5f)), color = stroke, corner = 12f, z = 1)
        val (kind, title) = parseClusterLabel(cluster)
        out += DrawCommand.DrawText(
            text = "${kind.uppercase()}  ${title.ifBlank { cluster.id.value }}",
            origin = Point(rect.left + 12f, rect.top + 8f),
            font = titleFont,
            color = stroke,
            maxWidth = rect.size.width - 24f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Top,
            z = 2,
        )
        for (nested in cluster.nestedClusters) drawCluster(nested, clusterRects, out)
    }

    private fun drawNode(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFF3E5F5.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF6A1B9A.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF4A148C.toInt())
        val members = membersOf(node)
        val title = titleOf(node)
        val isNote = node.payload[PlantUmlObjectParser.KIND_KEY] == "note"
        val titleMetrics = textMeasurer.measure(title, if (isNote) edgeLabelFont else titleFont, maxWidth = rect.size.width - 20f)
        val headerBottom = rect.top + titleMetrics.height + 18f

        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 8f, z = 2)
        out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = node.style.strokeWidth ?: 1.5f), color = strokeColor, corner = 8f, z = 3)
        out += DrawCommand.DrawText(
            text = title,
            origin = Point((rect.left + rect.right) / 2f, rect.top + 9f),
            font = if (isNote) edgeLabelFont else titleFont,
            color = textColor,
            maxWidth = rect.size.width - 20f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Top,
            z = 4,
        )
        if (members.isNotEmpty() && !isNote) {
            out += DrawCommand.StrokePath(
                path = PathCmd(
                    listOf(
                        PathOp.MoveTo(Point(rect.left, headerBottom)),
                        PathOp.LineTo(Point(rect.right, headerBottom)),
                    ),
                ),
                stroke = Stroke(width = 1f),
                color = strokeColor,
                z = 4,
            )
            var y = headerBottom + 8f
            for (member in members) {
                out += DrawCommand.DrawText(
                    text = member,
                    origin = Point(rect.left + 10f, y),
                    font = memberFont,
                    color = textColor,
                    maxWidth = rect.size.width - 20f,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    z = 4,
                )
                val metrics = textMeasurer.measure(member, memberFont, maxWidth = rect.size.width - 20f)
                y += metrics.height + 2f
            }
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
        val color = edge.style.color?.let { Color(it.argb) } ?: Color(0xFF546E7A.toInt())
        out += DrawCommand.StrokePath(
            path = PathCmd(ops),
            stroke = Stroke(width = edge.style.width ?: 1.5f, dash = edge.style.dash),
            color = color,
            z = 1,
        )
        when (edge.arrow) {
            com.hrm.diagram.core.ir.ArrowEnds.None -> Unit
            com.hrm.diagram.core.ir.ArrowEnds.ToOnly -> out += arrowHead(pts[pts.size - 2], pts.last(), color)
            com.hrm.diagram.core.ir.ArrowEnds.FromOnly -> out += arrowHead(pts[1], pts[0], color)
            com.hrm.diagram.core.ir.ArrowEnds.Both -> {
                out += arrowHead(pts[pts.size - 2], pts.last(), color)
                out += arrowHead(pts[1], pts[0], color)
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
                z = 3,
            )
        }
    }

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f),
            color = color,
            z = 2,
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
            z = 2,
        )
    }

    private fun titleOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.ifEmpty { node.id.value }
            is RichLabel.Markdown -> label.source.ifEmpty { node.id.value }
            is RichLabel.Html -> label.html.ifEmpty { node.id.value }
        }

    private fun membersOf(node: Node): List<String> =
        node.payload[PlantUmlObjectParser.MEMBERS_KEY]
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun parseClusterLabel(cluster: Cluster): Pair<String, String> {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: return "package" to cluster.id.value
        val parts = text.split('\n', limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "package" to text
    }

    private fun applyAnchoredNotes(ir: GraphIR, laidOut: LaidOutDiagram): LaidOutDiagram {
        val noteNodes = ir.nodes.filter { it.payload[PlantUmlObjectParser.KIND_KEY] == "note" }
        if (noteNodes.isEmpty()) return laidOut
        val nodePositions = LinkedHashMap(laidOut.nodePositions)
        val edgeRoutes = laidOut.edgeRoutes.toMutableList()
        for (note in noteNodes) {
            val target = note.payload[PlantUmlObjectParser.NOTE_TARGET_KEY]?.let(::NodeId) ?: continue
            val placement = note.payload[PlantUmlObjectParser.NOTE_PLACEMENT_KEY].orEmpty()
            val targetRect = nodePositions[target] ?: continue
            val noteRect = nodePositions[note.id] ?: continue
            val anchored = anchoredNoteRect(noteRect.size, targetRect, placement)
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
            "left" -> listOf(Point(noteRect.right, (noteRect.top + noteRect.bottom) / 2f), Point(targetRect.left, (targetRect.top + targetRect.bottom) / 2f))
            "top" -> listOf(Point((noteRect.left + noteRect.right) / 2f, noteRect.bottom), Point((targetRect.left + targetRect.right) / 2f, targetRect.top))
            "bottom" -> listOf(Point((noteRect.left + noteRect.right) / 2f, noteRect.top), Point((targetRect.left + targetRect.right) / 2f, targetRect.bottom))
            else -> listOf(Point(noteRect.left, (noteRect.top + noteRect.bottom) / 2f), Point(targetRect.right, (targetRect.top + targetRect.bottom) / 2f))
        }
}
