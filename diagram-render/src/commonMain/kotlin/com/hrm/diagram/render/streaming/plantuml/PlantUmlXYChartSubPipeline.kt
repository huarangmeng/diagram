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
import com.hrm.diagram.core.ir.AxisKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.xy.XYChartLayout
import com.hrm.diagram.parser.plantuml.PlantUmlXYChartParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.round

internal class PlantUmlXYChartSubPipeline(
    defaultKind: SeriesKind,
    textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlXYChartParser(defaultKind)
    private val layout = XYChartLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val axisTitleFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val axisLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

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

    private fun render(ir: XYChartIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val plot = laid.nodePositions[NodeId("xychart:plot")] ?: return emptyList()
        val bg = Color(0xFFFFFFFF.toInt())
        val axis = Color(0xFF90A4AE.toInt())
        val text = Color(0xFF263238.toInt())
        val palette = listOf(
            Color(0xFF42A5F5.toInt()),
            Color(0xFFEF5350.toInt()),
            Color(0xFF66BB6A.toInt()),
            Color(0xFFFFCA28.toInt()),
        )

        out += DrawCommand.FillRect(Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), bg, z = 0)
        drawTitles(ir, laid, out, text)
        drawAxes(plot, out, axis)

        val yMin = ir.yAxis.min ?: 0.0
        val yMax = ir.yAxis.max ?: 1.0
        val yTicks = 5
        for (i in 0..yTicks) {
            val t = i.toFloat() / yTicks.toFloat()
            val y = plot.bottom - plot.size.height * t
            out += DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(Point(plot.left - 4f, y)), PathOp.LineTo(Point(plot.right, y)))),
                Stroke(width = if (i == 0) 1.5f else 0.75f, dash = if (i == 0) null else listOf(4f, 4f)),
                axis,
                z = 1,
            )
            laid.nodePositions[NodeId("xychart:yLabel:$i")]?.let { labelRect ->
                val value = yMin + (yMax - yMin) * t.toDouble()
                out += DrawCommand.DrawText(formatTick(value), Point(labelRect.left, y), axisLabelFont, text, anchorY = TextAnchorY.Middle, z = 10)
            }
        }

        val itemCount = when {
            ir.xAxis.kind == AxisKind.Category -> ir.xAxis.categories.size
            else -> ir.series.maxOfOrNull { it.ys.size } ?: 0
        }
        if (itemCount <= 0) return out
        val slot = plot.size.width / itemCount.toFloat()
        for (i in 0 until itemCount) {
            val x = plot.left + slot * (i + 0.5f)
            ir.xAxis.categories.getOrNull(i)?.let { label ->
                out += DrawCommand.DrawText(label, Point(x, plot.bottom + 18f), axisLabelFont, text, maxWidth = slot - 8f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Top, z = 10)
            }
        }

        fun yOf(value: Double): Float {
            val denom = (yMax - yMin).takeIf { it != 0.0 } ?: 1.0
            val t = ((value - yMin) / denom).coerceIn(0.0, 1.0)
            return (plot.bottom - plot.size.height * t).toFloat()
        }

        fun pointAt(index: Int, value: Double): Point =
            Point(plot.left + slot * (index + 0.5f), yOf(value))

        ir.series.forEachIndexed { seriesIndex, series ->
            val color = palette[seriesIndex % palette.size]
            when (series.kind) {
                SeriesKind.Bar -> {
                    val barW = slot / (ir.series.size + 1f)
                    for (i in series.ys.indices) {
                        val x0 = plot.left + slot * i + barW * seriesIndex + 6f
                        val y = yOf(series.ys[i])
                        out += DrawCommand.FillRect(Rect.ltrb(x0, y, x0 + barW, plot.bottom), color, corner = 3f, z = 3)
                    }
                }
                SeriesKind.Line -> {
                    if (series.ys.isEmpty()) return@forEachIndexed
                    val ops = ArrayList<PathOp>()
                    ops += PathOp.MoveTo(pointAt(0, series.ys[0]))
                    for (i in 1 until series.ys.size) ops += PathOp.LineTo(pointAt(i, series.ys[i]))
                    out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = 2f), color, z = 4)
                }
                SeriesKind.Scatter -> {
                    for (i in series.ys.indices) {
                        val point = pointAt(i, series.ys[i])
                        out += DrawCommand.FillRect(
                            Rect.ltrb(point.x - 4f, point.y - 4f, point.x + 4f, point.y + 4f),
                            color,
                            corner = 4f,
                            z = 5,
                        )
                    }
                }
                SeriesKind.Area,
                -> Unit
            }
        }
        return out
    }

    private fun drawTitles(ir: XYChartIR, laid: LaidOutDiagram, out: MutableList<DrawCommand>, text: Color) {
        laid.nodePositions[NodeId("xychart:title")]?.let { rect ->
            if (!ir.title.isNullOrBlank()) {
                out += DrawCommand.DrawText(ir.title!!, Point(rect.left, rect.top), titleFont, text, anchorY = TextAnchorY.Top, z = 10)
            }
        }
        laid.nodePositions[NodeId("xychart:xTitle")]?.let { rect ->
            val title = (ir.xAxis.title as? RichLabel.Plain)?.text ?: return@let
            out += DrawCommand.DrawText(title, Point(rect.left, rect.top), axisTitleFont, text, anchorY = TextAnchorY.Top, z = 10)
        }
        laid.nodePositions[NodeId("xychart:yTitle")]?.let { rect ->
            val title = (ir.yAxis.title as? RichLabel.Plain)?.text ?: return@let
            out += DrawCommand.DrawText(title, Point(rect.left, rect.top), axisTitleFont, text, anchorY = TextAnchorY.Top, z = 10)
        }
    }

    private fun drawAxes(plot: Rect, out: MutableList<DrawCommand>, axis: Color) {
        out += DrawCommand.StrokePath(
            PathCmd(listOf(PathOp.MoveTo(Point(plot.left, plot.bottom)), PathOp.LineTo(Point(plot.right, plot.bottom)))),
            Stroke(width = 1.5f),
            axis,
            z = 1,
        )
        out += DrawCommand.StrokePath(
            PathCmd(listOf(PathOp.MoveTo(Point(plot.left, plot.top)), PathOp.LineTo(Point(plot.left, plot.bottom)))),
            Stroke(width = 1.5f),
            axis,
            z = 1,
        )
    }

    private fun formatTick(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else (round(value * 100.0) / 100.0).toString()
}
