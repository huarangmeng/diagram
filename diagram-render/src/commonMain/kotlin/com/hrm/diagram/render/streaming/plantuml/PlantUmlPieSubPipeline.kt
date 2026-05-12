package com.hrm.diagram.render.streaming.plantuml

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
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.pie.PieLayout
import com.hrm.diagram.parser.plantuml.PlantUmlPieParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal class PlantUmlPieSubPipeline(
    textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlPieParser()
    private val layout = PieLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val legendFont = FontSpec(family = "sans-serif", sizeSp = 12f)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        ).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun render(ir: PieIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val pad = 20f
        val radius = 120f
        val pieTop = laid.nodePositions[NodeId("pie:title")]?.bottom?.plus(10f) ?: pad
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

        val titleRect = laid.nodePositions[NodeId("pie:title")]
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

        for ((index, slice) in ir.slices.withIndex()) {
            val sweep = (slice.value / total) * 2.0 * PI
            val start = angle
            val end = angle + sweep
            val path = wedgePath(center, radius, start, end)
            val fill = palette[index % palette.size]
            out += DrawCommand.FillPath(path = path, color = fill, z = 1)
            out += DrawCommand.StrokePath(path = path, stroke = border, color = borderColor, z = 2)
            angle = end
        }

        for ((index, slice) in ir.slices.withIndex()) {
            val row = laid.nodePositions[NodeId("pie:legend:$index")] ?: continue
            val swatch = Rect.ltrb(row.left, row.top + 3f, row.left + 14f, row.bottom - 3f)
            val fill = palette[index % palette.size]
            out += DrawCommand.FillRect(rect = swatch, color = fill, corner = 3f, z = 5)
            out += DrawCommand.StrokeRect(rect = swatch, stroke = Stroke.Hairline, color = borderColor, corner = 3f, z = 6)
            out += DrawCommand.DrawText(
                text = labelOf(slice.label, index),
                origin = Point(swatch.right + 8f, (row.top + row.bottom) / 2f),
                font = legendFont,
                color = borderColor,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Middle,
                z = 7,
            )
            out += DrawCommand.DrawText(
                text = formatValue(slice.value),
                origin = Point(row.right, (row.top + row.bottom) / 2f),
                font = legendFont,
                color = borderColor,
                anchorX = TextAnchorX.End,
                anchorY = TextAnchorY.Middle,
                z = 7,
            )
        }

        out += DrawCommand.StrokeRect(
            rect = Rect(Point(0f, 0f), Size(laid.bounds.size.width, laid.bounds.size.height)),
            stroke = Stroke.Hairline,
            color = Color(0x1A000000),
            z = 0,
        )
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

    private fun polar(center: Point, radius: Float, angle: Double): Point =
        Point(
            (center.x + radius * cos(angle)).toFloat(),
            (center.y + radius * sin(angle)).toFloat(),
        )

    private fun arcCubic(ops: MutableList<PathOp>, center: Point, radius: Float, start: Double, end: Double) {
        var a0 = start
        val dir = if (end >= start) 1.0 else -1.0
        var remaining = end - start
        while (dir * remaining > 1e-6) {
            val step = dir * min(dir * remaining, PI / 2.0)
            val a1 = a0 + step
            cubicArcSegment(ops, center, radius, a0, a1)
            a0 = a1
            remaining = end - a0
        }
    }

    private fun cubicArcSegment(ops: MutableList<PathOp>, center: Point, radius: Float, a0: Double, a1: Double) {
        val theta = a1 - a0
        val k = (4.0 / 3.0) * tan(theta / 4.0)
        val p0 = polar(center, radius, a0)
        val p3 = polar(center, radius, a1)
        val dx0 = (-sin(a0) * k * radius).toFloat()
        val dy0 = (cos(a0) * k * radius).toFloat()
        val dx1 = (sin(a1) * k * radius).toFloat()
        val dy1 = (-cos(a1) * k * radius).toFloat()
        ops += PathOp.CubicTo(Point(p0.x + dx0, p0.y + dy0), Point(p3.x + dx1, p3.y + dy1), p3)
    }

    private fun labelOf(label: RichLabel, index: Int): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }.ifBlank { "slice$index" }

    private fun formatValue(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
