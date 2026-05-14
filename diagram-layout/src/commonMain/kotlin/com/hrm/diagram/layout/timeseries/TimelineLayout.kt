package com.hrm.diagram.layout.timeseries

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.TimeItem
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.max

/**
 * Deterministic layout for Mermaid timeline (represented as [TimeSeriesIR]).
 *
 * This is NOT a full gantt layout; it is a timeline-specific layout:
 * - title at top
 * - per track (section): header then a grid of time slots
 * - each item is placed inside its time slot, stacked vertically
 *
 * Incremental mode pins existing node rects and only appends new ones at the end of the flow.
 */
class TimelineLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<TimeSeriesIR> {

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val headerFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val itemFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val periodFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)

    override fun layout(previous: LaidOutDiagram?, model: TimeSeriesIR, options: LayoutOptions): LaidOutDiagram {
        val pad = 18f
        val gapY = 10f
        val slotW = 180f
        val minSlotH = 72f
        val maxWrap = 160f

        val prevPos = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()

        var y = pad
        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont)
            val r = Rect(Point(pad, y), Size(m.width, m.height))
            nodePositions[NodeId("timeline:title")] = pin(prevPos, NodeId("timeline:title"), r, options)
            y += m.height + gapY
        }

        // Determine distinct slots by range.startMs.
        val slots = model.items
            .map { it.range.startMs }
            .distinct()
            .sorted()
        val slotIndex = slots.withIndex().associate { it.value to it.index }
        val dir = model.styleHints.direction ?: Direction.LR

        for (track in model.tracks) {
            val headerId = NodeId("timeline:track:${track.id.value}")
            val headerText = (track.label as? RichLabel.Plain)?.text ?: track.id.value
            val hm = textMeasurer.measure(headerText, headerFont, maxWidth = 520f)
            val headerRect = Rect(Point(pad, y), Size(hm.width, hm.height))
            nodePositions[headerId] = pin(prevPos, headerId, headerRect, options)
            y += hm.height + 6f

            val itemsBySlot = model.items
                .filter { it.trackId == track.id }
                .groupBy { it.range.startMs }
            val slotHeights = slots.associateWith { slotMs ->
                requiredSlotHeight(itemsBySlot[slotMs].orEmpty(), maxWrap)
            }

            // Establish a local origin for this track block.
            val blockTop = y
            var blockH = 0f
            var blockW = 0f

            fun placeSlot(slotMs: Long, x0: Float, y0: Float) {
                val slotH = slotHeights[slotMs] ?: minSlotH
                val slotId = NodeId("timeline:slot:${track.id.value}:$slotMs")
                val slotRect = Rect(Point(x0, y0), Size(slotW, slotH))
                nodePositions[slotId] = pin(prevPos, slotId, slotRect, options)

                val items = itemsBySlot[slotMs].orEmpty()
                var iy = y0 + 10f
                for (it in items) {
                    val h = requiredItemHeight(it, maxWrap)
                    val itemId = NodeId("timeline:item:${it.id.value}")
                    val ir = Rect(Point(x0 + 10f, iy), Size(slotW - 20f, h))
                    nodePositions[itemId] = pin(prevPos, itemId, ir, options)
                    iy += h + 6f
                }
            }

            if (dir == Direction.TB) {
                // Vertical timeline: slots stacked vertically.
                var yy = blockTop
                for (slotMs in slots) {
                    placeSlot(slotMs, pad, yy)
                    yy += (slotHeights[slotMs] ?: minSlotH) + gapY
                }
                blockH = slots.sumOf { ((slotHeights[it] ?: minSlotH) + gapY).toDouble() }.toFloat()
                blockW = slotW
            } else {
                // Horizontal timeline: slots laid left-to-right.
                var xx = pad
                for (slotMs in slots) {
                    placeSlot(slotMs, xx, blockTop)
                    xx += slotW + 12f
                }
                blockW = if (slots.isEmpty()) 0f else (slots.size * slotW + (slots.size - 1) * 12f)
                blockH = slotHeights.values.maxOrNull() ?: minSlotH
            }

            y = blockTop + blockH + 12f

            // Track bounding box (optional; used by renderer if needed).
            val trackBoxId = NodeId("timeline:trackBox:${track.id.value}")
            nodePositions[trackBoxId] = pin(
                prevPos,
                trackBoxId,
                Rect(Point(pad, blockTop), Size(max(blockW, slotW), max(blockH, minSlotH))),
                options,
            )
        }

        val maxRight = nodePositions.values.maxOfOrNull { it.right } ?: (pad + slotW)
        val maxBottom = nodePositions.values.maxOfOrNull { it.bottom } ?: (pad + minSlotH)
        val bounds = Rect.ltrb(0f, 0f, maxRight + pad, maxBottom + pad)

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = bounds,
        )
    }

    private fun requiredSlotHeight(items: List<TimeItem>, maxWrap: Float): Float {
        if (items.isEmpty()) return 72f
        val stackedItems = items.sumOf { (requiredItemHeight(it, maxWrap) + 6f).toDouble() }.toFloat() - 6f
        return max(72f, stackedItems + 20f)
    }

    private fun requiredItemHeight(it: TimeItem, maxWrap: Float): Float {
        val label = it.payload["event"] ?: (it.label as? RichLabel.Plain)?.text ?: it.id.value
        val labelHeight = textMeasurer.measure(label, itemFont, maxWidth = maxWrap).height
        val periodHeight = it.payload["period"]?.let { p ->
            textMeasurer.measure(p, periodFont, maxWidth = maxWrap).height
        } ?: 0f
        val captionGap = if (periodHeight > 0f) 8f else 0f
        return max(24f, labelHeight + captionGap + periodHeight + 14f)
    }

    private fun pin(prev: Map<NodeId, Rect>, id: NodeId, fresh: Rect, options: LayoutOptions): Rect {
        if (!options.incremental) return fresh
        return prev[id] ?: fresh
    }
}
