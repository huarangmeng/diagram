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
 * - `-- Section --`
 */
@DiagramApi
class PlantUmlGanttParser {
    companion object {
        private val RESOURCE = Regex("""(?:^|\s+)on\s+\{([^}]+)}""", RegexOption.IGNORE_CASE)
        private val COLOR = Regex("""(?:^|\s+)is\s+colored(?:\s+in)?\s+(#[A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
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
        val depends: MutableList<String> = ArrayList(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val tasks: LinkedHashMap<String, Task> = LinkedHashMap()
    private val sectionOrder: MutableList<String> = arrayListOf("default")
    private val resourceOrder: MutableList<String> = ArrayList()
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
                task.durationMs = PlantUmlTemporalSupport.parseDuration(rest.removePrefixIgnoreCase("lasts ").trim())
            rest.startsWith("happens at ", ignoreCase = true) -> {
                val at = PlantUmlTemporalSupport.parseDate(rest.removePrefixIgnoreCase("happens at ").trim())
                task.startMs = at
                task.endMs = at?.plus(PlantUmlTemporalSupport.MS_PER_DAY / 2L)
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
            val end = task.endMs ?: (start + (task.durationMs ?: PlantUmlTemporalSupport.MS_PER_DAY))
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
            styleHints = StyleHints(extras = mapOf("plantuml.timeseries.kind" to "gantt", "gantt.axisFormat" to "%Y-%m-%d")),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun task(name: String): Task {
        val id = PlantUmlTemporalSupport.slug(name)
        return tasks.getOrPut(id) { Task(id = id, label = name, track = currentSection) }
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

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E015")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
