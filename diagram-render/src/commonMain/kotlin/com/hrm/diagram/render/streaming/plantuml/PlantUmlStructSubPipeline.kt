package com.hrm.diagram.render.streaming.plantuml

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
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.struct.StructLayout
import com.hrm.diagram.parser.plantuml.PlantUmlStructParser
import com.hrm.diagram.render.streaming.DiagramSnapshot

internal class PlantUmlStructSubPipeline(
    private val format: PlantUmlStructParser.Format,
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private companion object {
        val fill = Color(0xFFF6F8FA.toInt())
        val rootFill = Color(0xFFEAF5FF.toInt())
        val stroke = Color(0xFFD0D7DE.toInt())
        val rootStroke = Color(0xFF0969DA.toInt())
        val text = Color(0xFF24292F.toInt())
        val numberText = Color(0xFF0550AE.toInt())
        val booleanText = Color(0xFF8250DF.toInt())
        val nullText = Color(0xFF6E7781.toInt())
        val edge = Color(0xFF8C959F.toInt())
    }

    private val parser = PlantUmlStructParser(format)
    private val layout = StructLayout(textMeasurer)
    private val font = FontSpec(family = "monospace", sizeSp = 12f)
    private val rootFont = font.copy(weight = 600)

    override fun acceptLine(line: String) = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean) = parser.finish(blockClosed)

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

    private fun render(ir: StructIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val collapsiblePaths = parsePathSet(ir.styleHints.extras[PlantUmlStructParser.COLLAPSIBLE_PATHS_KEY].orEmpty())
        val scalarKinds = parsePathMap(ir.styleHints.extras[PlantUmlStructParser.SCALAR_KINDS_KEY].orEmpty())
        for (route in laid.edgeRoutes) {
            val ops = route.points.mapIndexed { index, point ->
                if (index == 0) PathOp.MoveTo(point) else PathOp.LineTo(point)
            }
            out += DrawCommand.StrokePath(PathCmd(ops), Stroke(width = 1.2f), edge, z = 0)
        }

        fun drawNode(node: StructNode, path: String, isRoot: Boolean) {
            val id = NodeId("struct_$path")
            val rect = laid.nodePositions[id] ?: return
            out += DrawCommand.FillRect(rect, if (isRoot) rootFill else fill, corner = 6f, z = 1)
            out += DrawCommand.StrokeRect(rect, Stroke(width = if (isRoot) 1.6f else 1f), if (isRoot) rootStroke else stroke, corner = 6f, z = 2)
            out += DrawCommand.DrawText(
                text = labelFor(node, path in collapsiblePaths),
                origin = Point(rect.left + 12f, rect.top + rect.size.height / 2f),
                font = if (isRoot) rootFont else font,
                color = scalarTextColor(scalarKinds[path]) ?: text,
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

    private fun labelFor(node: StructNode, collapsible: Boolean): String {
        val prefix = node.key?.let { "$it: " }.orEmpty()
        val marker = if (collapsible) "[-] " else ""
        return when (node) {
            is StructNode.ArrayNode -> "$marker$prefix[${node.items.size}]"
            is StructNode.ObjectNode -> "$marker$prefix{${node.entries.size}}"
            is StructNode.Scalar -> "$prefix${node.value}"
        }
    }

    private fun scalarTextColor(kind: String?): Color? = when (kind) {
        "number" -> numberText
        "boolean" -> booleanText
        "null" -> nullText
        else -> null
    }

    private fun parsePathSet(raw: String): Set<String> =
        raw.split("||").filterTo(LinkedHashSet()) { it.isNotBlank() }

    private fun parsePathMap(raw: String): Map<String, String> =
        raw.split("||").mapNotNull { entry ->
            val split = entry.lastIndexOf('|')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }.toMap()
}
