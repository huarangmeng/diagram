package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
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
import kotlin.math.floor
import kotlin.math.max

/**
 * Streaming parser for Mermaid `gantt` (Phase 2).
 *
 * Supported subset (matches `docs/syntax-compat/mermaid.md` Phase 2 target):
 * - `gantt` header
 * - `title ...`
 * - `dateFormat <fmt>` using Mermaid gantt tokens (for example `YYYY-MM-DD`, `MMMM D, YYYY h:mm A`, `DDD`, `Z`)
 * - `excludes weekends` / `excludes sunday,monday,...` / `excludes YYYY-MM-DD, ...`
 * - `weekend friday|saturday` (only when excluding weekends)
 * - `section <name...>`
 * - tasks:
 *   - tags: `crit`, `done`, `active`, `milestone`, `vert` (parsed into payload)
 *   - id + start + end/len, or start + end/len, or after <id...> + end/len, or implicit sequential
 *   - milestone uses same parsing, but renderer will treat it specially
 *
 * Mapping:
 * - sections -> [TimeTrack]
 * - tasks -> [TimeItem] with [TimeRange] in epoch ms
 */
class MermaidGanttParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0

    private var headerSeen = false
    private var title: String? = null
    private var dateFormat: String = "YYYY-MM-DD"
    private var axisFormat: String = "%Y-%m-%d"
    private var tickInterval: String? = null

    private var excludeWeekends: Boolean = false
    private var weekendStartsFriday: Boolean = false // false = Sat/Sun, true = Fri/Sat
    private val excludedWeekdays: MutableSet<Int> = HashSet() // 0..6 (Sun..Sat)
    private val excludedDatesEpochDay: MutableSet<Long> = HashSet()
    private var weekdayStart: String? = null
    private val clickHrefById: MutableMap<String, String> = LinkedHashMap()

    private data class Section(val name: String, val tasks: MutableList<Task>)
    private data class Task(
        val label: String,
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val tags: Set<String>,
        val depends: List<String>,
        val href: String? = null,
    )

    private val sections: MutableList<Section> = ArrayList()
    private var currentSectionIdx = 0
    private var autoTaskSeq = 0

    // For dependency resolution (ids -> end/start)
    private val startById: MutableMap<String, Long> = HashMap()
    private val endById: MutableMap<String, Long> = HashMap()
    private val lastEndBySection: MutableMap<Int, Long> = HashMap()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        val normalized = normalizeTokens(toks)
        if (normalized.isBlank()) return IrPatchBatch(seq, emptyList())

        if (!headerSeen) {
            if (normalized.startsWith("gantt")) {
                headerSeen = true
                ensureDefaultSection()
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'gantt' header")
        }

        when {
            normalized.startsWith("title ") -> {
                title = normalized.removePrefix("title ").trim().ifBlank { title }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("dateFormat ") -> {
                dateFormat = normalized.removePrefix("dateFormat ").trim().ifBlank { dateFormat }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("axisFormat ") -> {
                axisFormat = normalized.removePrefix("axisFormat ").trim().ifBlank { axisFormat }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("tickInterval ") -> {
                tickInterval = normalized.removePrefix("tickInterval ").trim().ifBlank { null }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("excludes ") -> {
                parseExcludes(normalized.removePrefix("excludes ").trim())
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("weekend ") -> {
                weekendStartsFriday = normalized.removePrefix("weekend ").trim().equals("friday", ignoreCase = true)
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("weekday ") -> {
                weekdayStart = normalized.removePrefix("weekday ").trim().ifBlank { null }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("section ") -> {
                val name = normalized.removePrefix("section ").trim().ifBlank { "section" }
                // Reuse implicit default if it is still empty.
                if (sections.size == 1 && sections[0].name == "default" && sections[0].tasks.isEmpty()) {
                    sections[0] = Section(name, ArrayList())
                    currentSectionIdx = 0
                } else {
                    sections += Section(name, ArrayList())
                    currentSectionIdx = sections.lastIndex
                }
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("click ") -> {
                parseClick(normalized.removePrefix("click ").trim())
                return IrPatchBatch(seq, emptyList())
            }
            normalized.startsWith("vert ") -> {
                val parsed = parseVertLine(normalized)
                if (parsed != null) {
                    sections[currentSectionIdx].tasks += parsed
                    startById[parsed.id] = parsed.startMs
                    endById[parsed.id] = parsed.endMs
                    return IrPatchBatch(seq, emptyList())
                }
            }
            else -> {
                val parsed = parseTaskLine(normalized)
                if (parsed != null) {
                    sections[currentSectionIdx].tasks += parsed
                    startById[parsed.id] = parsed.startMs
                    endById[parsed.id] = parsed.endMs
                    if (!parsed.tags.contains("vert")) {
                        lastEndBySection[currentSectionIdx] = parsed.endMs
                    }
                    return IrPatchBatch(seq, emptyList())
                }
            }
        }

        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): TimeSeriesIR {
        val tracks = ArrayList<TimeTrack>()
        val items = ArrayList<TimeItem>()
        var minMs = Long.MAX_VALUE
        var maxMs = Long.MIN_VALUE

        for ((sIdx, s) in sections.withIndex()) {
            val trackId = NodeId("gantt:section:$sIdx")
            tracks += TimeTrack(trackId, RichLabel.Plain(s.name))
            for (t in s.tasks) {
                val range = TimeRange(t.startMs, max(t.endMs, t.startMs))
                val href = t.href ?: clickHrefById[t.id]
                minMs = kotlin.math.min(minMs, range.startMs)
                maxMs = kotlin.math.max(maxMs, range.endMs)
                items += TimeItem(
                    id = NodeId(t.id),
                    label = RichLabel.Plain(t.label),
                    range = range,
                    trackId = trackId,
                    depends = t.depends.map { NodeId(it) },
                    payload = buildMap {
                        put("gantt.tags", t.tags.sorted().joinToString(","))
                        if (t.tags.contains("milestone")) put("gantt.kind", "milestone")
                        if (t.tags.contains("vert")) put("gantt.kind", "vert")
                        href?.let { put("gantt.href", it) }
                    },
                )
            }
        }

        if (minMs == Long.MAX_VALUE) {
            minMs = 0; maxMs = 1
        }
        return TimeSeriesIR(
            tracks = tracks,
            items = items,
            range = TimeRange(minMs, maxMs),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(
                extras = buildMap {
                    put("gantt.axisFormat", axisFormat)
                    tickInterval?.let { put("gantt.tickInterval", it) }
                    weekdayStart?.let { put("gantt.weekday", it) }
                },
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- parsing helpers ---

    private fun ensureDefaultSection() {
        if (sections.isEmpty()) sections += Section("default", ArrayList())
        currentSectionIdx = 0
    }

    private fun normalizeTokens(toks: List<Token>): String {
        // Join with spaces to preserve words; then normalize punctuation spacing.
        val s = toks.joinToString(" ") { it.text.toString() }.trim()
        return s
            .replace(Regex("\\s*:\\s*"), ":")
            .replace(Regex("\\s*,\\s*"), ",")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseExcludes(spec: String) {
        val s = spec.trim()
        if (s.equals("weekends", ignoreCase = true)) {
            excludeWeekends = true
            return
        }
        // Comma-separated list: weekdays or YYYY-MM-DD dates.
        for (part in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val wd = parseWeekday(part)
            if (wd != null) {
                excludedWeekdays += wd
                continue
            }
            val dt = MermaidGanttTime.parseDateTime(part, dateFormat)?.epochMs
            if (dt != null) {
                excludedDatesEpochDay += MermaidGanttTime.epochDay(dt)
            } else {
                diagnostics += Diagnostic(Severity.WARNING, "Unsupported excludes token '$part' ignored", "MERMAID-W012")
            }
        }
    }

    private fun parseWeekday(raw: String): Int? {
        return when (raw.lowercase()) {
            "sunday" -> 0
            "monday" -> 1
            "tuesday" -> 2
            "wednesday" -> 3
            "thursday" -> 4
            "friday" -> 5
            "saturday" -> 6
            else -> null
        }
    }

    private fun parseTaskLine(line: String): Task? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val label = line.substring(0, idx).trim()
        val metaRaw = line.substring(idx + 1).trim()
        if (label.isEmpty() || metaRaw.isEmpty()) return null

        val parts = metaRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.isEmpty()) return null

        val tags = LinkedHashSet<String>()
        while (parts.isNotEmpty()) {
            val p = parts.first()
            val isTag = p == "crit" || p == "done" || p == "active" || p == "milestone" || p == "vert"
            if (!isTag) break
            tags += p
            parts.removeAt(0)
        }

        val parsedMeta = parseTaskMetadata(parts) ?: return null
        val id = parsedMeta.id
        val startSpec = parsedMeta.startSpec
        val endSpec = parsedMeta.endSpec

        val sectionIdx = currentSectionIdx
        val defaultStart = lastEndBySection[sectionIdx] ?: 0L

        val (startMs, depends) = resolveStart(startSpec, defaultStart)
        val endMs = resolveEnd(endSpec, startMs)

        val finalId = (id ?: deriveId(label)).ifBlank { deriveId(label) }
        return Task(
            label = label,
            id = finalId,
            startMs = startMs,
            endMs = endMs,
            tags = tags,
            depends = depends,
            href = clickHrefById[finalId],
        )
    }

    private fun parseVertLine(line: String): Task? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val label = line.substring(0, idx)
            .removePrefix("vert")
            .trim()
            .trim('"', '\'')
            .ifBlank { "vert" }
        val dateSpec = line.substring(idx + 1).trim()
        val startMs = MermaidGanttTime.parseDateTime(dateSpec, dateFormat)?.epochMs ?: return null
        val id = deriveId("vert_$label")
        return Task(
            label = label,
            id = id,
            startMs = startMs,
            endMs = startMs,
            tags = setOf("vert"),
            depends = emptyList(),
            href = null,
        )
    }

    private data class ParsedTaskMetadata(
        val id: String?,
        val startSpec: String?,
        val endSpec: String?,
    )

    private fun parseTaskMetadata(parts: List<String>): ParsedTaskMetadata? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return ParsedTaskMetadata(id = null, startSpec = null, endSpec = parts[0])

        var best: ParsedTaskMetadata? = null

        for (split in 1 until parts.size) {
            val start = joinParts(parts, 0, split)
            val end = joinParts(parts, split, parts.size)
            if (isValidStartSpec(start) && isValidEndSpec(end)) {
                best = ParsedTaskMetadata(id = null, startSpec = start, endSpec = end)
                break
            }
        }
        if (best != null) return best

        if (parts.size >= 3 && isLikelyId(parts[0])) {
            for (split in 2..parts.lastIndex) {
                val start = joinParts(parts, 1, split)
                val end = joinParts(parts, split, parts.size)
                if (isValidStartSpec(start) && isValidEndSpec(end)) {
                    return ParsedTaskMetadata(id = parts[0], startSpec = start, endSpec = end)
                }
            }
        }

        return when (parts.size) {
            2 -> ParsedTaskMetadata(id = null, startSpec = parts[0], endSpec = parts[1])
            else -> ParsedTaskMetadata(id = parts[0], startSpec = parts[1], endSpec = joinParts(parts, 2, parts.size))
        }
    }

    private fun joinParts(parts: List<String>, start: Int, end: Int): String =
        parts.subList(start, end).joinToString(", ").trim()

    private fun isLikelyId(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.startsWith("after ") || text.startsWith("until ")) return false
        if (MermaidGanttTime.parseDateTime(text, dateFormat) != null) return false
        if (MermaidGanttTime.parseDuration(text) != null) return false
        return true
    }

    private fun isValidStartSpec(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.startsWith("after ")) {
            return text.removePrefix("after ").trim().split(' ').any { it.isNotBlank() }
        }
        return MermaidGanttTime.parseDateTime(text, dateFormat) != null
    }

    private fun isValidEndSpec(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.startsWith("until ")) return text.removePrefix("until ").trim().isNotEmpty()
        if (MermaidGanttTime.parseDuration(text) != null) return true
        return MermaidGanttTime.parseDateTime(text, dateFormat) != null
    }

    private fun parseClick(spec: String) {
        val id = spec.substringBefore(' ').trim()
        val tail = spec.substringAfter(' ', "").trim()
        if (id.isEmpty() || tail.isEmpty()) {
            diagnostics += Diagnostic(Severity.WARNING, "Invalid gantt click directive ignored", "MERMAID-W012")
            return
        }
        when {
            tail.startsWith("href ") -> {
                val href = sanitizeClickHref(tail.removePrefix("href "))
                if (href.isNotEmpty()) clickHrefById[id] = href
            }
            tail.startsWith("call ") -> {
                val js = tail.removePrefix("call ").trim()
                if (js.isNotEmpty()) clickHrefById[id] = "javascript:$js"
            }
            else -> diagnostics += Diagnostic(Severity.WARNING, "Unsupported gantt click directive '$tail' ignored", "MERMAID-W012")
        }
    }

    private fun sanitizeClickHref(raw: String): String =
        raw.trim()
            .trim('"', '\'')
            .trim()
            .trim('`')
            .trim()

    private fun resolveStart(spec: String?, defaultStart: Long): Pair<Long, List<String>> {
        if (spec == null) return defaultStart to emptyList()
        val s = spec.trim()
        if (s.startsWith("after ")) {
            val ids = s.removePrefix("after ").trim().split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) return defaultStart to emptyList()
            var st = defaultStart
            for (id in ids) st = max(st, endById[id] ?: defaultStart)
            return st to ids
        }
        // Explicit datetime.
        val dt = MermaidGanttTime.parseDateTime(s, dateFormat)?.epochMs
        if (dt != null) return dt to emptyList()
        return defaultStart to emptyList()
    }

    private fun resolveEnd(spec: String?, startMs: Long): Long {
        if (spec == null) return startMs
        val s = spec.trim()
        if (s.startsWith("until ")) {
            val id = s.removePrefix("until ").trim()
            return startById[id] ?: startMs
        }
        val dt = MermaidGanttTime.parseDateTime(s, dateFormat)?.epochMs
        if (dt != null) return dt
        val dur = MermaidGanttTime.parseDuration(s)
        if (dur != null) {
            return when (dur.unit) {
                MermaidGanttTime.DurationUnit.Millisecond,
                MermaidGanttTime.DurationUnit.Second,
                MermaidGanttTime.DurationUnit.Minute,
                MermaidGanttTime.DurationUnit.Hour,
                -> startMs + (dur.millis ?: 0L)
                MermaidGanttTime.DurationUnit.Day,
                MermaidGanttTime.DurationUnit.Week,
                -> addWorkingDays(startMs, dur, excludeWeekends, weekendStartsFriday, excludedWeekdays, excludedDatesEpochDay)
                MermaidGanttTime.DurationUnit.Month -> addCalendarMonths(
                    startMs,
                    dur.value,
                    excludeWeekends,
                    weekendStartsFriday,
                    excludedWeekdays,
                    excludedDatesEpochDay,
                )
                MermaidGanttTime.DurationUnit.Year -> addCalendarYears(
                    startMs,
                    dur.value,
                    excludeWeekends,
                    weekendStartsFriday,
                    excludedWeekdays,
                    excludedDatesEpochDay,
                )
            }
        }
        return startMs
    }

    private fun deriveId(label: String): String {
        autoTaskSeq++
        val base = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "task$autoTaskSeq" else "${base}_$autoTaskSeq"
    }

    private fun addWorkingDays(
        startMs: Long,
        dur: MermaidGanttTime.Duration,
        excludeWeekends: Boolean,
        weekendStartsFriday: Boolean,
        excludedWeekdays: Set<Int>,
        excludedDatesEpochDay: Set<Long>,
    ): Long {
        var remaining = when (dur.unit) {
            MermaidGanttTime.DurationUnit.Day -> floorToInt(dur.value)
            MermaidGanttTime.DurationUnit.Week -> floorToInt(dur.value * 7.0)
            else -> 0
        }
        var curDay = MermaidGanttTime.epochDay(startMs)
        // Interpret duration as "N included days" added to start.
        while (remaining > 0) {
            curDay += 1
            if (isExcludedDay(curDay, excludeWeekends, weekendStartsFriday, excludedWeekdays, excludedDatesEpochDay)) continue
            remaining--
        }
        return curDay * MermaidGanttTime.MS_PER_DAY
    }

    private fun addCalendarMonths(
        startMs: Long,
        value: Double,
        excludeWeekends: Boolean,
        weekendStartsFriday: Boolean,
        excludedWeekdays: Set<Int>,
        excludedDatesEpochDay: Set<Long>,
    ): Long {
        val wholeMonths = floorToInt(value)
        val fractional = value - wholeMonths
        var endMs = MermaidGanttTime.addCalendarMonths(startMs, wholeMonths)
        if (fractional > 0.0) {
            endMs = addWorkingDays(
                endMs,
                MermaidGanttTime.Duration(fractional * 30.0, MermaidGanttTime.DurationUnit.Day),
                excludeWeekends,
                weekendStartsFriday,
                excludedWeekdays,
                excludedDatesEpochDay,
            )
        }
        return endMs
    }

    private fun addCalendarYears(
        startMs: Long,
        value: Double,
        excludeWeekends: Boolean,
        weekendStartsFriday: Boolean,
        excludedWeekdays: Set<Int>,
        excludedDatesEpochDay: Set<Long>,
    ): Long {
        val wholeYears = floorToInt(value)
        val fractional = value - wholeYears
        var endMs = MermaidGanttTime.addCalendarYears(startMs, wholeYears)
        if (fractional > 0.0) {
            endMs = addCalendarMonths(
                endMs,
                fractional * 12.0,
                excludeWeekends,
                weekendStartsFriday,
                excludedWeekdays,
                excludedDatesEpochDay,
            )
        }
        return endMs
    }

    private fun floorToInt(value: Double): Int = floor(value).toInt()

    private fun isExcludedDay(
        epochDay: Long,
        excludeWeekends: Boolean,
        weekendStartsFriday: Boolean,
        excludedWeekdays: Set<Int>,
        excludedDatesEpochDay: Set<Long>,
    ): Boolean {
        if (epochDay in excludedDatesEpochDay) return true
        val wd = MermaidGanttTime.weekday(epochDay)
        if (wd in excludedWeekdays) return true
        if (excludeWeekends) {
            // 0..6 (Sun..Sat). weekend default Sat/Sun; optional Fri/Sat.
            val isWeekend = if (weekendStartsFriday) (wd == 5 || wd == 6) else (wd == 0 || wd == 6)
            if (isWeekend) return true
        }
        return false
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E203")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
