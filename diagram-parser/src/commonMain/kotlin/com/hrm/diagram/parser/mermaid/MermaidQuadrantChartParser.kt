package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.QuadrantPoint
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `quadrantChart`.
 *
 * Supported subset:
 * - `quadrantChart`
 * - `title ...`
 * - `x-axis low --> high` / `x-axis low`
 * - `y-axis low --> high` / `y-axis low`
 * - `quadrant-1..4 text`
 * - point lines: `Label: [x, y]`
 * - inline point styles after coordinates:
 *   - `color: #hex|cssColor`
 *   - `radius: 12`
 *   - `stroke-color: #hex|cssColor`
 *   - `stroke-width: 5px`
 *
 * Unsupported in Phase 2:
 * - `:::class` and `classDef` are ignored with warning.
 */
class MermaidQuadrantChartParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var title: String? = null
    private var xMinLabel: String? = null
    private var xMaxLabel: String? = null
    private var yMinLabel: String? = null
    private var yMaxLabel: String? = null
    private val quadrantLabels: MutableMap<Int, String> = LinkedHashMap()
    private val points: MutableList<QuadrantPoint> = ArrayList()
    private var autoId = 0

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.QUADRANT_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'quadrantChart' header")
        }

        val s = normalize(toks)
        when {
            s.startsWith("title ") -> title = stripQuotes(s.removePrefix("title ").trim()).ifBlank { title }
            s.startsWith("x-axis ") -> parseXAxis(s.removePrefix("x-axis ").trim())
            s.startsWith("y-axis ") -> parseYAxis(s.removePrefix("y-axis ").trim())
            s.startsWith("quadrant-") -> parseQuadrantLabel(s)
            s.startsWith("classDef ") || s.contains(":::") -> {
                diagnostics += Diagnostic(Severity.WARNING, "quadrantChart class styling is ignored in current renderer", "MERMAID-W010")
            }
            else -> parsePoint(s)
        }
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): QuadrantChartIR =
        QuadrantChartIR(
            xMinLabel = xMinLabel?.let { RichLabel.Plain(it) },
            xMaxLabel = xMaxLabel?.let { RichLabel.Plain(it) },
            yMinLabel = yMinLabel?.let { RichLabel.Plain(it) },
            yMaxLabel = yMaxLabel?.let { RichLabel.Plain(it) },
            quadrantLabels = quadrantLabels.mapValues { RichLabel.Plain(it.value) },
            points = points.toList(),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseXAxis(spec: String) {
        val parts = spec.split("-->").map { stripQuotes(it.trim()) }
        xMinLabel = parts.getOrNull(0)?.ifBlank { null }
        xMaxLabel = parts.getOrNull(1)?.ifBlank { null }
    }

    private fun parseYAxis(spec: String) {
        val parts = spec.split("-->").map { stripQuotes(it.trim()) }
        yMinLabel = parts.getOrNull(0)?.ifBlank { null }
        yMaxLabel = parts.getOrNull(1)?.ifBlank { null }
    }

    private fun parseQuadrantLabel(s: String) {
        val m = Regex("""^quadrant-(\d)\s+(.+)$""").matchEntire(s)
        if (m == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid quadrant label syntax", "MERMAID-E207")
            return
        }
        val idx = m.groupValues[1].toIntOrNull()
        val text = stripQuotes(m.groupValues[2].trim())
        if (idx !in 1..4 || text.isBlank()) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid quadrant label syntax", "MERMAID-E207")
            return
        }
        quadrantLabels[idx!!] = text
    }

    private fun parsePoint(s: String) {
        val colon = s.indexOf(':')
        val lbr = s.indexOf('[', startIndex = if (colon >= 0) colon + 1 else 0)
        val rbr = s.indexOf(']', startIndex = if (lbr >= 0) lbr + 1 else 0)
        if (colon <= 0 || lbr <= colon || rbr <= lbr) {
            diagnostics += Diagnostic(Severity.WARNING, "Unsupported quadrantChart line ignored: $s", "MERMAID-W012")
            return
        }
        var rawLabel = s.substring(0, colon).trim()
        val classIdx = rawLabel.indexOf(":::")
        if (classIdx >= 0) {
            diagnostics += Diagnostic(Severity.WARNING, "quadrantChart class styling is ignored in current renderer", "MERMAID-W010")
            rawLabel = rawLabel.substring(0, classIdx).trim()
        }
        val label = stripQuotes(rawLabel)
        val coords = s.substring(lbr + 1, rbr)
        val xy = coords.split(',').map { it.trim() }
        val x = xy.getOrNull(0)?.toDoubleOrNull()
        val y = xy.getOrNull(1)?.toDoubleOrNull()
        if (label.isBlank() || x == null || y == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid quadrant point syntax", "MERMAID-E207")
            return
        }
        if (x !in 0.0..1.0 || y !in 0.0..1.0) {
            diagnostics += Diagnostic(Severity.ERROR, "Quadrant point values must be in 0..1", "MERMAID-E207")
            return
        }
        val payload = parsePointStyle(s.substring(rbr + 1).trim())
        points += QuadrantPoint(
            id = NodeId(nextId(label)),
            label = RichLabel.Plain(label),
            x = x,
            y = y,
            payload = payload,
        )
    }

    private fun parsePointStyle(spec: String): Map<String, String> {
        if (spec.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val parts = splitComma(spec)
        for (part in parts) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val k = part.substring(0, idx).trim()
            val v = part.substring(idx + 1).trim()
            when (k) {
                "color", "stroke-color" -> {
                    val argb = MermaidCssColors.parseToArgbIntOrNull(v)
                    if (argb == null) {
                        diagnostics += Diagnostic(Severity.WARNING, "Unrecognized color '$v' ignored", "MERMAID-W011")
                    } else out[k] = argb.toString()
                }
                "radius", "stroke-width" -> {
                    val px = parseCssPx(v)
                    if (px == null) {
                        diagnostics += Diagnostic(Severity.WARNING, "Invalid size '$v' ignored", "MERMAID-W012")
                    } else out[k] = px.toString()
                }
                else -> diagnostics += Diagnostic(Severity.WARNING, "Unsupported point style '$k' ignored", "MERMAID-W012")
            }
        }
        return out
    }

    private fun parseCssPx(text: String): Float? {
        val s = text.trim().trimEnd(';').lowercase()
        val raw = when {
            s.endsWith("px") -> s.removeSuffix("px").trim()
            s.endsWith("pt") -> {
                val pt = s.removeSuffix("pt").trim().toFloatOrNull() ?: return null
                return pt * 4f / 3f
            }
            else -> s
        }
        return raw.toFloatOrNull()
    }

    private fun splitComma(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in s) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                cur.append(ch)
                continue
            }
            if (ch == ',') {
                out += cur.toString().trim()
                cur.setLength(0)
            } else cur.append(ch)
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

    private fun normalize(toks: List<Token>): String =
        toks.joinToString(" ") {
            when (it.kind) {
                MermaidTokenKind.LABEL -> "[${it.text}]"
                MermaidTokenKind.STRING -> "\"${it.text}\""
                else -> it.text.toString()
            }
        }
            .replace(Regex("\\s+"), " ")
            .replace("x - axis", "x-axis")
            .replace("y - axis", "y-axis")
            .replace("quadrant - ", "quadrant-")
            .replace("stroke - color", "stroke-color")
            .replace("stroke - width", "stroke-width")
            .replace("5 px", "5px")
            .trim()

    private fun nextId(label: String): String {
        autoId++
        val base = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "point_$autoId" else "${base}_$autoId"
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E207")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
