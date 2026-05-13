package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.wireframe.WireframeLayout
import com.hrm.diagram.parser.plantuml.PlantUmlSaltParser
import com.hrm.diagram.render.streaming.DiagramSnapshot

internal class PlantUmlSaltSubPipeline(
    textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private val parser = PlantUmlSaltParser()
    private val layout = WireframeLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val textFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val buttonFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)

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

    private fun render(ir: WireframeIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val rootRect = laid.nodePositions[NodeId("wire:root")]
        if (rootRect != null) {
            out += DrawCommand.FillRect(rootRect, Color(0xFFF8FAFC.toInt()), corner = 8f, z = 0)
            out += DrawCommand.StrokeRect(rootRect, Stroke(width = 1.2f), Color(0xFF94A3B8.toInt()), corner = 8f, z = 1)
            out += DrawCommand.DrawText(
                text = "Salt",
                origin = Point(rootRect.left + 12f, rootRect.top + 10f),
                font = titleFont,
                color = Color(0xFF475569.toInt()),
                anchorY = TextAnchorY.Top,
                z = 2,
            )
        }

        fun draw(box: WireBox, path: String) {
            val id = NodeId("wire:$path")
            if (path != "root") {
                val rect = laid.nodePositions[id] ?: return
                when (box) {
                    is WireBox.Plain if (isTable(box)) -> {
                        out += DrawCommand.FillRect(rect, Color(0xFFFFFFFF.toInt()), corner = 4f, z = 2)
                        out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), Color(0xFF94A3B8.toInt()), corner = 4f, z = 3)
                        box.children.filterIsInstance<WireBox.Plain>().forEachIndexed { rowIndex, row ->
                            row.children.forEachIndexed { columnIndex, cell ->
                                val cellRect = laid.nodePositions[NodeId("wire:$path.$rowIndex.$columnIndex")] ?: return@forEachIndexed
                                if (rowIndex == 0) {
                                    out += DrawCommand.FillRect(cellRect, Color(0xFFF1F5F9.toInt()), z = 3)
                                }
                                out += DrawCommand.StrokeRect(cellRect, Stroke(width = 1f), Color(0xFFCBD5E1.toInt()), z = 4)
                                out += DrawCommand.DrawText(
                                    text = labelOf(cell),
                                    origin = Point(cellRect.left + 8f, cellRect.top + cellRect.size.height / 2f),
                                    font = if (rowIndex == 0) buttonFont else textFont,
                                    color = Color(0xFF0F172A.toInt()),
                                    maxWidth = cellRect.size.width - 16f,
                                    anchorY = TextAnchorY.Middle,
                                    z = 5,
                                )
                            }
                        }
                    }
                    is WireBox.Plain if (box.children.isNotEmpty()) -> drawContainer(box, path, rect, laid, out)
                    is WireBox.Plain if (labelOf(box).startsWith("Separator:")) -> {
                        val dash = when (labelOf(box).substringAfter(':')) {
                            ".." -> listOf(2f, 4f)
                            "--" -> listOf(7f, 5f)
                            else -> null
                        }
                        out += DrawCommand.StrokePath(
                            PathCmd(listOf(PathOp.MoveTo(Point(rect.left + 8f, (rect.top + rect.bottom) / 2f)), PathOp.LineTo(Point(rect.right - 8f, (rect.top + rect.bottom) / 2f)))),
                            Stroke(width = 1.2f, dash = dash),
                            Color(0xFF94A3B8.toInt()),
                            z = 5,
                        )
                    }
                    is WireBox.Button -> {
                        out += DrawCommand.FillRect(rect, Color(0xFFE2E8F0.toInt()), corner = 6f, z = 3)
                        out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), Color(0xFF64748B.toInt()), corner = 6f, z = 4)
                        out += DrawCommand.DrawText(
                            text = labelOf(box),
                            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                            font = buttonFont,
                            color = Color(0xFF0F172A.toInt()),
                            maxWidth = rect.size.width - 16f,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 5,
                        )
                    }
                    is WireBox.Input -> {
                        out += DrawCommand.FillRect(rect, Color(0xFFFFFFFF.toInt()), corner = 5f, z = 3)
                        out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), Color(0xFFCBD5E1.toInt()), corner = 5f, z = 4)
                        out += DrawCommand.DrawText(
                            text = labelOf(box),
                            origin = Point(rect.left + 10f, rect.top + rect.size.height / 2f),
                            font = textFont,
                            color = Color(0xFF475569.toInt()),
                            maxWidth = rect.size.width - 20f,
                            anchorY = TextAnchorY.Middle,
                            z = 5,
                        )
                    }
                    is WireBox.Image -> {
                        out += DrawCommand.FillRect(rect, Color(0xFFF8FAFC.toInt()), corner = 6f, z = 3)
                        out += DrawCommand.StrokeRect(rect, Stroke(width = 1f, dash = listOf(5f, 4f)), Color(0xFF94A3B8.toInt()), corner = 6f, z = 4)
                        out += DrawCommand.DrawText(
                            text = labelOf(box),
                            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
                            font = textFont,
                            color = Color(0xFF475569.toInt()),
                            maxWidth = rect.size.width - 20f,
                            anchorX = TextAnchorX.Center,
                            anchorY = TextAnchorY.Middle,
                            z = 5,
                        )
                    }
                    is WireBox.TabbedGroup -> {
                        out += DrawCommand.FillRect(rect, Color(0xFFFFFFFF.toInt()), corner = 6f, z = 3)
                        out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), Color(0xFFCBD5E1.toInt()), corner = 6f, z = 4)
                        val tabs = box.tabs
                        val tabWidth = rect.size.width / tabs.size.coerceAtLeast(1)
                        tabs.forEachIndexed { index, tab ->
                            val left = rect.left + tabWidth * index
                            val tabRect = rect.copy(origin = Point(left, rect.top), size = rect.size.copy(width = tabWidth))
                            if (index == 0) {
                                out += DrawCommand.FillRect(tabRect, Color(0xFFE0F2FE.toInt()), corner = 6f, z = 4)
                            }
                            if (index > 0) {
                                out += DrawCommand.StrokePath(
                                    path = PathCmd(
                                        listOf(
                                            PathOp.MoveTo(Point(left, rect.top + 5f)),
                                            PathOp.LineTo(Point(left, rect.bottom - 5f)),
                                        ),
                                    ),
                                    stroke = Stroke(width = 1f),
                                    color = Color(0xFFCBD5E1.toInt()),
                                    z = 5,
                                )
                            }
                            out += DrawCommand.DrawText(
                                text = labelOf(tab),
                                origin = Point(left + tabWidth / 2f, rect.top + rect.size.height / 2f),
                                font = buttonFont,
                                color = Color(0xFF0F172A.toInt()),
                                maxWidth = tabWidth - 12f,
                                anchorX = TextAnchorX.Center,
                                anchorY = TextAnchorY.Middle,
                                z = 6,
                            )
                        }
                    }
                    else -> {
                        out += DrawCommand.DrawText(
                            text = labelOf(box),
                            origin = Point(rect.left + 8f, rect.top + rect.size.height / 2f),
                            font = textFont,
                            color = Color(0xFF0F172A.toInt()),
                            maxWidth = rect.size.width - 16f,
                            anchorY = TextAnchorY.Middle,
                            z = 5,
                        )
                    }
                }
            }
            when (box) {
                is WireBox.Plain -> box.children.forEachIndexed { index, child -> draw(child, "$path.$index") }
                is WireBox.Button,
                is WireBox.Image,
                is WireBox.Input,
                is WireBox.TabbedGroup,
                -> Unit
            }
        }

        draw(ir.root, "root")
        return out
    }

    private fun labelOf(box: WireBox): String =
        when (val label = box.label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }

    private fun drawContainer(
        box: WireBox.Plain,
        path: String,
        rect: com.hrm.diagram.core.draw.Rect,
        laid: LaidOutDiagram,
        out: MutableList<DrawCommand>,
    ) {
        val label = labelOf(box)
        val style = containerStyle(label)
        if (style.drawFrame) {
            out += DrawCommand.FillRect(rect, style.fill, corner = 7f, z = 2)
            out += DrawCommand.StrokeRect(rect, Stroke(width = 1f, dash = style.dash), style.stroke, corner = 7f, z = 3)
        }
        out += DrawCommand.DrawText(
            text = style.title,
            origin = Point(rect.left + 12f, rect.top + 8f),
            font = buttonFont,
            color = Color(0xFF334155.toInt()),
            maxWidth = rect.size.width - 24f,
            anchorY = TextAnchorY.Top,
            z = 5,
        )
        if (style.kind in setOf("Tree", "List", "Menu")) {
            box.children.forEachIndexed { index, _ ->
                val childRect = laid.nodePositions[NodeId("wire:$path.$index")] ?: return@forEachIndexed
                val start = Point(rect.left + 16f, rect.top + 28f)
                val mid = Point(rect.left + 16f, childRect.top + childRect.size.height / 2f)
                val end = Point(childRect.left - 6f, childRect.top + childRect.size.height / 2f)
                out += DrawCommand.StrokePath(
                    path = PathCmd(listOf(PathOp.MoveTo(start), PathOp.LineTo(mid), PathOp.LineTo(end))),
                    stroke = Stroke(width = 1f),
                    color = Color(0xFFCBD5E1.toInt()),
                    z = 3,
                )
            }
        }
        if (style.kind == "Scroll") {
            val x = rect.right - 12f
            out += DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(Point(x, rect.top + 28f)), PathOp.LineTo(Point(x, rect.bottom - 10f)))),
                Stroke(width = 2f),
                Color(0xFFCBD5E1.toInt()),
                z = 5,
            )
        }
    }

    private data class ContainerStyle(
        val kind: String,
        val title: String,
        val fill: Color,
        val stroke: Color,
        val dash: List<Float>? = null,
        val drawFrame: Boolean = true,
    )

    private fun containerStyle(label: String): ContainerStyle {
        val kind = label.substringBefore(':').trim()
        val title = label.substringAfter(':', label).trim()
        return when (kind) {
            "Frame" -> ContainerStyle(kind, title, Color(0xFFFFFFFF.toInt()), Color(0xFF94A3B8.toInt()))
            "Grid" -> ContainerStyle(kind, title, Color(0xFFF8FAFC.toInt()), Color(0xFF94A3B8.toInt()))
            "Menu" -> ContainerStyle(kind, title, Color(0xFFFFFBEB.toInt()), Color(0xFFF59E0B.toInt()))
            "List" -> ContainerStyle(kind, title, Color(0xFFF0FDF4.toInt()), Color(0xFF22C55E.toInt()))
            "Scroll" -> ContainerStyle(kind, title, Color(0xFFF8FAFC.toInt()), Color(0xFF64748B.toInt()), dash = listOf(5f, 4f))
            "Group" -> ContainerStyle(kind, title, Color(0x00FFFFFF), Color(0xFFCBD5E1.toInt()), drawFrame = false)
            else -> ContainerStyle("Tree", label, Color(0x00FFFFFF), Color(0xFFCBD5E1.toInt()), drawFrame = false)
        }
    }

    private fun isTable(box: WireBox): Boolean =
        box is WireBox.Plain && labelOf(box) == "Table" && box.children.any { isTableRow(it) }

    private fun isTableRow(box: WireBox): Boolean =
        box is WireBox.Plain && labelOf(box) == "Row"
}
