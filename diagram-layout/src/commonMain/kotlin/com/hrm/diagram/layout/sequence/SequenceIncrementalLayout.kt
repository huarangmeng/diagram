package com.hrm.diagram.layout.sequence

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind

/**
 * Incremental layout for [SequenceIR].
 *
 * Layout strategy (top-down):
 *  1. Measure each participant label, choose lane widths.
 *  2. Place a participant header rect at the top of each lane.
 *  3. Iterate [SequenceIR.messages] in order; assign each message a horizontal "row" with a
 *     measured height. Lifeline arrows cross between lane centres.
 *  4. Track activation stacks: `+`/`activate` push a `(participant, startY)`; `-`/`deactivate`
 *     pops and emits an activation rect into [LaidOutDiagram.clusterRects].
 *  5. Fragments are emitted as cluster rects spanning the messages they enclose.
 *
 * The layout reuses the previous [LaidOutDiagram] when called incrementally — each message's
 * Y coordinate depends only on prior messages, so an append-only message list maps to an
 * append-only row list (older rows never move).
 */
class SequenceIncrementalLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<SequenceIR> {
    private data class ScopeStyle(
        val fontSize: Float?,
        val fontName: String?,
        val lineThickness: Float?,
        val shadowing: Boolean?,
    )

    private data class SequenceLayoutStyle(
        val sequence: ScopeStyle,
        val participant: ScopeStyle,
        val actor: ScopeStyle,
        val boundary: ScopeStyle,
        val control: ScopeStyle,
        val entity: ScopeStyle,
        val database: ScopeStyle,
        val collections: ScopeStyle,
        val queue: ScopeStyle,
        val note: ScopeStyle,
        val box: ScopeStyle,
    )

    private data class SequenceLayoutFonts(
        val messageFont: FontSpec,
        val noteFont: FontSpec,
        val boxFont: FontSpec,
    )

    override fun layout(previous: LaidOutDiagram?, model: SequenceIR, options: LayoutOptions): LaidOutDiagram {
        return computeLayout(model)
    }

