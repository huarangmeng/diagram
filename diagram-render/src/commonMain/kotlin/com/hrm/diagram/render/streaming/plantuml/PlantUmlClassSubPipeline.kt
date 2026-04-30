package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassMember
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Visibility
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.classd.ClassDiagramLayout
import com.hrm.diagram.parser.plantuml.PlantUmlClassParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlClassSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlClassParser()
    private val layout = ClassDiagramLayout(textMeasurer)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir: ClassIR = parser.snapshot()
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
            drawCommands = renderClass(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun renderClass(ir: ClassIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val boxFill = Color(0xFFFFFDE7U.toInt())
        val boxStroke = Color(0xFF6D4C41U.toInt())
        val headerFill = Color(0xFFFFE0B2U.toInt())
        val textColor = Color(0xFF3E2723U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val noteFill = Color(0xFFFFF8E1U.toInt())
        val noteStroke = Color(0xFFFFA000U.toInt())
        val solid = Stroke(width = 1.5f)
        val dashed = Stroke(width = 1.5f, dash = listOf(6f, 4f))
        val headerFont = FontSpec(family = "sans-serif", sizeSp = 13f)
        val memberFont = FontSpec(family = "sans-serif", sizeSp = 11f)
        val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 10f)
        val sectionPad = 8f
        val rowGap = 2f

        for (c in ir.classes) {
            val r = laidOut.nodePositions[c.id] ?: continue
            val headerText = buildString {
                c.stereotype?.let { append("«").append(it).append("»\n") }
                append(c.name)
                c.generics?.let { append("<").append(it).append(">") }
            }
            val headerMetrics = textMeasurer.measure(headerText, headerFont)
            val headerH = headerMetrics.height + sectionPad

            out += DrawCommand.FillRect(rect = r, color = boxFill, corner = 4f, z = 2)
            out += DrawCommand.StrokeRect(rect = r, stroke = solid, color = boxStroke, corner = 4f, z = 4)
            out += DrawCommand.FillRect(
                rect = com.hrm.diagram.core.draw.Rect.ltrb(r.left, r.top, r.right, r.top + headerH),
                color = headerFill,
                corner = 4f,
                z = 3,
            )
            out += DrawCommand.DrawText(
                text = headerText,
                origin = Point((r.left + r.right) / 2f, r.top + sectionPad / 2f),
                font = headerFont,
                color = textColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Top,
                z = 5,
            )

            val attrs = c.members.filter { !it.isMethod }
            val methods = c.members.filter { it.isMethod }
            if (attrs.isNotEmpty() || methods.isNotEmpty()) {
                val divY = r.top + headerH
                out += DrawCommand.StrokePath(
                    path = PathCmd(listOf(PathOp.MoveTo(Point(r.left, divY)), PathOp.LineTo(Point(r.right, divY)))),
                    stroke = solid,
                    color = boxStroke,
                    z = 4,
                )
                var y = divY + rowGap
                for (a in attrs) {
                    val text = renderMemberLine(a)
                    val m = textMeasurer.measure(text, memberFont)
                    out += DrawCommand.DrawText(
                        text = text,
                        origin = Point(r.left + 6f, y),
                        font = memberFont,
                        color = textColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 5,
                    )
                    y += m.height
                }
                if (attrs.isNotEmpty() && methods.isNotEmpty()) {
                    out += DrawCommand.StrokePath(
                        path = PathCmd(listOf(PathOp.MoveTo(Point(r.left, y + rowGap)), PathOp.LineTo(Point(r.right, y + rowGap)))),
                        stroke = solid,
                        color = boxStroke,
                        z = 4,
                    )
                    y += rowGap * 2 + 2f
                }
                for (m in methods) {
                    val text = renderMemberLine(m)
                    val tm = textMeasurer.measure(text, memberFont)
                    out += DrawCommand.DrawText(
                        text = text,
                        origin = Point(r.left + 6f, y),
                        font = memberFont,
                        color = textColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 5,
                    )
                    y += tm.height
                }
            }
        }

        for ((id, rect) in laidOut.clusterRects) {
            if (!id.value.startsWith("note#")) continue
            val noteIdx = id.value.removePrefix("note#").toIntOrNull() ?: continue
            val note = ir.notes.getOrNull(noteIdx) ?: continue
            out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 6)
            out += DrawCommand.StrokeRect(rect = rect, stroke = solid, color = noteStroke, corner = 4f, z = 7)
            val text = (note.text as? RichLabel.Plain)?.text.orEmpty()
            if (text.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = text,
                    origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                    font = memberFont,
                    color = textColor,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 8,
                )
            }
        }

        for ((idx, route) in laidOut.edgeRoutes.withIndex()) {
            val rel = ir.relations.getOrNull(idx) ?: continue
            val pts = route.points
            if (pts.size < 2) continue
            val from = pts.first()
            val to = pts.last()
            val stroke = if (rel.kind == ClassRelationKind.Dependency || rel.kind == ClassRelationKind.Realization || rel.kind == ClassRelationKind.LinkDashed) dashed else solid
            val path = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4) {
                val ops = ArrayList<PathOp>()
                ops += PathOp.MoveTo(pts[0])
                var i = 1
                while (i + 2 <= pts.lastIndex) {
                    ops += PathOp.CubicTo(pts[i], pts[i + 1], pts[i + 2])
                    i += 3
                }
                PathCmd(ops)
            } else {
                PathCmd(listOf(PathOp.MoveTo(from), PathOp.LineTo(to)))
            }
            out += DrawCommand.StrokePath(path = path, stroke = stroke, color = edgeColor, z = 1)
            val tangentFrom = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4) pts[pts.size - 2] else from
            when (rel.kind) {
                ClassRelationKind.Inheritance, ClassRelationKind.Realization -> out += hollowTriangle(tangentFrom, to, edgeColor)
                ClassRelationKind.Composition -> out += filledDiamond(tangentFrom, to, edgeColor)
                ClassRelationKind.Aggregation -> out += hollowDiamond(tangentFrom, to, edgeColor)
                ClassRelationKind.Association, ClassRelationKind.Dependency -> out += openArrowHead(tangentFrom, to, edgeColor)
                ClassRelationKind.Link, ClassRelationKind.LinkDashed -> {}
            }
            rel.fromCardinality?.takeIf { it.isNotEmpty() }?.let {
                out += cardinalityText(from, pts.getOrElse(1) { to }, it, edgeLabelFont, textColor, atStart = true)
            }
            rel.toCardinality?.takeIf { it.isNotEmpty() }?.let {
                out += cardinalityText(tangentFrom, to, it, edgeLabelFont, textColor, atStart = false)
            }
            val labelText = (rel.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelText.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = labelText,
                    origin = Point((from.x + to.x) / 2f, (from.y + to.y) / 2f - 4f),
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

    private fun renderMemberLine(member: ClassMember): String {
        val prefix = when (member.visibility) {
            Visibility.PUBLIC -> "+"
            Visibility.PRIVATE -> "-"
            Visibility.PROTECTED -> "#"
            Visibility.PACKAGE -> "~"
        }
        val base = if (member.isMethod) {
            buildString {
                append(prefix).append(member.name).append('(')
                append(member.params.joinToString(", ") { it.type?.let { type -> "${it.name}: $type" } ?: it.name })
                append(')')
                member.type?.let { append(" : ").append(it) }
            }
        } else {
            buildString {
                append(prefix).append(member.name)
                member.type?.let { append(" : ").append(it) }
            }
        }
        return buildString {
            append(base)
            if (member.isStatic) append(" {static}")
            if (member.isAbstract) append(" {abstract}")
        }
    }

    private fun cardinalityText(from: Point, to: Point, text: String, font: FontSpec, color: Color, atStart: Boolean): DrawCommand {
        val t = if (atStart) 0.15f else 0.85f
        return DrawCommand.DrawText(
            text = text,
            origin = Point(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t - 4f),
            font = font,
            color = color,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 9,
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

    private fun hollowTriangle(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, 12f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close)),
            stroke = Stroke(width = 1.5f),
            color = color,
            z = 2,
        )
    }

    private fun filledDiamond(from: Point, to: Point, color: Color): DrawCommand =
        DrawCommand.FillPath(path = diamondPath(from, to, 10f), color = color, z = 2)

    private fun hollowDiamond(from: Point, to: Point, color: Color): DrawCommand =
        DrawCommand.StrokePath(path = diamondPath(from, to, 10f), stroke = Stroke(width = 1.5f), color = color, z = 2)

    private fun diamondPath(from: Point, to: Point, size: Float): PathCmd {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return PathCmd(listOf(PathOp.MoveTo(to), PathOp.Close))
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val mid = Point(to.x - ux * size, to.y - uy * size)
        val tail = Point(to.x - ux * size * 2f, to.y - uy * size * 2f)
        val side1 = Point(mid.x + nx * size * 0.5f, mid.y + ny * size * 0.5f)
        val side2 = Point(mid.x - nx * size * 0.5f, mid.y - ny * size * 0.5f)
        return PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(side1), PathOp.LineTo(tail), PathOp.LineTo(side2), PathOp.Close))
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
        return Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f) to Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
    }

    @Suppress("unused")
    private fun unusedNodeId() = NodeId("_")

    @Suppress("unused")
    private fun unusedRel() = ClassRelation(NodeId("a"), NodeId("b"), ClassRelationKind.Link)
}
