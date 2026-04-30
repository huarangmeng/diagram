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
import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.NodeStyle
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlActivityParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlActivitySubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlActivityParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(160f, 72f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(160f, 72f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private var idCounter: Int = 0

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        val lowered = lower(ir)
        measureNodes(lowered)
        val laidOut = layout.layout(
            previousSnapshot.laidOut,
            lowered,
            LayoutOptions(direction = lowered.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        ).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = render(lowered, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
    }

    private data class BuildResult(val entry: NodeId?, val exits: List<NodeId>)

    private fun lower(ir: ActivityIR): GraphIR {
        idCounter = 0
        val nodes = ArrayList<Node>()
        val edges = ArrayList<Edge>()
        val sequence = buildSequence(ir.blocks, nodes, edges)
        val hasStart = ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY] == "true"
        val hasStop = ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY] == "true"
        if (hasStart) {
            val start = makeNode("start", "Start", NodeShape.StartCircle, style(start = true))
            nodes += start
            sequence.entry?.let { edges += solidEdge(start.id, it) }
        }
        if (hasStop) {
            val stop = makeNode("stop", "Stop", NodeShape.EndCircle, style(stop = true))
            nodes += stop
            for (exit in sequence.exits) edges += solidEdge(exit, stop.id)
        }
        return GraphIR(
            nodes = nodes,
            edges = edges,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = ir.styleHints,
        )
    }

    private fun buildSequence(
        blocks: List<ActivityBlock>,
        nodes: MutableList<Node>,
        edges: MutableList<Edge>,
    ): BuildResult {
        var entry: NodeId? = null
        var pendingExits = emptyList<NodeId>()
        for (block in blocks) {
            val built = buildBlock(block, nodes, edges)
            if (built.entry != null) {
                if (entry == null) entry = built.entry
                for (exit in pendingExits) edges += solidEdge(exit, built.entry)
                pendingExits = built.exits
            }
        }
        return BuildResult(entry, pendingExits)
    }

    private fun buildBlock(
        block: ActivityBlock,
        nodes: MutableList<Node>,
        edges: MutableList<Edge>,
    ): BuildResult = when (block) {
        is ActivityBlock.Action -> {
            val id = nextId("action")
            nodes += Node(id, block.label, NodeShape.RoundedBox, style(action = true))
            BuildResult(id, listOf(id))
        }
        is ActivityBlock.Note -> {
            val id = nextId("note")
            nodes += Node(id, block.text, NodeShape.Note, style(note = true))
            BuildResult(id, listOf(id))
        }
        is ActivityBlock.IfElse -> {
            val condId = nextId("if")
            nodes += Node(condId, block.cond, NodeShape.Diamond, style(decision = true))
            val thenRes = buildSequence(block.thenBranch, nodes, edges)
            val elseRes = buildSequence(block.elseBranch, nodes, edges)
            thenRes.entry?.let { edges += labelledEdge(condId, it, "yes") }
            elseRes.entry?.let { edges += labelledEdge(condId, it, if (block.elseBranch.isNotEmpty()) "no" else "") }
            val exits = buildList {
                if (thenRes.exits.isNotEmpty()) addAll(thenRes.exits)
                if (block.elseBranch.isEmpty()) add(condId) else addAll(elseRes.exits)
            }
            BuildResult(condId, exits)
        }
        is ActivityBlock.While -> {
            val condId = nextId("while")
            nodes += Node(condId, block.cond, NodeShape.Diamond, style(decision = true))
            val bodyRes = buildSequence(block.body, nodes, edges)
            bodyRes.entry?.let { edges += labelledEdge(condId, it, "yes") }
            for (exit in bodyRes.exits) edges += solidEdge(exit, condId)
            BuildResult(condId, listOf(condId))
        }
        is ActivityBlock.ForkJoin -> {
            val forkId = nextId("fork")
            val joinId = nextId("join")
            nodes += Node(forkId, RichLabel.Empty, NodeShape.ForkBar, style(fork = true))
            nodes += Node(joinId, RichLabel.Empty, NodeShape.ForkBar, style(fork = true))
            for (branch in block.branches) {
                val res = buildSequence(branch, nodes, edges)
                res.entry?.let { edges += solidEdge(forkId, it) }
                for (exit in res.exits) edges += solidEdge(exit, joinId)
            }
            BuildResult(forkId, listOf(joinId))
        }
    }

    private fun nextId(prefix: String): NodeId = NodeId("$prefix#${idCounter++}")

    private fun makeNode(prefix: String, label: String, shape: NodeShape, style: NodeStyle): Node {
        val id = nextId(prefix)
        return Node(id = id, label = if (label.isEmpty()) RichLabel.Empty else RichLabel.Plain(label), shape = shape, style = style)
    }

    private fun style(
        action: Boolean = false,
        decision: Boolean = false,
        note: Boolean = false,
        start: Boolean = false,
        stop: Boolean = false,
        fork: Boolean = false,
    ): NodeStyle = when {
        start -> NodeStyle(fill = ArgbColor(0xFF263238.toInt()), stroke = ArgbColor(0xFF263238.toInt()), strokeWidth = 1.5f)
        stop -> NodeStyle(fill = ArgbColor(0xFFFFFFFF.toInt()), stroke = ArgbColor(0xFF263238.toInt()), strokeWidth = 1.5f)
        fork -> NodeStyle(fill = ArgbColor(0xFF263238.toInt()), stroke = ArgbColor(0xFF263238.toInt()), strokeWidth = 1.5f)
        note -> NodeStyle(fill = ArgbColor(0xFFFFF8E1.toInt()), stroke = ArgbColor(0xFFFFA000.toInt()), strokeWidth = 1.5f, textColor = ArgbColor(0xFF5D4037.toInt()))
        decision -> NodeStyle(fill = ArgbColor(0xFFE8F5E9.toInt()), stroke = ArgbColor(0xFF2E7D32.toInt()), strokeWidth = 1.5f, textColor = ArgbColor(0xFF1B5E20.toInt()))
        action -> NodeStyle(fill = ArgbColor(0xFFE3F2FD.toInt()), stroke = ArgbColor(0xFF1565C0.toInt()), strokeWidth = 1.5f, textColor = ArgbColor(0xFF0D47A1.toInt()))
        else -> NodeStyle.Default
    }

    private fun solidEdge(from: NodeId, to: NodeId): Edge = Edge(
        from = from,
        to = to,
        kind = EdgeKind.Solid,
        arrow = ArrowEnds.ToOnly,
        style = EdgeStyle(color = ArgbColor(0xFF546E7A.toInt()), width = 1.5f),
    )

    private fun labelledEdge(from: NodeId, to: NodeId, label: String): Edge =
        solidEdge(from, to).copy(label = label.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain))

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val label = labelTextOf(node)
            nodeSizes[node.id] = when (node.shape) {
                NodeShape.StartCircle, NodeShape.EndCircle -> Size(28f, 28f)
                NodeShape.ForkBar -> Size(80f, 12f)
                NodeShape.Note -> {
                    val m = textMeasurer.measure(label, labelFont, maxWidth = 160f)
                    Size((m.width + 22f).coerceAtLeast(96f), (m.height + 18f).coerceAtLeast(56f))
                }
                NodeShape.Diamond -> {
                    val m = textMeasurer.measure(label, labelFont, maxWidth = 120f)
                    Size((m.width + 44f).coerceAtLeast(92f), (m.height + 36f).coerceAtLeast(72f))
                }
                else -> {
                    val m = textMeasurer.measure(label, labelFont, maxWidth = 200f)
                    Size((m.width + 28f).coerceAtLeast(132f), (m.height + 20f).coerceAtLeast(56f))
                }
            }
        }
    }

    private fun render(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        for (node in ir.nodes) {
            val rect = laidOut.nodePositions[node.id] ?: continue
            val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE3F2FD.toInt())
            val stroke = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF1565C0.toInt())
            val text = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF0D47A1.toInt())
            when (node.shape) {
                NodeShape.StartCircle -> out += DrawCommand.FillRect(rect, fill, corner = rect.size.width / 2f, z = 2)
                NodeShape.EndCircle -> {
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), stroke, corner = rect.size.width / 2f, z = 2)
                    val inner = Rect.ltrb(rect.left + 4f, rect.top + 4f, rect.right - 4f, rect.bottom - 4f)
                    out += DrawCommand.FillRect(inner, stroke, corner = inner.size.width / 2f, z = 3)
                }
                NodeShape.ForkBar -> out += DrawCommand.FillRect(rect, stroke, corner = 2f, z = 2)
                NodeShape.Diamond -> {
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    val path = PathCmd(
                        listOf(
                            PathOp.MoveTo(Point(cx, rect.top)),
                            PathOp.LineTo(Point(rect.right, cy)),
                            PathOp.LineTo(Point(cx, rect.bottom)),
                            PathOp.LineTo(Point(rect.left, cy)),
                            PathOp.Close,
                        ),
                    )
                    out += DrawCommand.FillPath(path, fill, z = 2)
                    out += DrawCommand.StrokePath(path, Stroke(width = 1.5f), stroke, z = 3)
                    drawCenteredText(labelTextOf(node), rect, text, out)
                }
                NodeShape.Note -> {
                    out += DrawCommand.FillRect(rect, fill, corner = 4f, z = 2)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), stroke, corner = 4f, z = 3)
                    drawCenteredText(labelTextOf(node), rect, text, out)
                }
                else -> {
                    out += DrawCommand.FillRect(rect, fill, corner = 10f, z = 2)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1.5f), stroke, corner = 10f, z = 3)
                    drawCenteredText(labelTextOf(node), rect, text, out)
                }
            }
        }
        for ((index, route) in laidOut.edgeRoutes.withIndex()) {
            val edge = ir.edges.getOrNull(index) ?: continue
            val pts = route.points
            if (pts.size < 2) continue
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
            out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = edge.style.width ?: 1.5f, dash = edge.style.dash), color, z = 1)
            out += openArrowHead(pts[pts.size - 2], pts.last(), color)
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
                    z = 4,
                )
            }
        }
        return out
    }

    private fun drawCenteredText(text: String, rect: Rect, color: Color, out: MutableList<DrawCommand>) {
        if (text.isEmpty()) return
        out += DrawCommand.DrawText(
            text = text,
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
            font = labelFont,
            color = color,
            maxWidth = rect.size.width - 16f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 4,
        )
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f),
            color = color,
            z = 3,
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
            z = 3,
        )
    }

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }
}
