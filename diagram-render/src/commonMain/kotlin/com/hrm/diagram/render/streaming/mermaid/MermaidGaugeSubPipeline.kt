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
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.gauge.GaugeLayout
import com.hrm.diagram.parser.mermaid.MermaidGaugeParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal class MermaidGaugeSubPipeline : MermaidSubPipeline {
    private val parser = MermaidGaugeParser()
    private val layout = GaugeLayout()

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val valueFont = FontSpec(family = "sans-serif", sizeSp = 20f, weight = 600)
    private val minMaxFont = FontSpec(family = "sans-serif", sizeSp = 12f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<com.hrm.diagram.core.streaming.IrPatch>()
        for (line in lines) {
            val batch = parser.acceptLine(line)
            newPatches += batch.patches
        }
        val ir = parser.snapshot()

        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)
        val newDiagnostics = newPatches.filterIsInstance<com.hrm.diagram.core.streaming.IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = SessionPatch(
            seq = seq,
            addedNodes = emptyList(),
            addedEdges = emptyList(),
            addedDrawCommands = draw,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return PipelineAdvance(snapshot = snap, patch = patch)
    }

    private fun render(ir: GaugeIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds

        val titleRect = laid.nodePositions[NodeId("gauge:title")]
        val valueRect = laid.nodePositions[NodeId("gauge:value")]
        val minRect = laid.nodePositions[NodeId("gauge:min")]
        val maxRect = laid.nodePositions[NodeId("gauge:max")]

        val textColor = Color(0xFF263238.toInt())
        val bgArc = Color(0xFFCFD8DC.toInt())
        val fgArc = Color(0xFF42A5F5.toInt())
        val needleColor = Color(0xFFEF5350.toInt())

        // Background frame.
        out += DrawCommand.FillRect(rect = Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), color = Color(0xFFFFFFFF.toInt()), corner = 0f, z = 0)

        // Title.
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(
                text = ir.title!!,
                origin = Point(titleRect.left, titleRect.top),
                font = titleFont,
                color = textColor,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = 10,
            )
        }

        // Gauge geometry (semi-circle).
        val center = Point(bounds.size.width / 2f, 150f)
        val radius = 110f
        val start = PI // left
        val end = 0.0 // right
        val stroke = Stroke(width = 10f)

        // Track.
        out += DrawCommand.StrokePath(path = arcPath(center, radius, start, end), stroke = stroke, color = bgArc, z = 1)

        // Value fill (clamped).
        val denom = (ir.max - ir.min).takeIf { it != 0.0 } ?: 1.0
        val t = ((ir.value - ir.min) / denom).coerceIn(0.0, 1.0)
        val vEnd = start + (end - start) * t
        out += DrawCommand.StrokePath(path = arcPath(center, radius, start, vEnd), stroke = stroke, color = fgArc, z = 2)

        // Needle.
        val needleLen = radius - 8f
        val a = vEnd
        val tip = Point((center.x + needleLen * cos(a)).toFloat(), (center.y + needleLen * sin(a)).toFloat())
        out += DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(center), PathOp.LineTo(tip))),
            stroke = Stroke(width = 3f),
            color = needleColor,
            z = 3,
        )

        // Value text.
        if (valueRect != null) {
            out += DrawCommand.DrawText(
                text = formatValue(ir.value),
                origin = Point(center.x, center.y),
                font = valueFont,
                color = textColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 11,
            )
        }

        // Min/Max.
        if (minRect != null) {
            out += DrawCommand.DrawText(
                text = formatValue(ir.min),
                origin = Point(minRect.left, minRect.top),
                font = minMaxFont,
                color = textColor,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = 11,
            )
        }
        if (maxRect != null) {
            out += DrawCommand.DrawText(
                text = formatValue(ir.max),
                origin = Point(maxRect.right, maxRect.top),
                font = minMaxFont,
                color = textColor,
                anchorX = TextAnchorX.End,
                anchorY = TextAnchorY.Top,
                z = 11,
            )
        }
        return out
    }

    private fun formatValue(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    private fun arcPath(center: Point, r: Float, start: Double, end: Double): PathCmd {
        val ops = ArrayList<PathOp>()
        ops += PathOp.MoveTo(polar(center, r, start))
        arcCubic(ops, center, r, start, end)
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
            val step = dir * min(dir * remaining, PI / 2.0)
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

