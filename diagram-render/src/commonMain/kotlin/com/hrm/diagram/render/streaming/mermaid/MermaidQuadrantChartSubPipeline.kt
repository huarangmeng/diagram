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
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.QuadrantPoint
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.layout.quadrant.QuadrantChartLayout
import com.hrm.diagram.parser.mermaid.MermaidQuadrantChartParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

internal class MermaidQuadrantChartSubPipeline : MermaidSubPipeline {
    private var styleExtras: Map<String, String> = emptyMap()

    override fun updateStyleExtras(extras: Map<String, String>) {
        styleExtras = extras
    }

    private val parser = MermaidQuadrantChartParser()
    private val layout = QuadrantChartLayout()
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val axisFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val quadrantFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val pointFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        for (line in lines) {
            val batch = parser.acceptLine(line)
            newPatches += batch.patches
        }
        val ir = parser.snapshot()
        val laid = layout.layout(ir)
        val draw = render(ir, laid)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = SessionPatch(seq = seq, addedNodes = emptyList(), addedEdges = emptyList(), addedDrawCommands = draw, newDiagnostics = newDiagnostics, isFinal = isFinal)
        return PipelineAdvance(snapshot = snap, patch = patch)
    }

    private fun render(ir: QuadrantChartIR, laid: com.hrm.diagram.layout.LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val plot = laid.nodePositions[NodeId("quadrant:plot")] ?: return emptyList()
        val midX = (plot.left + plot.right) / 2f
        val midY = (plot.top + plot.bottom) / 2f
        val themeRaw = MermaidRenderThemeUtils.decodeRawThemeTokens(styleExtras["mermaid.themeTokens"])
        val text = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrantTitleFill"]) ?: Color(0xFF263238.toInt())
        val border = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrantExternalBorderStrokeFill"]) ?: Color(0xFF90A4AE.toInt())
        val q1 = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrant1Fill"]) ?: Color(0xFFE3F2FD.toInt())
        val q2 = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrant2Fill"]) ?: Color(0xFFE8F5E9.toInt())
        val q3 = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrant3Fill"]) ?: Color(0xFFFFF8E1.toInt())
        val q4 = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrant4Fill"]) ?: Color(0xFFFCE4EC.toInt())

        out += DrawCommand.FillRect(Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), Color.White, corner = 0f, z = 0)
        out += DrawCommand.FillRect(Rect.ltrb(midX, plot.top, plot.right, midY), q1, corner = 0f, z = 1)
        out += DrawCommand.FillRect(Rect.ltrb(plot.left, plot.top, midX, midY), q2, corner = 0f, z = 1)
        out += DrawCommand.FillRect(Rect.ltrb(plot.left, midY, midX, plot.bottom), q3, corner = 0f, z = 1)
        out += DrawCommand.FillRect(Rect.ltrb(midX, midY, plot.right, plot.bottom), q4, corner = 0f, z = 1)
        out += DrawCommand.StrokeRect(plot, Stroke(width = 2f), border, corner = 0f, z = 2)
        out += DrawCommand.StrokePath(PathCmd(listOf(PathOp.MoveTo(Point(midX, plot.top)), PathOp.LineTo(Point(midX, plot.bottom)))), Stroke(width = 1.5f), border, z = 2)
        out += DrawCommand.StrokePath(PathCmd(listOf(PathOp.MoveTo(Point(plot.left, midY)), PathOp.LineTo(Point(plot.right, midY)))), Stroke(width = 1.5f), border, z = 2)

        laid.nodePositions[NodeId("quadrant:title")]?.let { r ->
            if (!ir.title.isNullOrBlank()) {
                out += DrawCommand.DrawText(ir.title!!, Point(r.left, r.top), titleFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
            }
        }
        drawLabel(out, laid, "quadrant:xMin", (ir.xMinLabel as? RichLabel.Plain)?.text, axisFont, text, TextAnchorX.Start, TextAnchorY.Top)
        drawLabel(out, laid, "quadrant:xMax", (ir.xMaxLabel as? RichLabel.Plain)?.text, axisFont, text, TextAnchorX.End, TextAnchorY.Top)
        drawLabel(out, laid, "quadrant:yMin", (ir.yMinLabel as? RichLabel.Plain)?.text, axisFont, text, TextAnchorX.Start, TextAnchorY.Middle)
        drawLabel(out, laid, "quadrant:yMax", (ir.yMaxLabel as? RichLabel.Plain)?.text, axisFont, text, TextAnchorX.Start, TextAnchorY.Middle)
        drawLabel(out, laid, "quadrant:q1", (ir.quadrantLabels[1] as? RichLabel.Plain)?.text, quadrantFont, text, TextAnchorX.Start, TextAnchorY.Top)
        drawLabel(out, laid, "quadrant:q2", (ir.quadrantLabels[2] as? RichLabel.Plain)?.text, quadrantFont, text, TextAnchorX.Start, TextAnchorY.Top)
        drawLabel(out, laid, "quadrant:q3", (ir.quadrantLabels[3] as? RichLabel.Plain)?.text, quadrantFont, text, TextAnchorX.Start, TextAnchorY.Top)
        drawLabel(out, laid, "quadrant:q4", (ir.quadrantLabels[4] as? RichLabel.Plain)?.text, quadrantFont, text, TextAnchorX.Start, TextAnchorY.Top)

        for (p in ir.points) {
            val pt = pointAt(plot, p)
            val fill = p.payload["color"]?.toIntOrNull()?.let(::Color)
                ?: MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrantPointFill"])
                ?: Color(0xFF1E88E5.toInt())
            val strokeColor = p.payload["stroke-color"]?.toIntOrNull()?.let(::Color)
                ?: MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrantExternalBorderStrokeFill"])
                ?: Color(0xFF0D47A1.toInt())
            val radius = p.payload["radius"]?.toFloatOrNull() ?: 5f
            val strokeWidth = p.payload["stroke-width"]?.toFloatOrNull() ?: 1f
            val rect = Rect.ltrb(pt.x - radius, pt.y - radius, pt.x + radius, pt.y + radius)
            out += DrawCommand.FillRect(rect, fill, corner = radius, z = 5)
            out += DrawCommand.StrokeRect(rect, Stroke(width = strokeWidth), strokeColor, corner = radius, z = 6)

            val labelRect = laid.nodePositions[NodeId("quadrant:pointLabel:${p.id.value}")]
            val label = (p.label as? RichLabel.Plain)?.text ?: p.id.value
            if (labelRect != null) {
                val pointText = MermaidRenderThemeUtils.parseThemeColor(themeRaw["quadrantPointTextFill"]) ?: text
                out += DrawCommand.DrawText(label, Point(labelRect.left, labelRect.top), pointFont, pointText, maxWidth = labelRect.size.width, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 7)
            }
        }
        return out
    }

    private fun drawLabel(
        out: MutableList<DrawCommand>,
        laid: com.hrm.diagram.layout.LaidOutDiagram,
        id: String,
        text: String?,
        font: FontSpec,
        color: Color,
        ax: TextAnchorX,
        ay: TextAnchorY,
    ) {
        if (text.isNullOrBlank()) return
        val r = laid.nodePositions[NodeId(id)] ?: return
        val p = when (ay) {
            TextAnchorY.Middle -> Point(r.left, (r.top + r.bottom) / 2f)
            else -> Point(if (ax == TextAnchorX.End) r.right else r.left, r.top)
        }
        out += DrawCommand.DrawText(text = text, origin = p, font = font, color = color, anchorX = ax, anchorY = ay, z = 10)
    }

    private fun pointAt(plot: Rect, p: QuadrantPoint): Point =
        Point(
            (plot.left + plot.size.width * p.x).toFloat(),
            (plot.bottom - plot.size.height * p.y).toFloat(),
        )
}
