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
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassMember
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Visibility
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.classd.ClassDiagramLayout
import com.hrm.diagram.parser.mermaid.MermaidClassParser
import kotlin.math.sqrt

/** Sub-pipeline for `classDiagram` Mermaid sources. */
internal class MermaidClassSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidClassParser()
    private val layout = ClassDiagramLayout(textMeasurer)
    private var graphStyles: MermaidGraphStyleState? = null

    override fun updateGraphStyles(styles: MermaidGraphStyleState) {
        graphStyles = styles
    }

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

        val ir: ClassIR = parser.snapshot()
        val opts = LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout.layout(previousSnapshot.laidOut, ir, opts).copy(seq = seq)
        val drawCommands = renderClass(ir, laidOut)
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

    private fun renderClass(ir: ClassIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val boxFill = Color(0xFFFFFDE7U.toInt())
        val boxStroke = Color(0xFF6D4C41U.toInt())
        val headerFill = Color(0xFFFFE0B2U.toInt())
        val textColor = Color(0xFF3E2723U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val noteFill = Color(0xFFFFF8E1U.toInt())
        val noteStroke = Color(0xFFFFA000U.toInt())
        val nsStroke = Color(0xFF7E57C2U.toInt())

        // Map every class id → its resolved cssClass name (if any). Both inline `:::name`
        // (stored on ClassNode.cssClass) and bulk `cssClass "A,B" styleName` (stored in
        // ClassIR.cssClasses) feed this map; bulk assignments win since they typically
        // appear later in the source.
        val classStyleByName = HashMap<NodeId, String>()
        for (c in ir.classes) c.cssClass?.let { classStyleByName[c.id] = it }
        for (def in ir.cssClasses) {
            for (raw in def.name.split(',')) {
                val key = raw.trim()
                if (key.isNotEmpty()) classStyleByName[NodeId(key)] = def.style
            }
        }
        val resolvedNodeStyleById = computeClassNodeStyles(ir, classStyleByName)

        val solid = Stroke(width = 1.5f)
        val dashed = Stroke(width = 1.5f, dash = listOf(6f, 4f))
        val headerFont = FontSpec(family = "sans-serif", sizeSp = 13f)
        val memberFont = FontSpec(family = "sans-serif", sizeSp = 11f)
        val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 10f)

        // Padding constants must mirror ClassDiagramLayout.measureClass so layout-computed
        // box dimensions and render-drawn content always agree (no clipped names, no orphan
        // horizontal lines below the box).
        val sectionPad = 8f
        val rowGap = 2f

        // Namespace clusters first (lowest z).
        for ((id, rect) in laidOut.clusterRects) {
            val v = id.value
            if (v.startsWith("ns#")) {
                out += DrawCommand.StrokeRect(rect = rect, stroke = dashed, color = nsStroke, corner = 6f, z = 0)
                val title = v.removePrefix("ns#")
                out += DrawCommand.DrawText(
                    text = title,
                    origin = Point(rect.left + 8f, rect.top + 4f),
                    font = memberFont,
                    color = nsStroke,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    z = 1,
                )
            }
        }

        // Class boxes. We measure each text fragment with the same TextMeasurer the layout
        // used so divider positions track real glyph heights at any density.
        for (c in ir.classes) {
            val r = laidOut.nodePositions[c.id] ?: continue
            val st = resolvedNodeStyleById[c.id]
            val palette = paletteFor(classStyleByName[c.id])
            val cBoxFill = st?.fill?.let { Color(it.argb) } ?: (palette?.fill ?: boxFill)
            val cBoxStroke = st?.stroke?.let { Color(it.argb) } ?: (palette?.stroke ?: boxStroke)
            val cHeaderFill = st?.fill?.let { Color(it.argb) } ?: (palette?.header ?: headerFill)
            val cTextColor = st?.textColor?.let { Color(it.argb) } ?: (palette?.text ?: textColor)
            val strokeWidth = st?.strokeWidth ?: solid.width
            val borderStroke = Stroke(width = strokeWidth)
            val headerText = buildString {
                c.stereotype?.let { append("«").append(it).append("»\n") }
                append(c.name)
                c.generics?.let { append("~").append(it).append("~") }
            }
            val headerMetrics = textMeasurer.measure(headerText, headerFont)
            val headerH = headerMetrics.height + sectionPad

            out += DrawCommand.FillRect(rect = r, color = cBoxFill, corner = 4f, z = 2)
            out += DrawCommand.StrokeRect(rect = r, stroke = borderStroke, color = cBoxStroke, corner = 4f, z = 4)

            val headerRect = Rect.ltrb(r.left, r.top, r.right, r.top + headerH)
            out += DrawCommand.FillRect(rect = headerRect, color = cHeaderFill, corner = 4f, z = 3)

            val cx = (r.left + r.right) / 2f
            out += DrawCommand.DrawText(
                text = headerText,
                origin = Point(cx, r.top + sectionPad / 2f),
                font = headerFont,
                color = cTextColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Top,
                z = 5,
            )

            val attrs = c.members.filter { !it.isMethod }
            val methods = c.members.filter { it.isMethod }
            val hasAttrs = attrs.isNotEmpty()
            val hasMethods = methods.isNotEmpty()

            // Header divider only if there's any body content; otherwise the box stays a
            // single header chip and we don't draw a stray horizontal line.
            if (hasAttrs || hasMethods) {
                val divY1 = r.top + headerH
                out += DrawCommand.StrokePath(
                    path = PathCmd(listOf(PathOp.MoveTo(Point(r.left, divY1)), PathOp.LineTo(Point(r.right, divY1)))),
                    stroke = borderStroke, color = cBoxStroke, z = 4,
                )
                var y = divY1 + rowGap
                for (a in attrs) {
                    val line = renderMemberLine(a)
                    val lm = textMeasurer.measure(line, memberFont)
                    out += DrawCommand.DrawText(
                        text = line,
                        origin = Point(r.left + 6f, y),
                        font = memberFont,
                        color = cTextColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 5,
                    )
                    y += lm.height
                }
                if (hasAttrs && hasMethods) {
                    out += DrawCommand.StrokePath(
                        path = PathCmd(listOf(PathOp.MoveTo(Point(r.left, y + rowGap)), PathOp.LineTo(Point(r.right, y + rowGap)))),
                        stroke = borderStroke, color = cBoxStroke, z = 4,
                    )
                    y += rowGap * 2 + 2f
                }
                for (m in methods) {
                    val line = renderMemberLine(m)
                    val lm = textMeasurer.measure(line, memberFont)
                    out += DrawCommand.DrawText(
                        text = line,
                        origin = Point(r.left + 6f, y),
                        font = memberFont,
                        color = cTextColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Top,
                        z = 5,
                    )
                    y += lm.height
                }
            }
        }

        // Notes.
        for ((id, rect) in laidOut.clusterRects) {
            val v = id.value
            if (!v.startsWith("note#")) continue
            val noteIdx = v.removePrefix("note#").toIntOrNull() ?: continue
            val note = ir.notes.getOrNull(noteIdx) ?: continue
            out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 6)
            out += DrawCommand.StrokeRect(rect = rect, stroke = solid, color = noteStroke, corner = 4f, z = 7)
            val text = (note.text as? RichLabel.Plain)?.text ?: ""
            if (text.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = text,
                    origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                    font = memberFont,
                    color = textColor,
                    maxWidth = rect.right - rect.left - 8f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 8,
                )
            }
        }

        // Relations / edges.
        for ((idx, route) in laidOut.edgeRoutes.withIndex()) {
            val rel = ir.relations.getOrNull(idx) ?: continue
            val pts = route.points
            if (pts.size < 2) continue
            val from = pts.first()
            val to = pts.last()
            val stroke = if (rel.kind == ClassRelationKind.Dependency || rel.kind == ClassRelationKind.Realization || rel.kind == ClassRelationKind.LinkDashed) dashed else solid
            val path = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4 && (pts.size - 1) % 3 == 0) {
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
            out += DrawCommand.StrokePath(path = path, stroke = stroke, color = edgeColor, z = 1)
            // Arrowhead direction uses the last segment's tangent so heads stay aligned with
            // the curve, not with the straight from→to line.
            val tangentFrom = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4) pts[pts.size - 2] else from
            // Arrowheads at the `to` end.
            when (rel.kind) {
                ClassRelationKind.Inheritance, ClassRelationKind.Realization ->
                    out += hollowTriangle(tangentFrom, to, edgeColor)
                ClassRelationKind.Composition ->
                    out += filledDiamond(tangentFrom, to, edgeColor)
                ClassRelationKind.Aggregation ->
                    out += hollowDiamond(tangentFrom, to, edgeColor)
                ClassRelationKind.Association, ClassRelationKind.Dependency ->
                    out += openArrowHead(tangentFrom, to, edgeColor)
                ClassRelationKind.Link, ClassRelationKind.LinkDashed -> { /* no head */ }
            }

            // Cardinality labels.
            rel.fromCardinality?.let {
                if (it.isNotEmpty()) out += cardinalityText(from, pts.getOrElse(1) { to }, it, edgeLabelFont, textColor, atStart = true)
            }
            rel.toCardinality?.let {
                if (it.isNotEmpty()) out += cardinalityText(tangentFrom, to, it, edgeLabelFont, textColor, atStart = false)
            }
            // Edge label at the geometric midpoint of the curve (or straight line).
            val labelText = (rel.label as? RichLabel.Plain)?.text ?: ""
            if (labelText.isNotEmpty()) {
                val (mx, my) = if (route.kind == com.hrm.diagram.layout.RouteKind.Bezier && pts.size >= 4) {
                    val p0 = pts[0]; val p1 = pts[1]; val p2 = pts[2]; val p3 = pts[3]
                    val t = 0.5f
                    val mt = 1f - t
                    val x = mt * mt * mt * p0.x + 3f * mt * mt * t * p1.x + 3f * mt * t * t * p2.x + t * t * t * p3.x
                    val y = mt * mt * mt * p0.y + 3f * mt * mt * t * p1.y + 3f * mt * t * t * p2.y + t * t * t * p3.y
                    x to y
                } else {
                    ((from.x + to.x) / 2f) to ((from.y + to.y) / 2f)
                }
                out += DrawCommand.DrawText(
                    text = labelText,
                    origin = Point(mx, my - 4f),
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

    private fun computeClassNodeStyles(
        ir: ClassIR,
        classStyleByName: Map<NodeId, String>,
    ): Map<NodeId, com.hrm.diagram.core.ir.NodeStyle> {
        val styles = graphStyles ?: return emptyMap()
        val defaultDecl = styles.classDefs["default"]
        if (defaultDecl == null && styles.nodeInline.isEmpty() && styles.classDefs.isEmpty()) return emptyMap()

        val out = HashMap<NodeId, com.hrm.diagram.core.ir.NodeStyle>()
        for (c in ir.classes) {
            val id = c.id
            var decl: com.hrm.diagram.parser.mermaid.MermaidStyleDecl? = null
            defaultDecl?.let { decl = mergeDecl(decl, it) }

            // `:::someclass` and bulk `cssClass "A,B" someclass` point to a named classDef.
            val clsName = classStyleByName[id]
            if (!clsName.isNullOrBlank()) {
                styles.classDefs[clsName]?.let { decl = mergeDecl(decl, it) }
            }

            // `style Animal ...` in classDiagram.
            styles.nodeInline[id]?.let { decl = mergeDecl(decl, it) }

            if (decl != null) {
                val d = decl
                out[id] = com.hrm.diagram.core.ir.NodeStyle(
                    fill = d.fill,
                    stroke = d.stroke,
                    strokeWidth = d.strokeWidthPx,
                    textColor = d.textColor,
                )
            }
        }
        return out
    }

    private fun mergeDecl(
        base: com.hrm.diagram.parser.mermaid.MermaidStyleDecl?,
        override: com.hrm.diagram.parser.mermaid.MermaidStyleDecl,
    ): com.hrm.diagram.parser.mermaid.MermaidStyleDecl {
        val b = base ?: com.hrm.diagram.parser.mermaid.MermaidStyleDecl()
        return com.hrm.diagram.parser.mermaid.MermaidStyleDecl(
            fill = override.fill ?: b.fill,
            stroke = override.stroke ?: b.stroke,
            strokeWidthPx = override.strokeWidthPx ?: b.strokeWidthPx,
            strokeDashArrayPx = override.strokeDashArrayPx ?: b.strokeDashArrayPx,
            textColor = override.textColor ?: b.textColor,
            fontFamily = override.fontFamily ?: b.fontFamily,
            fontSizePx = override.fontSizePx ?: b.fontSizePx,
            fontWeight = override.fontWeight ?: b.fontWeight,
            italic = override.italic ?: b.italic,
            extras = if (b.extras.isEmpty()) override.extras else b.extras + override.extras,
        )
    }


    private fun renderMemberLine(m: ClassMember): String {
        val sb = StringBuilder()
        sb.append(when (m.visibility) {
            Visibility.PUBLIC -> "+"
            Visibility.PRIVATE -> "-"
            Visibility.PROTECTED -> "#"
            Visibility.PACKAGE -> "~"
        })
        sb.append(m.name)
        if (m.isMethod) {
            sb.append('(')
            sb.append(m.params.joinToString(", ") { p -> p.type?.let { "${p.name}: $it" } ?: p.name })
            sb.append(')')
            m.type?.let { sb.append(" : ").append(it) }
        } else {
            m.type?.let { sb.append(" : ").append(it) }
        }
        if (m.isStatic) sb.append('$')
        if (m.isAbstract) sb.append('*')
        return sb.toString()
    }

    private fun cardinalityText(from: Point, to: Point, text: String, font: FontSpec, color: Color, atStart: Boolean): DrawCommand {
        val t = if (atStart) 0.15f else 0.85f
        val x = from.x + (to.x - from.x) * t
        val y = from.y + (to.y - from.y) * t
        return DrawCommand.DrawText(
            text = text,
            origin = Point(x, y - 4f),
            font = font,
            color = color,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 9,
        )
    }

    // ---- Arrowhead helpers ----

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, size = 8f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = 1.5f), color = color, z = 2,
        )
    }

    private fun hollowTriangle(from: Point, to: Point, color: Color): DrawCommand {
        val (p1, p2) = headPoints(from, to, size = 12f)
        val path = PathCmd(listOf(
            PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close,
        ))
        return DrawCommand.StrokePath(path = path, stroke = Stroke(width = 1.5f), color = color, z = 2)
    }

    private fun filledDiamond(from: Point, to: Point, color: Color): DrawCommand {
        val path = diamondPath(from, to, size = 10f)
        return DrawCommand.FillPath(path = path, color = color, z = 2)
    }

    private fun hollowDiamond(from: Point, to: Point, color: Color): DrawCommand {
        val path = diamondPath(from, to, size = 10f)
        return DrawCommand.StrokePath(path = path, stroke = Stroke(width = 1.5f), color = color, z = 2)
    }

    private fun diamondPath(from: Point, to: Point, size: Float): PathCmd {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return PathCmd(listOf(PathOp.MoveTo(to), PathOp.Close))
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        val tip = to
        val mid = Point(to.x - ux * size, to.y - uy * size)
        val side1 = Point(mid.x + nx * size * 0.5f, mid.y + ny * size * 0.5f)
        val side2 = Point(mid.x - nx * size * 0.5f, mid.y - ny * size * 0.5f)
        val tail = Point(to.x - ux * size * 2f, to.y - uy * size * 2f)
        return PathCmd(listOf(
            PathOp.MoveTo(tip),
            PathOp.LineTo(side1),
            PathOp.LineTo(tail),
            PathOp.LineTo(side2),
            PathOp.Close,
        ))
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

    @Suppress("unused")
    private fun unusedRel() = ClassRelation(NodeId("a"), NodeId("b"), ClassRelationKind.Link)

    private data class ClassPalette(val fill: Color, val stroke: Color, val header: Color, val text: Color)

    /**
     * Resolves a cssClass name to a built-in palette. Names are case-insensitive. Returns null
     * for unknown / null input so the caller falls back to the default amber palette.
     *
     * Built-in names: red, orange, yellow, green, cyan, blue, indigo, purple, pink, gray.
     */
    private fun paletteFor(name: String?): ClassPalette? {
        if (name.isNullOrBlank()) return null
        return when (name.lowercase()) {
            "red" -> ClassPalette(
                fill = Color(0xFFFFEBEEU.toInt()), stroke = Color(0xFFC62828U.toInt()),
                header = Color(0xFFFFCDD2U.toInt()), text = Color(0xFFB71C1CU.toInt()))
            "orange" -> ClassPalette(
                fill = Color(0xFFFFF3E0U.toInt()), stroke = Color(0xFFEF6C00U.toInt()),
                header = Color(0xFFFFE0B2U.toInt()), text = Color(0xFFE65100U.toInt()))
            "yellow" -> ClassPalette(
                fill = Color(0xFFFFFDE7U.toInt()), stroke = Color(0xFFF9A825U.toInt()),
                header = Color(0xFFFFF59DU.toInt()), text = Color(0xFFF57F17U.toInt()))
            "green" -> ClassPalette(
                fill = Color(0xFFE8F5E9U.toInt()), stroke = Color(0xFF2E7D32U.toInt()),
                header = Color(0xFFC8E6C9U.toInt()), text = Color(0xFF1B5E20U.toInt()))
            "cyan" -> ClassPalette(
                fill = Color(0xFFE0F7FAU.toInt()), stroke = Color(0xFF00838FU.toInt()),
                header = Color(0xFFB2EBF2U.toInt()), text = Color(0xFF006064U.toInt()))
            "blue" -> ClassPalette(
                fill = Color(0xFFE3F2FDU.toInt()), stroke = Color(0xFF1565C0U.toInt()),
                header = Color(0xFFBBDEFBU.toInt()), text = Color(0xFF0D47A1U.toInt()))
            "indigo" -> ClassPalette(
                fill = Color(0xFFE8EAF6U.toInt()), stroke = Color(0xFF283593U.toInt()),
                header = Color(0xFFC5CAE9U.toInt()), text = Color(0xFF1A237EU.toInt()))
            "purple" -> ClassPalette(
                fill = Color(0xFFF3E5F5U.toInt()), stroke = Color(0xFF6A1B9AU.toInt()),
                header = Color(0xFFE1BEE7U.toInt()), text = Color(0xFF4A148CU.toInt()))
            "pink" -> ClassPalette(
                fill = Color(0xFFFCE4ECU.toInt()), stroke = Color(0xFFAD1457U.toInt()),
                header = Color(0xFFF8BBD0U.toInt()), text = Color(0xFF880E4FU.toInt()))
            "gray", "grey" -> ClassPalette(
                fill = Color(0xFFECEFF1U.toInt()), stroke = Color(0xFF455A64U.toInt()),
                header = Color(0xFFCFD8DCU.toInt()), text = Color(0xFF263238U.toInt()))
            else -> null
        }
    }
}
