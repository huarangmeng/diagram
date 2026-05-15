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
    private val axisFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun layout(previous: LaidOutDiagram?, model: TimeSeriesIR, options: LayoutOptions): LaidOutDiagram {
        val prevPos = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()

        val pad = 18f
        val leftLabelW = computeLeftLabelWidth(model)
        val rowH = 26f
        val rowGap = 8f
        val chartPad = 10f
        val topAxisH = 42f
        val axisToRowsGap = 10f
        val compactMode = model.styleHints.extras["gantt.displayMode"]?.equals("compact", ignoreCase = true) == true
        val vertItems = model.items.filter { it.payload["gantt.kind"] == "vert" }
        val topMarkerLaneH = if (vertItems.isEmpty()) {
            0f
        } else {
            vertItems.maxOf { item ->
                val labelText = (item.label as? RichLabel.Plain)?.text ?: item.id.value
                textMeasurer.measure(labelText, itemFont, maxWidth = 120f).height
            } + 14f
        }

        var y = pad
        model.title?.takeIf { it.isNotBlank() }?.let { t ->
            val m = textMeasurer.measure(t, titleFont, maxWidth = 600f)
            val r = Rect(Point(pad, y), Size(m.width, m.height))
            nodePositions[NodeId("gantt:title")] = pin(prevPos, NodeId("gantt:title"), r, options)
            y += m.height + 10f
        }

        val topMarkerLaneTop = y
        val axisTop = y + topMarkerLaneH + topAxisH
        val axisLeft = pad + leftLabelW + chartPad
        val axisWidth = 560f
        var axisBottom = axisTop
        y = axisTop + axisToRowsGap

        val range = model.range
        val span = (range.endMs - range.startMs).coerceAtLeast(1L).toDouble()
        fun xOf(ms: Long): Float {
            val t = ((ms - range.startMs).toDouble() / span).coerceIn(0.0, 1.0)
            return (axisLeft + (axisWidth * t)).toFloat()
        }

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
        layoutAxisScaleLabels(model, axisRect, nodePositions, prevPos, options)
        for (item in vertItems) {
            val centerX = xOf((item.range.startMs + item.range.endMs) / 2L)
            val markerId = NodeId("gantt:vert:${item.id.value}")
            val labelId = NodeId("gantt:vertLabel:${item.id.value}")
            val markerRect = Rect.ltrb(centerX - 0.5f, axisRect.top, centerX + 0.5f, axisRect.bottom)
            nodePositions[markerId] = pin(prevPos, markerId, markerRect, options)
            val labelText = (item.label as? RichLabel.Plain)?.text ?: item.id.value
            val metrics = textMeasurer.measure(labelText, itemFont, maxWidth = 120f)
            val preferredLeft = if (centerX + metrics.width + 8f <= axisRect.right) {
                centerX + 8f
            } else {
                centerX - metrics.width - 8f
            }
            val labelLeft = preferredLeft.coerceIn(axisRect.left + 4f, axisRect.right - metrics.width - 4f)
            val labelRect = Rect(
                Point(labelLeft, topMarkerLaneTop + (topMarkerLaneH - metrics.height) / 2f),
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

    private fun computeLeftLabelWidth(model: TimeSeriesIR): Float {
        val minWidth = 160f
        val maxWidth = 320f
        val probeWidth = maxWidth - 20f
        val trackMax = model.tracks.maxOfOrNull { track ->
            val text = (track.label as? RichLabel.Plain)?.text ?: track.id.value
            textMeasurer.measure(text, trackFont, maxWidth = probeWidth).width
        } ?: 0f
        val itemMax = model.items
            .asSequence()
            .filter { it.payload["gantt.kind"] != "vert" }
            .map { item -> (item.label as? RichLabel.Plain)?.text ?: item.id.value }
            .maxOfOrNull { label -> textMeasurer.measure(label, itemFont, maxWidth = probeWidth).width } ?: 0f
        val desired = max(trackMax, itemMax) + 16f
        return desired.coerceIn(minWidth, maxWidth)
    }

    private fun layoutAxisScaleLabels(
        model: TimeSeriesIR,
        axisRect: Rect,
        nodePositions: MutableMap<NodeId, Rect>,
        prevPos: Map<NodeId, Rect>,
        options: LayoutOptions,
    ) {
        val axisFormat = model.styleHints.extras["gantt.axisFormat"] ?: "%Y-%m-%d"
        val tickInterval = GanttAxisSupport.parseTickInterval(
            model.styleHints.extras["gantt.tickInterval"],
            model.range.endMs - model.range.startMs,
        )
        val ticks = GanttAxisSupport.buildTicks(model.range.startMs, model.range.endMs, tickInterval)
        val span = (model.range.endMs - model.range.startMs).coerceAtLeast(1L).toDouble()
        fun xOf(ms: Long): Float {
            val t = ((ms - model.range.startMs).toDouble() / span).coerceIn(0.0, 1.0)
            return (axisRect.left + axisRect.size.width * t).toFloat()
        }
        layoutMajorScaleLabels(ticks, tickInterval, axisRect, ::xOf, nodePositions, prevPos, options)
        for (i in 0 until ticks.lastIndex) {
            val tick = ticks[i]
            val next = ticks[i + 1]
            val left = xOf(tick)
            val right = xOf(next)
            val cellWidth = right - left
            if (cellWidth < 14f) continue
            val label = GanttAxisSupport.formatMinorTick(tick, axisFormat, tickInterval)
            val metrics = textMeasurer.measure(label, axisFont)
            if (metrics.width > cellWidth - 4f) continue
            val rect = Rect(
                Point(left + (cellWidth - metrics.width) / 2f, axisRect.top - metrics.height - 4f),
                Size(metrics.width, metrics.height),
            )
            val id = NodeId("gantt:axisTickLabel:$tick")
            nodePositions[id] = pin(prevPos, id, rect, options)
        }
    }

    private fun layoutMajorScaleLabels(
        ticks: List<Long>,
        tickInterval: Long,
        axisRect: Rect,
        xOf: (Long) -> Float,
        nodePositions: MutableMap<NodeId, Rect>,
        prevPos: Map<NodeId, Rect>,
        options: LayoutOptions,
    ) {
        if (ticks.size < 2) return
        var groupStartIndex = 0
        var groupKey = GanttAxisSupport.majorTickKey(ticks.first(), tickInterval)
        for (i in 1 until ticks.size) {
            val key = if (i < ticks.lastIndex) GanttAxisSupport.majorTickKey(ticks[i], tickInterval) else null
            if (key == groupKey) continue
            val start = ticks[groupStartIndex]
            val end = ticks[i]
            val left = xOf(start)
            val right = xOf(end)
            val width = right - left
            if (width >= 32f) {
                val label = GanttAxisSupport.formatMajorTick(start, tickInterval)
                val metrics = textMeasurer.measure(label, axisFont)
                if (metrics.width <= width - 6f) {
                    val rect = Rect(
                        Point(left + (width - metrics.width) / 2f, axisRect.top - metrics.height * 2f - 10f),
                        Size(metrics.width, metrics.height),
                    )
                    val id = NodeId("gantt:axisMajorLabel:${groupKey}:$start")
                    nodePositions[id] = pin(prevPos, id, rect, options)
                }
            }
            groupStartIndex = i
            groupKey = key ?: groupKey
        }
    }
}
