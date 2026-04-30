package com.hrm.diagram.render.streaming.plantuml

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
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.sequence.SequenceLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlSequenceParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlSequenceSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlSequenceParser()
    private val layout = SequenceLayouts.forSequence(textMeasurer)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir: SequenceIR = parser.snapshot()
        val laidOut = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(
                incremental = !isFinal,
                allowGlobalReflow = isFinal,
            ),
        ).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = renderSequence(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
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
            out += DrawCommand.StrokePath(
                path = PathCmd(listOf(PathOp.MoveTo(Point(cx, r.bottom)), PathOp.LineTo(Point(cx, bottomY)))),
                stroke = dashedStroke,
                color = lifelineColor,
                z = 0,
            )
        }

        for ((id, rect) in laidOut.clusterRects) {
            when {
                id.value.contains("#act#") -> {
                    out += DrawCommand.FillRect(rect = rect, color = activationFill, corner = 0f, z = 5)
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = activationStroke, corner = 0f, z = 6)
                }
                id.value.startsWith("note#") -> {
                    out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 7)
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = noteStroke, corner = 4f, z = 8)
                }
                id.value.startsWith("frag#") -> {
                    out += DrawCommand.StrokeRect(rect = rect, stroke = solidStroke, color = fragStroke, corner = 4f, z = 9)
                }
            }
        }

        val noteRectsByIndex = HashMap<Int, Rect>()
        var noteIndex = 0
        for ((id, rect) in laidOut.clusterRects) {
            if (id.value.startsWith("note#")) noteRectsByIndex[noteIndex++] = rect
        }

        var edgeIndex = 0
        var noteDrawIndex = 0
        for (msg in ir.messages) {
            when (msg.kind) {
                MessageKind.Note -> {
                    if (msg.activate || msg.deactivate) continue
                    val rect = noteRectsByIndex[noteDrawIndex++] ?: continue
                    val labelText = (msg.label as? RichLabel.Plain)?.text.orEmpty()
                    if (labelText.isNotEmpty()) {
                        out += DrawCommand.DrawText(
                            text = labelText,
                            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                            font = msgFont,
                            color = msgText,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 10,
                        )
                    }
                }
                else -> {
                    val route = laidOut.edgeRoutes.getOrNull(edgeIndex++) ?: continue
                    val from = route.points.first()
                    val to = route.points.last()
                    val stroke = if (msg.kind == MessageKind.Reply) dashedStroke else solidStroke
                    out += DrawCommand.StrokePath(
                        path = PathCmd(listOf(PathOp.MoveTo(from), PathOp.LineTo(to))),
                        stroke = stroke,
                        color = msgColor,
                        z = 1,
                    )
                    when (msg.kind) {
                        MessageKind.Async -> out += openArrowHead(from, to, msgColor)
                        MessageKind.Destroy -> out += xMark(to, msgColor)
                        else -> out += filledArrowHead(from, to, msgColor)
                    }
                    val labelText = (msg.label as? RichLabel.Plain)?.text.orEmpty()
                    if (labelText.isNotEmpty()) {
                        out += DrawCommand.DrawText(
                            text = labelText,
                            origin = Point((from.x + to.x) / 2f, from.y - 4f),
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
        val (p1, p2) = headPoints(from, to, 8f)
        return DrawCommand.FillPath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close)),
            color = color,
            z = 2,
        )
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, 8f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = 1.5f),
            color = color,
            z = 2,
        )
    }

    private fun xMark(at: Point, color: Color): DrawCommand {
        val size = 5f
        return DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(at.x - size, at.y - size)),
                    PathOp.LineTo(Point(at.x + size, at.y + size)),
                    PathOp.MoveTo(Point(at.x - size, at.y + size)),
                    PathOp.LineTo(Point(at.x + size, at.y - size)),
                ),
            ),
            stroke = Stroke(width = 1.5f),
            color = color,
            z = 2,
        )
    }

    private fun headPoints(from: Point, to: Point, size: Float): Pair<Point, Point> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return Point(to.x, to.y) to Point(to.x, to.y)
        val ux = dx / len
        val uy = dy / len
        val baseX = to.x - ux * size
        val baseY = to.y - uy * size
        val nx = -uy
        val ny = ux
        return Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f) to
            Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
    }
}
