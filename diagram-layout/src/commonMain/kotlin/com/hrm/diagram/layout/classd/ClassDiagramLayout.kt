package com.hrm.diagram.layout.classd

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
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
 * Simple grid layout for [ClassIR].
 *
 * Each [ClassNode] is placed in a fixed-size cell on a grid. Rows / columns are determined by
 * the diagram direction hint (`TB`/`BT` → column-major flow, `LR`/`RL` → row-major flow).
 * Namespace clusters get a bounding rect computed from their member node positions. Notes are
 * placed to the right of their target class (or in a standalone column for free-floating notes).
 */
class ClassDiagramLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<ClassIR> {
    private data class ClassLayoutFonts(
        val headerFont: FontSpec,
        val memberFont: FontSpec,
        val labelFont: FontSpec,
        val noteFont: FontSpec,
        val packageFont: FontSpec,
    )

    override fun layout(previous: LaidOutDiagram?, model: ClassIR, options: LayoutOptions): LaidOutDiagram {
        return computeLayout(model, options)
    }

    private fun computeLayout(ir: ClassIR, options: LayoutOptions): LaidOutDiagram {
        if (ir.classes.isEmpty()) {
            return LaidOutDiagram(
                source = ir,
                nodePositions = emptyMap(),
                edgeRoutes = emptyList(),
                clusterRects = emptyMap(),
                bounds = Rect.ltrb(0f, 0f, 0f, 0f),
            )
        }

        val fonts = resolveFonts(ir)
        val headerFont = fonts.headerFont
        val memberFont = fonts.memberFont

        val sizes = HashMap<NodeId, Pair<Float, Float>>(ir.classes.size)
        for (c in ir.classes) {
            sizes[c.id] = measureClass(c, headerFont, memberFont)
        }
        val maxW = sizes.values.maxOf { it.first }
        val maxH = sizes.values.maxOf { it.second }

        val direction = ir.styleHints.direction ?: options.direction ?: Direction.TB
        val isHorizontal = direction == Direction.LR || direction == Direction.RL

        val n = ir.classes.size
        val cols: Int; val rows: Int
        if (isHorizontal) {
            rows = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
            cols = ceil(n.toFloat() / rows).toInt().coerceAtLeast(1)
        } else {
            cols = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
            rows = ceil(n.toFloat() / cols).toInt().coerceAtLeast(1)
        }

        // Reserve enough gap between cells for edge labels and cardinality strings to fit
        // without colliding with neighboring class boxes.
        val labelFont = fonts.labelFont
        var maxLabelW = 0f
        for (rel in ir.relations) {
            val labelText = when (val l = rel.label) {
                is com.hrm.diagram.core.ir.RichLabel.Plain -> l.text
                else -> ""
            }
            val parts = listOfNotNull(rel.fromCardinality, labelText.takeIf { it.isNotEmpty() }, rel.toCardinality)
            for (p in parts) {
                val mw = textMeasurer.measure(p, labelFont).width
                if (mw > maxLabelW) maxLabelW = mw
            }
        }
        val gap = (40f + maxLabelW * 1.2f).coerceAtMost(220f)
        val marginX = 20f; val marginY = 20f

        val nodePositions = LinkedHashMap<NodeId, Rect>(n)
        for ((i, c) in ir.classes.withIndex()) {
            val (col, row) = if (isHorizontal) {
                (i / rows) to (i % rows)
            } else {
                (i % cols) to (i / cols)
            }
            val (w, h) = sizes.getValue(c.id)
            // Anchor each cell to its top-left corner, then center the actual rect inside.
            val cellLeft = marginX + col * (maxW + gap)
            val cellTop = marginY + row * (maxH + gap)
            val left = cellLeft + (maxW - w) / 2f
            val top = cellTop + (maxH - h) / 2f
            nodePositions[c.id] = Rect.ltrb(left, top, left + w, top + h)
        }

        // Mirror across the appropriate axis for BT (vertical-reversed) and RL (horizontal-reversed)
        // so the gallery sample with `direction BT` actually renders bottom-up.
        if (direction == Direction.BT || direction == Direction.RL) {
            val maxX = nodePositions.values.maxOfOrNull { it.right } ?: 0f
            val maxY = nodePositions.values.maxOfOrNull { it.bottom } ?: 0f
            for ((id, r) in nodePositions.entries.toList()) {
                nodePositions[id] = if (direction == Direction.RL) {
                    Rect.ltrb(maxX - r.right, r.top, maxX - r.left, r.bottom)
                } else {
                    Rect.ltrb(r.left, maxY - r.bottom, r.right, maxY - r.top)
                }
            }
        }

        // Cluster rects for namespaces.
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (ns in ir.namespaces) {
            val rects = ns.members.mapNotNull { nodePositions[it] }
            if (rects.isEmpty()) continue
            val titleMetrics = textMeasurer.measure(ns.id, fonts.packageFont, maxWidth = 220f)
            val left = rects.minOf { it.left } - 12f
            val top = rects.minOf { it.top } - (titleMetrics.height + 18f)
            val right = rects.maxOf { it.right } + 12f
            val bottom = rects.maxOf { it.bottom } + 12f
            clusterRects[NodeId("ns#${ns.id}")] = Rect.ltrb(left, top, right, bottom)
        }

        // Note rects: position relative to target class per `placement` (LeftOf/RightOf/TopOf/
        // BottomOf), or stack standalone notes off the right side of the diagram. Each note
        // remembers its preferred push direction so the GLOBAL overlap-resolution pass below
        // (after coord translation) can keep nudging it the same way until it's clear.
        val noteGap = 16f
        var standaloneY = marginY
        // (noteId, push direction unit vector)
        val noteDirs = LinkedHashMap<NodeId, Pair<Float, Float>>()
        for ((noteIdx, note) in ir.notes.withIndex()) {
            val noteText = when (val l = note.text) {
                is com.hrm.diagram.core.ir.RichLabel.Plain -> l.text
                else -> ""
            }
            val m = textMeasurer.measure(noteText, fonts.noteFont, maxWidth = 180f)
            val w = (m.width + 16f).coerceAtLeast(60f)
            val h = (m.height + 12f).coerceAtLeast(28f)
            val target = note.targetClass?.let { nodePositions[it] }
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

        // Edge routes: cubic bezier with control points along the OUTWARD NORMAL of the edge
        // each endpoint sits on. This guarantees the curve leaves perpendicular to the box
        // border (so it never re-enters) regardless of the relative positions of the two boxes.
        val edgeRoutes = ArrayList<EdgeRoute>(ir.relations.size)
        for (rel in ir.relations) {
            val a = nodePositions[rel.from] ?: continue
            val b = nodePositions[rel.to] ?: continue
            val from = clipPoint(a, centerOf(b))
            val to = clipPoint(b, centerOf(a))
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dist = sqrt(dx * dx + dy * dy)
            // Offset is proportional to distance (clamped) so adjacent boxes get a gentle
            // bow and far-apart boxes get sweeping curves without ringing.
            val offset = (dist * 0.4f).coerceIn(20f, 90f)
            val n1 = outwardNormal(a, from)
            val n2 = outwardNormal(b, to)
            val c1 = Point(from.x + n1.x * offset, from.y + n1.y * offset)
            val c2 = Point(to.x + n2.x * offset, to.y + n2.y * offset)
            edgeRoutes += EdgeRoute(
                from = rel.from,
                to = rel.to,
                points = listOf(from, c1, c2, to),
                kind = RouteKind.Bezier,
            )
        }

        // If notes pushed content into negative coordinates (LeftOf / TopOf the leftmost
        // class), translate everything so the diagram still starts at (marginX, marginY).
        val allRects = nodePositions.values + clusterRects.values
        val minLeft = allRects.minOf { it.left }
        val minTop = allRects.minOf { it.top }
        val dx = if (minLeft < marginX) marginX - minLeft else 0f
        val dy = if (minTop < marginY) marginY - minTop else 0f
        if (dx != 0f || dy != 0f) {
            val shift: (Rect) -> Rect = { r -> Rect.ltrb(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy) }
            for ((k, v) in nodePositions.toMap()) nodePositions[k] = shift(v)
            for ((k, v) in clusterRects.toMap()) clusterRects[k] = shift(v)
            for (i in edgeRoutes.indices) {
                val e = edgeRoutes[i]
                edgeRoutes[i] = e.copy(points = e.points.map { Point(it.x + dx, it.y + dy) })
            }
        }

        // Global multi-pass overlap resolver. After translation, repeatedly nudge each note
        // along its preferred direction until it no longer overlaps any class box, edge label
        // bbox, or other note. Bounded by a safety counter.
        run {
            val noteIds = noteDirs.keys.toList()
            val classRects = nodePositions.values.toList()
            // Reserved bboxes for each edge midpoint label (label text + cardinality strings).
            val labelRects = ArrayList<Rect>()
            for (rel in ir.relations) {
                val a = nodePositions[rel.from] ?: continue
                val b = nodePositions[rel.to] ?: continue
                val mid = Point((a.left + a.right + b.left + b.right) / 4f, (a.top + a.bottom + b.top + b.bottom) / 4f)
                val labelText = (rel.label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text.orEmpty()
                if (labelText.isNotEmpty()) {
                    val mm = textMeasurer.measure(labelText, memberFont)
                    labelRects += Rect.ltrb(mid.x - mm.width / 2f - 4f, mid.y - mm.height / 2f - 2f,
                        mid.x + mm.width / 2f + 4f, mid.y + mm.height / 2f + 2f)
                }
            }
            var iter = 0
            val maxIter = 600
            while (iter++ < maxIter) {
                var anyMoved = false
                for (id in noteIds) {
                    val r = clusterRects[id] ?: continue
                    val (dirX, dirY) = noteDirs[id] ?: continue
                    val collides = rectOverlapsAnyRect(r, classRects) ||
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

        var maxRight = nodePositions.values.maxOf { it.right }
        var maxBottom = nodePositions.values.maxOf { it.bottom }
        if (clusterRects.isNotEmpty()) {
            maxRight = maxOf(maxRight, clusterRects.values.maxOf { it.right })
            maxBottom = maxOf(maxBottom, clusterRects.values.maxOf { it.bottom })
        }
        // After resolution notes may have been pushed into negative coords (e.g. LeftOf or
        // TopOf). Run a second translation to keep the diagram origin at (marginX, marginY).
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
            maxRight += dx2
            maxBottom += dy2
        }
        val bounds = Rect.ltrb(0f, 0f, maxRight + marginX, maxBottom + marginY)

        return LaidOutDiagram(
            source = ir,
            nodePositions = nodePositions,
            edgeRoutes = edgeRoutes,
            clusterRects = clusterRects,
            bounds = bounds,
        )
    }

    private fun measureClass(c: ClassNode, headerFont: FontSpec, memberFont: FontSpec): Pair<Float, Float> {
        // Constants below MUST match MermaidClassSubPipeline.renderClass so that the
        // box height granted here is exactly large enough to contain everything render
        // draws (no clipped text, no horizontal divider falling below the box bottom).
        val padX = 12f
        val sectionPad = 8f
        val rowGap = 2f
        val headerText = buildString {
            c.stereotype?.let { append("«").append(it).append("»\n") }
            append(c.name)
            c.generics?.let { append("~").append(it).append("~") }
        }
        val hm = textMeasurer.measure(headerText, headerFont)
        var maxLineW = hm.width
        // Header section: text + sectionPad worth of breathing room (matches headerH in renderer).
        var totalH = hm.height + sectionPad
        val attrs = c.members.filter { !it.isMethod }
        val methods = c.members.filter { it.isMethod }
        val hasAttrs = attrs.isNotEmpty()
        val hasMethods = methods.isNotEmpty()
        if (hasAttrs || hasMethods) {
            totalH += rowGap // gap between header divider and first member row
            for (a in attrs) {
                val mm = textMeasurer.measure(renderMemberLine(a), memberFont)
                if (mm.width > maxLineW) maxLineW = mm.width
                totalH += mm.height
            }
            if (hasAttrs && hasMethods) {
                totalH += rowGap * 2 + 2f // mid divider region
            }
            for (m in methods) {
                val mm = textMeasurer.measure(renderMemberLine(m), memberFont)
                if (mm.width > maxLineW) maxLineW = mm.width
                totalH += mm.height
            }
            totalH += sectionPad // bottom padding inside the box
        }
        val w = (maxLineW + 2 * padX).coerceAtLeast(80f)
        val h = totalH.coerceAtLeast(hm.height + sectionPad * 2)
        return w to h
    }

    private fun resolveFonts(ir: ClassIR): ClassLayoutFonts {
        val extras = ir.styleHints.extras
        val classFamily = parseFontFamily(extras[STYLE_CLASS_FONT_NAME_KEY]) ?: "sans-serif"
        val classSize = parseFontSize(extras[STYLE_CLASS_FONT_SIZE_KEY]) ?: 11f
        val noteFamily = parseFontFamily(extras[STYLE_NOTE_FONT_NAME_KEY]) ?: classFamily
        val noteSize = parseFontSize(extras[STYLE_NOTE_FONT_SIZE_KEY]) ?: classSize
        val packageFamily = parseFontFamily(extras[STYLE_PACKAGE_FONT_NAME_KEY]) ?: classFamily
        val packageSize = parseFontSize(extras[STYLE_PACKAGE_FONT_SIZE_KEY]) ?: classSize
        return ClassLayoutFonts(
            headerFont = FontSpec(family = classFamily, sizeSp = classSize, weight = 600),
            memberFont = FontSpec(family = classFamily, sizeSp = classSize),
            labelFont = FontSpec(family = classFamily, sizeSp = classSize),
            noteFont = FontSpec(family = noteFamily, sizeSp = noteSize),
            packageFont = FontSpec(family = packageFamily, sizeSp = packageSize, weight = 600),
        )
    }

    private fun parseFontFamily(raw: String?): String? = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun parseFontSize(raw: String?): Float? = raw?.trim()?.toFloatOrNull()?.takeIf { it > 0f }

    private fun renderMemberLine(m: com.hrm.diagram.core.ir.ClassMember): String {
        val sb = StringBuilder()
        sb.append(when (m.visibility) {
            com.hrm.diagram.core.ir.Visibility.PUBLIC -> "+"
            com.hrm.diagram.core.ir.Visibility.PRIVATE -> "-"
            com.hrm.diagram.core.ir.Visibility.PROTECTED -> "#"
            com.hrm.diagram.core.ir.Visibility.PACKAGE -> "~"
        })
        sb.append(m.name)
        if (m.isMethod) {
            sb.append('(')
            sb.append(m.params.joinToString(", ") { p -> p.type?.let { "${p.name}: $it" } ?: p.name })
            sb.append(')')
            m.type?.let { sb.append(" : ").append(it) }
        } else {
            m.type?.let { sb.append(" : ").append(it) }
        }
        if (m.isStatic) sb.append('$')
        if (m.isAbstract) sb.append('*')
        return sb.toString()
    }

    private fun centerOf(r: Rect): Point = Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)

    /** Returns the intersection of segment [center,target] with the perimeter of [box]. */
    private fun clipPoint(box: Rect, target: Point): Point {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val dx = target.x - cx
        val dy = target.y - cy
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

    /**
     * Approximates the outward unit normal at point [p] on the perimeter of [box]: picks the
     * dominant axis (whichever distance to a side is smallest) and returns the unit vector
     * pointing away from the box across that side. Used to bias bezier control points.
     */
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

    private fun rectOverlapsAny(r: Rect, rects: Collection<Rect>, exclude: Rect): Boolean {
        for (other in rects) {
            if (other === exclude) continue
            if (rectsOverlap(r, other)) return true
        }
        return false
    }

    private fun rectOverlapsAnyRect(r: Rect, rects: List<Rect>): Boolean {
        for (other in rects) if (rectsOverlap(r, other)) return true
        return false
    }

    private companion object {
        const val STYLE_CLASS_FONT_SIZE_KEY = "plantuml.class.style.class.fontSize"
        const val STYLE_CLASS_FONT_NAME_KEY = "plantuml.class.style.class.fontName"
        const val STYLE_NOTE_FONT_SIZE_KEY = "plantuml.class.style.note.fontSize"
        const val STYLE_NOTE_FONT_NAME_KEY = "plantuml.class.style.note.fontName"
        const val STYLE_PACKAGE_FONT_SIZE_KEY = "plantuml.class.style.package.fontSize"
        const val STYLE_PACKAGE_FONT_NAME_KEY = "plantuml.class.style.package.fontName"
    }
}
