package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
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
import kotlin.math.max
import kotlin.math.min

/**
 * Streaming parser for a useful PlantUML `gantt` subset.
 *
 * Supported forms include:
 * - `Project starts YYYY-MM-DD`
 * - `[Task] starts YYYY-MM-DD` / `[Task] starts at YYYY-MM-DD`
 * - `[Task] lasts 5 days`
 * - `[Task] ends YYYY-MM-DD`
 * - `[A] -> [B]`
 * - `[Task] on {Resource}`
 * - `[Task] is colored in #Color`
 * - `[Task] is 50% complete`, `[Task] is complete`
 * - `[Task] is milestone`, `[Task] is critical/dashed/bold`
 * - `note bottom of [Task] : text`
 * - `saturday are closed`, `YYYY-MM-DD is closed`, `YYYY-MM-DD to YYYY-MM-DD are closed`
 * - `-- Section --`
 */
@DiagramApi
class PlantUmlGanttParser {
    companion object {
        private val RESOURCE = Regex("""(?:^|\s+)on\s+\{([^\}]+)\}""", RegexOption.IGNORE_CASE)
        private val COLOR = Regex("""(?:^|\s+)is\s+colored(?:\s+in)?\s+(#[A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
        private val DATE_CLOSED = Regex("""^(\d{4}-\d{2}-\d{2})(?:\s+to\s+(\d{4}-\d{2}-\d{2}))?\s+(?:is|are)\s+(?:closed|off|holiday)s?$""", RegexOption.IGNORE_CASE)
        private val WEEKDAY_CLOSED = Regex("""^(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\s+(?:is|are)\s+(?:closed|off|holiday)s?$""", RegexOption.IGNORE_CASE)
        private val NOTE = Regex("""^note\s+(?:top|bottom|left|right)?\s*of\s+\[([^\]]+)\]\s*:?\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val PROGRESS = Regex("""^is\s+(\d{1,3})%\s+(?:complete|completed|done)$""", RegexOption.IGNORE_CASE)
        private val STYLE = Regex("""^is\s+(?:marked\s+as\s+)?(milestone|critical|dashed|bold)$""", RegexOption.IGNORE_CASE)
        private val LASTS_DAYS = Regex("""^lasts\s+(\d+)\s+days?$""", RegexOption.IGNORE_CASE)
    }

    private data class Task(
        val id: String,
        val label: String,
        var startMs: Long? = null,
        var endMs: Long? = null,
        var durationMs: Long? = null,
        var track: String = "default",
        var resource: String? = null,
        var color: String? = null,
        var progress: Int? = null,
        var milestone: Boolean = false,
        var style: String? = null,
        var note: String? = null,
        var workingDays: Int? = null,
        val depends: MutableList<String> = ArrayList(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val tasks: LinkedHashMap<String, Task> = LinkedHashMap()
    private val sectionOrder: MutableList<String> = arrayListOf("default")
    private val resourceOrder: MutableList<String> = ArrayList()
    private val closedWeekdays: MutableSet<Int> = LinkedHashSet()
    private val closedRanges: MutableList<TimeRange> = ArrayList()
    private var currentSection = "default"
    private var projectStartMs: Long = 0L
    private var title: String? = null
    private var seq: Long = 0

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (trimmed.equals("@startgantt", ignoreCase = true) || trimmed.equals("@endgantt", ignoreCase = true)) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("title ", ignoreCase = true)) {
            title = trimmed.substringAfter(' ').trim().ifBlank { title }
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("Project starts", ignoreCase = true)) {
            PlantUmlTemporalSupport.parseDate(trimmed.substringAfter("starts").trim())?.let { projectStartMs = it }
            return IrPatchBatch(seq, emptyList())
        }
        parseClosedLine(trimmed)?.let { return it }
        parseNote(trimmed)?.let { return it }
        if (trimmed.startsWith("--") && trimmed.endsWith("--")) {
            currentSection = trimmed.trim('-').trim().ifBlank { "section" }
            if (currentSection !in sectionOrder) sectionOrder += currentSection
            return IrPatchBatch(seq, emptyList())
        }
        parseDependency(trimmed)?.let { (from, to) ->
            task(to).depends += from
            return IrPatchBatch(seq, emptyList())
        }
        val taskName = bracketName(trimmed) ?: return errorBatch("Invalid PlantUML gantt line: $trimmed")
        val task = task(taskName)
        task.track = currentSection
        var rest = trimmed.substringAfter("]").trim()
        RESOURCE.find(rest)?.let { m ->
            val resource = m.groupValues[1].trim()
            if (resource.isNotEmpty()) {
                task.resource = resource
                if (resource !in resourceOrder) resourceOrder += resource
            }
            rest = rest.removeRange(m.range).trim()
        }
        COLOR.find(rest)?.let { m ->
            val color = m.groupValues[1].trim()
            if (color.isNotEmpty()) task.color = color
            rest = rest.removeRange(m.range).trim()
        }
        when {
            rest.startsWith("starts at ", ignoreCase = true) ->
                task.startMs = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("starts at ").trim())
            rest.startsWith("starts ", ignoreCase = true) ->
                task.startMs = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("starts ").trim())
            rest.startsWith("ends at ", ignoreCase = true) ->
                task.endMs = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("ends at ").trim())
            rest.startsWith("ends ", ignoreCase = true) ->
                task.endMs = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("ends ").trim())
            rest.startsWith("lasts ", ignoreCase = true) ->
                parseDuration(rest, task)
            rest.startsWith("happens at ", ignoreCase = true) -> {
                val at = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("happens at ").trim())
                task.startMs = at
                task.endMs = at?.plus(PlantUmlTemporalSupport.MS_PER_DAY / 2L)
                task.milestone = true
            }
            rest.equals("is complete", ignoreCase = true) || rest.equals("is completed", ignoreCase = true) ->
                task.progress = 100
            PROGRESS.matches(rest) -> {
                val progress = PROGRESS.matchEntire(rest)!!.groupValues[1].toInt().coerceIn(0, 100)
                task.progress = progress
            }
            STYLE.matches(rest) -> {
                val style = STYLE.matchEntire(rest)!!.groupValues[1].lowercase()
                if (style == "milestone") task.milestone = true else task.style = style
            }
            rest.startsWith("on ", ignoreCase = true) || rest.startsWith("is colored", ignoreCase = true) -> Unit
            rest.isBlank() -> Unit
            else -> return errorBatch("Unsupported PlantUML gantt task operation: $trimmed")
        }
        return IrPatchBatch(seq, emptyList())
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (blockClosed) return IrPatchBatch(seq, emptyList())
        val d = Diagnostic(Severity.ERROR, "Missing @endgantt closing delimiter", "PLANTUML-E015")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    fun snapshot(): TimeSeriesIR {
        val sectionTracks = sectionOrder.mapIndexed { idx, name -> TimeTrack(NodeId("gantt:section:$idx"), RichLabel.Plain(name)) }
        val resourceTracks = resourceOrder.mapIndexed { idx, name -> TimeTrack(NodeId("gantt:resource:$idx"), RichLabel.Plain("Resource: $name")) }
        val tracks = sectionTracks + resourceTracks
        val trackIdByName = sectionOrder.withIndex().associate { it.value to NodeId("gantt:section:${it.index}") }
        val trackIdByResource = resourceOrder.withIndex().associate { it.value to NodeId("gantt:resource:${it.index}") }
        val items = ArrayList<TimeItem>()
        val resolvedEnd = LinkedHashMap<String, Long>()
        var cursor = projectStartMs
        var minMs = Long.MAX_VALUE
        var maxMs = Long.MIN_VALUE
        for (task in tasks.values) {
            val dependencyEnd = task.depends.mapNotNull { resolvedEnd[it] }.maxOrNull()
            val start = task.startMs ?: dependencyEnd ?: cursor
            val end = task.endMs ?: inferEnd(start, task)
            resolvedEnd[task.id] = end
            cursor = max(cursor, end)
            minMs = min(minMs, start)
            maxMs = max(maxMs, end)
            items += TimeItem(
                id = NodeId(task.id),
                label = RichLabel.Plain(task.label),
                range = TimeRange(start, max(end, start)),
                trackId = task.resource?.let { trackIdByResource[it] } ?: trackIdByName[task.track] ?: tracks.first().id,
                depends = task.depends.map { NodeId(it) },
                payload = buildMap {
                    task.resource?.let { put("gantt.resource", it) }
                    task.color?.let { put("gantt.color", it) }
                    task.progress?.let { put("gantt.progress", it.toString()) }
                    if (task.milestone) put("gantt.kind", "milestone")
                    task.style?.let { put("gantt.style", it) }
                    task.note?.let { put("gantt.note", it) }
                    task.workingDays?.let { put("gantt.workingDays", it.toString()) }
                },
            )
        }
        if (minMs == Long.MAX_VALUE) {
            minMs = projectStartMs
            maxMs = projectStartMs + PlantUmlTemporalSupport.MS_PER_DAY
        }
        return TimeSeriesIR(
            tracks = tracks,
            items = items,
            range = TimeRange(minMs, max(maxMs, minMs + 1L)),
            title = title,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(
                extras = buildMap {
                    put("plantuml.timeseries.kind", "gantt")
                    put("gantt.axisFormat", "%Y-%m-%d")
                    if (closedWeekdays.isNotEmpty()) put("gantt.closedWeekdays", closedWeekdays.sorted().joinToString(","))
                    if (closedRanges.isNotEmpty()) put("gantt.closedRanges", closedRanges.joinToString("|") { "${it.startMs}:${it.endMs}" })
                },
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun task(name: String): Task {
        val id = PlantUmlTemporalSupport.slug(name)
        return tasks.getOrPut(id) { Task(id = id, label = name, track = currentSection) }
    }

    private fun parseDuration(rest: String, task: Task) {
        task.durationMs = PlantUmlTemporalSupport.parseDuration(rest.removePrefixIgnoreCase("lasts ").trim())
        LASTS_DAYS.matchEntire(rest)?.let { task.workingDays = it.groupValues[1].toIntOrNull()?.coerceAtLeast(1) }
    }

    private fun parseNote(line: String): IrPatchBatch? {
        val m = NOTE.matchEntire(line) ?: return null
        val taskName = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        if (taskName.isNotEmpty() && text.isNotEmpty()) task(taskName).note = text
        return IrPatchBatch(seq, emptyList())
    }

    private fun bracketName(line: String): String? {
        if (!line.startsWith("[")) return null
        val end = line.indexOf(']')
        if (end <= 1) return null
        return line.substring(1, end).trim()
    }

    private fun parseDependency(line: String): Pair<String, String>? {
        val left = bracketName(line) ?: return null
        val tail = line.substringAfter("]").trim()
        if (!tail.startsWith("->")) return null
        val right = bracketName(tail.removePrefix("->").trim()) ?: return null
        return PlantUmlTemporalSupport.slug(left) to PlantUmlTemporalSupport.slug(right)
    }

    private fun parseClosedLine(line: String): IrPatchBatch? {
        WEEKDAY_CLOSED.matchEntire(line)?.let { m ->
            weekdayIndex(m.groupValues[1])?.let { closedWeekdays += it }
            return IrPatchBatch(seq, emptyList())
        }
        DATE_CLOSED.matchEntire(line)?.let { m ->
            val start = PlantUmlTemporalSupport.parseDate(m.groupValues[1]) ?: return errorBatch("Invalid PlantUML gantt closed date: $line")
            val endRaw = m.groupValues.getOrNull(2).orEmpty()
            val end = if (endRaw.isNotBlank()) PlantUmlTemporalSupport.parseDate(endRaw) ?: return errorBatch("Invalid PlantUML gantt closed date: $line") else start
            closedRanges += TimeRange(min(start, end), max(start, end) + PlantUmlTemporalSupport.MS_PER_DAY)
            return IrPatchBatch(seq, emptyList())
        }
        return null
    }

    private fun inferEnd(start: Long, task: Task): Long =
        task.workingDays
            ?.takeIf { closedWeekdays.isNotEmpty() || closedRanges.isNotEmpty() }
            ?.let { addWorkingDays(start, it) }
            ?: (start + (task.durationMs ?: PlantUmlTemporalSupport.MS_PER_DAY))

    private fun addWorkingDays(start: Long, days: Int): Long {
        var cursor = start
        var remaining = days
        var guard = 0
        while (remaining > 0 && guard < 4096) {
            if (isWorkingDay(cursor)) remaining--
            cursor += PlantUmlTemporalSupport.MS_PER_DAY
            guard++
        }
        return max(cursor, start + 1L)
    }

    private fun isWorkingDay(dayStart: Long): Boolean =
        weekdayIndex(dayStart) !in closedWeekdays && closedRanges.none { dayStart >= it.startMs && dayStart < it.endMs }

    private fun weekdayIndex(dayStartMs: Long): Int {
        val epochDay = floorDiv(dayStartMs, PlantUmlTemporalSupport.MS_PER_DAY)
        return floorMod(epochDay + 3L, 7L).toInt() + 1
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        val remainder = value % divisor
        return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1L else quotient
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val mod = value % divisor
        return if (mod < 0L) mod + divisor else mod
    }

    private fun weekdayIndex(raw: String): Int? =
        when (raw.lowercase().removeSuffix("s")) {
            "monday" -> 1
            "tuesday" -> 2
            "wednesday" -> 3
            "thursday" -> 4
            "friday" -> 5
            "saturday" -> 6
            "sunday" -> 7
            else -> null
        }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E015")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
