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
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.pie.PieLayout
import com.hrm.diagram.parser.mermaid.MermaidPieParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal class MermaidPieSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidPieParser()
    private val layout = PieLayout(textMeasurer)

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val legendFont = FontSpec(family = "sans-serif", sizeSp = 12f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (line in lines) {
            parser.acceptLine(line)
        }
        val ir = parser.snapshot()

        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)

        val out = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(
            snapshot = out,
            patch = SessionPatch.empty(seq, isFinal),
        )
    }

    private fun render(ir: PieIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()

        // Compute pie geometry from bounds. Layout fixes pie at left, legend at right.
        val pad = 20f
        val radius = 120f
        val diameter = radius * 2f
        val pieTop = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("pie:title")]?.bottom?.plus(10f) ?: pad
        val center = Point(pad + radius, pieTop + radius)

        val total = ir.slices.sumOf { it.value }.takeIf { it > 0.0 } ?: 1.0
        var angle = -PI / 2.0

        val palette = listOf(
            Color(0xFF42A5F5.toInt()),
            Color(0xFF66BB6A.toInt()),
            Color(0xFFFFCA28.toInt()),
            Color(0xFFEF5350.toInt()),
            Color(0xFFAB47BC.toInt()),
            Color(0xFF26C6DA.toInt()),
        )
        val border = Stroke(width = 1f)
        val borderColor = Color(0xFF263238.toInt())

        // Title.
        val titleRect = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("pie:title")]
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(
                text = ir.title!!,
                origin = Point(titleRect.left, titleRect.top),
                font = titleFont,
                color = borderColor,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = 10,
            )
        }

        // Wedges.
        for ((i, s) in ir.slices.withIndex()) {
            val sweep = (s.value / total) * 2.0 * PI
            val start = angle
            val end = angle + sweep
            val fill = palette[i % palette.size]
            val path = wedgePath(center, radius, start, end)
            out += DrawCommand.FillPath(path = path, color = fill, z = 1)
            out += DrawCommand.StrokePath(path = path, stroke = border, color = borderColor, z = 2)
            angle = end
        }

        // Legend rows from layout nodePositions.
        for ((i, s) in ir.slices.withIndex()) {
            val row = laid.nodePositions[com.hrm.diagram.core.ir.NodeId("pie:legend:$i")] ?: continue
            val swatch = Rect.ltrb(row.left, row.top + 3f, row.left + 14f, row.bottom - 3f)
            val fill = palette[i % palette.size]
            out += DrawCommand.FillRect(rect = swatch, color = fill, corner = 3f, z = 5)
            out += DrawCommand.StrokeRect(rect = swatch, stroke = Stroke.Hairline, color = borderColor, corner = 3f, z = 6)

            val label = (s.label as? RichLabel.Plain)?.text ?: "slice$i"
            out += DrawCommand.DrawText(
                text = label,
                origin = Point(swatch.right + 8f, (row.top + row.bottom) / 2f),
                font = legendFont,
                color = borderColor,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Middle,
                z = 7,
            )
            val valueText = if (s.value % 1.0 == 0.0) s.value.toInt().toString() else s.value.toString()
            out += DrawCommand.DrawText(
                text = valueText,
                origin = Point(row.right, (row.top + row.bottom) / 2f),
                font = legendFont,
                color = borderColor,
                anchorX = TextAnchorX.End,
                anchorY = TextAnchorY.Middle,
                z = 7,
            )
        }

        // Overall border (optional).
        out += DrawCommand.StrokeRect(rect = Rect(Point(0f, 0f), Size(laid.bounds.size.width, laid.bounds.size.height)), stroke = Stroke.Hairline, color = Color(0x1A000000), corner = 0f, z = 0)
        return out
    }

    private fun wedgePath(center: Point, radius: Float, start: Double, end: Double): PathCmd {
        val ops = ArrayList<PathOp>()
        ops += PathOp.MoveTo(center)
        ops += PathOp.LineTo(polar(center, radius, start))
        arcCubic(ops, center, radius, start, end)
        ops += PathOp.Close
        return PathCmd(ops)
    }

    private fun polar(center: Point, r: Float, a: Double): Point =
        Point(
            (center.x + r * cos(a)).toFloat(),
            (center.y + r * sin(a)).toFloat(),
        )

    private fun arcCubic(ops: MutableList<PathOp>, center: Point, r: Float, start: Double, end: Double) {
        var a0 = start
        val dir = if (end >= start) 1.0 else -1.0
        var remaining = (end - start)
        while (dir * remaining > 1e-6) {
            val step = dir * min(dir * remaining, PI / 2.0) // <= 90deg per segment
            val a1 = a0 + step
            cubicArcSegment(ops, center, r, a0, a1)
            a0 = a1
            remaining = end - a0
        }
    }

    private fun cubicArcSegment(ops: MutableList<PathOp>, center: Point, r: Float, a0: Double, a1: Double) {
        val theta = a1 - a0
        val k = (4.0 / 3.0) * tan(theta / 4.0)
        val p0 = polar(center, r, a0)
        val p3 = polar(center, r, a1)
        val dx0 = (-sin(a0) * k * r).toFloat()
        val dy0 = (cos(a0) * k * r).toFloat()
        val dx1 = (sin(a1) * k * r).toFloat()
        val dy1 = (-cos(a1) * k * r).toFloat()
        val c1 = Point(p0.x + dx0, p0.y + dy0)
        val c2 = Point(p3.x + dx1, p3.y + dy1)
        ops += PathOp.CubicTo(c1, c2, p3)
    }
}
