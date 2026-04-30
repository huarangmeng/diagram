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
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
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
        val laidOut = baseLaid.copy(clusterRects = clusterRects, bounds = bounds, seq = seq)
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
                        width = (metrics.width + 36f).coerceAtLeast(48f),
                        height = (metrics.height + 36f).coerceAtLeast(48f),
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
                out += DrawCommand.DrawText(
                    text = label,
                    origin = Point((rect.left + rect.right) / 2f, rect.bottom + 12f),
                    font = if (kind == "port") iconFallbackFont else labelFont,
                    color = textColor,
                    maxWidth = 120f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Top,
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
            }
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
}
