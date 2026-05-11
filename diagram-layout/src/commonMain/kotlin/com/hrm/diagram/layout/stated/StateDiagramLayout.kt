package com.hrm.diagram.layout.stated

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.ir.StateNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Grid-based layout for [StateIR] mirroring the structure of `ClassDiagramLayout`:
 *
 *  - Each top-level state node gets a fixed cell on a grid sized to fit the largest box.
 *  - Composite states grow to enclose their children, which are themselves laid out
 *    recursively in a sub-grid inside the parent rect.
 *  - Edge routes are cubic bezier curves whose control points are pushed along the
 *    outward normal of each endpoint (so the curve always leaves perpendicular).
 *  - Notes are placed relative to their target state and then nudged out of overlap
 *    via a global multi-pass overlap resolver.
 */
class StateDiagramLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<StateIR> {
    private data class StateLayoutFonts(
        val labelFont: FontSpec,
        val nodeFont: FontSpec,
        val noteFont: FontSpec,
    )

    private companion object {
        const val REGION_PREFIX = "__plantuml_state_region__#"
        const val STYLE_STATE_FONT_SIZE_KEY = "plantuml.state.style.state.fontSize"
        const val STYLE_STATE_FONT_NAME_KEY = "plantuml.state.style.state.fontName"
        const val STYLE_NOTE_FONT_SIZE_KEY = "plantuml.state.style.note.fontSize"
        const val STYLE_NOTE_FONT_NAME_KEY = "plantuml.state.style.note.fontName"
    }

    override fun layout(previous: LaidOutDiagram?, model: StateIR, options: LayoutOptions): LaidOutDiagram {
        return computeLayout(model, options)
    }

