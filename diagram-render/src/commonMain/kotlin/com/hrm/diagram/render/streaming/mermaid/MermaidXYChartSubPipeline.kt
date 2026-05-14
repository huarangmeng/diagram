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
import com.hrm.diagram.core.draw.Transform
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.xy.XYChartLayout
import com.hrm.diagram.parser.mermaid.MermaidXYChartParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.abs
import kotlin.math.round

internal class MermaidXYChartSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private var styleExtras: Map<String, String> = emptyMap()

    override fun updateStyleExtras(extras: Map<String, String>) {
        styleExtras = extras
    }

    private val parser = MermaidXYChartParser()
    private val layout = XYChartLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val axisTitleFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val axisLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

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
        val laid = layout.layout(previousSnapshot.laidOut, ir, LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal))
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

    private fun render(ir: XYChartIR, laid: com.hrm.diagram.layout.LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val plot = laid.nodePositions[NodeId("xychart:plot")] ?: return emptyList()
        val horizontal = ir.styleHints.extras["xyChart.orientation"] == "horizontal" || ir.styleHints.direction == Direction.LR
        val showDataLabel = styleExtras["mermaid.config.xyChart.showDataLabel"]?.lowercase() == "true" ||
            ir.styleHints.extras["xyChart.showDataLabel"]?.lowercase() == "true"
        val showDataLabelOutsideBar = styleExtras["mermaid.config.xyChart.showDataLabelOutsideBar"]?.lowercase() == "true" ||
            ir.styleHints.extras["xyChart.showDataLabelOutsideBar"]?.lowercase() == "true"
        val themeRaw = MermaidRenderThemeUtils.decodeRawThemeTokens(styleExtras["mermaid.themeTokens"])

        val bg = MermaidRenderThemeUtils.parseThemeColor(themeRaw["backgroundColor"]) ?: Color(0xFFFFFFFF.toInt())
        val axis = MermaidRenderThemeUtils.parseThemeColor(themeRaw["xAxisLineColor"]) ?: Color(0xFF90A4AE.toInt())
        val yAxis = MermaidRenderThemeUtils.parseThemeColor(themeRaw["yAxisLineColor"]) ?: axis
        val text = MermaidRenderThemeUtils.parseThemeColor(themeRaw["titleColor"]) ?: Color(0xFF263238.toInt())
        val xAxisLabelColor = MermaidRenderThemeUtils.parseThemeColor(themeRaw["xAxisLabelColor"]) ?: text
        val yAxisLabelColor = MermaidRenderThemeUtils.parseThemeColor(themeRaw["yAxisLabelColor"]) ?: text
        val xAxisTitleColor = MermaidRenderThemeUtils.parseThemeColor(themeRaw["xAxisTitleColor"]) ?: text
        val yAxisTitleColor = MermaidRenderThemeUtils.parseThemeColor(themeRaw["yAxisTitleColor"]) ?: text
        val dataLabelColor = MermaidRenderThemeUtils.parseThemeColor(themeRaw["dataLabelColor"]) ?: text
        val palette = listOf(
            Color(0xFF42A5F5.toInt()),
            Color(0xFFEF5350.toInt()),
            Color(0xFF66BB6A.toInt()),
            Color(0xFFFFCA28.toInt()),
        ).let { defaults ->
            val custom = themeRaw["plotColorPalette"]
                ?.split(',')
                ?.mapNotNull { MermaidRenderThemeUtils.parseThemeColor(it.trim()) }
                .orEmpty()
            if (custom.isEmpty()) defaults else custom
        }

        out += DrawCommand.FillRect(rect = Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), color = bg, corner = 0f, z = 0)

        laid.nodePositions[NodeId("xychart:title")]?.let { r ->
            if (!ir.title.isNullOrBlank()) {
                out += DrawCommand.DrawText(ir.title!!, Point(r.left, r.top), titleFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
            }
        }
        laid.nodePositions[NodeId("xychart:xTitle")]?.let { r ->
            val t = (ir.xAxis.title as? RichLabel.Plain)?.text ?: return@let
            out += DrawCommand.DrawText(t, Point(r.left, r.top), axisTitleFont, xAxisTitleColor, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
        }
        laid.nodePositions[NodeId("xychart:yTitle")]?.let { r ->
            val t = (ir.yAxis.title as? RichLabel.Plain)?.text ?: return@let
            out += DrawCommand.Group(
                transform = Transform(
                    translate = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f),
                    rotateDeg = -90f,
                ),
                children = listOf(
                    DrawCommand.DrawText(
                        t,
                        Point(0f, 0f),
                        axisTitleFont,
                        yAxisTitleColor,
                        anchorX = TextAnchorX.Center,
                        anchorY = TextAnchorY.Middle,
                        z = 10,
                    ),
                ),
                z = 10,
            )
        }

        // Axes.
        out += DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(Point(plot.left, plot.bottom)), PathOp.LineTo(Point(plot.right, plot.bottom)))),
            stroke = Stroke(width = 1.5f),
            color = axis,
            z = 1,
        )
        out += DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(Point(plot.left, plot.top)), PathOp.LineTo(Point(plot.left, plot.bottom)))),
            stroke = Stroke(width = 1.5f),
            color = yAxis,
            z = 1,
        )

        // Y ticks / labels.
        val yMin = ir.yAxis.min ?: 0.0
        val yMax = ir.yAxis.max ?: 1.0
        val yTicks = 5
        for (i in 0..yTicks) {
            val t = i.toFloat() / yTicks.toFloat()
            val y = plot.bottom - (plot.size.height * t)
            out += DrawCommand.StrokePath(
                path = PathCmd(listOf(PathOp.MoveTo(Point(plot.left - 4f, y)), PathOp.LineTo(Point(plot.right, y)))),
                stroke = Stroke(width = if (i == 0) 1.5f else 0.75f, dash = if (i == 0) null else listOf(4f, 4f)),
                color = yAxis,
                z = 1,
            )
            val v = yMin + (yMax - yMin) * t.toDouble()
            val txt = formatTick(v)
            val labelRect = laid.nodePositions[NodeId("xychart:yLabel:$i")]
            if (labelRect != null) {
                out += DrawCommand.DrawText(txt, Point(labelRect.left, y), axisLabelFont, yAxisLabelColor, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Middle, z = 10)
            }
        }

        val itemCount = when {
            ir.xAxis.kind == com.hrm.diagram.core.ir.AxisKind.Category -> ir.xAxis.categories.size
            else -> ir.series.maxOfOrNull { it.ys.size } ?: 0
        }
        if (itemCount <= 0) return out

        val slot = plot.size.width / itemCount.toFloat()
        for (i in 0 until itemCount) {
            val x = plot.left + slot * (i + 0.5f)
            val label = ir.xAxis.categories.getOrNull(i)
            if (label != null) {
                out += DrawCommand.DrawText(label, Point(x, plot.bottom + 18f), axisLabelFont, xAxisLabelColor, maxWidth = slot - 8f, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Top, z = 10)
            }
        }

        fun yOf(v: Double): Float {
            val denom = ((yMax - yMin).takeIf { it != 0.0 } ?: 1.0)
            val t = ((v - yMin) / denom).coerceIn(0.0, 1.0)
            return (plot.bottom - plot.size.height * t).toFloat()
        }

        fun pointAt(i: Int, v: Double): Point {
            val x = plot.left + slot * (i + 0.5f)
            val y = yOf(v)
            return if (!horizontal) Point(x, y) else Point(plot.left + (plot.size.width * ((v - yMin) / ((yMax - yMin).takeIf { it != 0.0 } ?: 1.0))).toFloat(), plot.top + (plot.size.height / itemCount.toFloat()) * (i + 0.5f))
        }

        ir.series.forEachIndexed { sIdx, s ->
            val color = palette[sIdx % palette.size]
            when (s.kind) {
                SeriesKind.Bar -> {
                    val barW = slot / (ir.series.size + 1f)
                    for (i in s.ys.indices) {
                        val x0 = plot.left + slot * i + barW * sIdx + 6f
                        val y = yOf(s.ys[i])
                        val rect = if (!horizontal) Rect.ltrb(x0, y, x0 + barW, plot.bottom) else Rect.ltrb(plot.left, plot.top + (plot.size.height / itemCount.toFloat()) * i + 6f, pointAt(i, s.ys[i]).x, plot.top + (plot.size.height / itemCount.toFloat()) * (i + 1) - 6f)
                        out += DrawCommand.FillRect(rect = rect, color = color, corner = 3f, z = 3)
                        if (showDataLabel) {
                            val anchorY = if (!horizontal && showDataLabelOutsideBar) rect.top - 4f else (rect.top + rect.bottom) / 2f
                            val textAnchorY = if (!horizontal && showDataLabelOutsideBar) TextAnchorY.Bottom else TextAnchorY.Middle
                            out += DrawCommand.DrawText(
                                text = formatTick(s.ys[i]),
                                origin = Point((rect.left + rect.right) / 2f, anchorY),
                                font = axisLabelFont,
                                color = dataLabelColor,
                                anchorX = TextAnchorX.Center,
                                anchorY = textAnchorY,
                                z = 10,
                            )
                        }
                    }
                }
                SeriesKind.Line -> {
                    if (s.ys.isEmpty()) return@forEachIndexed
                    val ops = ArrayList<PathOp>()
                    ops += PathOp.MoveTo(pointAt(0, s.ys[0]))
                    for (i in 1 until s.ys.size) ops += PathOp.LineTo(pointAt(i, s.ys[i]))
                    out += DrawCommand.StrokePath(path = PathCmd(ops), stroke = Stroke(width = 2f), color = color, z = 4)
                    if (showDataLabel) {
                        for (i in s.ys.indices) {
                            val p = pointAt(i, s.ys[i])
                            out += DrawCommand.DrawText(
                                text = formatTick(s.ys[i]),
                                origin = Point(p.x, p.y - 6f),
                                font = axisLabelFont,
                                color = dataLabelColor,
                                anchorX = TextAnchorX.Center,
                                anchorY = TextAnchorY.Bottom,
                                z = 10,
                            )
                        }
                    }
                }
                SeriesKind.Scatter -> {
                    for (i in s.ys.indices) {
                        val p = pointAt(i, s.ys[i])
                        val r = Rect.ltrb(p.x - 4f, p.y - 4f, p.x + 4f, p.y + 4f)
                        out += DrawCommand.FillRect(rect = r, color = color, corner = 4f, z = 5)
                        if (showDataLabel) {
                            out += DrawCommand.DrawText(
                                text = formatTick(s.ys[i]),
                                origin = Point(p.x, p.y - 6f),
                                font = axisLabelFont,
                                color = dataLabelColor,
                                anchorX = TextAnchorX.Center,
                                anchorY = TextAnchorY.Bottom,
                                z = 10,
                            )
                        }
                    }
                }
                SeriesKind.Area -> {
                    if (s.ys.isEmpty()) return@forEachIndexed
                    val ops = ArrayList<PathOp>()
                    val start = pointAt(0, s.ys[0])
                    ops += PathOp.MoveTo(Point(start.x, plot.bottom))
                    ops += PathOp.LineTo(start)
                    for (i in 1 until s.ys.size) ops += PathOp.LineTo(pointAt(i, s.ys[i]))
                    val end = pointAt(s.ys.lastIndex, s.ys.last())
                    ops += PathOp.LineTo(Point(end.x, plot.bottom))
                    ops += PathOp.Close
                    out += DrawCommand.FillPath(path = PathCmd(ops), color = Color.argb(90, color.r, color.g, color.b), z = 2)
                }
            }
        }
        return out
    }

    private fun formatTick(v: Double): String {
        val rounded = round(v)
        if (abs(v - rounded) < 1e-9) return rounded.toInt().toString()
        return (round(v * 100.0) / 100.0).toString()
    }
}