    private fun computeLayout(ir: SequenceIR): LaidOutDiagram {
        val participants = ir.participants
        if (participants.isEmpty()) {
            return LaidOutDiagram(
                source = ir,
                nodePositions = emptyMap(),
                edgeRoutes = emptyList(),
                clusterRects = emptyMap(),
                bounds = Rect.ltrb(0f, 0f, 0f, 0f),
            )
        }

        val style = resolveStyle(ir)
        val fonts = resolveFonts(style)

        val laneWidth = HashMap<NodeId, Float>(participants.size)
        var headerHeight = HEADER_H
        for (p in participants) {
            val text = labelOrId(p.label, p.id)
            val participantFont = participantFontFor(p.kind.name.lowercase(), style)
            val m = textMeasurer.measure(text, participantFont)
            val w = (m.width + 2 * LANE_PAD).coerceAtLeast(LANE_W_MIN)
            laneWidth[p.id] = w
            headerHeight = headerHeight.coerceAtLeast(m.height + 18f)
        }

        val laneCenter = HashMap<NodeId, Float>(participants.size)
        val laneLeft = HashMap<NodeId, Float>(participants.size)
        val laneRight = HashMap<NodeId, Float>(participants.size)
        var x = MARGIN_X
        for (p in participants) {
            val w = laneWidth.getValue(p.id)
            laneLeft[p.id] = x
            laneCenter[p.id] = x + w / 2f
            laneRight[p.id] = x + w
            x += w
        }
        val rightEdge = x + MARGIN_X

        val boxTitleBand = measureBoxTitleBand(ir, fonts.boxFont)
        val headerTop = START_Y + boxTitleBand
        val headerBottom = headerTop + headerHeight
        val nodePositions = LinkedHashMap<NodeId, Rect>()
        for (p in participants) {
            nodePositions[p.id] = Rect.ltrb(
                laneLeft.getValue(p.id),
                headerTop,
                laneRight.getValue(p.id),
                headerBottom,
            )
        }

        val edgeRoutes = ArrayList<EdgeRoute>()
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        val activeStacks = HashMap<NodeId, ArrayDeque<Float>>()
        val messageRowY = ArrayList<Float>(ir.messages.size)
        var currentY = headerBottom + ROW_GAP
        var noteIdx = 0
        var activationIdx = 0

        for (msg in ir.messages) {
            val labelText = (msg.label as? RichLabel.Plain)?.text ?: ""
            val measured = textMeasurer.measure(labelText, if (msg.kind == MessageKind.Note) fonts.noteFont else fonts.messageFont)
            val rowH = (measured.height + 16f).coerceAtLeast(ROW_H_MIN)
            val rowMid = currentY + rowH / 2f
            messageRowY += rowMid

            when (msg.kind) {
                MessageKind.Note -> {
                    if (msg.activate && !msg.deactivate) {
                        val stack = activeStacks.getOrPut(msg.from) { ArrayDeque() }
                        stack.addLast(rowMid)
                    } else if (msg.deactivate && !msg.activate) {
                        val stack = activeStacks.getOrPut(msg.from) { ArrayDeque() }
                        if (stack.isNotEmpty()) {
                            val startY = stack.removeLast()
                            val cx = laneCenter.getValue(msg.from)
                            val rect = Rect.ltrb(cx - ACT_HALF_W, startY, cx + ACT_HALF_W, rowMid)
                            clusterRects[NodeId("${msg.from.value}#act#${activationIdx++}")] = rect
                        }
                    } else {
                        val from = laneCenter.getValue(msg.from)
                        val to = laneCenter.getValue(msg.to)
                        val minX = minOf(from, to)
                        val maxX = maxOf(from, to)
                        val measuredWidth = textMeasurer.measure(labelText, fonts.noteFont, maxWidth = 220f).width + 24f
                        val spanWidth = (maxX - minX + 100f).coerceAtLeast(measuredWidth)
                        val center = (from + to) / 2f
                        val noteLeft = center - spanWidth / 2f
                        val noteRight = center + spanWidth / 2f
                        val rect = Rect.ltrb(noteLeft, currentY + 4f, noteRight, currentY + rowH - 4f)
                        clusterRects[NodeId("note#${noteIdx++}")] = rect
                    }
                }
                else -> {
                    val fromX = laneCenter.getValue(msg.from)
                    val toX = laneCenter.getValue(msg.to)
                    edgeRoutes += EdgeRoute(
                        from = msg.from,
                        to = msg.to,
                        points = listOf(Point(fromX, rowMid), Point(toX, rowMid)),
                        kind = RouteKind.Polyline,
                    )
                    if (msg.activate) {
                        val stack = activeStacks.getOrPut(msg.to) { ArrayDeque() }
                        stack.addLast(rowMid)
                    }
                    if (msg.deactivate) {
                        val stack = activeStacks.getOrPut(msg.from) { ArrayDeque() }
                        if (stack.isNotEmpty()) {
                            val startY = stack.removeLast()
                            val cx = laneCenter.getValue(msg.from)
                            val rect = Rect.ltrb(cx - ACT_HALF_W, startY, cx + ACT_HALF_W, rowMid)
                            clusterRects[NodeId("${msg.from.value}#act#${activationIdx++}")] = rect
                        }
                    }
                }
            }
            currentY += rowH
        }

        for ((pid, stack) in activeStacks) {
            while (stack.isNotEmpty()) {
                val startY = stack.removeLast()
                val cx = laneCenter.getValue(pid)
                val rect = Rect.ltrb(cx - ACT_HALF_W, startY, cx + ACT_HALF_W, currentY)
                clusterRects[NodeId("${pid.value}#act#${activationIdx++}")] = rect
            }
        }

        // Fragment cluster rects: span the Y range of messages they contain.
        val msgIndex = HashMap<SequenceMessage, Int>(ir.messages.size)
        for ((i, m) in ir.messages.withIndex()) msgIndex[m] = i
        var fragIdx = 0
        for (frag in ir.fragments) {
            val all = frag.branches.flatten()
            if (all.isEmpty()) { fragIdx++; continue }
            val indices = all.mapNotNull { msgIndex[it] }
            if (indices.isEmpty()) { fragIdx++; continue }
            val firstIdx = indices.min()
            val lastIdx = indices.max()
            val topY = messageRowY[firstIdx] - FRAG_PAD
            val botY = messageRowY[lastIdx] + FRAG_PAD
            val rect = Rect.ltrb(MARGIN_X / 2f, topY, rightEdge - MARGIN_X / 2f, botY)
            clusterRects[NodeId("frag#${fragIdx++}")] = rect
        }

        val bottom = currentY + MARGIN_Y
        val bounds = Rect.ltrb(0f, 0f, rightEdge, bottom)

        return LaidOutDiagram(
            source = ir,
            nodePositions = nodePositions,
            edgeRoutes = edgeRoutes,
            clusterRects = clusterRects,
            bounds = bounds,
        )
    }

