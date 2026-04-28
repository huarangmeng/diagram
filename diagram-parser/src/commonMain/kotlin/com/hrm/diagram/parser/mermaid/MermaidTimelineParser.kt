package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.TimeItem
import com.hrm.diagram.core.ir.TimeRange
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.ir.TimeTrack
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `timeline` (Phase 2).
 *
 * Supported subset (aligned with Mermaid docs):
 * - `timeline` header, optionally followed by direction `TD`/`LR`
 * - `title <text...>`
 * - `section <name...>` (groups subsequent time periods)
 * - time period line:
 *     `<period> : <event> : <event> ...`
 * - continuation line (additional events for previous period):
 *     `: <event>`
 *
 * Mapping to [TimeSeriesIR]:
 * - Each `section` becomes one [TimeTrack] (default section id `timeline:section:0`).
 * - Each time period is mapped to an ordinal time slot: startMs = i * 1000, endMs = startMs + 1000.
 * - Each event becomes a [TimeItem] anchored to that slot, with distinct id per (section, period, event).
 */
class MermaidTimelineParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0

    private var headerSeen = false
    private var direction: Direction = Direction.LR
    private var title: String? = null

    private data class Section(val name: String, val periods: MutableList<Period>)
    private data class Period(val label: String, val events: MutableList<String>)

    private val sections: MutableList<Section> = ArrayList()
    private var currentSectionIdx: Int = 0
    private var lastPeriodRef: Pair<Int, Int>? = null // (sectionIdx, periodIdx)

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.TIMELINE_HEADER) {
                headerSeen = true
                // optional direction token on same line: `timeline TD`
                toks.drop(1).firstOrNull { it.kind == MermaidTokenKind.DIRECTION }?.let {
                    direction = parseDirection(it.text.toString()) ?: direction
                }
                ensureDefaultSection()
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'timeline' header")
        }

        // title <rest...>
        if (toks.first().kind == MermaidTokenKind.IDENT && toks.first().text.toString() == "title") {
            val rest = toks.drop(1).joinToString(" ") { it.text.toString() }.trim()
            if (rest.isNotEmpty()) title = rest
            return IrPatchBatch(seq, emptyList())
        }

        // section <name...>
        if (toks.first().kind == MermaidTokenKind.IDENT && toks.first().text.toString() == "section") {
            val rest = toks.drop(1).joinToString(" ") { it.text.toString() }.trim()
            val name = rest.ifBlank { "section" }
            // If we only have the implicit default section and it is still empty, reuse it
            // as the first explicit section to match Mermaid's mental model.
            if (sections.size == 1 && sections[0].name == "default" && sections[0].periods.isEmpty()) {
                sections[0] = Section(name = name, periods = ArrayList())
                currentSectionIdx = 0
            } else {
                sections += Section(name = name, periods = ArrayList())
                currentSectionIdx = sections.lastIndex
            }
            lastPeriodRef = null
            return IrPatchBatch(seq, emptyList())
        }

        // continuation `: event`
        if (toks.first().kind == MermaidTokenKind.COLON) {
            val ref = lastPeriodRef ?: return errorBatch("Timeline continuation without a preceding time period")
            val ev = joinText(toks.drop(1)).trim()
            if (ev.isNotEmpty()) {
                sections[ref.first].periods[ref.second].events += ev
            }
            return IrPatchBatch(seq, emptyList())
        }

        // period line: <period> : <event> (: <event>)*
        val firstColonIdx = toks.indexOfFirst { it.kind == MermaidTokenKind.COLON }
        if (firstColonIdx <= 0) return errorBatch("Invalid timeline line (expected '<period> : <event>')")
        val periodText = joinText(toks.subList(0, firstColonIdx)).trim()
        val events = splitEvents(toks.subList(firstColonIdx + 1, toks.size))
        if (periodText.isEmpty() || events.isEmpty()) return errorBatch("Invalid timeline line (empty period or events)")

        val sec = sections[currentSectionIdx]
        val p = Period(label = periodText, events = events.toMutableList())
        sec.periods += p
        lastPeriodRef = currentSectionIdx to (sec.periods.lastIndex)
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): TimeSeriesIR {
        // Flatten sections into tracks/items.
        val tracks = ArrayList<TimeTrack>()
        val items = ArrayList<TimeItem>()
        var minSlot = Long.MAX_VALUE
        var maxSlot = Long.MIN_VALUE

        for ((sIdx, s) in sections.withIndex()) {
            val trackId = NodeId("timeline:section:$sIdx")
            tracks += TimeTrack(id = trackId, label = RichLabel.Plain(s.name))
            for ((pIdx, p) in s.periods.withIndex()) {
                val slotStart = slotStartMs(pIdx)
                val slotRange = TimeRange(startMs = slotStart, endMs = slotStart + 1000)
                minSlot = kotlin.math.min(minSlot, slotRange.startMs)
                maxSlot = kotlin.math.max(maxSlot, slotRange.endMs)
                for ((eIdx, ev) in p.events.withIndex()) {
                    items += TimeItem(
                        id = NodeId("timeline:item:$sIdx:$pIdx:$eIdx"),
                        label = RichLabel.Plain("${p.label} : $ev"),
                        range = slotRange,
                        trackId = trackId,
                        payload = mapOf("period" to p.label, "event" to ev),
                    )
                }
            }
        }

        if (minSlot == Long.MAX_VALUE) {
            minSlot = 0
            maxSlot = 1000
        }

        return TimeSeriesIR(
            tracks = tracks,
            items = items,
            range = TimeRange(minSlot, maxSlot),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(
                direction = direction,
                extras = mapOf("timeline.disableMulticolor" to "false"),
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun ensureDefaultSection() {
        if (sections.isEmpty()) sections += Section(name = "default", periods = ArrayList())
        currentSectionIdx = 0
    }

    private fun splitEvents(toks: List<Token>): List<String> {
        // Events are delimited by ':' tokens. Everything else is text.
        val out = ArrayList<String>()
        var cur = ArrayList<Token>()
        fun flush() {
            val s = joinText(cur).trim()
            if (s.isNotEmpty()) out += s
            cur.clear()
        }
        for (t in toks) {
            if (t.kind == MermaidTokenKind.COLON) {
                flush()
            } else {
                cur += t
            }
        }
        flush()
        return out
    }

    private fun joinText(toks: List<Token>): String =
        toks.joinToString(" ") { it.text.toString() }

    private fun slotStartMs(periodIdx: Int): Long = periodIdx.toLong() * 1000L

    private fun parseDirection(raw: String): Direction? =
        when (raw.trim().uppercase()) {
            "LR" -> Direction.LR
            "TD", "TB" -> Direction.TB
            "RL" -> Direction.RL
            "BT" -> Direction.BT
            else -> null
        }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E202")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
