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
            drawEdge(ir.edges.getOrNull(idx), route, out)
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
            text = archimateIcon(stereotype),
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

    private fun drawEdge(edge: Edge?, route: EdgeRoute, out: MutableList<DrawCommand>) {
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
        val color = edge?.style?.color?.let { Color(it.argb) } ?: Color(0xFF607D8B.toInt())
        out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = edge?.style?.width ?: 1.4f, dash = edge?.style?.dash), color, z = 1)
        if (edge?.arrow == ArrowEnds.ToOnly) out += openArrowHead(pts[pts.size - 2], pts.last(), color)
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

    private fun archimateIcon(stereotype: String): String =
        when {
            stereotype.contains("business", ignoreCase = true) -> "B"
            stereotype.contains("application", ignoreCase = true) -> "A"
            stereotype.contains("technology", ignoreCase = true) -> "T"
            stereotype.contains("motivation", ignoreCase = true) -> "M"
            else -> "Ar"
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
