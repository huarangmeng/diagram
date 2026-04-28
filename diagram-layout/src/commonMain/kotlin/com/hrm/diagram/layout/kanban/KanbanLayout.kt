package com.hrm.diagram.layout.kanban

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.max

/**
 * Deterministic column/card layout for Mermaid kanban.
 *
 * Output ids:
 * - `kanban:column:<columnId>`
 * - `kanban:columnHeader:<columnId>`
 * - `kanban:card:<cardId>`
 *
 * Incremental mode keeps existing rects pinned and only allocates new ones.
 */
class KanbanLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val headerFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val cardFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val metaFont = FontSpec(family = "sans-serif", sizeSp = 10f)

    fun layout(previous: LaidOutDiagram?, model: KanbanIR, options: LayoutOptions): LaidOutDiagram {
        val prevPos = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()

        val pad = 18f
        val columnW = 240f
        val columnGap = 16f
        val columnHeaderH = 34f
        val cardGap = 10f
        val cardPad = 10f
        val titleId = NodeId("kanban:title")

        var topY = pad
        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont, maxWidth = 800f)
            val fresh = Rect(Point(pad, topY), Size(m.width, m.height))
            nodePositions[titleId] = if (options.incremental) (prevPos[titleId] ?: fresh) else fresh
            topY += m.height + 10f
        }

        var maxBottom = topY
        for ((idx, col) in model.columns.withIndex()) {
            val x = pad + idx * (columnW + columnGap)
            val colRectId = NodeId("kanban:column:${col.id.value}")
            val headerId = NodeId("kanban:columnHeader:${col.id.value}")

            val headerFresh = Rect(Point(x, topY), Size(columnW, columnHeaderH))
            nodePositions[headerId] = if (options.incremental) (prevPos[headerId] ?: headerFresh) else headerFresh

            var y = topY + columnHeaderH + 10f
            var colBottom = y
            for (card in col.cards) {
                val text = (card.label as? RichLabel.Plain)?.text ?: ""
                val metaLines = buildMetaLines(card.payload)
                val textM = textMeasurer.measure(text, cardFont, maxWidth = columnW - cardPad * 2)
                var h = textM.height + cardPad * 2
                for (m in metaLines) {
                    val mm = textMeasurer.measure(m, metaFont, maxWidth = columnW - cardPad * 2)
                    h += mm.height + 4f
                }
                h = max(h, 48f)
                val cardId = NodeId("kanban:card:${card.id.value}")
                val fresh = Rect(Point(x, y), Size(columnW, h))
                nodePositions[cardId] = if (options.incremental) (prevPos[cardId] ?: fresh) else fresh
                y += h + cardGap
                colBottom = y
            }

            val colFresh = Rect(Point(x, topY), Size(columnW, max(columnHeaderH + 16f, colBottom - topY)))
            nodePositions[colRectId] = if (options.incremental) (prevPos[colRectId] ?: colFresh) else colFresh
            maxBottom = max(maxBottom, colFresh.bottom)
        }

        val maxRight = nodePositions.values.maxOfOrNull { it.right } ?: (pad + columnW)
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, maxRight + pad, maxBottom + pad),
        )
    }

    private fun buildMetaLines(payload: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        payload["ticket"]?.let { out += it }
        payload["assigned"]?.let { out += it }
        payload["priority"]?.let { out += it }
        return out
    }
}

