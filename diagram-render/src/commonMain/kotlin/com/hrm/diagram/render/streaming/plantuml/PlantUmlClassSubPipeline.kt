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
        val palette = paletteOf(ir)
        val classStrokeWidth = floatExtra(ir, PlantUmlClassParser.STYLE_CLASS_LINE_THICKNESS_KEY) ?: 1.5f
        val noteStrokeWidth = floatExtra(ir, PlantUmlClassParser.STYLE_NOTE_LINE_THICKNESS_KEY) ?: 1.5f
        val packageStrokeWidth = floatExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_LINE_THICKNESS_KEY) ?: 1.5f
        val solid = Stroke(width = classStrokeWidth)
        val dashed = Stroke(width = classStrokeWidth, dash = listOf(6f, 4f))
        val headerFont = fontExtra(
            ir = ir,
            fontNameKey = PlantUmlClassParser.STYLE_CLASS_FONT_NAME_KEY,
            fontSizeKey = PlantUmlClassParser.STYLE_CLASS_FONT_SIZE_KEY,
            base = FontSpec(family = "sans-serif", sizeSp = 13f),
        )
        val memberFont = fontExtra(
            ir = ir,
            fontNameKey = PlantUmlClassParser.STYLE_CLASS_FONT_NAME_KEY,
            fontSizeKey = PlantUmlClassParser.STYLE_CLASS_FONT_SIZE_KEY,
            base = FontSpec(family = "sans-serif", sizeSp = 11f),
        )
        val edgeLabelFont = FontSpec(
            family = headerFont.family,
            sizeSp = (headerFont.sizeSp - 1f).coerceAtLeast(10f),
        )
        val namespaceFont = fontExtra(
            ir = ir,
            fontNameKey = PlantUmlClassParser.STYLE_PACKAGE_FONT_NAME_KEY,
            fontSizeKey = PlantUmlClassParser.STYLE_PACKAGE_FONT_SIZE_KEY,
            base = FontSpec(family = "sans-serif", sizeSp = 11f, weight = 600),
        )
        val noteFont = fontExtra(
            ir = ir,
            fontNameKey = PlantUmlClassParser.STYLE_NOTE_FONT_NAME_KEY,
            fontSizeKey = PlantUmlClassParser.STYLE_NOTE_FONT_SIZE_KEY,
            base = FontSpec(family = "sans-serif", sizeSp = 11f),
        )
        val classShadowing = boolExtra(ir, PlantUmlClassParser.STYLE_CLASS_SHADOWING_KEY) == true
        val noteShadowing = boolExtra(ir, PlantUmlClassParser.STYLE_NOTE_SHADOWING_KEY) == true
        val packageShadowing = boolExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_SHADOWING_KEY) == true
        val sectionPad = 8f
        val rowGap = 2f

        for ((id, rect) in laidOut.clusterRects) {
            if (!id.value.startsWith("ns#")) continue
            val title = id.value.removePrefix("ns#")
            if (packageShadowing) {
                out += DrawCommand.FillRect(
                    rect = PlantUmlTreeRenderSupport.offsetRect(rect, 4f, 4f),
                    color = PlantUmlTreeRenderSupport.shadowColor(),
                    corner = 8f,
                    z = 0,
                )
            }
            palette.namespaceFill?.let {
                out += DrawCommand.FillRect(rect = rect, color = it, corner = 8f, z = 0)
            }
            out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = packageStrokeWidth, dash = listOf(6f, 4f)), color = palette.namespaceStroke, corner = 8f, z = 0)
            val chipWidth = (textMeasurer.measure(title, namespaceFont).width + 20f).coerceAtLeast(54f)
            val chipRect = com.hrm.diagram.core.draw.Rect.ltrb(rect.left + 10f, rect.top - 10f, rect.left + 10f + chipWidth, rect.top + 12f)
            if (packageShadowing) {
                out += DrawCommand.FillRect(
                    rect = PlantUmlTreeRenderSupport.offsetRect(chipRect, 3f, 3f),
                    color = PlantUmlTreeRenderSupport.shadowColor(),
                    corner = 10f,
                    z = 1,
                )
            }
            out += DrawCommand.FillRect(rect = chipRect, color = palette.namespaceChipFill, corner = 10f, z = 1)
            out += DrawCommand.StrokeRect(
                rect = chipRect,
                stroke = Stroke(width = if (packageStrokeWidth > 1f) packageStrokeWidth * 0.75f else 1f),
                color = palette.namespaceStroke,
                corner = 10f,
                z = 2,
            )
            out += DrawCommand.DrawText(
                text = title,
                origin = Point((chipRect.left + chipRect.right) / 2f, (chipRect.top + chipRect.bottom) / 2f),
                font = namespaceFont,
                color = palette.namespaceText,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 3,
            )
        }

        for (c in ir.classes) {
            val r = laidOut.nodePositions[c.id] ?: continue
            val classPalette = paletteFor(c.stereotype, ir)
            val boxFill = classPalette.boxFill
            val boxStroke = classPalette.stroke
            val headerFill = classPalette.headerFill
            val textColor = classPalette.text
            val headerText = buildString {
                c.stereotype?.let { append("«").append(it).append("»\n") }
                append(c.name)
                c.generics?.let { append("<").append(it).append(">") }
            }
            val headerMetrics = textMeasurer.measure(headerText, headerFont)
            val headerH = headerMetrics.height + sectionPad

            if (classShadowing) {
                out += DrawCommand.FillRect(
                    rect = PlantUmlTreeRenderSupport.offsetRect(r, 4f, 4f),
                    color = PlantUmlTreeRenderSupport.shadowColor(),
                    corner = 4f,
                    z = 1,
                )
            }
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
            if (noteShadowing) {
                out += DrawCommand.FillRect(
                    rect = PlantUmlTreeRenderSupport.offsetRect(rect, 4f, 4f),
                    color = PlantUmlTreeRenderSupport.shadowColor(),
                    corner = 4f,
                    z = 5,
                )
            }
            out += DrawCommand.FillRect(rect = rect, color = palette.noteFill, corner = 4f, z = 6)
            out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = noteStrokeWidth), color = palette.noteStroke, corner = 4f, z = 7)
            val text = (note.text as? RichLabel.Plain)?.text.orEmpty()
            if (text.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = text,
                    origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                    font = noteFont,
                    color = palette.noteText,
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
            out += DrawCommand.StrokePath(path = path, stroke = stroke, color = palette.edgeColor, z = 1)
            val tangentFrom = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4) pts[pts.size - 2] else from
            when (rel.kind) {
                ClassRelationKind.Inheritance, ClassRelationKind.Realization -> out += hollowTriangle(tangentFrom, to, palette.edgeColor, classStrokeWidth)
                ClassRelationKind.Composition -> out += filledDiamond(tangentFrom, to, palette.edgeColor)
                ClassRelationKind.Aggregation -> out += hollowDiamond(tangentFrom, to, palette.edgeColor, classStrokeWidth)
                ClassRelationKind.Association, ClassRelationKind.Dependency -> out += openArrowHead(tangentFrom, to, palette.edgeColor, classStrokeWidth)
                ClassRelationKind.Link, ClassRelationKind.LinkDashed -> {}
            }
            rel.fromCardinality?.takeIf { it.isNotEmpty() }?.let {
                out += cardinalityText(from, pts.getOrElse(1) { to }, it, edgeLabelFont, palette.commonTextColor, atStart = true)
            }
            rel.toCardinality?.takeIf { it.isNotEmpty() }?.let {
                out += cardinalityText(tangentFrom, to, it, edgeLabelFont, palette.commonTextColor, atStart = false)
            }
            val labelText = (rel.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelText.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = labelText,
                    origin = Point((from.x + to.x) / 2f, (from.y + to.y) / 2f - 4f),
                    font = edgeLabelFont,
                    color = palette.commonTextColor,
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

    private fun openArrowHead(from: Point, to: Point, color: Color, strokeWidth: Float): DrawCommand {
        val (p1, p2) = headPoints(from, to, 8f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = strokeWidth),
            color = color,
            z = 2,
        )
    }

    private fun hollowTriangle(from: Point, to: Point, color: Color, strokeWidth: Float): DrawCommand {
        val (p1, p2) = headPoints(from, to, 12f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close)),
            stroke = Stroke(width = strokeWidth),
            color = color,
            z = 2,
        )
    }

    private fun filledDiamond(from: Point, to: Point, color: Color): DrawCommand =
        DrawCommand.FillPath(path = diamondPath(from, to, 10f), color = color, z = 2)

    private fun hollowDiamond(from: Point, to: Point, color: Color, strokeWidth: Float): DrawCommand =
        DrawCommand.StrokePath(path = diamondPath(from, to, 10f), stroke = Stroke(width = strokeWidth), color = color, z = 2)

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

    private fun paletteFor(stereotype: String?, ir: ClassIR): ClassPalette {
        val base = when (stereotype?.lowercase()) {
        "interface" -> ClassPalette(
            boxFill = Color(0xFFE3F2FDU.toInt()),
            headerFill = Color(0xFFBBDEFB.toInt()),
            stroke = Color(0xFF1565C0.toInt()),
            text = Color(0xFF0D47A1.toInt()),
        )
        "abstract" -> ClassPalette(
            boxFill = Color(0xFFF3E5F5.toInt()),
            headerFill = Color(0xFFE1BEE7.toInt()),
            stroke = Color(0xFF8E24AA.toInt()),
            text = Color(0xFF4A148C.toInt()),
        )
        "enum" -> ClassPalette(
            boxFill = Color(0xFFE8F5E9.toInt()),
            headerFill = Color(0xFFC8E6C9.toInt()),
            stroke = Color(0xFF2E7D32.toInt()),
            text = Color(0xFF1B5E20.toInt()),
        )
        else -> ClassPalette(
            boxFill = Color(0xFFFFFDE7U.toInt()),
            headerFill = Color(0xFFFFE0B2U.toInt()),
            stroke = Color(0xFF6D4C41U.toInt()),
            text = Color(0xFF3E2723U.toInt()),
        )
        }
        val fill = colorExtra(ir, PlantUmlClassParser.STYLE_CLASS_FILL_KEY)
        val stroke = colorExtra(ir, PlantUmlClassParser.STYLE_CLASS_STROKE_KEY)
        val text = colorExtra(ir, PlantUmlClassParser.STYLE_CLASS_TEXT_KEY)
        return base.copy(
            boxFill = fill ?: base.boxFill,
            headerFill = fill?.let { PlantUmlTreeRenderSupport.darken(it, 0.08f) } ?: base.headerFill,
            stroke = stroke ?: base.stroke,
            text = text ?: base.text,
        )
    }

    private fun paletteOf(ir: ClassIR): ClassRenderPalette = ClassRenderPalette(
        noteFill = colorExtra(ir, PlantUmlClassParser.STYLE_NOTE_FILL_KEY) ?: Color(0xFFFFF8E1U.toInt()),
        noteStroke = colorExtra(ir, PlantUmlClassParser.STYLE_NOTE_STROKE_KEY) ?: Color(0xFFFFA000U.toInt()),
        noteText = colorExtra(ir, PlantUmlClassParser.STYLE_NOTE_TEXT_KEY) ?: Color(0xFF3E2723U.toInt()),
        namespaceFill = colorExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_FILL_KEY),
        namespaceChipFill = colorExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_FILL_KEY) ?: Color(0xFFFFFFFF.toInt()),
        namespaceStroke = colorExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_STROKE_KEY) ?: Color(0xFF8D6E63U.toInt()),
        namespaceText = colorExtra(ir, PlantUmlClassParser.STYLE_PACKAGE_TEXT_KEY) ?: Color(0xFF5D4037U.toInt()),
        edgeColor = colorExtra(ir, PlantUmlClassParser.STYLE_EDGE_COLOR_KEY) ?: Color(0xFF455A64U.toInt()),
        commonTextColor = colorExtra(ir, PlantUmlClassParser.STYLE_CLASS_TEXT_KEY) ?: Color(0xFF3E2723U.toInt()),
    )

    private fun colorExtra(ir: ClassIR, key: String): Color? =
        ir.styleHints.extras[key]?.let(PlantUmlTreeRenderSupport::parsePlantUmlColor)

    private fun floatExtra(ir: ClassIR, key: String): Float? =
        PlantUmlTreeRenderSupport.parsePlantUmlFloat(ir.styleHints.extras[key])

    private fun boolExtra(ir: ClassIR, key: String): Boolean? =
        PlantUmlTreeRenderSupport.parsePlantUmlBoolean(ir.styleHints.extras[key])

    private fun fontExtra(ir: ClassIR, fontNameKey: String, fontSizeKey: String, base: FontSpec): FontSpec =
        PlantUmlTreeRenderSupport.resolveFontSpec(
            base = base,
            familyRaw = ir.styleHints.extras[fontNameKey],
            sizeRaw = ir.styleHints.extras[fontSizeKey],
        )

    private data class ClassPalette(
        val boxFill: Color,
        val headerFill: Color,
        val stroke: Color,
        val text: Color,
    )

    private data class ClassRenderPalette(
        val noteFill: Color,
        val noteStroke: Color,
        val noteText: Color,
        val namespaceFill: Color?,
        val namespaceChipFill: Color,
        val namespaceStroke: Color,
        val namespaceText: Color,
        val edgeColor: Color,
        val commonTextColor: Color,
    )
}
