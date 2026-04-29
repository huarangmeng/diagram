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
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.sankey.SankeyLayout
import com.hrm.diagram.parser.mermaid.MermaidSankeyParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.max

internal class MermaidSankeySubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private val parser = MermaidSankeyParser()
    private val layout = SankeyLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (line in lines) parser.acceptLine(line)
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)
        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(snapshot = snap, patch = SessionPatch.empty(seq, isFinal))
    }

    private fun render(ir: SankeyIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val text = Color(0xFF263238.toInt())
        val border = Color(0xFF607D8B.toInt())
        val nodeColors = ir.nodes.mapIndexed { index, node -> node.id to palette(index) }.toMap()
        val values = computeNodeValues(ir)
        val outOffset = LinkedHashMap<NodeId, Float>()
        val inOffset = LinkedHashMap<NodeId, Float>()

        out += DrawCommand.FillRect(Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), Color(0xFFFFFFFF.toInt()), z = 0)
        val titleRect = laid.nodePositions[NodeId("sankey:title")]
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(ir.title!!, Point(titleRect.left, titleRect.top), titleFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
        }

        for (flow in ir.flows) {
            val fromRect = laid.nodePositions[flow.from] ?: continue
            val toRect = laid.nodePositions[flow.to] ?: continue
            val fromScale = fromRect.size.height / max((values[flow.from] ?: 1.0).toFloat(), 1f)
            val toScale = toRect.size.height / max((values[flow.to] ?: 1.0).toFloat(), 1f)
            val thickness = max(6f, flow.value.toFloat() * minOf(fromScale, toScale))
            val fromTop = fromRect.top + (outOffset[flow.from] ?: 0f)
            val toTop = toRect.top + (inOffset[flow.to] ?: 0f)
            outOffset[flow.from] = (outOffset[flow.from] ?: 0f) + thickness
            inOffset[flow.to] = (inOffset[flow.to] ?: 0f) + thickness
            out += DrawCommand.FillPath(
                path = bandPath(
                    from = Point(fromRect.right, fromTop + thickness / 2f),
                    to = Point(toRect.left, toTop + thickness / 2f),
                    thickness = thickness,
                ),
                color = Color.argb(110, nodeColors[flow.from]?.r ?: 120, nodeColors[flow.from]?.g ?: 120, nodeColors[flow.from]?.b ?: 120),
                z = 2,
            )
        }

        for ((index, node) in ir.nodes.withIndex()) {
            val rect = laid.nodePositions[node.id] ?: continue
            val labelRect = laid.nodePositions[NodeId("sankey:label:${node.id.value}")]
            val fill = nodeColors[node.id] ?: palette(index)
            out += DrawCommand.FillRect(rect, fill, corner = 6f, z = 4)
            out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), border, corner = 6f, z = 5)
            val label = (node.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelRect != null) {
                out += DrawCommand.DrawText(label, Point(labelRect.left, labelRect.top), labelFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
            }
        }
        return out
    }

    private fun computeNodeValues(ir: SankeyIR): Map<NodeId, Double> {
        val incoming = LinkedHashMap<NodeId, Double>()
        val outgoing = LinkedHashMap<NodeId, Double>()
        for (flow in ir.flows) {
            outgoing[flow.from] = (outgoing[flow.from] ?: 0.0) + flow.value
            incoming[flow.to] = (incoming[flow.to] ?: 0.0) + flow.value
        }
        return ir.nodes.associate { node -> node.id to maxOf(incoming[node.id] ?: 0.0, outgoing[node.id] ?: 0.0, 1.0) }
    }

    private fun bandPath(from: Point, to: Point, thickness: Float): PathCmd {
        val c1 = Point(from.x + (to.x - from.x) * 0.35f, from.y)
        val c2 = Point(from.x + (to.x - from.x) * 0.65f, to.y)
        val ops = listOf(
            PathOp.MoveTo(Point(from.x, from.y - thickness / 2f)),
            PathOp.CubicTo(Point(c1.x, c1.y - thickness / 2f), Point(c2.x, c2.y - thickness / 2f), Point(to.x, to.y - thickness / 2f)),
            PathOp.LineTo(Point(to.x, to.y + thickness / 2f)),
            PathOp.CubicTo(Point(c2.x, c2.y + thickness / 2f), Point(c1.x, c1.y + thickness / 2f), Point(from.x, from.y + thickness / 2f)),
            PathOp.Close,
        )
        return PathCmd(ops)
    }

    private fun palette(index: Int): Color = listOf(
        Color(0xFF42A5F5.toInt()),
        Color(0xFF66BB6A.toInt()),
        Color(0xFFFFCA28.toInt()),
        Color(0xFFAB47BC.toInt()),
        Color(0xFF26C6DA.toInt()),
        Color(0xFFEF5350.toInt()),
    )[index % 6]
}