    private fun labelOrId(label: RichLabel, id: NodeId): String =
        (label as? RichLabel.Plain)?.text?.takeIf { it.isNotEmpty() } ?: id.value

    private fun resolveStyle(ir: SequenceIR): SequenceLayoutStyle {
        val extras = ir.styleHints.extras
        fun scope(name: String): ScopeStyle = ScopeStyle(
            fontSize = parseFontSize(extras["plantuml.sequence.style.$name.fontSize"]),
            fontName = parseFontFamily(extras["plantuml.sequence.style.$name.fontName"]),
            lineThickness = parseFloat(extras["plantuml.sequence.style.$name.lineThickness"]),
            shadowing = parseBoolean(extras["plantuml.sequence.style.$name.shadowing"]),
        )
        return SequenceLayoutStyle(
            sequence = scope("sequence"),
            participant = scope("participant"),
            actor = scope("actor"),
            boundary = scope("boundary"),
            control = scope("control"),
            entity = scope("entity"),
            database = scope("database"),
            collections = scope("collections"),
            queue = scope("queue"),
            note = scope("note"),
            box = scope("box"),
        )
    }

    private fun resolveFonts(style: SequenceLayoutStyle): SequenceLayoutFonts {
        val messageFont = resolveFont(FontSpec(family = "sans-serif", sizeSp = 11f), style.sequence)
        val noteFont = resolveFont(messageFont, style.note)
        val boxBase = FontSpec(family = "sans-serif", sizeSp = 11f, weight = 600)
        val boxFont = resolveFont(boxBase, style.box)
        return SequenceLayoutFonts(
            messageFont = messageFont,
            noteFont = noteFont,
            boxFont = boxFont,
        )
    }

    private fun participantFontFor(kind: String, style: SequenceLayoutStyle): FontSpec {
        val base = resolveFont(FontSpec(family = "sans-serif", sizeSp = 13f), style.participant)
        val scope = when (kind) {
            "actor" -> style.actor
            "boundary" -> style.boundary
            "control" -> style.control
            "entity" -> style.entity
            "database" -> style.database
            "collections" -> style.collections
            "queue" -> style.queue
            else -> null
        }
        return scope?.let { resolveFont(base, it) } ?: base
    }

    private fun measureBoxTitleBand(ir: SequenceIR, boxFont: FontSpec): Float {
        val raw = ir.styleHints.extras["plantuml.sequence.boxes"].orEmpty()
        if (raw.isEmpty()) return 0f
        val titles = raw.split("||")
            .filter { it.isNotEmpty() }
            .map { it.split("|", limit = 3).firstOrNull().orEmpty().replace("\\|", "|") }
            .filter { it.isNotEmpty() }
        if (titles.isEmpty()) return 0f
        val maxHeight = titles.maxOf { textMeasurer.measure(it, boxFont).height }
        return maxHeight + 14f
    }

    private fun resolveFont(base: FontSpec, scope: ScopeStyle): FontSpec =
        base.copy(
            family = scope.fontName ?: base.family,
            sizeSp = scope.fontSize ?: base.sizeSp,
        )

    private fun parseFontFamily(raw: String?): String? = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
    private fun parseFontSize(raw: String?): Float? = raw?.trim()?.toFloatOrNull()?.takeIf { it > 0f }
    private fun parseFloat(raw: String?): Float? = raw?.trim()?.toFloatOrNull()?.takeIf { it > 0f }
    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }

    private companion object {
        const val LANE_PAD = 30f
        const val LANE_W_MIN = 120f
        const val MARGIN_X = 20f
        const val MARGIN_Y = 20f
        const val START_Y = 20f
        const val HEADER_H = 40f
        const val ROW_H_MIN = 36f
        const val ROW_GAP = 10f
        const val ACT_HALF_W = 6f
        const val FRAG_PAD = 12f
    }
}
