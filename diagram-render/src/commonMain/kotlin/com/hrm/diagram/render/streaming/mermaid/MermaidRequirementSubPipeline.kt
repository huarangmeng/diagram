package com.hrm.diagram.render.streaming.mermaid

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
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.mermaid.MermaidRequirementParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.sqrt

internal class MermaidRequirementSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private val parser = MermaidRequirementParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private var graphStyles: MermaidGraphStyleState? = null
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(180f, 96f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(180f, 96f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val boldLabelFont = labelFont.copy(weight = 700)
    private val italicLabelFont = labelFont.copy(italic = true)
    private val boldItalicLabelFont = labelFont.copy(weight = 700, italic = true)
    private val codeLabelFont = labelFont.copy(family = "monospace")
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun updateGraphStyles(styles: MermaidGraphStyleState) {
        graphStyles = styles
    }

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        val addedNodeIds = ArrayList<NodeId>()
        for (line in lines) {
            val batch = parser.acceptLine(line)
            for (patch in batch.patches) {
                newPatches += patch
                if (patch is IrPatch.AddNode) addedNodeIds += patch.node.id
            }
        }
        val ir0 = parser.snapshot()
        val ir = graphStyles?.applyTo(ir0) ?: ir0
        for (node in ir.nodes) {
            val metrics = measureLabel(node)
            val padX = if (node.shape is NodeShape.RoundedBox) 18f else 14f
            val padY = 12f
            nodeSizes[node.id] = Size((metrics.width + padX * 2f).coerceAtLeast(140f), (metrics.height + padY * 2f).coerceAtLeast(72f))
        }
        val laidOut: LaidOutDiagram = layout.layout(
            previousSnapshot.laidOut,
            ir,
            LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        ).copy(seq = seq)
        val drawCommands = flowchartRender(ir, laidOut)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }
        val snapshot = DiagramSnapshot(
            ir = ir,
            laidOut = laidOut,
            drawCommands = drawCommands,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(
            snapshot = snapshot,
            patch = SessionPatch(
                seq = seq,
                addedNodes = addedNodeIds,
                addedEdges = newPatches.filterIsInstance<IrPatch.AddEdge>().map { it.edge },
                addedDrawCommands = drawCommands,
                newDiagnostics = newDiagnostics,
                isFinal = isFinal,
            ),
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
    }

    private fun flowchartRender(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(ir.nodes.size * 3 + ir.edges.size * 2)
        val defaultNodeFill = Color(0xFFE3F2FD.toInt())
        val defaultNodeStroke = Color(0xFF1565C0.toInt())
        val defaultTextColor = Color(0xFF0D47A1.toInt())
        val defaultEdgeColor = Color(0xFF455A64.toInt())
        val defaultEdgeLabelColor = Color(0xFF263238.toInt())
        val defaultEdgeLabelBg = Color(0xF0FFFFFF.toInt())

        for (n in ir.nodes) {
            val r = laidOut.nodePositions[n.id] ?: continue
            val nodeFill = n.style.fill?.let { Color(it.argb) } ?: defaultNodeFill
            val nodeStroke = n.style.stroke?.let { Color(it.argb) } ?: defaultNodeStroke
            val textColor = n.style.textColor?.let { Color(it.argb) } ?: defaultTextColor
            val stroke = Stroke(width = n.style.strokeWidth ?: 1.5f)
            when (n.shape) {
                is NodeShape.Diamond -> {
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val path = PathCmd(
                        listOf(
                            PathOp.MoveTo(Point(cx, r.top)),
                            PathOp.LineTo(Point(r.right, cy)),
                            PathOp.LineTo(Point(cx, r.bottom)),
                            PathOp.LineTo(Point(r.left, cy)),
                            PathOp.Close,
                        ),
                    )
                    out += DrawCommand.FillPath(path = path, color = nodeFill, z = 1)
                    out += DrawCommand.StrokePath(path = path, stroke = stroke, color = nodeStroke, z = 2)
                }
                else -> {
                    val corner = when (n.shape) {
                        is NodeShape.Circle -> minOf(r.size.width, r.size.height) / 2f
                        is NodeShape.Stadium -> minOf(r.size.width, r.size.height) / 2f
                        is NodeShape.RoundedBox -> 14f
                        else -> 4f
                    }
                    out += DrawCommand.FillRect(rect = r, color = nodeFill, corner = corner, z = 1)
                    out += DrawCommand.StrokeRect(rect = r, stroke = stroke, color = nodeStroke, corner = corner, z = 2)
                }
            }
            drawNodeLabel(n, r, textColor, out)
        }
        for ((idx, route) in laidOut.edgeRoutes.withIndex()) {
            val pts = route.points
            if (pts.size < 2) continue
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
            val path = PathCmd(ops)
            val edge = ir.edges.getOrNull(idx) ?: continue
            val edgeColor = edge.style.color?.let { Color(it.argb) } ?: defaultEdgeColor
            val edgeStroke = Stroke(width = edge.style.width ?: 1.5f, dash = edge.style.dash)
            out += DrawCommand.StrokePath(path = path, stroke = edgeStroke, color = edgeColor, z = 0)
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
            val text = (edge.label as? RichLabel.Plain)?.text ?: continue
            if (text.isEmpty()) continue
            val midIdx = pts.size / 2
            val midPoint = pts[midIdx]
            val metrics = textMeasurer.measure(text, edgeLabelFont)
            val padding = 4f
            val bgRect = Rect.ltrb(
                midPoint.x - metrics.width / 2f - padding,
                midPoint.y - metrics.height / 2f - padding / 2f,
                midPoint.x + metrics.width / 2f + padding,
                midPoint.y + metrics.height / 2f + padding / 2f,
            )
            val bg = edge.style.labelBg?.let { Color(it.argb) } ?: defaultEdgeLabelBg
            out += DrawCommand.FillRect(rect = bgRect, color = bg, corner = 3f, z = 4)
            out += DrawCommand.DrawText(
                text = text,
                origin = midPoint,
                font = edgeLabelFont,
                color = defaultEdgeLabelColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 5,
            )
        }
        return out
    }

    private fun labelTextOf(n: Node): String =
        when (val label = n.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotEmpty() } ?: n.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotEmpty() } ?: n.id.value
            else -> n.id.value
        }

    private fun measureLabel(node: Node): Size {
        val layout = richLabelLayout(node.label, maxWidth = 220f)
        return Size(layout.width, layout.height)
    }

    private fun drawNodeLabel(node: Node, rect: Rect, textColor: Color, out: MutableList<DrawCommand>) {
        val layout = richLabelLayout(node.label, maxWidth = rect.size.width - 16f)
        val startY = rect.top + ((rect.size.height - layout.height) / 2f).coerceAtLeast(6f)
        var y = startY
        for (line in layout.lines) {
            var x = rect.left + (rect.size.width - line.width) / 2f
            for (run in line.runs) {
                if (run.text.isNotEmpty()) {
                    out += DrawCommand.DrawText(
                        text = run.text,
                        origin = Point(x, y),
                        font = run.font,
                        color = if (run.isLink) Color(0xFF1565C0.toInt()) else textColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 3,
                    )
                }
                x += run.width
            }
            y += line.height
        }
    }

    private fun richLabelLayout(label: RichLabel, maxWidth: Float): RichTextLayout {
        val raw = when (label) {
            is RichLabel.Markdown -> label.source
            is RichLabel.Plain -> label.text
            else -> ""
        }
        val source = raw.ifEmpty { "" }
        val lines = source.split('\n')
        val lineLayouts = lines.map { layoutMarkdownLine(it, maxWidth) }
        val width = lineLayouts.maxOfOrNull { it.width } ?: 0f
        val height = lineLayouts.sumOf { it.height.toDouble() }.toFloat().coerceAtLeast(labelFont.sizeSp + 4f)
        return RichTextLayout(width = width, height = height, lines = lineLayouts)
    }

    private fun layoutMarkdownLine(line: String, maxWidth: Float): RichTextLine {
        val runs = parseMarkdownRuns(line)
            .map { token ->
                val font = fontFor(token)
                val metrics = textMeasurer.measure(token.text, font, maxWidth = maxWidth)
                RichTextRun(
                    text = token.text,
                    font = font,
                    width = metrics.width,
                    height = metrics.height,
                    isLink = token.isLink,
                )
            }
        val width = runs.sumOf { it.width.toDouble() }.toFloat()
        val height = runs.maxOfOrNull { it.height } ?: (labelFont.sizeSp + 4f)
        return RichTextLine(width = width, height = height, runs = runs)
    }

    private fun parseMarkdownRuns(line: String): List<MarkdownToken> {
        if (line.isEmpty()) return listOf(MarkdownToken(text = ""))
        val out = ArrayList<MarkdownToken>()
        var i = 0
        while (i < line.length) {
            if (line.startsWith("**", i) || line.startsWith("__", i)) {
                val marker = line.substring(i, i + 2)
                val end = line.indexOf(marker, i + 2)
                if (end > i + 2) {
                    out += MarkdownToken(text = line.substring(i + 2, end), bold = true)
                    i = end + 2
                    continue
                }
            }
            if (line[i] == '*' || line[i] == '_') {
                val marker = line[i]
                val end = line.indexOf(marker, i + 1)
                if (end > i + 1) {
                    out += MarkdownToken(text = line.substring(i + 1, end), italic = true)
                    i = end + 1
                    continue
                }
            }
            if (line[i] == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    out += MarkdownToken(text = line.substring(i + 1, end), code = true)
                    i = end + 1
                    continue
                }
            }
            if (line[i] == '[') {
                val textEnd = line.indexOf(']', i + 1)
                if (textEnd > i + 1 && textEnd + 1 < line.length && line[textEnd + 1] == '(') {
                    val urlEnd = line.indexOf(')', textEnd + 2)
                    if (urlEnd > textEnd + 2) {
                        out += MarkdownToken(text = line.substring(i + 1, textEnd), isLink = true)
                        i = urlEnd + 1
                        continue
                    }
                }
            }
            val next = nextMarkdownBoundary(line, i + 1)
            out += MarkdownToken(text = line.substring(i, next))
            i = next
        }
        return out.filter { it.text.isNotEmpty() }.ifEmpty { listOf(MarkdownToken(text = "")) }
    }

    private fun nextMarkdownBoundary(line: String, start: Int): Int {
        var i = start
        while (i < line.length) {
            val ch = line[i]
            if (ch == '*' || ch == '_' || ch == '`' || ch == '[') return i
            i++
        }
        return line.length
    }

    private fun fontFor(token: MarkdownToken): FontSpec = when {
        token.code -> codeLabelFont
        token.bold && token.italic -> boldItalicLabelFont
        token.bold -> boldLabelFont
        token.italic -> italicLabelFont
        else -> labelFont
    }

    private data class MarkdownToken(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val isLink: Boolean = false,
    )

    private data class RichTextRun(
        val text: String,
        val font: FontSpec,
        val width: Float,
        val height: Float,
        val isLink: Boolean = false,
    )

    private data class RichTextLine(
        val width: Float,
        val height: Float,
        val runs: List<RichTextRun>,
    )

    private data class RichTextLayout(
        val width: Float,
        val height: Float,
        val lines: List<RichTextLine>,
    )

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.FillPath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.Close)),
            color = color,
            z = 1,
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
        val path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close))
        return DrawCommand.FillPath(path = path, color = color, z = 1)
}
}
