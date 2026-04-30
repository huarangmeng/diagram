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
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.stated.StateDiagramLayout
import com.hrm.diagram.parser.plantuml.PlantUmlStateParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlStateSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlStateParser()
    private val layout = StateDiagramLayout(textMeasurer)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir: StateIR = parser.snapshot()
        val laidOut = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(
                direction = ir.styleHints.direction,
                incremental = !isFinal,
                allowGlobalReflow = isFinal,
            ),
        ).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = renderState(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun renderState(ir: StateIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val boxFill = Color(0xFFE8F5E9U.toInt())
        val boxStroke = Color(0xFF2E7D32U.toInt())
        val compositeFill = Color(0xFFF1F8E9U.toInt())
        val compositeStroke = Color(0xFF33691EU.toInt())
        val textColor = Color(0xFF1B5E20U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val noteFill = Color(0xFFFFF8E1U.toInt())
        val noteStroke = Color(0xFFFFA000U.toInt())
        val pseudoFill = Color(0xFF000000U.toInt())

        val solid = Stroke(width = 1.5f)
        val nodeFont = FontSpec(family = "sans-serif", sizeSp = 12f)
        val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 10f)
        val pseudoFont = FontSpec(family = "sans-serif", sizeSp = 11f)

        for (s in ir.states) {
            if (s.kind != StateKind.Composite) continue
            val r = laidOut.nodePositions[s.id] ?: continue
            out += DrawCommand.FillRect(rect = r, color = compositeFill, corner = 8f, z = 0)
            out += DrawCommand.StrokeRect(rect = r, stroke = solid, color = compositeStroke, corner = 8f, z = 1)
            val title = s.description ?: s.name
            if (title.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = title,
                    origin = Point(r.left + 8f, r.top + 4f),
                    font = nodeFont,
                    color = compositeStroke,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    z = 2,
                )
            }
        }

        for (s in ir.states) {
            val r = laidOut.nodePositions[s.id] ?: continue
            when (s.kind) {
                StateKind.Composite -> {}
                StateKind.Initial -> {
                    val w = r.right - r.left
                    out += DrawCommand.FillRect(rect = r, color = pseudoFill, corner = w / 2f, z = 4)
                }
                StateKind.Final -> {
                    val w = r.right - r.left
                    out += DrawCommand.StrokeRect(rect = r, stroke = solid, color = pseudoFill, corner = w / 2f, z = 4)
                    val pad = 4f
                    val inner = Rect.ltrb(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
                    val iw = inner.right - inner.left
                    out += DrawCommand.FillRect(rect = inner, color = pseudoFill, corner = iw / 2f, z = 5)
                }
                StateKind.Choice -> {
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
                    out += DrawCommand.FillPath(path = path, color = boxFill, z = 4)
                    out += DrawCommand.StrokePath(path = path, stroke = solid, color = boxStroke, z = 5)
                }
                StateKind.Fork, StateKind.Join -> {
                    out += DrawCommand.FillRect(rect = r, color = pseudoFill, corner = 2f, z = 4)
                }
                StateKind.History, StateKind.DeepHistory -> {
                    val w = r.right - r.left
                    out += DrawCommand.FillRect(rect = r, color = boxFill, corner = w / 2f, z = 4)
                    out += DrawCommand.StrokeRect(rect = r, stroke = solid, color = boxStroke, corner = w / 2f, z = 5)
                    out += DrawCommand.DrawText(
                        text = if (s.kind == StateKind.DeepHistory) "H*" else "H",
                        origin = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f),
                        font = pseudoFont,
                        color = textColor,
                        anchorX = TextAnchorX.Center,
                        anchorY = TextAnchorY.Middle,
                        z = 6,
                    )
                }
                StateKind.Simple -> {
                    out += DrawCommand.FillRect(rect = r, color = boxFill, corner = 8f, z = 4)
                    out += DrawCommand.StrokeRect(rect = r, stroke = solid, color = boxStroke, corner = 8f, z = 5)
                    val name = s.description ?: s.name
                    if (name.isNotEmpty()) {
                        out += DrawCommand.DrawText(
                            text = name,
                            origin = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f),
                            font = nodeFont,
                            color = textColor,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 6,
                        )
                    }
                }
            }
        }

        for ((id, rect) in laidOut.clusterRects) {
            if (!id.value.startsWith("note#")) continue
            val noteIdx = id.value.removePrefix("note#").toIntOrNull() ?: continue
            val note = ir.notes.getOrNull(noteIdx) ?: continue
            out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 7)
            out += DrawCommand.StrokeRect(rect = rect, stroke = solid, color = noteStroke, corner = 4f, z = 8)
            val text = (note.text as? RichLabel.Plain)?.text.orEmpty()
            if (text.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = text,
                    origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                    font = nodeFont,
                    color = textColor,
                    maxWidth = rect.right - rect.left - 8f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 9,
                )
            }
        }

        for ((idx, route) in laidOut.edgeRoutes.withIndex()) {
            val tr = ir.transitions.getOrNull(idx) ?: continue
            val pts = route.points
            if (pts.size < 2) continue
            val from = pts.first()
            val to = pts.last()
            val path = if (route.kind == RouteKind.Bezier && pts.size >= 4 && (pts.size - 1) % 3 == 0) {
                val ops = ArrayList<PathOp>(1 + (pts.size - 1) / 3)
                ops += PathOp.MoveTo(pts[0])
                var i = 1
                while (i + 2 <= pts.size - 1) {
                    ops += PathOp.CubicTo(pts[i], pts[i + 1], pts[i + 2])
                    i += 3
                }
                PathCmd(ops)
            } else {
                PathCmd(listOf(PathOp.MoveTo(from), PathOp.LineTo(to)))
            }
            out += DrawCommand.StrokePath(path = path, stroke = solid, color = edgeColor, z = 3)
            val tangentFrom = if (route.kind == RouteKind.Bezier && pts.size >= 4) pts[pts.size - 2] else from
            out += openArrowHead(tangentFrom, to, edgeColor)
            val labelText = (tr.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelText.isNotEmpty()) {
                val labelPoint = if (route.kind == RouteKind.Bezier && pts.size >= 4) {
                    cubicMidPoint(pts[0], pts[1], pts[2], pts[3])
                } else {
                    Point((from.x + to.x) / 2f, (from.y + to.y) / 2f)
                }
                out += DrawCommand.DrawText(
                    text = labelText,
                    origin = Point(labelPoint.x, labelPoint.y - 4f),
                    font = edgeLabelFont,
                    color = textColor,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Bottom,
                    z = 9,
                )
            }
        }

        return out
    }

    private fun cubicMidPoint(p0: Point, p1: Point, p2: Point, p3: Point): Point {
        val t = 0.5f
        val mt = 1f - t
        return Point(
            mt * mt * mt * p0.x + 3f * mt * mt * t * p1.x + 3f * mt * t * t * p2.x + t * t * t * p3.x,
            mt * mt * mt * p0.y + 3f * mt * mt * t * p1.y + 3f * mt * t * t * p2.y + t * t * t * p3.y,
        )
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f),
            color = color,
            z = 4,
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
            z = 4,
        )
    }
}
