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
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlC4Parser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.min
import kotlin.math.sqrt

internal class PlantUmlC4SubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlC4Parser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(188f, 96f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(188f, 96f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val clusterFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val edgeFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val stereoFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        measureNodes(ir)
        val base = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, base.nodePositions, clusterRects)
        val laid = base.copy(clusterRects = clusterRects, bounds = computeBounds(base.nodePositions.values + clusterRects.values), seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val metrics = textMeasurer.measure(labelTextOf(node), labelFont, maxWidth = 220f)
            val extraWidth = if (node.shape is NodeShape.Component) 18f else 0f
            nodeSizes[node.id] = Size(
                width = (metrics.width + 32f + extraWidth).coerceAtLeast(150f),
                height = (metrics.height + 26f).coerceAtLeast(78f),
            )
        }
    }

    private fun computeClusterRect(cluster: Cluster, nodePositions: Map<NodeId, Rect>, out: MutableMap<NodeId, Rect>): Rect? {
        val childRects = cluster.children.mapNotNull { nodePositions[it] }.toMutableList()
        childRects += cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        if (childRects.isEmpty()) return null
        val title = clusterTitle(cluster)
        val titleMetrics = textMeasurer.measure(title, clusterFont, maxWidth = 220f)
        val rect = Rect.ltrb(
            childRects.minOf { it.left } - 20f,
            childRects.minOf { it.top } - titleMetrics.height - 28f,
            childRects.maxOf { it.right } + 20f,
            childRects.maxOf { it.bottom } + 20f,
        )
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 420f, 260f)
        return Rect.ltrb(
            rects.minOf { it.left }.coerceAtMost(0f),
            rects.minOf { it.top }.coerceAtMost(0f),
            rects.maxOf { it.right } + 24f,
            rects.maxOf { it.bottom } + 24f,
        )
    }

    private fun render(ir: GraphIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        out += DrawCommand.FillRect(laid.bounds, Color(0xFFFFFFFF.toInt()), z = -1)
        val nodeLinks = parser.nodeLinkSnapshot()
        val edgeLinks = parser.edgeLinkSnapshot()
        val edgePresentation = parser.edgePresentationSnapshot()
        for (cluster in ir.clusters) drawCluster(cluster, laid.clusterRects, nodeLinks, out)
        for ((idx, route) in laid.edgeRoutes.withIndex()) drawEdge(ir.edges.getOrNull(idx), edgePresentation[idx], edgeLinks[idx], route, out)
        for (node in ir.nodes) drawNode(node, laid.nodePositions[node.id] ?: continue, nodeLinks[node.id], out)
        return out
    }

    private fun drawCluster(cluster: Cluster, rects: Map<NodeId, Rect>, links: Map<NodeId, String>, out: MutableList<DrawCommand>) {
        val rect = rects[cluster.id] ?: return
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF90A4AE.toInt())
        out += DrawCommand.FillRect(rect, cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF8FBFF.toInt()), corner = 14f, z = 0)
        out += DrawCommand.StrokeRect(rect, Stroke(width = cluster.style.strokeWidth ?: 1.4f, dash = listOf(8f, 5f)), strokeColor, corner = 14f, z = 1)
        val chip = Rect.ltrb(rect.left + 10f, rect.top + 10f, min(rect.right - 10f, rect.left + 190f), rect.top + 42f)
        out += DrawCommand.FillRect(chip, Color(0xFFFFFFFF.toInt()), corner = 10f, z = 2)
        out += DrawCommand.StrokeRect(chip, Stroke(width = 1f), strokeColor, corner = 10f, z = 3)
        out += DrawCommand.DrawText(clusterTitle(cluster), Point(chip.left + 10f, chip.top + 10f), clusterFont, strokeColor, maxWidth = chip.size.width - 20f, anchorY = TextAnchorY.Top, z = 4)
        links[cluster.id]?.let { href ->
            out += DrawCommand.Hyperlink(href, rect, z = 9)
            drawLinkBadge(rect, strokeColor, out)
        }
    }

    private fun drawNode(node: Node, rect: Rect, link: String?, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE3F2FD.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF1E88E5.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF0D47A1.toInt())
        val shape = node.shape
        when (shape) {
            is NodeShape.Actor, is NodeShape.Stadium -> drawActorNode(rect, fill, strokeColor, out)
            is NodeShape.Cylinder -> drawCylinderNode(rect, fill, strokeColor, out)
            is NodeShape.Hexagon -> drawHexagonNode(rect, fill, strokeColor, out)
            is NodeShape.Component -> drawComponentNode(rect, fill, strokeColor, out)
            is NodeShape.Package -> drawDeploymentNode(rect, fill, strokeColor, out)
            is NodeShape.Custom -> if (shape.name == "octagon") {
                drawOctagonNode(rect, fill, strokeColor, out)
            } else {
                out += DrawCommand.FillRect(rect, fill, corner = 12f, z = 4)
                out += DrawCommand.StrokeRect(rect, Stroke(width = node.style.strokeWidth ?: 1.4f), strokeColor, corner = 12f, z = 5)
            }
            else -> {
                out += DrawCommand.FillRect(rect, fill, corner = 12f, z = 4)
                out += DrawCommand.StrokeRect(rect, Stroke(width = node.style.strokeWidth ?: 1.4f), strokeColor, corner = 12f, z = 5)
            }
        }
        if (!link.isNullOrBlank() || node.payload[PlantUmlC4Parser.LINK_KEY]?.isNotBlank() == true) {
            drawLinkBadge(rect, strokeColor, out)
            out += DrawCommand.Hyperlink(link ?: node.payload[PlantUmlC4Parser.LINK_KEY].orEmpty(), rect, z = 10)
        }
        val lines = labelTextOf(node).lines()
        val stereo = lines.firstOrNull().orEmpty()
        val body = lines.drop(1).joinToString("\n")
        out += DrawCommand.DrawText(stereo, Point(rect.left + 14f, rect.top + 10f), stereoFont, strokeColor, maxWidth = rect.size.width - 28f, anchorY = TextAnchorY.Top, z = 6)
        out += DrawCommand.DrawText(body, Point(rect.left + 14f, rect.top + 30f), labelFont, textColor, maxWidth = rect.size.width - 28f, anchorY = TextAnchorY.Top, z = 6)
    }

    private fun drawDeploymentNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 8f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.4f), strokeColor, corner = 8f, z = 5)
        val tab = Rect.ltrb(rect.left + 10f, rect.top - 8f, rect.left + 72f, rect.top + 12f)
        out += DrawCommand.FillRect(tab, fill, corner = 5f, z = 4)
        out += DrawCommand.StrokeRect(tab, Stroke(width = 1.2f), strokeColor, corner = 5f, z = 5)
    }

    private fun drawLinkBadge(rect: Rect, strokeColor: Color, out: MutableList<DrawCommand>) {
        val badge = Rect.ltrb(rect.right - 28f, rect.bottom - 24f, rect.right - 8f, rect.bottom - 6f)
        out += DrawCommand.FillRect(badge, Color(0xEEFFFFFF.toInt()), corner = 9f, z = 7)
        out += DrawCommand.StrokeRect(badge, Stroke(width = 1f), strokeColor, corner = 9f, z = 8)
        out += DrawCommand.DrawText("L", Point((badge.left + badge.right) / 2f, (badge.top + badge.bottom) / 2f), stereoFont, strokeColor, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 9)
    }

    private fun drawActorNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 16f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.4f), strokeColor, corner = 16f, z = 5)
    }

    private fun drawHexagonNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        val inset = min(rect.size.width, rect.size.height) * 0.18f
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
        out += DrawCommand.StrokePath(path, Stroke(width = 1.4f), strokeColor, z = 5)
    }

    private fun drawOctagonNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        val inset = min(rect.size.width, rect.size.height) * 0.18f
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(Point(rect.left + inset, rect.top)),
                PathOp.LineTo(Point(rect.right - inset, rect.top)),
                PathOp.LineTo(Point(rect.right, rect.top + inset)),
                PathOp.LineTo(Point(rect.right, rect.bottom - inset)),
                PathOp.LineTo(Point(rect.right - inset, rect.bottom)),
                PathOp.LineTo(Point(rect.left + inset, rect.bottom)),
                PathOp.LineTo(Point(rect.left, rect.bottom - inset)),
                PathOp.LineTo(Point(rect.left, rect.top + inset)),
                PathOp.Close,
            ),
        )
        out += DrawCommand.FillPath(path, fill, z = 4)
        out += DrawCommand.StrokePath(path, Stroke(width = 1.4f), strokeColor, z = 5)
    }

    private fun drawCylinderNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 10f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.4f), strokeColor, corner = 10f, z = 5)
        out += DrawCommand.StrokeRect(Rect(rect.origin, Size(rect.size.width, 18f)), Stroke(width = 1f), strokeColor, corner = 9f, z = 6)
    }

    private fun drawComponentNode(rect: Rect, fill: Color, strokeColor: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.FillRect(rect, fill, corner = 10f, z = 4)
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.4f), strokeColor, corner = 10f, z = 5)
        val tab = Rect.ltrb(rect.right - 30f, rect.top + 12f, rect.right - 10f, rect.top + 30f)
        out += DrawCommand.StrokeRect(tab, Stroke(width = 1f), strokeColor, corner = 3f, z = 6)
    }

    private fun drawEdge(
        edge: Edge?,
        presentation: PlantUmlC4Parser.C4EdgePresentation?,
        link: String?,
        route: EdgeRoute,
        out: MutableList<DrawCommand>,
    ) {
        if (edge?.kind == EdgeKind.Invisible) return
        val pts = route.points
        if (pts.size < 2) return
        val ops = ArrayList<PathOp>()
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
        val color = edge?.style?.color?.let { Color(it.argb) } ?: Color(0xFF546E7A.toInt())
        out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = edge?.style?.width ?: 1.5f, dash = edge?.style?.dash), color, z = 2)
        if (edge?.arrow == ArrowEnds.ToOnly || edge?.arrow == ArrowEnds.Both) out += openArrowHead(pts[pts.size - 2], pts.last(), color)
        if (edge?.arrow == ArrowEnds.Both) out += openArrowHead(pts[1], pts.first(), color)
        val label = edge?.label?.let(::labelTextOf).orEmpty()
        if (label.isNotBlank()) {
            val mid = pts[pts.size / 2]
            val origin = Point(mid.x + (presentation?.offsetX ?: 0f), mid.y + (presentation?.offsetY ?: 0f))
            val labelRect = Rect(Point(origin.x - 52f, origin.y - 12f), Size(104f, 24f))
            out += DrawCommand.FillRect(labelRect, Color(0xEEFFFFFF.toInt()), corner = 4f, z = 3)
            val labelColor = presentation?.textColor?.let { Color(it.argb) } ?: Color(0xFF37474F.toInt())
            out += DrawCommand.DrawText(label, origin, edgeFont, labelColor, maxWidth = 100f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 4)
            link?.let {
                out += DrawCommand.Hyperlink(it, labelRect, z = 10)
                out += DrawCommand.DrawText("L", Point(labelRect.right - 8f, labelRect.top + 8f), stereoFont, color, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 5)
            }
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
            PathCmd(listOf(PathOp.MoveTo(Point(bx + nx * size * 0.5f, by + ny * size * 0.5f)), PathOp.LineTo(to), PathOp.LineTo(Point(bx - nx * size * 0.5f, by - ny * size * 0.5f)))),
            Stroke(width = 1.5f),
            color,
            z = 3,
        )
    }

    private fun labelTextOf(node: Node): String = labelTextOf(node.label).ifBlank { node.id.value }

    private fun labelTextOf(label: RichLabel): String = when (label) {
        is RichLabel.Plain -> label.text
        is RichLabel.Markdown -> label.source
        is RichLabel.Html -> label.html
    }

    private fun clusterTitle(cluster: Cluster): String {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: cluster.id.value
        val lines = text.lines()
        return if (lines.size >= 2) "${lines[1]}\n[${lines[0]}]" else text
    }
}
