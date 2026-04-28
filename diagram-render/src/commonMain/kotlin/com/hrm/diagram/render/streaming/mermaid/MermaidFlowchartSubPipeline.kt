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
import com.hrm.diagram.core.text.TextMetrics
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.mermaid.MermaidFlowchartParser
import com.hrm.diagram.parser.mermaid.MermaidTokenKind
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

/**
 * Sub-pipeline that handles the original flowchart subset. Lifted out of the previous
 * monolithic `MermaidSessionPipeline` so that a top-level dispatcher can route between
 * flowchart and sequenceDiagram sources.
 */
internal class MermaidFlowchartSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidFlowchartParser()
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val nodeMetrics: MutableMap<NodeId, TextMetrics> = HashMap()
    private var graphStyles: MermaidGraphStyleState? = null
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(120f, 48f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(120f, 48f) },
    )

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
        for (lineToks in lines) {
            val batch = parser.acceptLine(lineToks)
            for (p in batch.patches) {
                newPatches += p
                if (p is IrPatch.AddNode) addedNodeIds += p.node.id
            }
        }

        val ir0: GraphIR = parser.snapshot()
        val ir: GraphIR = graphStyles?.applyTo(ir0) ?: ir0
        val needRemeasure = isFinal
        for (n in ir.nodes) {
            if (!needRemeasure && n.id in nodeSizes) continue
            val (size, metrics) = measureNode(n)
            nodeSizes[n.id] = size
            nodeMetrics[n.id] = metrics
        }
        val layoutOptions = LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout
            .layout(previousSnapshot.laidOut, ir, layoutOptions)
            .copy(seq = seq)
        val drawCommands = renderDraw(ir, laidOut)
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
        val patch = SessionPatch(
            seq = seq,
            addedNodes = addedNodeIds,
            addedEdges = newPatches.filterIsInstance<IrPatch.AddEdge>().map { it.edge },
            addedDrawCommands = drawCommands,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return PipelineAdvance(
            snapshot = snapshot,
            patch = patch,
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
        nodeMetrics.clear()
    }

    private fun labelTextOf(n: Node): String =
        (n.label as? RichLabel.Plain)?.text?.takeIf { it.isNotEmpty() } ?: n.id.value

    private fun measureNode(n: Node): Pair<Size, TextMetrics> {
        val text = labelTextOf(n)
        val maxWrap = 220f
        val raw = textMeasurer.measure(text, labelFont, maxWidth = maxWrap)
        val (padX, padY) = when (n.shape) {
            is NodeShape.Circle, is NodeShape.Diamond -> 28f to 18f
            is NodeShape.Stadium, is NodeShape.RoundedBox -> 18f to 12f
            else -> 14f to 10f
        }
        val minW = 64f; val minH = 36f
        val w = (raw.width + 2 * padX).coerceAtLeast(minW)
        var h = (raw.height + 2 * padY).coerceAtLeast(minH)
        val finalW: Float; val finalH: Float
        if (n.shape is NodeShape.Circle) {
            val side = maxOf(w, h)
            finalW = side; finalH = side
        } else if (n.shape is NodeShape.Diamond) {
            finalW = w * 1.45f
            finalH = (h * 1.45f).coerceAtLeast(minH)
            h = finalH
        } else {
            finalW = w; finalH = h
        }
        return Size(finalW, finalH) to raw
    }

    private fun renderDraw(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(ir.nodes.size * 3 + ir.edges.size * 2)
        val defaultNodeFill = Color(0xFFE3F2FDU.toInt())
        val defaultNodeStroke = Color(0xFF1565C0U.toInt())
        val defaultTextColor = Color(0xFF0D47A1U.toInt())
        val defaultEdgeColor = Color(0xFF455A64U.toInt())
        val defaultEdgeLabelColor = Color(0xFF263238U.toInt())
        val defaultEdgeLabelBg = Color(0xF0FFFFFFU.toInt())

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
                    val path = PathCmd(listOf(
                        PathOp.MoveTo(Point(cx, r.top)),
                        PathOp.LineTo(Point(r.right, cy)),
                        PathOp.LineTo(Point(cx, r.bottom)),
                        PathOp.LineTo(Point(r.left, cy)),
                        PathOp.Close,
                    ))
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
            val labelStr = labelTextOf(n)
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            out += DrawCommand.DrawText(
                text = labelStr,
                origin = Point(cx, cy),
                font = labelFont,
                color = textColor,
                maxWidth = r.size.width - 12f,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 3,
            )
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

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.FillPath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.Close)), color = color, z = 1,
        )
        val ux = dx / len; val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size; val baseY = to.y - uy * size
        val nx = -uy; val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        val path = PathCmd(listOf(
            PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close,
        ))
        return DrawCommand.FillPath(path = path, color = color, z = 1)
    }
}

/** Internal SPI used by the dispatcher to delegate per-line work. */
internal interface MermaidSubPipeline {
    /** Optional hook for Mermaid GraphIR-based styling (flowchart/erDiagram). */
    fun updateGraphStyles(styles: MermaidGraphStyleState) {}

    fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance

    fun dispose() {}
}

/** Helper kept here to avoid duplicating across sub-pipelines. */
internal fun isLineNonBlank(line: List<Token>): Boolean =
    line.any { it.kind != MermaidTokenKind.COMMENT && it.kind != MermaidTokenKind.NEWLINE }
