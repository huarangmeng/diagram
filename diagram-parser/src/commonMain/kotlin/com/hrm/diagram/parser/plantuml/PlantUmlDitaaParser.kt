package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.NodeStyle
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for a minimal PlantUML ditaa subset.
 *
 * The parser recognizes rectangular ASCII boxes made from `+`, `-` and `|`, extracts their inner
 * text as node labels, maps ditaa shape/color markers, and creates directional edges when ASCII
 * connectors sit between two boxes.
 *
 * Example:
 * ```text
 * +-----+   +-----+
 * | Foo |-->| Bar |
 * +-----+   +-----+
 * ```
 */
@DiagramApi
class PlantUmlDitaaParser {
    companion object {
        const val GRID_KEY = "plantuml.ditaa.grid"
        const val ROUNDED_KEY = "plantuml.ditaa.rounded"
        const val HANDWRITTEN_KEY = "plantuml.ditaa.handwritten"
        const val SHAPE_KEY = "plantuml.ditaa.shape"
        private val COLOR_MARKER = Regex("""\bc([A-Za-z]{3}|[A-Fa-f0-9]{3}|[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})\b""")
        private val SHAPE_MARKER = Regex("""\{(io|mo|tr|d|s|c|o)\}""", RegexOption.IGNORE_CASE)
    }

    private data class Box(
        val id: NodeId,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val label: String,
        val fill: ArgbColor?,
        val shape: NodeShape,
        val shapeTag: String?,
        val rounded: Boolean,
        val handwritten: Boolean,
    )
    private data class LabelInfo(val text: String, val fill: ArgbColor?, val shape: NodeShape, val shapeTag: String?)
    private data class EdgeInfo(
        val from: Box,
        val to: Box,
        val arrow: ArrowEnds,
        val kind: EdgeKind,
        val width: Float,
        val dash: List<Float>?,
    )

