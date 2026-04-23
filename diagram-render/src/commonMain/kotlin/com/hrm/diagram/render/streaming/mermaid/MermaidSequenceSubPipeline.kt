package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.sequence.SequenceLayouts
import com.hrm.diagram.parser.mermaid.MermaidSequenceParser
import kotlin.math.sqrt

/**
 * Sub-pipeline that handles `sequenceDiagram` sources. Owns its own parser, layout, and
 * renderer; results funnel back through the same [PipelineAdvance] envelope as the flowchart
 * sub-pipeline.
 */
internal class MermaidSequenceSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidSequenceParser()
    private val layout = SequenceLayouts.forSequence(textMeasurer)

    override fun acceptLines(
        previousSnapshot: com.hrm.diagram.render.streaming.DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): com.hrm.diagram.render.streaming.PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        for (lineToks in lines) {
            val batch = parser.acceptLine(lineToks)
            for (p in batch.patches) newPatches += p
        }

        val ir: SequenceIR = parser.snapshot()
        val opts = LayoutOptions(
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout.layout(previousSnapshot.laidOut, ir, opts).copy(seq = seq)
        val drawCommands = renderSequence(ir, laidOut)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snapshot = com.hrm.diagram.render.streaming.DiagramSnapshot(
            ir = ir,
            laidOut = laidOut,
            drawCommands = drawCommands,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = com.hrm.diagram.render.streaming.SessionPatch(
            seq = seq,
            addedNodes = emptyList(),
            addedEdges = emptyList(),
            addedDrawCommands = drawCommands,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return com.hrm.diagram.render.streaming.PipelineAdvance(
            snapshot = snapshot,
            patch = patch,
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    private fun renderSequence(ir: SequenceIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val headerFill = Color(0xFFE3F2FDU.toInt())
        val headerStroke = Color(0xFF1565C0U.toInt())
        val headerText = Color(0xFF0D47A1U.toInt())
        val lifelineColor = Color(0xFF90A4AEU.toInt())
        val msgColor = Color(0xFF263238U.toInt())
        val msgText = Color(0xFF263238U.toInt())
        val noteFill = Color(0xFFFFF8E1U.toInt())
        val noteStroke = Color(0xFFFFA000U.toInt())
        val activationFill = Color(0xFFFFFFFFU.toInt())
        val activationStroke = Color(0xFF1565C0U.toInt())
        val fragStroke = Color(0xFF6A1B9AU.toInt())

        val headerStrokeStyle = Stroke(width = 1.5f)
        val solidStroke = Stroke(width = 1.5f)
        val dashedStroke = Stroke(width = 1.5f, dash = listOf(6f, 4f))
        val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
        val msgFont = FontSpec(family = "sans-serif", sizeSp = 11f)

        val bottomY = laidOut.bounds.bottom

        // Headers + lifelines.
        for (p in ir.participants) {
            val r = laidOut.nodePositions[p.id] ?: continue
            out += DrawCommand.FillRect(rect = r, color = headerFill, corner = 6f, z = 2)
            out += DrawCommand.StrokeRect(rect = r, stroke = headerStrokeStyle, color = headerStroke, corner = 6f, z = 3)
            val text = (p.label as? RichLabel.Plain)?.text?.takeIf { it.isNotEmpty() } ?: p.id.value
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            out += DrawCommand.DrawText(
                text = text,
                origin = Point(cx, cy),
                font = labelFont,
                color = headerText,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 4,
            )
            // Lifeline (dashed) from header bottom to bottom of diagram.
            val lifelinePath = PathCmd(listOf(
                PathOp.MoveTo(Point(cx, r.bottom)),
                PathOp.LineTo(Point(cx, bottomY)),
            ))
            out += DrawCommand.StrokePath(path = lifelinePath, stroke = dashedStroke, color = lifelineColor, z = 0)
        }

        // Activation rects (clusterRects with `#act#` keys) and notes (`note#`) and fragments (`frag#`).
        for ((id, rect) in laidOut.clusterRects) {
            val v = id.value
            when {
                v.contains("#act#") -> {
                    out += DrawCommand.FillRect(rect = rect, color = activationFill, corner = 0f, z = 5)
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = activationStroke, corner = 0f, z = 6)
                }
                v.startsWith("note#") -> {
                    out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 7)
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = noteStroke, corner = 4f, z = 8)
                }
                v.startsWith("frag#") -> {
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = fragStroke, corner = 4f, z = 9)
                }
            }
        }

        // Map note rects to messages by index for label drawing.
        val noteRectsByIdx = HashMap<Int, Rect>()
        var ni = 0
        for ((id, rect) in laidOut.clusterRects) {
            if (id.value.startsWith("note#")) {
                noteRectsByIdx[ni++] = rect
            }
        }

        // Messages (arrows, labels). Iterate in order; index into edgeRoutes for non-note kinds.
        val edgeRoutesById = laidOut.edgeRoutes
        var edgeIdx = 0
        var noteIdx2 = 0
        for (msg in ir.messages) {
            when (msg.kind) {
                MessageKind.Note -> {
                    if (msg.activate || msg.deactivate) continue
                    val rect = noteRectsByIdx[noteIdx2++] ?: continue
                    val labelStr = (msg.label as? RichLabel.Plain)?.text ?: ""
                    if (labelStr.isNotEmpty()) {
                        val cx = (rect.left + rect.right) / 2f
                        val cy = (rect.top + rect.bottom) / 2f
                        out += DrawCommand.DrawText(
                            text = labelStr,
                            origin = Point(cx, cy),
                            font = msgFont,
                            color = msgText,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 10,
                        )
                    }
                }
                else -> {
                    val route = edgeRoutesById.getOrNull(edgeIdx++) ?: continue
                    val pts = route.points
                    val from = pts.first()
                    val to = pts.last()
                    val stroke = when (msg.kind) {
                        MessageKind.Reply -> dashedStroke
                        else -> solidStroke
                    }
                    val path = PathCmd(listOf(PathOp.MoveTo(from), PathOp.LineTo(to)))
                    out += DrawCommand.StrokePath(path = path, stroke = stroke, color = msgColor, z = 1)
                    when (msg.kind) {
                        MessageKind.Async -> out += openArrowHead(from, to, msgColor)
                        MessageKind.Destroy -> out += xMark(to, msgColor)
                        else -> out += filledArrowHead(from, to, msgColor)
                    }
                    val labelStr = (msg.label as? RichLabel.Plain)?.text ?: ""
                    if (labelStr.isNotEmpty()) {
                        val mx = (from.x + to.x) / 2f
                        val my = from.y - 4f
                        out += DrawCommand.DrawText(
                            text = labelStr,
                            origin = Point(mx, my),
                            font = msgFont,
                            color = msgText,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Bottom,
                            z = 10,
                        )
                    }
                }
            }
        }

        return out
    }

    private fun filledArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, size = 8f)
        val path = PathCmd(listOf(
            PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close,
        ))
        return DrawCommand.FillPath(path = path, color = color, z = 2)
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, size = 8f)
        val path = PathCmd(listOf(
            PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2),
        ))
        return DrawCommand.StrokePath(path = path, stroke = Stroke(width = 1.5f), color = color, z = 2)
    }

    private fun xMark(at: Point, color: Color): DrawCommand {
        val s = 5f
        val ops = listOf(
            PathOp.MoveTo(Point(at.x - s, at.y - s)),
            PathOp.LineTo(Point(at.x + s, at.y + s)),
            PathOp.MoveTo(Point(at.x - s, at.y + s)),
            PathOp.LineTo(Point(at.x + s, at.y - s)),
        )
        return DrawCommand.StrokePath(path = PathCmd(ops), stroke = Stroke(width = 1.5f), color = color, z = 2)
    }

    private fun headPoints(from: Point, to: Point, size: Float): Pair<Point, Point> {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return Point(to.x, to.y) to Point(to.x, to.y)
        val ux = dx / len; val uy = dy / len
        val baseX = to.x - ux * size; val baseY = to.y - uy * size
        val nx = -uy; val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        return p1 to p2
    }

    @Suppress("unused")
    private fun unusedNodeId() = NodeId("_")
}
