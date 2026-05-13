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
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlNetworkParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlNetworkSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlNetworkParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(132f, 58f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(132f, 58f) },
    )
    private val nodeFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val detailFont = FontSpec(family = "monospace", sizeSp = 10f)
    private val clusterFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val edgeFont = FontSpec(family = "sans-serif", sizeSp = 10f)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        measureNodes(ir)
        val base = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, base.nodePositions, clusterRects)
        val laid = base.copy(
            clusterRects = clusterRects,
            bounds = computeBounds(base.nodePositions.values + clusterRects.values),
            seq = seq,
        )
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val text = labelTextOf(node)
            val metrics = textMeasurer.measure(text, nodeFont, maxWidth = 180f)
            nodeSizes[node.id] = Size(
                width = (metrics.width + 32f).coerceAtLeast(118f),
                height = (metrics.height + 24f).coerceAtLeast(52f),
            )
        }
    }

    private fun computeClusterRect(cluster: Cluster, nodePositions: Map<NodeId, Rect>, out: MutableMap<NodeId, Rect>): Rect? {
        val children = ArrayList<Rect>()
        children += cluster.children.mapNotNull { nodePositions[it] }
        children += cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        if (children.isEmpty()) return null
        val label = clusterTitle(cluster)
        val title = textMeasurer.measure(label, clusterFont, maxWidth = 220f)
        val rect = Rect.ltrb(
            children.minOf { it.left } - 22f,
            children.minOf { it.top } - title.height - 32f,
            children.maxOf { it.right } + 22f,
            children.maxOf { it.bottom } + 20f,
        )
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 360f, 220f)
        return Rect.ltrb(
            left = rects.minOf { it.left }.coerceAtMost(0f),
            top = rects.minOf { it.top }.coerceAtMost(0f),
            right = rects.maxOf { it.right } + 24f,
            bottom = rects.maxOf { it.bottom } + 24f,
        )
    }

    private fun render(ir: GraphIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        out += DrawCommand.FillRect(laid.bounds, Color(0xFFFFFFFF.toInt()), z = -1)
        for (cluster in ir.clusters) drawCluster(cluster, laid.clusterRects, out)
        for (node in ir.nodes) drawNode(node, laid.nodePositions[node.id] ?: continue, out)
        for ((idx, route) in laid.edgeRoutes.withIndex()) {
            drawEdge(ir.edges.getOrNull(idx), route, out)
        }
        return out
    }

    private fun drawCluster(cluster: Cluster, rects: Map<NodeId, Rect>, out: MutableList<DrawCommand>) {
        val rect = rects[cluster.id] ?: return
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF6F8FA.toInt())
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF607D8B.toInt())
        out += DrawCommand.FillRect(rect, fill, corner = 14f, z = 0)
        out += DrawCommand.StrokeRect(rect, Stroke(width = cluster.style.strokeWidth ?: 1.4f, dash = listOf(8f, 5f)), strokeColor, corner = 14f, z = 1)
        val chip = Rect.ltrb(rect.left + 12f, rect.top + 10f, rect.right - 12f, rect.top + 36f)
        out += DrawCommand.FillRect(chip, Color(0xFFECEFF1.toInt()), corner = 13f, z = 2)
        out += DrawCommand.DrawText(
            text = clusterTitle(cluster),
            origin = Point(chip.left + 12f, chip.top + chip.size.height / 2f),
            font = clusterFont,
            color = Color(0xFF37474F.toInt()),
            maxWidth = chip.size.width - 24f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Middle,
            z = 3,
        )
        for (nested in cluster.nestedClusters) drawCluster(nested, rects, out)
    }

    private fun drawNode(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE3F2FD.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF1976D2.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF0D47A1.toInt())
        when (node.shape) {
            is NodeShape.Cloud -> drawCloudNode(rect, fill, strokeColor, out)
            is NodeShape.Cylinder -> drawCylinderNode(rect, fill, strokeColor, out)
            is NodeShape.Hexagon -> drawHexagonNode(rect, fill, strokeColor, out)
            is NodeShape.Actor -> drawActorNode(rect, fill, strokeColor, out)
            is NodeShape.Component -> drawComponentNode(rect, fill, strokeColor, out)
            is NodeShape.Box -> {
                out += DrawCommand.FillRect(rect, fill, corner = 2f, z = 4)
                out += DrawCommand.StrokeRect(rect, Stroke(width = node.style.strokeWidth ?: 1.5f), strokeColor, corner = 2f, z = 5)
            }
            else -> {
                out += DrawCommand.FillRect(rect, fill, corner = 10f, z = 4)
                out += DrawCommand.StrokeRect(rect, Stroke(width = node.style.strokeWidth ?: 1.5f), strokeColor, corner = 10f, z = 5)
            }
        }
        val lines = labelTextOf(node).split('\n')
        out += DrawCommand.DrawText(
            text = lines.firstOrNull().orEmpty(),
            origin = Point(rect.left + 14f, rect.top + 10f),
            font = nodeFont,
            color = textColor,
            maxWidth = rect.size.width - 28f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Top,
            z = 6,
        )
        val details = lines.drop(1).joinToString("\n")
        if (details.isNotBlank()) {
            out += DrawCommand.DrawText(
                text = details,
                origin = Point(rect.left + 14f, rect.top + 30f),
                font = detailFont,
                color = Color(0xFF455A64.toInt()),
                maxWidth = rect.size.width - 28f,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = 6,
            )
        }
    }

    private fun drawCylinderNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 10f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), strokeColor, corner = 10f, z = 5)
        out += DrawCommand.StrokeRect(Rect(rect.origin, Size(rect.size.width, 18f)), Stroke(width = 1f), strokeColor, corner = 9f, z = 6)
    }

    private fun drawHexagonNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        val inset = minOf(rect.size.width, rect.size.height) * 0.18f
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(Point(rect.left + inset, rect.top)),
                PathOp.LineTo(Point(rect.right - inset, rect.top)),
                PathOp.LineTo(Point(rect.right, rect.top + rect.size.height / 2f)),
                PathOp.LineTo(Point(rect.right - inset, rect.bottom)),
                PathOp.LineTo(Point(rect.left + inset, rect.bottom)),
                PathOp.LineTo(Point(rect.left, rect.top + rect.size.height / 2f)),
                PathOp.Close,
            ),
        )
        out += DrawCommand.FillPath(path, fill, z = 4)
        out += DrawCommand.StrokePath(path, Stroke(width = 1.5f), strokeColor, z = 5)
    }

    private fun drawActorNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = rect.size.height / 2f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), strokeColor, corner = rect.size.height / 2f, z = 5)
    }

    private fun drawComponentNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 8f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), strokeColor, corner = 8f, z = 5)
        val tab = Rect.ltrb(rect.right - 26f, rect.top + 10f, rect.right - 8f, rect.top + 28f)
        out += DrawCommand.StrokeRect(tab, Stroke(width = 1f), strokeColor, corner = 3f, z = 6)
    }

    private fun drawCloudNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(Point(rect.left + rect.size.width * 0.18f, rect.bottom - rect.size.height * 0.28f)),
                PathOp.CubicTo(
                    Point(rect.left, rect.bottom - rect.size.height * 0.38f),
                    Point(rect.left + rect.size.width * 0.08f, rect.top + rect.size.height * 0.24f),
                    Point(rect.left + rect.size.width * 0.30f, rect.top + rect.size.height * 0.34f),
                ),
                PathOp.CubicTo(
                    Point(rect.left + rect.size.width * 0.36f, rect.top + rect.size.height * 0.08f),
                    Point(rect.left + rect.size.width * 0.66f, rect.top + rect.size.height * 0.08f),
                    Point(rect.left + rect.size.width * 0.72f, rect.top + rect.size.height * 0.34f),
                ),
                PathOp.CubicTo(
                    Point(rect.right, rect.top + rect.size.height * 0.28f),
                    Point(rect.right, rect.bottom - rect.size.height * 0.22f),
                    Point(rect.left + rect.size.width * 0.76f, rect.bottom - rect.size.height * 0.22f),
                ),
                PathOp.LineTo(Point(rect.left + rect.size.width * 0.18f, rect.bottom - rect.size.height * 0.28f)),
                PathOp.Close,
            ),
        )
        out += DrawCommand.FillPath(path, fill, z = 4)
        out += DrawCommand.StrokePath(path, Stroke(width = 1.5f), strokeColor, z = 5)
    }

    private fun drawEdge(edge: com.hrm.diagram.core.ir.Edge?, route: EdgeRoute, out: MutableList<DrawCommand>) {
        val pts = route.points
        if (pts.size < 2) return
        val ops = ArrayList<PathOp>(pts.size)
        ops += PathOp.MoveTo(pts.first())
        when (route.kind) {
            RouteKind.Bezier -> {
                var i = 1
                while (i + 2 < pts.size) {
                    ops += PathOp.CubicTo(pts[i], pts[i + 1], pts[i + 2])
                    i += 3
                }
                if (i < pts.size) ops += PathOp.LineTo(pts.last())
            }
            else -> for (i in 1 until pts.size) ops += PathOp.LineTo(pts[i])
        }
        val color = edge?.style?.color?.let { Color(it.argb) } ?: Color(0xFF78909C.toInt())
        out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = edge?.style?.width ?: 1.4f, dash = edge?.style?.dash), color, z = 2)
        when (edge?.arrow) {
            com.hrm.diagram.core.ir.ArrowEnds.ToOnly -> out += openArrowHead(pts[pts.size - 2], pts.last(), color)
            com.hrm.diagram.core.ir.ArrowEnds.FromOnly -> out += openArrowHead(pts[1], pts.first(), color)
            com.hrm.diagram.core.ir.ArrowEnds.Both -> {
                out += openArrowHead(pts[pts.size - 2], pts.last(), color)
                out += openArrowHead(pts[1], pts.first(), color)
            }
            else -> Unit
        }
        val label = edge?.label?.let(::labelTextOf).orEmpty()
        if (label.isNotBlank()) {
            val mid = pts[pts.size / 2]
            out += DrawCommand.FillRect(Rect(Point(mid.x - 42f, mid.y - 10f), Size(84f, 20f)), Color(0xDDFFFFFF.toInt()), corner = 4f, z = 3)
            out += DrawCommand.DrawText(label, mid, edgeFont, Color(0xFF455A64.toInt()), maxWidth = 80f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 4)
        }
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: 1f
        val ux = dx / len
        val uy = dy / len
        val size = 8f
        val bx = to.x - ux * size
        val by = to.y - uy * size
        val nx = -uy
        val ny = ux
        return DrawCommand.StrokePath(
            PathCmd(
                listOf(
                    PathOp.MoveTo(Point(bx + nx * size * 0.5f, by + ny * size * 0.5f)),
                    PathOp.LineTo(to),
                    PathOp.LineTo(Point(bx - nx * size * 0.5f, by - ny * size * 0.5f)),
                ),
            ),
            Stroke(width = 1.4f),
            color,
            z = 3,
        )
    }

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotBlank() } ?: node.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotBlank() } ?: node.id.value
            is RichLabel.Html -> label.html.takeIf { it.isNotBlank() } ?: node.id.value
        }

    private fun labelTextOf(label: RichLabel): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }

    private fun clusterTitle(cluster: Cluster): String {
        val label = (cluster.label as? RichLabel.Plain)?.text ?: cluster.id.value
        val lines = label.split('\n')
        return when {
            lines.size >= 3 -> "${lines[1]}  ${lines[2]}"
            lines.size >= 2 -> lines[1]
            else -> label
        }
    }
}