    private fun computeLayout(ir: StateIR, options: LayoutOptions): LaidOutDiagram {
        if (ir.states.isEmpty()) {
            return LaidOutDiagram(
                source = ir,
                nodePositions = emptyMap(),
                edgeRoutes = emptyList(),
                clusterRects = emptyMap(),
                bounds = Rect.ltrb(0f, 0f, 0f, 0f),
            )
        }
        val fonts = resolveFonts(ir)

        // Build parent map (child -> parent) by inverting children lists.
        val parentOf = HashMap<NodeId, NodeId>()
        for (s in ir.states) for (c in s.children) parentOf[c] = s.id
        val byId = ir.states.associateBy { it.id }
        val topLevel = ir.states.filter { it.id !in parentOf }

        val direction = ir.styleHints.direction ?: options.direction ?: Direction.TB
        val isHorizontal = direction == Direction.LR || direction == Direction.RL

        val sizes = HashMap<NodeId, Pair<Float, Float>>()
        val childPositionsRel = HashMap<NodeId, Map<NodeId, Rect>>()
        for (s in topLevel) measureRec(s, byId, sizes, childPositionsRel, isHorizontal, fonts)

        val nodePositions = LinkedHashMap<NodeId, Rect>()
        val maxW = topLevel.maxOf { sizes.getValue(it.id).first }
        val maxH = topLevel.maxOf { sizes.getValue(it.id).second }
        val n = topLevel.size
        val cols: Int; val rows: Int
        if (isHorizontal) {
            rows = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
            cols = ceil(n.toFloat() / rows).toInt().coerceAtLeast(1)
        } else {
            cols = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
            rows = ceil(n.toFloat() / cols).toInt().coerceAtLeast(1)
        }

        var maxLabelW = 0f
        for (t in ir.transitions) {
            val txt = (t.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text.orEmpty()
            if (txt.isNotEmpty()) {
                val mw = textMeasurer.measure(txt, fonts.labelFont).width
                if (mw > maxLabelW) maxLabelW = mw
            }
        }
        val gap = (40f + maxLabelW * 1.2f).coerceAtMost(220f)
        val marginX = 20f; val marginY = 20f

        for ((i, s) in topLevel.withIndex()) {
            val (col, row) = if (isHorizontal) (i / rows) to (i % rows) else (i % cols) to (i / cols)
            val (w, h) = sizes.getValue(s.id)
            val cellLeft = marginX + col * (maxW + gap)
            val cellTop = marginY + row * (maxH + gap)
            val left = cellLeft + (maxW - w) / 2f
            val top = cellTop + (maxH - h) / 2f
            placeRec(s, byId, sizes, childPositionsRel, nodePositions, Point(left, top))
        }

        if (direction == Direction.BT || direction == Direction.RL) {
            val maxX = nodePositions.values.maxOf { it.right }
            val maxY = nodePositions.values.maxOf { it.bottom }
            for ((id, r) in nodePositions.entries.toList()) {
                nodePositions[id] = if (direction == Direction.RL) {
                    Rect.ltrb(maxX - r.right, r.top, maxX - r.left, r.bottom)
                } else {
                    Rect.ltrb(r.left, maxY - r.bottom, r.right, maxY - r.top)
                }
            }
        }

        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (s in ir.states) {
            if (s.kind == StateKind.Composite) {
                nodePositions[s.id]?.let { clusterRects[NodeId("composite#${s.id.value}")] = it }
            }
        }

        val noteGap = 16f
        var standaloneY = marginY
        val noteDirs = LinkedHashMap<NodeId, Pair<Float, Float>>()
        for ((noteIdx, note) in ir.notes.withIndex()) {
            val noteText = (note.text as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text.orEmpty()
            val m = textMeasurer.measure(noteText, fonts.noteFont, maxWidth = 180f)
            val w = (m.width + 16f).coerceAtLeast(60f)
            val h = (m.height + 12f).coerceAtLeast(28f)
            val target = note.targetState?.let { nodePositions[it] }
            val rect = if (target != null) {
                when (note.placement) {
                    com.hrm.diagram.core.ir.NotePlacement.LeftOf ->
                        Rect.ltrb(target.left - noteGap - w, target.top, target.left - noteGap, target.top + h)
                    com.hrm.diagram.core.ir.NotePlacement.TopOf -> {
                        val cx = (target.left + target.right) / 2f
                        Rect.ltrb(cx - w / 2f, target.top - noteGap - h, cx + w / 2f, target.top - noteGap)
                    }
                    com.hrm.diagram.core.ir.NotePlacement.BottomOf -> {
                        val cx = (target.left + target.right) / 2f
                        Rect.ltrb(cx - w / 2f, target.bottom + noteGap, cx + w / 2f, target.bottom + noteGap + h)
                    }
                    com.hrm.diagram.core.ir.NotePlacement.RightOf,
                    com.hrm.diagram.core.ir.NotePlacement.Standalone ->
                        Rect.ltrb(target.right + noteGap, target.top, target.right + noteGap + w, target.top + h)
                }
            } else {
                val rightCol = nodePositions.values.maxOfOrNull { it.right } ?: marginX
                val r = Rect.ltrb(rightCol + 24f, standaloneY, rightCol + 24f + w, standaloneY + h)
                standaloneY += h + 12f
                r
            }
            val noteId = NodeId("note#$noteIdx")
            val dir = if (target != null) {
                when (note.placement) {
                    com.hrm.diagram.core.ir.NotePlacement.LeftOf -> -1f to 0f
                    com.hrm.diagram.core.ir.NotePlacement.RightOf,
                    com.hrm.diagram.core.ir.NotePlacement.Standalone -> 1f to 0f
                    com.hrm.diagram.core.ir.NotePlacement.TopOf -> 0f to -1f
                    com.hrm.diagram.core.ir.NotePlacement.BottomOf -> 0f to 1f
                }
            } else {
                0f to 1f
            }
            noteDirs[noteId] = dir
            clusterRects[noteId] = rect
        }

        val edgeRoutes = ArrayList<EdgeRoute>(ir.transitions.size)
        for (t in ir.transitions) {
            val a = nodePositions[t.from] ?: continue
            val b = nodePositions[t.to] ?: continue
            val from = clipPoint(a, centerOf(b))
            val to = clipPoint(b, centerOf(a))
            val dx = to.x - from.x; val dy = to.y - from.y
            val dist = sqrt(dx * dx + dy * dy)
            val offset = (dist * 0.4f).coerceIn(20f, 90f)
            val n1 = outwardNormal(a, from)
            val n2 = outwardNormal(b, to)
            val c1 = Point(from.x + n1.x * offset, from.y + n1.y * offset)
            val c2 = Point(to.x + n2.x * offset, to.y + n2.y * offset)
            edgeRoutes += EdgeRoute(
                from = t.from, to = t.to,
                points = listOf(from, c1, c2, to),
                kind = RouteKind.Bezier,
            )
        }

        // Translation 1.
        val all = nodePositions.values + clusterRects.values
        val minLeft = all.minOf { it.left }
        val minTop = all.minOf { it.top }
        val dx1 = if (minLeft < marginX) marginX - minLeft else 0f
        val dy1 = if (minTop < marginY) marginY - minTop else 0f
        if (dx1 != 0f || dy1 != 0f) {
            val shift: (Rect) -> Rect = { r -> Rect.ltrb(r.left + dx1, r.top + dy1, r.right + dx1, r.bottom + dy1) }
            for ((k, v) in nodePositions.toMap()) nodePositions[k] = shift(v)
            for ((k, v) in clusterRects.toMap()) clusterRects[k] = shift(v)
            for (i in edgeRoutes.indices) {
                val e = edgeRoutes[i]
                edgeRoutes[i] = e.copy(points = e.points.map { Point(it.x + dx1, it.y + dy1) })
            }
        }

        // Overlap resolver. Notes only nudge against TOP-LEVEL node rects to avoid pushing
        // them away from composite children which sit inside a parent rect.
        run {
            val noteIds = noteDirs.keys.toList()
            val nodeRectList = topLevel.mapNotNull { nodePositions[it.id] }
            val labelRects = ArrayList<Rect>()
            for (t in ir.transitions) {
                val a = nodePositions[t.from] ?: continue
                val b = nodePositions[t.to] ?: continue
                val mid = Point((a.left + a.right + b.left + b.right) / 4f, (a.top + a.bottom + b.top + b.bottom) / 4f)
                val labelText = (t.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text.orEmpty()
                if (labelText.isNotEmpty()) {
                    val mm = textMeasurer.measure(labelText, fonts.labelFont)
                    labelRects += Rect.ltrb(
                        mid.x - mm.width / 2f - 4f, mid.y - mm.height / 2f - 2f,
                        mid.x + mm.width / 2f + 4f, mid.y + mm.height / 2f + 2f,
                    )
                }
            }
            var iter = 0
            val maxIter = 600
            while (iter++ < maxIter) {
                var anyMoved = false
                for (id in noteIds) {
                    val r = clusterRects[id] ?: continue
                    val (dirX, dirY) = noteDirs[id] ?: continue
                    val collides = rectOverlapsAnyRect(r, nodeRectList) ||
                        rectOverlapsAnyRect(r, labelRects) ||
                        noteIds.any { other -> other != id && clusterRects[other]?.let { rectsOverlap(r, it) } == true }
                    if (collides) {
                        val step = 8f
                        clusterRects[id] = Rect.ltrb(
                            r.left + dirX * step, r.top + dirY * step,
                            r.right + dirX * step, r.bottom + dirY * step,
                        )
                        anyMoved = true
                    }
                }
                if (!anyMoved) break
            }
        }

        // Translation 2.
        val minLeft2 = (nodePositions.values + clusterRects.values).minOf { it.left }
        val minTop2 = (nodePositions.values + clusterRects.values).minOf { it.top }
        val dx2 = if (minLeft2 < marginX) marginX - minLeft2 else 0f
        val dy2 = if (minTop2 < marginY) marginY - minTop2 else 0f
        if (dx2 != 0f || dy2 != 0f) {
            val shift2: (Rect) -> Rect = { r -> Rect.ltrb(r.left + dx2, r.top + dy2, r.right + dx2, r.bottom + dy2) }
            for ((k, v) in nodePositions.toMap()) nodePositions[k] = shift2(v)
            for ((k, v) in clusterRects.toMap()) clusterRects[k] = shift2(v)
            for (i in edgeRoutes.indices) {
                val e = edgeRoutes[i]
                edgeRoutes[i] = e.copy(points = e.points.map { Point(it.x + dx2, it.y + dy2) })
            }
        }

        val maxRight = (nodePositions.values + clusterRects.values).maxOf { it.right }
        val maxBottom = (nodePositions.values + clusterRects.values).maxOf { it.bottom }
        val bounds = Rect.ltrb(0f, 0f, maxRight + marginX, maxBottom + marginY)

        return LaidOutDiagram(
            source = ir,
            nodePositions = nodePositions,
            edgeRoutes = edgeRoutes,
            clusterRects = clusterRects,
            bounds = bounds,
        )
    }

    private fun measureRec(
        s: StateNode,
        byId: Map<NodeId, StateNode>,
        sizes: HashMap<NodeId, Pair<Float, Float>>,
        childRel: HashMap<NodeId, Map<NodeId, Rect>>,
        isHorizontal: Boolean,
        fonts: StateLayoutFonts,
    ) {
        if (s.kind == StateKind.Composite && s.children.isNotEmpty()) {
            for (cid in s.children) byId[cid]?.let { measureRec(it, byId, sizes, childRel, isHorizontal, fonts) }
            val childMaxW = s.children.mapNotNull { sizes[it]?.first }.maxOrNull() ?: 0f
            val childMaxH = s.children.mapNotNull { sizes[it]?.second }.maxOrNull() ?: 0f
            val nC = s.children.size
            val regionChildrenOnly = s.children.all { isRegionId(it) }
            val cols: Int; val rows: Int
            if (regionChildrenOnly) {
                cols = 1
                rows = nC.coerceAtLeast(1)
            } else if (isHorizontal) {
                rows = ceil(sqrt(nC.toFloat())).toInt().coerceAtLeast(1)
                cols = ceil(nC.toFloat() / rows).toInt().coerceAtLeast(1)
            } else {
                cols = ceil(sqrt(nC.toFloat())).toInt().coerceAtLeast(1)
                rows = ceil(nC.toFloat() / cols).toInt().coerceAtLeast(1)
            }
            val innerGap = if (regionChildrenOnly) 18f else 24f
            val titleH = if (isRegionId(s.id)) {
                0f
            } else {
                textMeasurer.measure(displayName(s), fonts.nodeFont).height + 8f
            }
            val padInside = 12f
            val innerW = cols * childMaxW + (cols - 1).coerceAtLeast(0) * innerGap + 2 * padInside
            val innerH = rows * childMaxH + (rows - 1).coerceAtLeast(0) * innerGap + 2 * padInside
            val totalW = innerW.coerceAtLeast(120f)
            val totalH = (titleH + innerH).coerceAtLeast(80f)
            sizes[s.id] = totalW to totalH

            val rel = LinkedHashMap<NodeId, Rect>()
            for ((i, cid) in s.children.withIndex()) {
                val (col, row) = if (isHorizontal) (i / rows) to (i % rows) else (i % cols) to (i / cols)
                val (w, h) = sizes[cid] ?: (40f to 40f)
                val cellLeft = padInside + col * (childMaxW + innerGap)
                val cellTop = titleH + padInside + row * (childMaxH + innerGap)
                val left = cellLeft + (childMaxW - w) / 2f
                val top = cellTop + (childMaxH - h) / 2f
                rel[cid] = Rect.ltrb(left, top, left + w, top + h)
            }
            childRel[s.id] = rel
        } else {
            sizes[s.id] = measureNode(s, fonts)
        }
    }

    private fun placeRec(
        s: StateNode,
        byId: Map<NodeId, StateNode>,
        sizes: HashMap<NodeId, Pair<Float, Float>>,
        childRel: HashMap<NodeId, Map<NodeId, Rect>>,
        nodePositions: LinkedHashMap<NodeId, Rect>,
        topLeft: Point,
    ) {
        val (w, h) = sizes.getValue(s.id)
        nodePositions[s.id] = Rect.ltrb(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h)
        if (s.kind == StateKind.Composite && s.children.isNotEmpty()) {
            val rel = childRel[s.id] ?: return
            for (cid in s.children) {
                val r = rel[cid] ?: continue
                val child = byId[cid] ?: continue
                placeRec(child, byId, sizes, childRel, nodePositions, Point(topLeft.x + r.left, topLeft.y + r.top))
            }
        }
    }

    private fun measureNode(s: StateNode, fonts: StateLayoutFonts): Pair<Float, Float> = when (s.kind) {
        StateKind.Initial -> 16f to 16f
        StateKind.Final -> 22f to 22f
        StateKind.Choice -> 28f to 28f
        StateKind.Fork, StateKind.Join -> 70f to 12f
        StateKind.History, StateKind.DeepHistory -> 26f to 26f
        StateKind.Composite, StateKind.Simple -> {
            val name = displayName(s)
            val m = textMeasurer.measure(name, fonts.nodeFont)
            val w = (m.width + 24f).coerceAtLeast(60f)
            val h = (m.height + 16f).coerceAtLeast(36f)
            w to h
        }
    }

    private fun resolveFonts(ir: StateIR): StateLayoutFonts {
        val extras = ir.styleHints.extras
        val nodeFont = resolveFont(
            base = FontSpec(family = "sans-serif", sizeSp = 12f),
            familyRaw = extras[STYLE_STATE_FONT_NAME_KEY],
            sizeRaw = extras[STYLE_STATE_FONT_SIZE_KEY],
        )
        val noteFont = resolveFont(
            base = nodeFont,
            familyRaw = extras[STYLE_NOTE_FONT_NAME_KEY],
            sizeRaw = extras[STYLE_NOTE_FONT_SIZE_KEY],
        )
        val labelFont = nodeFont.copy(sizeSp = (nodeFont.sizeSp - 1f).coerceAtLeast(10f))
        return StateLayoutFonts(labelFont = labelFont, nodeFont = nodeFont, noteFont = noteFont)
    }

    private fun resolveFont(base: FontSpec, familyRaw: String?, sizeRaw: String?): FontSpec =
        base.copy(
            family = parseFontFamily(familyRaw) ?: base.family,
            sizeSp = parseFontSize(sizeRaw) ?: base.sizeSp,
        )

    private fun parseFontFamily(raw: String?): String? =
        raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun parseFontSize(raw: String?): Float? =
        raw?.trim()?.toFloatOrNull()?.takeIf { it > 0f }

    private fun displayName(s: StateNode): String = s.description ?: s.name

    private fun isRegionId(id: NodeId): Boolean = id.value.startsWith(REGION_PREFIX)

    private fun centerOf(r: Rect): Point = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)

    private fun clipPoint(box: Rect, target: Point): Point {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val dx = target.x - cx; val dy = target.y - cy
        if (dx == 0f && dy == 0f) return Point(cx, cy)
        val hw = (box.right - box.left) / 2f
        val hh = (box.bottom - box.top) / 2f
        val absDx = if (dx < 0f) -dx else dx
        val absDy = if (dy < 0f) -dy else dy
        val sx = if (absDx > 0f) hw / absDx else Float.POSITIVE_INFINITY
        val sy = if (absDy > 0f) hh / absDy else Float.POSITIVE_INFINITY
        val s = if (sx < sy) sx else sy
        return Point(cx + dx * s, cy + dy * s)
    }

    private fun outwardNormal(box: Rect, p: Point): Point {
        val dLeft = kotlin.math.abs(p.x - box.left)
        val dRight = kotlin.math.abs(p.x - box.right)
        val dTop = kotlin.math.abs(p.y - box.top)
        val dBottom = kotlin.math.abs(p.y - box.bottom)
        val m = minOf(dLeft, dRight, dTop, dBottom)
        return when (m) {
            dLeft -> Point(-1f, 0f)
            dRight -> Point(1f, 0f)
            dTop -> Point(0f, -1f)
            else -> Point(0f, 1f)
        }
    }

    private fun rectsOverlap(a: Rect, b: Rect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun rectOverlapsAnyRect(r: Rect, rects: List<Rect>): Boolean {
        for (other in rects) if (rectsOverlap(r, other)) return true
        return false
    }
}
