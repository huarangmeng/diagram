package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.struct.StructLayout
import com.hrm.diagram.parser.mermaid.MermaidPacketParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

internal class MermaidPacketSubPipeline(
    textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private companion object {
        val fill = Color(0xFFFFF8E1.toInt())
        val rootFill = Color(0xFFFFECB3.toInt())
        val stroke = Color(0xFFFFB300.toInt())
        val rootStroke = Color(0xFFFF8F00.toInt())
        val text = Color(0xFF3E2723.toInt())
        val edge = Color(0xFFFFB300.toInt())
    }

    private val parser = MermaidPacketParser()
    private val layout = StructLayout(textMeasurer)
    private val font = FontSpec(family = "monospace", sizeSp = 12f)
    private val rootFont = font.copy(weight = 700)

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
        ).copy(seq = seq)
        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(snapshot = snap, patch = SessionPatch.empty(seq, isFinal))
    }

    private fun render(ir: StructIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        for (route in laid.edgeRoutes) {
            out += DrawCommand.StrokePath(
                PathCmd(route.points.mapIndexed { index, point -> if (index == 0) PathOp.MoveTo(point) else PathOp.LineTo(point) }),
                Stroke(width = 1.2f),
                edge,
                z = 0,
            )
        }
        fun drawNode(node: StructNode, path: String, isRoot: Boolean) {
            val id = NodeId("struct_$path")
            val rect = laid.nodePositions[id] ?: return
            out += DrawCommand.FillRect(rect, if (isRoot) rootFill else fill, corner = 7f, z = 1)
            out += DrawCommand.StrokeRect(rect, Stroke(width = if (isRoot) 1.8f else 1f), if (isRoot) rootStroke else stroke, corner = 7f, z = 2)
            out += DrawCommand.DrawText(
                text = labelFor(node),
                origin = Point(rect.left + 12f, rect.top + rect.size.height / 2f),
                font = if (isRoot) rootFont else font,
                color = text,
                maxWidth = rect.size.width - 24f,
                anchorY = TextAnchorY.Middle,
                z = 3,
            )
            when (node) {
                is StructNode.ArrayNode -> node.items.forEachIndexed { index, child -> drawNode(child, "$path.$index", false) }
                is StructNode.ObjectNode -> node.entries.forEachIndexed { index, child -> drawNode(child, "$path.$index", false) }
                is StructNode.Scalar -> Unit
            }
        }
        drawNode(ir.root, "root", true)
        return out
    }

    private fun labelFor(node: StructNode): String {
        val prefix = node.key?.let { "$it: " }.orEmpty()
        return when (node) {
            is StructNode.ArrayNode -> "$prefix[${node.items.size}]"
            is StructNode.ObjectNode -> "$prefix{${node.entries.size}}"
            is StructNode.Scalar -> "$prefix${node.value}"
        }
    }
}
