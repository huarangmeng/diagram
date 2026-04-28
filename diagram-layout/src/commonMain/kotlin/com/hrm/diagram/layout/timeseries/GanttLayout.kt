package com.hrm.diagram.layout.timeseries

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
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
 * Deterministic layout for Mermaid gantt (represented as [TimeSeriesIR]).
 *
 * Layout outputs:
 * - `gantt:title`
 * - `gantt:track:<trackId>`
 * - `gantt:item:<itemId>` bar rects
 * - `gantt:axis` overall chart rect
 *
 * Incremental pinning:
 * - when [LayoutOptions.incremental] keep existing rects; only create rects for new ids.
 * - full reflow allowed at finish (caller sets allowGlobalReflow).
 */
class GanttLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<TimeSeriesIR> {

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val trackFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val itemFont = FontSpec(family = "sans-serif", sizeSp = 12f)

    override fun layout(previous: LaidOutDiagram?, model: TimeSeriesIR, options: LayoutOptions): LaidOutDiagram {
        val prevPos = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()

        val pad = 18f
        val leftLabelW = 160f
        val rowH = 26f
        val rowGap = 8f
        val chartPad = 10f
        val topAxisH = 22f
        val compactMode = model.styleHints.extras["gantt.displayMode"]?.equals("compact", ignoreCase = true) == true

        var y = pad
        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont, maxWidth = 600f)
            val r = Rect(Point(pad, y), Size(m.width, m.height))
            nodePositions[NodeId("gantt:title")] = pin(prevPos, NodeId("gantt:title"), r, options)
            y += m.height + 10f
        }

        val axisTop = y + topAxisH
        val axisLeft = pad + leftLabelW + chartPad
        val axisWidth = 560f
        var axisBottom = axisTop

        val range = model.range
        val span = (range.endMs - range.startMs).coerceAtLeast(1L).toDouble()
        fun xOf(ms: Long): Float {
            val t = ((ms - range.startMs).toDouble() / span).coerceIn(0.0, 1.0)
            return (axisLeft + (axisWidth * t)).toFloat()
        }

        val vertItems = model.items.filter { it.payload["gantt.kind"] == "vert" }

        for (track in model.tracks) {
            val trackId = NodeId("gantt:track:${track.id.value}")
            val trackName = (track.label as? RichLabel.Plain)?.text ?: track.id.value
            val tm = textMeasurer.measure(trackName, trackFont, maxWidth = leftLabelW - 10f)
            val tr = Rect(Point(pad, y), Size(leftLabelW - 10f, tm.height))
            nodePositions[trackId] = pin(prevPos, trackId, tr, options)

            y += max(tm.height, rowH) + 6f
            val items = model.items
                .filter { it.trackId == track.id }
                .filter { it.payload["gantt.kind"] != "vert" }
                .sortedWith(compareBy<TimeItem> { it.range.startMs }.thenBy { it.range.endMs }.thenBy { it.id.value })
            val laneEnds = ArrayList<Long>()
            for (it in items) {
                val lane = if (!compactMode) {
                    laneEnds += it.range.endMs
                    laneEnds.lastIndex
                } else {
                    var laneIdx = laneEnds.indexOfFirst { laneEnd -> it.range.startMs >= laneEnd }
                    if (laneIdx < 0) {
                        laneEnds += it.range.endMs
                        laneIdx = laneEnds.lastIndex
                    } else {
                        laneEnds[laneIdx] = it.range.endMs
                    }
                    laneIdx
                }
                val barTop = y + lane * (rowH + rowGap)
                val barH = rowH
                val x0 = xOf(it.range.startMs)
                val x1 = xOf(it.range.endMs)
                val bar = Rect.ltrb(x0, barTop, max(x1, x0 + 1f), barTop + barH)
                val id = NodeId("gantt:item:${it.id.value}")
                nodePositions[id] = pin(prevPos, id, bar, options)

                // Label rect for task name (left side).
                val labelId = NodeId("gantt:itemLabel:${it.id.value}")
                val labelText = (it.label as? RichLabel.Plain)?.text ?: it.id.value
                val lm = textMeasurer.measure(labelText, itemFont, maxWidth = leftLabelW - 10f)
                val lr = Rect(Point(pad, barTop + (barH - lm.height) / 2f), Size(leftLabelW - 10f, lm.height))
                nodePositions[labelId] = pin(prevPos, labelId, lr, options)
            }
            val laneCount = laneEnds.size.coerceAtLeast(1)
            y += laneCount * rowH + (laneCount - 1) * rowGap
            axisBottom = max(axisBottom, y)
            y += 6f
        }

        val axisRect = Rect.ltrb(axisLeft, axisTop, axisLeft + axisWidth, axisBottom)
        nodePositions[NodeId("gantt:axis")] = pin(prevPos, NodeId("gantt:axis"), axisRect, options)
        for (item in vertItems) {
            val centerX = xOf((item.range.startMs + item.range.endMs) / 2L)
            val markerId = NodeId("gantt:vert:${item.id.value}")
            val labelId = NodeId("gantt:vertLabel:${item.id.value}")
            val markerRect = Rect.ltrb(centerX - 0.5f, axisRect.top, centerX + 0.5f, axisRect.bottom)
            nodePositions[markerId] = pin(prevPos, markerId, markerRect, options)
            val labelText = (item.label as? RichLabel.Plain)?.text ?: item.id.value
            val metrics = textMeasurer.measure(labelText, itemFont, maxWidth = 120f)
            val labelRect = Rect(
                Point((centerX - metrics.width / 2f).coerceAtLeast(axisLeft), axisTop - metrics.height - 8f),
                Size(metrics.width, metrics.height),
            )
            nodePositions[labelId] = pin(prevPos, labelId, labelRect, options)
        }

        val maxRight = nodePositions.values.maxOfOrNull { it.right } ?: (axisLeft + axisWidth)
        val maxBottom = nodePositions.values.maxOfOrNull { it.bottom } ?: axisBottom
        val bounds = Rect.ltrb(0f, 0f, maxRight + pad, maxBottom + pad)

        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = bounds,
        )
    }

    private fun pin(prev: Map<NodeId, Rect>, id: NodeId, fresh: Rect, options: LayoutOptions): Rect {
        if (!options.incremental) return fresh
        return prev[id] ?: fresh
    }
}
