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
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlArchimateParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlArchimateSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlArchimateParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(150f, 70f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(150f, 70f) },
    )
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val stereotypeFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val edgeFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val relationTypes: List<String>
        get() = parser.relationTypesSnapshot()

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        measureNodes(ir)
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, laid.nodePositions, clusterRects)
        val finalLaid = laid.copy(
            clusterRects = clusterRects,
            bounds = computeBounds(laid.nodePositions.values + clusterRects.values),
            seq = seq,
        )
        return PlantUmlRenderState(
            ir = ir,
            laidOut = finalLaid,
            drawCommands = render(ir, finalLaid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val label = labelTextOf(node)
            val stereotype = node.payload[PlantUmlArchimateParser.STEREOTYPE_KEY].orEmpty()
            val labelMetrics = textMeasurer.measure(label, titleFont, maxWidth = 180f)
            val stereotypeMetrics = textMeasurer.measure(stereotype, stereotypeFont, maxWidth = 180f)
            nodeSizes[node.id] = Size(
                width = maxOf(labelMetrics.width, stereotypeMetrics.width, 118f) + 36f,
                height = labelMetrics.height + stereotypeMetrics.height + 34f,
            )
        }
    }

    private fun computeClusterRect(cluster: Cluster, nodePositions: Map<NodeId, Rect>, out: MutableMap<NodeId, Rect>): Rect? {
        val childRects = cluster.children.mapNotNull { nodePositions[it] }.toMutableList()
        childRects += cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        if (childRects.isEmpty()) return null
        val titleMetrics = textMeasurer.measure(clusterTitle(cluster), titleFont, maxWidth = 220f)
        val rect = Rect.ltrb(
            childRects.minOf { it.left } - 22f,
            childRects.minOf { it.top } - titleMetrics.height - 30f,
            childRects.maxOf { it.right } + 22f,
            childRects.maxOf { it.bottom } + 22f,
        )
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 420f, 260f)
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
        for ((idx, route) in laid.edgeRoutes.withIndex()) {
            drawEdge(ir.edges.getOrNull(idx), idx, route, out)
        }
        for (node in ir.nodes) {
            drawNode(node, laid.nodePositions[node.id] ?: continue, out)
        }
        return out
    }

    private fun drawCluster(cluster: Cluster, rects: Map<NodeId, Rect>, out: MutableList<DrawCommand>) {
        val rect = rects[cluster.id] ?: return
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF8FAFC.toInt())
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF78909C.toInt())
        out += DrawCommand.FillRect(rect, fill, corner = 16f, z = 0)
        out += DrawCommand.StrokeRect(rect, Stroke(width = cluster.style.strokeWidth ?: 1.2f, dash = listOf(8f, 5f)), strokeColor, corner = 16f, z = 1)
        val titleRect = Rect.ltrb(rect.left + 12f, rect.top + 10f, rect.right - 12f, rect.top + 34f)
        out += DrawCommand.DrawText(
            text = clusterTitle(cluster),
            origin = Point(titleRect.left, titleRect.top),
            font = titleFont,
            color = strokeColor,
            maxWidth = titleRect.size.width,
            anchorY = TextAnchorY.Top,
            z = 2,
        )
        for (nested in cluster.nestedClusters) drawCluster(nested, rects, out)
    }

    private fun drawNode(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFECEFF1.toInt())
        val stroke = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF455A64.toInt())
        val text = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF263238.toInt())
        val stereotype = node.payload[PlantUmlArchimateParser.STEREOTYPE_KEY].orEmpty()
        out += DrawCommand.FillRect(rect, fill, corner = 12f, z = 3)
        out += DrawCommand.StrokeRect(rect, Stroke(width = node.style.strokeWidth ?: 1.4f), stroke, corner = 12f, z = 4)
        val header = Rect.ltrb(rect.left, rect.top, rect.right, rect.top + 20f)
        out += DrawCommand.FillRect(header, Color(0x33FFFFFF), corner = 12f, z = 5)
        out += DrawCommand.DrawText(
            text = archimateIconCode(node),
            origin = Point(rect.right - 18f, rect.top + 10f),
            font = stereotypeFont,
            color = stroke,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 6,
        )
        if (stereotype.isNotBlank()) {
            out += DrawCommand.DrawText(
                text = "<<$stereotype>>",
                origin = Point(rect.left + 14f, rect.top + 11f),
                font = stereotypeFont,
                color = Color(0xFF546E7A.toInt()),
                maxWidth = rect.size.width - 36f,
                anchorY = TextAnchorY.Middle,
                z = 6,
            )
        }
        drawArchimateIcon(node, rect, stroke, out)
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point(rect.left + 14f, rect.top + 40f),
            font = titleFont,
            color = text,
            maxWidth = rect.size.width - 28f,
            anchorY = TextAnchorY.Middle,
            z = 6,
        )
    }

    private fun drawEdge(edge: Edge?, edgeIndex: Int, route: EdgeRoute, out: MutableList<DrawCommand>) {
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
        val relationType = relationTypes.getOrNull(edgeIndex).orEmpty()
        val color = edge?.style?.color?.let { Color(it.argb) } ?: Color(0xFF607D8B.toInt())
        out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = edge?.style?.width ?: 1.4f, dash = edge?.style?.dash), color, z = 1)
        drawRelationStart(relationType, pts.first(), pts[1], color, out)
        if (edge?.arrow == ArrowEnds.ToOnly) {
            out += when (relationType) {
                "realization", "specialization" -> triangleArrowHead(pts[pts.size - 2], pts.last(), color, filled = false)
                else -> openArrowHead(pts[pts.size - 2], pts.last(), color)
            }
        }
        drawRelationMidMarker(relationType, pts, color, out)
        val label = edge?.label?.let(::labelTextOf).orEmpty()
        if (label.isNotBlank()) {
            val mid = pts[pts.size / 2]
            out += DrawCommand.FillRect(Rect(Point(mid.x - 46f, mid.y - 10f), Size(92f, 20f)), Color(0xDDFFFFFF.toInt()), corner = 4f, z = 2)
            out += DrawCommand.DrawText(label, mid, edgeFont, Color(0xFF455A64.toInt()), maxWidth = 90f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 3)
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
            z = 2,
        )
    }

    private fun triangleArrowHead(from: Point, to: Point, color: Color, filled: Boolean): DrawCommand {
        val geom = edgeGeometry(from, to, size = 10f)
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(to),
                PathOp.LineTo(Point(geom.baseX + geom.nx * 5f, geom.baseY + geom.ny * 5f)),
                PathOp.LineTo(Point(geom.baseX - geom.nx * 5f, geom.baseY - geom.ny * 5f)),
                PathOp.Close,
            ),
        )
        return if (filled) DrawCommand.FillPath(path, color, z = 2) else DrawCommand.StrokePath(path, Stroke(width = 1.4f), color, z = 2)
    }

    private fun drawRelationStart(relationType: String, start: Point, next: Point, color: Color, out: MutableList<DrawCommand>) {
        when (relationType) {
            "composition" -> out += diamondMarker(start, next, color, filled = true)
            "aggregation" -> out += diamondMarker(start, next, color, filled = false)
        }
    }

    private fun drawRelationMidMarker(relationType: String, pts: List<Point>, color: Color, out: MutableList<DrawCommand>) {
        if (relationType != "assignment" && relationType != "access") return
        val a = pts[(pts.size / 2 - 1).coerceAtLeast(0)]
        val b = pts[(pts.size / 2).coerceAtMost(pts.lastIndex)]
        val mid = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        out += circleMarker(mid, color, filled = relationType == "assignment")
    }

    private fun diamondMarker(at: Point, toward: Point, color: Color, filled: Boolean): DrawCommand {
        val geom = edgeGeometry(at, toward, size = -12f)
        val center = Point(at.x + geom.ux * 6f, at.y + geom.uy * 6f)
        val path = PathCmd(
            listOf(
                PathOp.MoveTo(at),
                PathOp.LineTo(Point(center.x + geom.nx * 5f, center.y + geom.ny * 5f)),
                PathOp.LineTo(Point(at.x + geom.ux * 12f, at.y + geom.uy * 12f)),
                PathOp.LineTo(Point(center.x - geom.nx * 5f, center.y - geom.ny * 5f)),
                PathOp.Close,
            ),
        )
        return if (filled) DrawCommand.FillPath(path, color, z = 2) else DrawCommand.StrokePath(path, Stroke(width = 1.4f), color, z = 2)
    }

    private fun circleMarker(center: Point, color: Color, filled: Boolean): DrawCommand {
        val r = 4.2f
        val path = ovalPath(Rect.ltrb(center.x - r, center.y - r, center.x + r, center.y + r))
        return if (filled) DrawCommand.FillPath(path, color, z = 2) else DrawCommand.StrokePath(path, Stroke(width = 1.3f), color, z = 2)
    }

    private data class EdgeGeometry(
        val ux: Float,
        val uy: Float,
        val nx: Float,
        val ny: Float,
        val baseX: Float,
        val baseY: Float,
    )

    private fun edgeGeometry(from: Point, to: Point, size: Float): EdgeGeometry {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: 1f
        val ux = dx / len
        val uy = dy / len
        return EdgeGeometry(ux = ux, uy = uy, nx = -uy, ny = ux, baseX = to.x - ux * size, baseY = to.y - uy * size)
    }

    private fun archimateIconCode(node: Node): String =
        when (domainOf(node)) {
            "business" -> "B"
            "application" -> "A"
            "technology" -> "T"
            "physical" -> "P"
            "motivation" -> "M"
            "strategy" -> "S"
            "implementation" -> "I"
            else -> "Ar"
        }

    private fun drawArchimateIcon(node: Node, rect: Rect, stroke: Color, out: MutableList<DrawCommand>) {
        val icon = Rect.ltrb(rect.right - 35f, rect.top + 24f, rect.right - 12f, rect.top + 47f)
        when (domainOf(node)) {
            "business" -> drawBusinessIcon(icon, stroke, out)
            "application" -> drawApplicationIcon(icon, stroke, out)
            "technology" -> drawTechnologyIcon(icon, stroke, out)
            "physical" -> drawPhysicalIcon(icon, stroke, out)
            "motivation" -> drawMotivationIcon(icon, stroke, out)
            "strategy" -> drawStrategyIcon(icon, stroke, out)
            "implementation" -> drawImplementationIcon(icon, stroke, out)
            else -> out += DrawCommand.DrawIcon("archimate:${node.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY].orEmpty()}", icon, z = 6)
        }
    }

    private fun domainOf(node: Node): String {
        val value = "${node.payload[PlantUmlArchimateParser.ELEMENT_TYPE_KEY].orEmpty()} ${node.payload[PlantUmlArchimateParser.STEREOTYPE_KEY].orEmpty()}".lowercase()
        return when {
            value.contains("business") -> "business"
            value.contains("application") -> "application"
            value.contains("technology") -> "technology"
            value.contains("physical") -> "physical"
            value.contains("motivation") -> "motivation"
            value.contains("strategy") -> "strategy"
            value.contains("implementation") || value.contains("migration") -> "implementation"
            else -> "generic"
        }
    }

    private fun drawBusinessIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        val head = ovalPath(Rect.ltrb(rect.left + 8f, rect.top + 2f, rect.right - 8f, rect.top + 10f))
        val body = PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 5f, rect.bottom - 2f)), PathOp.LineTo(Point(rect.left + 9f, rect.top + 12f)), PathOp.LineTo(Point(rect.right - 9f, rect.top + 12f)), PathOp.LineTo(Point(rect.right - 5f, rect.bottom - 2f))))
        out += DrawCommand.StrokePath(head, Stroke(width = 1.2f), color, z = 6)
        out += DrawCommand.StrokePath(body, Stroke(width = 1.2f), color, z = 6)
    }

    private fun drawApplicationIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.StrokeRect(rect, Stroke(width = 1.2f), color, corner = 3f, z = 6)
        out += DrawCommand.StrokeRect(Rect.ltrb(rect.left + 4f, rect.top + 5f, rect.left + 12f, rect.top + 12f), Stroke(width = 1f), color, corner = 2f, z = 6)
        out += DrawCommand.StrokeRect(Rect.ltrb(rect.right - 12f, rect.bottom - 12f, rect.right - 4f, rect.bottom - 5f), Stroke(width = 1f), color, corner = 2f, z = 6)
    }

    private fun drawTechnologyIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        val rack1 = Rect.ltrb(rect.left + 2f, rect.top + 4f, rect.right - 2f, rect.top + 12f)
        val rack2 = Rect.ltrb(rect.left + 2f, rect.top + 13f, rect.right - 2f, rect.top + 21f)
        out += DrawCommand.StrokeRect(rack1, Stroke(width = 1.1f), color, corner = 2f, z = 6)
        out += DrawCommand.StrokeRect(rack2, Stroke(width = 1.1f), color, corner = 2f, z = 6)
        out += DrawCommand.DrawText("..", Point(rect.right - 8f, rect.top + 8f), stereotypeFont, color, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, z = 6)
    }

    private fun drawPhysicalIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        val path = PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 3f, rect.bottom - 3f)), PathOp.LineTo(Point(rect.left + 3f, rect.top + 11f)), PathOp.LineTo(Point(rect.left + 10f, rect.top + 7f)), PathOp.LineTo(Point(rect.left + 10f, rect.top + 11f)), PathOp.LineTo(Point(rect.left + 18f, rect.top + 7f)), PathOp.LineTo(Point(rect.left + 18f, rect.bottom - 3f)), PathOp.Close))
        out += DrawCommand.StrokePath(path, Stroke(width = 1.2f), color, z = 6)
    }

    private fun drawMotivationIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        val path = PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 3f, rect.top + 12f)), PathOp.LineTo(Point(rect.left + 11f, rect.top + 4f)), PathOp.LineTo(Point(rect.right - 3f, rect.top + 12f)), PathOp.LineTo(Point(rect.left + 11f, rect.bottom - 3f)), PathOp.Close))
        out += DrawCommand.StrokePath(path, Stroke(width = 1.2f), color, z = 6)
    }

    private fun drawStrategyIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        val path = PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 4f, rect.bottom - 4f)), PathOp.LineTo(Point(rect.left + 4f, rect.top + 7f)), PathOp.LineTo(Point(rect.right - 4f, rect.top + 7f)), PathOp.LineTo(Point(rect.right - 9f, rect.top + 3f)), PathOp.MoveTo(Point(rect.right - 4f, rect.top + 7f)), PathOp.LineTo(Point(rect.right - 9f, rect.top + 12f))))
        out += DrawCommand.StrokePath(path, Stroke(width = 1.3f), color, z = 6)
    }

    private fun drawImplementationIcon(rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        out += DrawCommand.StrokeRect(Rect.ltrb(rect.left + 4f, rect.top + 4f, rect.right - 4f, rect.bottom - 4f), Stroke(width = 1.2f), color, corner = 2f, z = 6)
        out += DrawCommand.StrokePath(PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 8f, rect.top + 10f)), PathOp.LineTo(Point(rect.right - 8f, rect.bottom - 10f)), PathOp.MoveTo(Point(rect.right - 8f, rect.top + 10f)), PathOp.LineTo(Point(rect.left + 8f, rect.bottom - 10f)))), Stroke(width = 1.1f), color, z = 6)
    }

    private fun ovalPath(rect: Rect): PathCmd {
        val c = 0.5522848f
        val rx = rect.size.width / 2f
        val ry = rect.size.height / 2f
        val cx = rect.left + rx
        val cy = rect.top + ry
        return PathCmd(
            listOf(
                PathOp.MoveTo(Point(cx + rx, cy)),
                PathOp.CubicTo(Point(cx + rx, cy + ry * c), Point(cx + rx * c, cy + ry), Point(cx, cy + ry)),
                PathOp.CubicTo(Point(cx - rx * c, cy + ry), Point(cx - rx, cy + ry * c), Point(cx - rx, cy)),
                PathOp.CubicTo(Point(cx - rx, cy - ry * c), Point(cx - rx * c, cy - ry), Point(cx, cy - ry)),
                PathOp.CubicTo(Point(cx + rx * c, cy - ry), Point(cx + rx, cy - ry * c), Point(cx + rx, cy)),
                PathOp.Close,
            ),
        )
    }

    private fun labelTextOf(node: Node): String = labelTextOf(node.label).ifBlank { node.id.value }

    private fun labelTextOf(label: RichLabel): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }

    private fun clusterTitle(cluster: Cluster): String {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: cluster.id.value
        val lines = text.lines()
        return if (lines.size >= 2) lines[1] else text
    }
}