    private val lines: MutableList<String> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var handwritten = false
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.startsWith("skinparam handwritten", ignoreCase = true)) {
            handwritten = trimmed.substringAfter("handwritten", "").trim().equals("true", ignoreCase = true)
            return IrPatchBatch(seq, emptyList())
        }
        lines += line.trimEnd()
        return IrPatchBatch(seq, emptyList())
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (blockClosed) return IrPatchBatch(seq, emptyList())
        val d = Diagnostic(Severity.ERROR, "Missing @endditaa closing delimiter", "PLANTUML-E020")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    fun snapshot(): GraphIR {
        val boxes = detectBoxes()
        val nodes = boxes.map { box ->
            Node(
                id = box.id,
                label = RichLabel.Plain(box.label.ifBlank { box.id.value }),
                shape = box.shape,
                style = NodeStyle(
                    fill = box.fill ?: ArgbColor(0xFFFFFDE7.toInt()),
                    stroke = ArgbColor(0xFF8D6E63.toInt()),
                    strokeWidth = 1.4f,
                    textColor = ArgbColor(0xFF3E2723.toInt()),
                ),
                payload = buildMap {
                    put(GRID_KEY, "${box.left},${box.top},${box.right},${box.bottom}")
                    box.shapeTag?.let { put(SHAPE_KEY, it) }
                    if (box.rounded) put(ROUNDED_KEY, "true")
                    if (box.handwritten) put(HANDWRITTEN_KEY, "true")
                },
            )
        }
        return GraphIR(
            nodes = nodes,
            edges = detectEdges(boxes),
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(
                extras = buildMap {
                    put("plantuml.graph.kind", "ditaa")
                    if (handwritten) put(HANDWRITTEN_KEY, "true")
                },
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun detectBoxes(): List<Box> {
        val out = ArrayList<Box>()
        val seen = HashSet<String>()
        for (y in lines.indices) {
            val line = lines[y]
            for (x in line.indices) {
                val topLeft = charAt(x, y)
                if (!isTopLeftCorner(topLeft)) continue
                val rightCandidates = (x + 2 until line.length).filter { isTopRightCorner(charAt(it, y)) && horizontalBetween(x, it, y) }
                for (r in rightCandidates) {
                    val bottom = (y + 2 until lines.size).firstOrNull {
                        isBottomLeftCorner(charAt(x, it)) &&
                            isBottomRightCorner(charAt(r, it)) &&
                            horizontalBetween(x, r, it) &&
                            verticalBetween(y, it, x) &&
                            verticalBetween(y, it, r)
                    } ?: continue
                    val key = "$x,$y,$r,$bottom"
                    if (!seen.add(key)) continue
                    val label = extractLabel(x, y, r, bottom)
                    val id = NodeId("ditaa:${out.size}:${PlantUmlTemporalSupport.slug(label.text.ifBlank { "box" })}")
                    val rounded = topLeft != '+' || charAt(r, y) != '+' || charAt(x, bottom) != '+' || charAt(r, bottom) != '+'
                    out += Box(id, x, y, r, bottom, label.text, label.fill, label.shape, label.shapeTag, rounded, handwritten)
                    break
                }
            }
        }
        return out.filterNot { outer -> out.any { inner -> inner != outer && inner.left <= outer.left && inner.top <= outer.top && inner.right >= outer.right && inner.bottom >= outer.bottom && (inner.right - inner.left) * (inner.bottom - inner.top) > (outer.right - outer.left) * (outer.bottom - outer.top) } }
    }

    private fun detectEdges(boxes: List<Box>): List<Edge> {
        val out = ArrayList<Edge>()
        for (from in boxes) {
            for (to in boxes) {
                if (from == to) continue
                val edge = detectEdge(from, to)
                if (edge != null) {
                    out += Edge(
                        from = edge.from.id,
                        to = edge.to.id,
                        kind = edge.kind,
                        arrow = edge.arrow,
                        style = EdgeStyle(color = ArgbColor(0xFF6D4C41.toInt()), width = edge.width, dash = edge.dash),
                    )
                }
            }
        }
        return out.distinctBy { it.from to it.to }
    }

    private fun detectEdge(from: Box, to: Box): EdgeInfo? =
        detectHorizontalEdge(from, to)
            ?: detectHorizontalEdge(to, from)?.let { reverseEdge(it) }
            ?: detectVerticalEdge(from, to)
            ?: detectVerticalEdge(to, from)?.let { reverseEdge(it) }

    private fun detectHorizontalEdge(left: Box, right: Box): EdgeInfo? {
        if (left.right >= right.left) return null
        val yRange = maxOf(left.top + 1, right.top + 1)..minOf(left.bottom - 1, right.bottom - 1)
        for (y in yRange) {
            val segment = substring(left.right + 1, right.left, y)
            val presentation = edgePresentation(segment) ?: continue
            val arrow = when {
                segment.contains("<->") || segment.contains("<=>") -> ArrowEnds.Both
                segment.contains("<-") || segment.contains("<=") -> ArrowEnds.FromOnly
                segment.contains("->") || segment.contains("=>") -> ArrowEnds.ToOnly
                segment.any { it == '-' || it == '=' || it == ':' } -> ArrowEnds.None
                else -> continue
            }
            return EdgeInfo(left, right, arrow, presentation.first, presentation.second, presentation.third)
        }
        return null
    }

    private fun detectVerticalEdge(top: Box, bottom: Box): EdgeInfo? {
        if (top.bottom >= bottom.top) return null
        val xRange = maxOf(top.left + 1, bottom.left + 1)..minOf(top.right - 1, bottom.right - 1)
        for (x in xRange) {
            val chars = (top.bottom + 1 until bottom.top).map { charAt(x, it) }.joinToString("")
            val presentation = edgePresentation(chars) ?: continue
            val arrow = when {
                chars.contains('v') && chars.contains('^') -> ArrowEnds.Both
                chars.contains('^') -> ArrowEnds.FromOnly
                chars.contains('v') -> ArrowEnds.ToOnly
                chars.any { it == '|' || it == ':' || it == '=' } -> ArrowEnds.None
                else -> continue
            }
            return EdgeInfo(top, bottom, arrow, presentation.first, presentation.second, presentation.third)
        }
        return null
    }

    private fun reverseEdge(edge: EdgeInfo): EdgeInfo =
        edge.copy(
            from = edge.to,
            to = edge.from,
            arrow = when (edge.arrow) {
                ArrowEnds.ToOnly -> ArrowEnds.FromOnly
                ArrowEnds.FromOnly -> ArrowEnds.ToOnly
                else -> edge.arrow
            },
        )

    private fun edgePresentation(segment: String): Triple<EdgeKind, Float, List<Float>?>? {
        if (segment.none { it in "-=|:<>^v" }) return null
        val dash = if (segment.contains(':')) listOf(4f, 4f) else null
        val width = if (segment.contains('=')) 2.4f else 1.5f
        val kind = if (dash != null) EdgeKind.Dashed else EdgeKind.Solid
        return Triple(kind, width, dash)
    }

    private fun horizontalBetween(left: Int, right: Int, y: Int): Boolean =
        (left + 1 until right).all { charAt(it, y) == '-' || charAt(it, y) == '=' }

    private fun verticalBetween(top: Int, bottom: Int, x: Int): Boolean =
        (top + 1 until bottom).all { charAt(x, it) == '|' || charAt(x, it) == ':' }

    private fun isTopLeftCorner(c: Char): Boolean = c == '+' || c == '/'

    private fun isTopRightCorner(c: Char): Boolean = c == '+' || c == '\\'

    private fun isBottomLeftCorner(c: Char): Boolean = c == '+' || c == '\\'

    private fun isBottomRightCorner(c: Char): Boolean = c == '+' || c == '/'

    private fun extractLabel(left: Int, top: Int, right: Int, bottom: Int): LabelInfo {
        val raw = (top + 1 until bottom)
            .joinToString("\n") { y -> substring(left + 1, right, y).trim() }
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")
        var fill: ArgbColor? = null
        var shapeTag: String? = null
        val cleaned = COLOR_MARKER.replace(raw) { match ->
            fill = fill ?: ditaaColor(match.groupValues[1])
            ""
        }.let { withoutColor ->
            SHAPE_MARKER.replace(withoutColor) { match ->
                shapeTag = shapeTag ?: match.groupValues[1].lowercase()
                ""
            }
        }.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return LabelInfo(cleaned, fill, shapeFor(shapeTag), shapeTag)
    }

    private fun shapeFor(tag: String?): NodeShape =
        when (tag) {
            "d" -> NodeShape.Note
            "s" -> NodeShape.Cylinder
            "io" -> NodeShape.Parallelogram
            "c" -> NodeShape.Diamond
            "o" -> NodeShape.Ellipse
            "mo" -> NodeShape.Hexagon
            "tr" -> NodeShape.Trapezoid
            else -> NodeShape.RoundedBox
        }

    private fun ditaaColor(raw: String): ArgbColor? {
        val text = raw.trim()
        val named = when (text.lowercase()) {
            "red" -> 0xFFE53935.toInt()
            "gre", "grn" -> 0xFF43A047.toInt()
            "blu" -> 0xFF1E88E5.toInt()
            "yel" -> 0xFFFDD835.toInt()
            "org" -> 0xFFFB8C00.toInt()
            "pnk", "pin" -> 0xFFD81B60.toInt()
            "pur", "vio" -> 0xFF8E24AA.toInt()
            "gry", "gra" -> 0xFF78909C.toInt()
            "blk" -> 0xFF37474F.toInt()
            "wht" -> 0xFFFFFFFF.toInt()
            else -> null
        }
        if (named != null) return ArgbColor(named)
        val argb = when (text.length) {
            3 -> "FF${text.map { "$it$it" }.joinToString("")}"
            6 -> "FF$text"
            8 -> text
            else -> return null
        }
        return argb.toLongOrNull(16)?.let { ArgbColor(it.toInt()) }
    }

    private fun substring(start: Int, end: Int, y: Int): String {
        if (y !in lines.indices || start >= end) return ""
        val line = lines[y]
        return (start until end).map { if (it in line.indices) line[it] else ' ' }.joinToString("")
    }

    private fun charAt(x: Int, y: Int): Char =
        lines.getOrNull(y)?.getOrNull(x) ?: ' '
}
