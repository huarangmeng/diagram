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
import com.hrm.diagram.core.ir.ArrowEnds
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
import com.hrm.diagram.parser.mermaid.MermaidErParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.sqrt

/** Sub-pipeline for Mermaid `erDiagram` sources (Phase 1 subset). */
internal class MermaidErSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidErParser()
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val nodeMetrics: MutableMap<NodeId, TextMetrics> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(140f, 56f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(140f, 56f) },
    )

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        val addedNodeIds = ArrayList<NodeId>()
        for (lineToks in lines) {
            val batch: IrPatchBatch = parser.acceptLine(lineToks)
            for (p in batch.patches) {
                newPatches += p
                if (p is IrPatch.AddNode) addedNodeIds += p.node.id
            }
        }

        val ir: GraphIR = parser.snapshot()
        val needRemeasure = isFinal
        for (n in ir.nodes) {
            if (!needRemeasure && n.id in nodeSizes) continue
            val (size, metrics) = measureNode(n)
            nodeSizes[n.id] = size
            nodeMetrics[n.id] = metrics
        }
        val opts = LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout.layout(previousSnapshot.laidOut, ir, opts).copy(seq = seq)
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
        val maxWrap = 260f
        val raw = textMeasurer.measure(text, labelFont, maxWidth = maxWrap)
        val (padX, padY) = when (n.shape) {
            is NodeShape.RoundedBox -> 18f to 12f
            else -> 14f to 10f
        }
        val minW = 84f; val minH = 40f
        val w = (raw.width + 2 * padX).coerceAtLeast(minW)
        val h = (raw.height + 2 * padY).coerceAtLeast(minH)
        return Size(w, h) to raw
    }

    private fun renderDraw(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(ir.nodes.size * 3 + ir.edges.size * 2)
        val nodeFill = Color(0xFFE8F5E9U.toInt())
        val nodeStroke = Color(0xFF2E7D32U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val textColor = Color(0xFF1B5E20U.toInt())
        val edgeLabelColor = Color(0xFF263238U.toInt())
        val edgeLabelBg = Color(0xF0FFFFFFU.toInt())
        val stroke = Stroke(width = 1.5f)
        val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

        for (n in ir.nodes) {
            val r = laidOut.nodePositions[n.id] ?: continue
            val corner = when (n.shape) {
                is NodeShape.RoundedBox -> 12f
                else -> 4f
            }
            out += DrawCommand.FillRect(rect = r, color = nodeFill, corner = corner, z = 1)
            out += DrawCommand.StrokeRect(rect = r, stroke = stroke, color = nodeStroke, corner = corner, z = 2)
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            out += DrawCommand.DrawText(
                text = labelTextOf(n),
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
            out += DrawCommand.StrokePath(path = path, stroke = stroke, color = edgeColor, z = 0)

            val edge = ir.edges.getOrNull(idx) ?: continue
            if (edge.arrow != ArrowEnds.None) {
                val endTail = pts[pts.size - 2]
                val endHead = pts.last()
                val startTail = pts[1]
                val startHead = pts[0]
                when (edge.arrow) {
                    ArrowEnds.None -> Unit
                    ArrowEnds.ToOnly -> out += arrowHead(endTail, endHead, edgeColor)
                    ArrowEnds.FromOnly -> out += arrowHead(startTail, startHead, edgeColor)
                    ArrowEnds.Both -> {
                        out += arrowHead(endTail, endHead, edgeColor)
                        out += arrowHead(startTail, startHead, edgeColor)
                    }
                }
            }

            val text = (edge.label as? RichLabel.Plain)?.text ?: continue
            if (text.isEmpty()) continue
            val midPoint = pts[pts.size / 2]
            val metrics = textMeasurer.measure(text, edgeLabelFont)
            val padding = 4f
            val bgRect = Rect.ltrb(
                midPoint.x - metrics.width / 2f - padding,
                midPoint.y - metrics.height / 2f - padding / 2f,
                midPoint.x + metrics.width / 2f + padding,
                midPoint.y + metrics.height / 2f + padding / 2f,
            )
            out += DrawCommand.FillRect(rect = bgRect, color = edgeLabelBg, corner = 3f, z = 4)
            out += DrawCommand.DrawText(
                text = text,
                origin = midPoint,
                font = edgeLabelFont,
                color = edgeLabelColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 5,
            )
        }

        return out
    }

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val ux = dx / len; val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size; val baseY = to.y - uy * size
        val nx = -uy; val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        val path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close))
        return DrawCommand.FillPath(path = path, color = color, z = 1)
    }
}

