package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
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
 * text as node labels, and creates simple left-to-right / top-to-bottom edges when an arrow sits
 * between two boxes.
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
        private val COLOR_MARKER = Regex("""\bc([A-Za-z]{3}|[A-Fa-f0-9]{3}|[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})\b""")
    }

    private data class Box(
        val id: NodeId,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val label: String,
        val fill: ArgbColor?,
        val rounded: Boolean,
        val handwritten: Boolean,
    )
    private data class LabelInfo(val text: String, val fill: ArgbColor?)

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
                shape = NodeShape.RoundedBox,
                style = NodeStyle(
                    fill = box.fill ?: ArgbColor(0xFFFFFDE7.toInt()),
                    stroke = ArgbColor(0xFF8D6E63.toInt()),
                    strokeWidth = 1.4f,
                    textColor = ArgbColor(0xFF3E2723.toInt()),
                ),
                payload = buildMap {
                    put(GRID_KEY, "${box.left},${box.top},${box.right},${box.bottom}")
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
                    out += Box(id, x, y, r, bottom, label.text, label.fill, rounded, handwritten)
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
                if (hasHorizontalArrow(from, to) || hasVerticalArrow(from, to)) {
                    out += Edge(
                        from = from.id,
                        to = to.id,
                        arrow = ArrowEnds.ToOnly,
                        style = EdgeStyle(color = ArgbColor(0xFF6D4C41.toInt()), width = 1.5f),
                    )
                }
            }
        }
        return out.distinctBy { it.from to it.to }
    }

    private fun hasHorizontalArrow(from: Box, to: Box): Boolean {
        if (from.right >= to.left) return false
        val yRange = maxOf(from.top + 1, to.top + 1)..minOf(from.bottom - 1, to.bottom - 1)
        for (y in yRange) {
            val segment = substring(from.right + 1, to.left, y)
            if (segment.contains("->") || segment.contains("-->") || segment.contains("=>")) return true
        }
        return false
    }

    private fun hasVerticalArrow(from: Box, to: Box): Boolean {
        if (from.bottom >= to.top) return false
        val xRange = maxOf(from.left + 1, to.left + 1)..minOf(from.right - 1, to.right - 1)
        for (x in xRange) {
            val chars = (from.bottom + 1 until to.top).map { charAt(x, it) }.joinToString("")
            if (chars.contains("|") && chars.contains("v")) return true
        }
        return false
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
        val cleaned = COLOR_MARKER.replace(raw) { match ->
            fill = fill ?: ditaaColor(match.groupValues[1])
            ""
        }.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return LabelInfo(cleaned, fill)
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
