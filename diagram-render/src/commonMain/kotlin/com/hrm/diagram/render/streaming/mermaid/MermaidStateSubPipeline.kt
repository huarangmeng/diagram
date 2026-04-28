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
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.ir.StateNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.stated.StateDiagramLayout
import com.hrm.diagram.parser.mermaid.MermaidStateParser
import kotlin.math.sqrt

/** Sub-pipeline for `stateDiagram` / `stateDiagram-v2` Mermaid sources. */
internal class MermaidStateSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidStateParser()
    private val layout = StateDiagramLayout(textMeasurer)
    private var graphStyles: MermaidGraphStyleState? = null

    override fun updateGraphStyles(styles: MermaidGraphStyleState) {
        // Reuse the same style carrier as GraphIR diagrams: classDef/class/style/::: bindings.
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

        val ir: StateIR = parser.snapshot()
        val opts = LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout.layout(previousSnapshot.laidOut, ir, opts).copy(seq = seq)
        val drawCommands = renderState(ir, laidOut)
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

    private fun renderState(ir: StateIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val boxFill = Color(0xFFE3F2FDU.toInt())
        val boxStroke = Color(0xFF1565C0U.toInt())
        val compositeFill = Color(0xFFF5F5F5U.toInt())
        val compositeStroke = Color(0xFF6D4C41U.toInt())
        val textColor = Color(0xFF263238U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val noteFill = Color(0xFFFFF8E1U.toInt())
        val noteStroke = Color(0xFFFFA000U.toInt())
        val pseudoFill = Color(0xFF000000U.toInt())

        val solid = Stroke(width = 1.5f)
        val thick = Stroke(width = 4f)
        val nodeFont = FontSpec(family = "sans-serif", sizeSp = 12f)
        val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 10f)
        val pseudoFont = FontSpec(family = "sans-serif", sizeSp = 11f)

        val byId = ir.states.associateBy { it.id }
        val styleById = computeStateNodeStyles()

        // Composite states first (lowest z) so children paint on top.
        for (s in ir.states) {
            if (s.kind != StateKind.Composite) continue
            val r = laidOut.nodePositions[s.id] ?: continue
            val st = styleById[s.id]
            val fill = st?.fill?.let { Color(it.argb) } ?: compositeFill
            val strokeColor = st?.stroke?.let { Color(it.argb) } ?: compositeStroke
            val strokeWidth = st?.strokeWidth ?: solid.width
            out += DrawCommand.FillRect(rect = r, color = fill, corner = 8f, z = 0)
            out += DrawCommand.StrokeRect(rect = r, stroke = Stroke(width = strokeWidth), color = strokeColor, corner = 8f, z = 1)
            val title = s.description ?: s.name
            if (title.isNotEmpty()) {
                out += DrawCommand.DrawText(
                    text = title,
                    origin = Point(r.left + 8f, r.top + 4f),
                    font = nodeFont,
                    color = st?.textColor?.let { Color(it.argb) } ?: strokeColor,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    z = 2,
                )
            }
        }

        // Non-composite state shapes (per-kind).
        for (s in ir.states) {
            val r = laidOut.nodePositions[s.id] ?: continue
            when (s.kind) {
                StateKind.Composite -> { /* already drawn above */ }
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
                    val path = PathCmd(listOf(
                        PathOp.MoveTo(Point(cx, r.top)),
                        PathOp.LineTo(Point(r.right, cy)),
                        PathOp.LineTo(Point(cx, r.bottom)),
                        PathOp.LineTo(Point(r.left, cy)),
                        PathOp.Close,
                    ))
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
                    val letter = if (s.kind == StateKind.DeepHistory) "H*" else "H"
                    out += DrawCommand.DrawText(
                        text = letter,
                        origin = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f),
                        font = pseudoFont,
                        color = textColor,
                        anchorX = TextAnchorX.Center,
                        anchorY = TextAnchorY.Middle,
                        z = 6,
                    )
                }
                StateKind.Simple -> {
                    val st = styleById[s.id]
                    val fill = st?.fill?.let { Color(it.argb) } ?: boxFill
                    val strokeColor = st?.stroke?.let { Color(it.argb) } ?: boxStroke
                    val strokeWidth = st?.strokeWidth ?: solid.width
                    val stroke = Stroke(width = strokeWidth)
                    out += DrawCommand.FillRect(rect = r, color = fill, corner = 8f, z = 4)
                    out += DrawCommand.StrokeRect(rect = r, stroke = stroke, color = strokeColor, corner = 8f, z = 5)
                    val name = s.description ?: s.name
                    if (name.isNotEmpty()) {
                        out += DrawCommand.DrawText(
                            text = name,
                            origin = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f),
                            font = nodeFont,
                            color = st?.textColor?.let { Color(it.argb) } ?: textColor,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 6,
                        )
                    }
                }
            }
            // Suppress lint about unused thick stroke when only Fork/Join hit it.
            @Suppress("UNUSED_EXPRESSION") thick
        }

        // Notes.
        for ((id, rect) in laidOut.clusterRects) {
            val v = id.value
            if (!v.startsWith("note#")) continue
            val noteIdx = v.removePrefix("note#").toIntOrNull() ?: continue
            val note = ir.notes.getOrNull(noteIdx) ?: continue
            out += DrawCommand.FillRect(rect = rect, color = noteFill, corner = 4f, z = 7)
            out += DrawCommand.StrokeRect(rect = rect, stroke = solid, color = noteStroke, corner = 4f, z = 8)
            val text = (note.text as? RichLabel.Plain)?.text ?: ""
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

        // Transitions / edges.
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

            val labelText = (tr.label as? RichLabel.Plain)?.text ?: ""
            if (labelText.isNotEmpty()) {
                val (mx, my) = if (route.kind == RouteKind.Bezier && pts.size >= 4) {
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

        // Reference unused parameter to avoid lint.
        @Suppress("UNUSED_VARIABLE") val unused = byId
        return out
    }

    private fun computeStateNodeStyles(): Map<NodeId, com.hrm.diagram.core.ir.NodeStyle> {
        val styles = graphStyles ?: return emptyMap()
        val defaultDecl = styles.classDefs["default"]
        if (defaultDecl == null && styles.nodeClassBindings.isEmpty() && styles.nodeInline.isEmpty()) return emptyMap()

        val out = HashMap<NodeId, com.hrm.diagram.core.ir.NodeStyle>()
        for (s in parser.snapshot().states) {
            val id = s.id
            var decl: com.hrm.diagram.parser.mermaid.MermaidStyleDecl? = null
            defaultDecl?.let { decl = mergeDecl(decl, it) }
            styles.nodeClassBindings[id]?.forEach { cls ->
                val d = styles.classDefs[cls] ?: return@forEach
                decl = mergeDecl(decl, d)
            }
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


    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x; val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f), color = color, z = 4,
        )
        val ux = dx / len; val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size; val baseY = to.y - uy * size
        val nx = -uy; val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = 1.5f), color = color, z = 4,
        )
    }

    @Suppress("unused")
    private fun unusedNode() = StateNode(NodeId("_"), "_")
}
