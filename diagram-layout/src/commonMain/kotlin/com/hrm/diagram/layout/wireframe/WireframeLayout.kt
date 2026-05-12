package com.hrm.diagram.layout.wireframe

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.max

/**
 * Vertical box layout for PlantUML Salt wireframes represented as [WireframeIR].
 *
 * This MVP intentionally keeps the geometry simple: root frame plus vertically stacked child
 * widgets. IDs are path-derived (`wire:root.0`) so streaming layout can pin existing widgets.
 */
class WireframeLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<WireframeIR> {
    private val textFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val buttonFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)
    private val pad = 18f
    private val indent = 18f
    private val rowGap = 10f

    override fun layout(previous: LaidOutDiagram?, model: WireframeIR, options: LayoutOptions): LaidOutDiagram {
        val previousPositions = previous?.nodePositions.orEmpty()
        val positions = LinkedHashMap<NodeId, Rect>()
        var y = pad + 36f
        var maxRight = 260f

        fun place(box: WireBox, path: String, depth: Int) {
            val id = NodeId("wire:$path")
            if (isTable(box)) {
                val x = pad + depth * indent
                val top = y
                val tableSize = measureTable(box as WireBox.Plain)
                val tableRect = Rect(Point(x, top), tableSize)
                positions[id] = tableRect

                val rows = box.children.filterIsInstance<WireBox.Plain>()
                val columnWidths = tableColumnWidths(rows)
                val rowHeights = tableRowHeights(rows)
                var rowTop = top
                rows.forEachIndexed { rowIndex, row ->
                    var cellLeft = x
                    row.children.forEachIndexed { columnIndex, cell ->
                        val width = columnWidths.getOrElse(columnIndex) { 96f }
                        val height = rowHeights.getOrElse(rowIndex) { 32f }
                        positions[NodeId("wire:$path.$rowIndex.$columnIndex")] = Rect(Point(cellLeft, rowTop), Size(width, height))
                        cellLeft += width
                    }
                    rowTop += rowHeights.getOrElse(rowIndex) { 32f }
                }

                y = tableRect.bottom + rowGap
                maxRight = max(maxRight, tableRect.right + pad)
                return
            }
            if (box is WireBox.Plain && box.children.isNotEmpty()) {
                val x = pad + depth * indent
                val top = y
                val titleSize = measureBox(box)
                y += titleSize.height + rowGap
                box.children.forEachIndexed { index, child -> place(child, "$path.$index", depth + 1) }
                val childRects = box.children.indices.mapNotNull { index -> positions[NodeId("wire:$path.$index")] }
                val contentRight = childRects.maxOfOrNull { it.right } ?: (x + titleSize.width)
                val contentBottom = childRects.maxOfOrNull { it.bottom } ?: (top + titleSize.height)
                val width = max(titleSize.width, contentRight - x + pad)
                val height = max(titleSize.height + 16f, contentBottom - top + pad)
                val origin = if (options.incremental) previousPositions[id]?.origin ?: Point(x, top) else Point(x, top)
                val rect = Rect(origin, Size(width, height))
                positions[id] = rect
                y = rect.bottom + rowGap
                maxRight = max(maxRight, rect.right + pad)
                return
            }

            val x = pad + depth * indent
            val size = measureBox(box)
            val fresh = Rect(origin = Point(x, y), size = size)
            val rect = if (options.incremental) previousPositions[id] ?: fresh else fresh
            positions[id] = rect
            y = rect.bottom + rowGap
            maxRight = max(maxRight, rect.right + pad)
        }

        (model.root as? WireBox.Plain)?.children.orEmpty().forEachIndexed { index, child -> place(child, "root.$index", 1) }

        val frameHeight = max(100f, y - rowGap + pad)
        val rootRect = Rect(Point(pad, pad), Size(maxRight - pad, frameHeight))
        val rootId = NodeId("wire:root")
        positions[rootId] = if (options.incremental) previousPositions[rootId] ?: rootRect else rootRect

        return LaidOutDiagram(
            source = model,
            nodePositions = positions,
            edgeRoutes = emptyList(),
            bounds = Rect.ltrb(0f, 0f, maxRight + pad, frameHeight + pad),
        )
    }

    private fun measureBox(box: WireBox): Size {
        val label = labelOf(box)
        val font = if (box is WireBox.Button || box is WireBox.TabbedGroup) buttonFont else textFont
        val metrics = textMeasurer.measure(label, font, maxWidth = 320f)
        val minWidth = when (box) {
            is WireBox.Button -> 96f
            is WireBox.Input -> 180f
            is WireBox.TabbedGroup -> 220f
            is WireBox.Plain -> if (box.children.isNotEmpty()) 220f else 120f
            else -> 120f
        }
        val horizontalPadding = when (box) {
            is WireBox.Button -> 36f
            is WireBox.Input -> 28f
            is WireBox.TabbedGroup -> 48f
            is WireBox.Plain -> if (box.children.isNotEmpty()) 36f else 16f
            else -> 16f
        }
        val minHeight = when (box) {
            is WireBox.Button,
            is WireBox.Input,
            is WireBox.TabbedGroup,
            -> 34f
            is WireBox.Plain -> if (box.children.isNotEmpty()) 32f else 28f
            else -> 28f
        }
        return Size(
            width = max(minWidth, metrics.width + horizontalPadding),
            height = max(minHeight, metrics.height + 12f),
        )
    }

    private fun measureTable(table: WireBox.Plain): Size {
        val rows = table.children.filterIsInstance<WireBox.Plain>()
        val width = tableColumnWidths(rows).sum()
        val height = tableRowHeights(rows).sum()
        return Size(width = max(180f, width), height = max(32f, height))
    }

    private fun tableColumnWidths(rows: List<WireBox.Plain>): List<Float> {
        val columns = rows.maxOfOrNull { it.children.size } ?: 0
        return (0 until columns).map { column ->
            val maxCellWidth = rows.maxOfOrNull { row ->
                val cell = row.children.getOrNull(column) ?: return@maxOfOrNull 0f
                val metrics = textMeasurer.measure(labelOf(cell), textFont, maxWidth = 240f)
                metrics.width + 24f
            } ?: 0f
            max(72f, maxCellWidth)
        }
    }

    private fun tableRowHeights(rows: List<WireBox.Plain>): List<Float> =
        rows.map { row ->
            val maxCellHeight = row.children.maxOfOrNull { cell ->
                val metrics = textMeasurer.measure(labelOf(cell), textFont, maxWidth = 240f)
                metrics.height + 14f
            } ?: 0f
            max(32f, maxCellHeight)
        }

    private fun isTable(box: WireBox): Boolean =
        box is WireBox.Plain && labelOf(box) == "Table" && box.children.any { isTableRow(it) }

    private fun isTableRow(box: WireBox): Boolean =
        box is WireBox.Plain && labelOf(box) == "Row"

    private fun labelOf(box: WireBox): String =
        when (box) {
            is WireBox.TabbedGroup -> box.tabs.joinToString("   ") { labelText(it.label) }
            else -> labelText(box.label)
        }

    private fun labelText(label: RichLabel): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }
}
