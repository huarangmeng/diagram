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
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.parser.plantuml.PlantUmlDitaaParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlDitaaSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlDitaaParser()
    private val labelFont = FontSpec(family = "monospace", sizeSp = 12f, weight = 600)
    private val cellWidth = 12f
    private val cellHeight = 18f
    private val margin = 24f

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        val laid = layout(ir, previousSnapshot.laidOut, !isFinal).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun layout(ir: GraphIR, previous: LaidOutDiagram?, incremental: Boolean): LaidOutDiagram {
        val positions = LinkedHashMap<NodeId, Rect>()
        var maxRight = 360f
        var maxBottom = 220f
        for (node in ir.nodes) {
            val grid = node.payload[PlantUmlDitaaParser.GRID_KEY]?.split(',')?.mapNotNull { it.toIntOrNull() }
            val fresh = if (grid != null && grid.size == 4) {
                val left = margin + grid[0] * cellWidth
                val top = margin + grid[1] * cellHeight
                val width = ((grid[2] - grid[0] + 1) * cellWidth).coerceAtLeast(96f)
                val height = ((grid[3] - grid[1] + 1) * cellHeight).coerceAtLeast(54f)
                Rect(Point(left, top), Size(width, height))
            } else {
                val idx = positions.size
                Rect(Point(margin + idx * 140f, margin), Size(120f, 60f))
            }
            val rect = if (incremental) previous?.nodePositions?.get(node.id) ?: fresh else fresh
            positions[node.id] = rect
            maxRight = maxOf(maxRight, rect.right + margin)
            maxBottom = maxOf(maxBottom, rect.bottom + margin)
        }
        val routes = ir.edges.mapNotNull { edge ->
            val from = positions[edge.from] ?: return@mapNotNull null
            val to = positions[edge.to] ?: return@mapNotNull null
            val start = Point(from.right, (from.top + from.bottom) / 2f)
            val end = Point(to.left, (to.top + to.bottom) / 2f)
            EdgeRoute(edge.from, edge.to, listOf(start, Point((start.x + end.x) / 2f, start.y), Point((start.x + end.x) / 2f, end.y), end), RouteKind.Orthogonal)
        }
        return LaidOutDiagram(
            source = ir,
            nodePositions = positions,
            edgeRoutes = routes,
            bounds = Rect.ltrb(0f, 0f, maxRight, maxBottom),
        )
    }

    private fun render(ir: GraphIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        out += DrawCommand.FillRect(laid.bounds, Color(0xFFFFFFFF.toInt()), z = -1)
        val handwritten = ir.styleHints.extras[PlantUmlDitaaParser.HANDWRITTEN_KEY] == "true"
        for ((idx, route) in laid.edgeRoutes.withIndex()) drawEdge(ir.edges.getOrNull(idx), route, out, handwritten)
        for (node in ir.nodes) drawNode(node, laid.nodePositions[node.id] ?: continue, out, handwritten)
        return out
    }

    private fun drawNode(node: Node, rect: Rect, out: MutableList<DrawCommand>, handwrittenGraph: Boolean) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFFFFDE7.toInt())
        val stroke = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF8D6E63.toInt())
        val text = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF3E2723.toInt())
        val rounded = node.payload[PlantUmlDitaaParser.ROUNDED_KEY] == "true"
        val handwritten = handwrittenGraph || node.payload[PlantUmlDitaaParser.HANDWRITTEN_KEY] == "true"
        val corner = if (rounded) 16f else 8f
        out += DrawCommand.FillRect(rect, fill, corner = corner, z = 3)
        val strokeSpec = Stroke(width = node.style.strokeWidth ?: 1.4f)
        if (handwritten) {
            out += sketchRect(node.id.value, rect, strokeSpec, stroke)
        } else {
            out += DrawCommand.StrokeRect(rect, strokeSpec, stroke, corner = corner, z = 4)
        }
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
            font = labelFont,
            color = text,
            maxWidth = rect.size.width - 18f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 5,
        )
    }

    private fun drawEdge(edge: Edge?, route: EdgeRoute, out: MutableList<DrawCommand>, handwritten: Boolean) {
        val pts = route.points
        if (pts.size < 2) return
        val ops = ArrayList<PathOp>()
        val key = "${edge?.from?.value.orEmpty()}->${edge?.to?.value.orEmpty()}"
        val drawnPoints = if (handwritten) jitteredPoints(key, pts) else pts
        ops += PathOp.MoveTo(drawnPoints.first())
        for (i in 1 until drawnPoints.size) ops += PathOp.LineTo(drawnPoints[i])
        val color = edge?.style?.color?.let { Color(it.argb) } ?: Color(0xFF6D4C41.toInt())
        val stroke = Stroke(width = edge?.style?.width ?: 1.5f, dash = if (handwritten) listOf(7f, 2f, 2f, 2f) else null)
        out += DrawCommand.StrokePath(PathCmd(ops), stroke, color, z = 1)
        if (edge?.arrow == ArrowEnds.ToOnly) out += openArrowHead(drawnPoints[drawnPoints.size - 2], drawnPoints.last(), color)
    }

    private fun sketchRect(key: String, rect: Rect, stroke: Stroke, color: Color): DrawCommand =
        DrawCommand.StrokePath(
            PathCmd(
                listOf(
                    PathOp.MoveTo(jitterPoint(key, 0, Point(rect.left, rect.top))),
                    PathOp.LineTo(jitterPoint(key, 1, Point(rect.right, rect.top))),
                    PathOp.LineTo(jitterPoint(key, 2, Point(rect.right, rect.bottom))),
                    PathOp.LineTo(jitterPoint(key, 3, Point(rect.left, rect.bottom))),
                    PathOp.Close,
                ),
            ),
            stroke.copy(dash = listOf(8f, 2f, 2f, 2f)),
            color,
            z = 4,
        )

    private fun jitteredPoints(key: String, points: List<Point>): List<Point> =
        points.mapIndexed { index, point -> jitterPoint(key, index, point) }

    private fun jitterPoint(key: String, index: Int, point: Point): Point =
        Point(point.x + jitter(key, index, 0), point.y + jitter(key, index, 1))

    private fun jitter(key: String, index: Int, salt: Int): Float {
        val h = key.hashCode() * 31 + index * 131 + salt * 17
        val bucket = ((h % 7) + 7) % 7
        return (bucket - 3) * 0.45f
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
            z = 2,
        )
    }

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotBlank() } ?: node.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotBlank() } ?: node.id.value
            is RichLabel.Html -> label.html.takeIf { it.isNotBlank() } ?: node.id.value
        }
}
