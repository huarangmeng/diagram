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
        val laidOut = layout.layout(
            previousSnapshot.laidOut,
            ir,
            LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        ).copy(seq = seq)
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
            val titleMetrics = textMeasurer.measure(title, titleFont, maxWidth = 220f)
            val memberMetrics = if (members.isEmpty()) null else textMeasurer.measure(members.joinToString("\n"), memberFont, maxWidth = 220f)
            val width = maxOf(
                titleMetrics.width + 28f,
                (memberMetrics?.width ?: 0f) + 28f,
                124f,
            )
            val height = titleMetrics.height + 22f + if (memberMetrics != null) memberMetrics.height + 18f else 0f
            nodeSizes[node.id] = Size(width, height.coerceAtLeast(56f))
        }
    }

    private fun render(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
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

    private fun drawNode(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFF3E5F5.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF6A1B9A.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF4A148C.toInt())
        val members = membersOf(node)
        val title = titleOf(node)
        val titleMetrics = textMeasurer.measure(title, titleFont, maxWidth = rect.size.width - 20f)
        val headerBottom = rect.top + titleMetrics.height + 18f

        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 8f, z = 2)
        out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = node.style.strokeWidth ?: 1.5f), color = strokeColor, corner = 8f, z = 3)
        out += DrawCommand.DrawText(
            text = title,
            origin = Point((rect.left + rect.right) / 2f, rect.top + 9f),
            font = titleFont,
            color = textColor,
            maxWidth = rect.size.width - 20f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Top,
            z = 4,
        )
        if (members.isNotEmpty()) {
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
}
