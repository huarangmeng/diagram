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
 * Streaming parser for PlantUML timing diagrams.
 *
 * Supported subset:
 * - track declarations: `clock`, `binary`, `concise`, `robust`
 * - clock period: `clock "Clock" as CLK with period 50`
 * - clock duty cycle/offset: `clock "Clock" as CLK with period 100 duty 25% offset 10`
 * - aliases: `"Label" as ID`
 * - time markers: `@0`, `@+100`, `@1h`, `@2024-01-01`, `@100 : label`
 * - scale: `scale 100` / `scale 100 as 50 pixels`
 * - axis: `hide time-axis`
 * - state assignments: `ID is high`, `ID is "Waiting"`
 * - state display/boundary hints: `ID is Busy : Processing <<dashed>>`
 * - messages: `A -> B : label`
 * - constraints: `@10 <-> @50 : label`
 */
@DiagramApi
class PlantUmlTimingParser {
    private data class Track(
        val id: String,
        val label: String,
        val kind: String,
        val clockPeriodMs: Long? = null,
        val clockDutyPercent: Double? = null,
        val clockOffsetMs: Long? = null,
    )
    private data class ClockOptions(val periodMs: Long, val dutyPercent: Double? = null, val offsetMs: Long? = null)
    private data class Segment(
        val trackId: String,
        val state: String,
        val startMs: Long,
        var endMs: Long? = null,
        val displayText: String? = null,
        val boundaryStyle: String? = null,
    )
    private data class ParsedState(
        val trackId: String,
        val state: String,
        val displayText: String?,
        val boundaryStyle: String?,
    )
    private data class Marker(
        val id: NodeId,
        val label: String,
        val range: TimeRange,
        val trackId: NodeId,
        val payload: Map<String, String>,
    )

    companion object {
        private val SCALE = Regex("""^scale\s+(\S+)(?:\s+as\s+(.+))?$""", RegexOption.IGNORE_CASE)
        private val HIDE_TIME_AXIS = Regex("""^hide\s+time[-\s]axis$""", RegexOption.IGNORE_CASE)
        private val WITH_PERIOD = Regex("""\s+with\s+period\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val BOUNDARY_HINT = Regex("""\s*<<\s*(dashed|thick|none|solid)\s*>>\s*$""", RegexOption.IGNORE_CASE)
        private val MESSAGE = Regex("""^([A-Za-z0-9_.:-]+)\s+[-.]+>\s+([A-Za-z0-9_.:-]+)(?:\s*:\s*(.+))?$""")
        private val CONSTRAINT = Regex("""^(@\S+)\s+<[-.]+>\s+(@\S+)(?:\s*:\s*(.+))?$""")
    }

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val tracks: LinkedHashMap<String, Track> = LinkedHashMap()
    private val openSegments: MutableMap<String, Segment> = LinkedHashMap()
    private val segments: MutableList<Segment> = ArrayList()
    private val markers: MutableList<Marker> = ArrayList()
    private var currentTimeMs = 0L
    private var scaleMs: Long? = null
    private var scaleLabel: String? = null
    private var hideTimeAxis: Boolean = false
    private var seq = 0L
    private var markerSeq = 0

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        parseConstraint(trimmed)?.let {
            markers += it
            return IrPatchBatch(seq, emptyList())
        }
        if (HIDE_TIME_AXIS.matches(trimmed)) {
            hideTimeAxis = true
            return IrPatchBatch(seq, emptyList())
        }
        if (parseScale(trimmed)) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("@")) {
            val markerToken = trimmed.substringBefore(' ')
            val next = parseTimeMarker(markerToken) ?: return errorBatch("Invalid PlantUML timing time marker: $trimmed")
            advanceTime(next)
            val tail = trimmed.substringAfter(' ', "").trim()
            if (tail.startsWith(":")) {
                markers += timeLabelMarker(tail.removePrefix(":").trim().ifBlank { markerToken.removePrefix("@") })
                return IrPatchBatch(seq, emptyList())
            }
            if (tail.isNotEmpty()) return acceptLine(tail)
            return IrPatchBatch(seq, emptyList())
        }
        parseDeclaration(trimmed)?.let {
            tracks[it.id] = it
            return IrPatchBatch(seq, emptyList())
        }
        parseState(trimmed)?.let { parsed ->
            val trackId = parsed.trackId
            if (trackId !in tracks) tracks[trackId] = Track(trackId, trackId, "concise")
            openSegments.remove(trackId)?.let {
                it.endMs = currentTimeMs
                segments += it
            }
            openSegments[trackId] = Segment(trackId, parsed.state, currentTimeMs, displayText = parsed.displayText, boundaryStyle = parsed.boundaryStyle)
            return IrPatchBatch(seq, emptyList())
        }
        parseMessage(trimmed)?.let {
            markers += it
            return IrPatchBatch(seq, emptyList())
        }
        return errorBatch("Invalid PlantUML timing line: $trimmed")
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (!blockClosed) {
            val d = Diagnostic(Severity.ERROR, "Missing @enduml closing delimiter for timing diagram", "PLANTUML-E016")
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): TimeSeriesIR {
        val explicitSegments = segments + openSegments.values.map { it.copy(endMs = max(currentTimeMs + 1L, it.startMs + 1L)) }
        val orderedTracks = tracks.values.toList()
        val allSegments = explicitSegments + autoClockSegments(explicitSegments)
        val items = ArrayList<TimeItem>()
        var minMs = Long.MAX_VALUE
        var maxMs = Long.MIN_VALUE
        for ((idx, segment) in allSegments.withIndex()) {
            val end = segment.endMs ?: (segment.startMs + 1L)
            minMs = min(minMs, segment.startMs)
            maxMs = max(maxMs, end)
            items += TimeItem(
                id = NodeId("timing:${segment.trackId}:$idx"),
                label = RichLabel.Plain(segment.displayText ?: segment.state),
                range = TimeRange(segment.startMs, max(end, segment.startMs)),
                trackId = NodeId("timing:track:${segment.trackId}"),
                payload = buildMap {
                    put("timing.state", segment.state)
                    segment.displayText?.let { put("timing.text", it) }
                    segment.boundaryStyle?.let { put("timing.boundary", it) }
                    put("timing.trackKind", tracks[segment.trackId]?.kind ?: "concise")
                    tracks[segment.trackId]?.clockPeriodMs?.let { put("timing.clockPeriodMs", it.toString()) }
                    tracks[segment.trackId]?.clockDutyPercent?.let { put("timing.clockDutyPercent", it.toString()) }
                    tracks[segment.trackId]?.clockOffsetMs?.let { put("timing.clockOffsetMs", it.toString()) }
                },
            )
        }
        for (marker in markers) {
            minMs = min(minMs, marker.range.startMs)
            maxMs = max(maxMs, marker.range.endMs)
            items += TimeItem(
                id = marker.id,
                label = RichLabel.Plain(marker.label),
                range = marker.range,
                trackId = marker.trackId,
                payload = marker.payload,
            )
        }
        if (minMs == Long.MAX_VALUE) {
            minMs = 0L
            maxMs = max(currentTimeMs, 1L)
        }
        val extraTracks = markers.mapNotNull { marker ->
            when (marker.payload["timing.track"]) {
                "constraints" -> TimeTrack(NodeId("timing:track:constraints"), RichLabel.Plain("Constraints"))
                "events" -> TimeTrack(NodeId("timing:track:events"), RichLabel.Plain("Events"))
                else -> null
            }
        }.distinctBy { it.id }
        return TimeSeriesIR(
            tracks = orderedTracks.map { TimeTrack(NodeId("timing:track:${it.id}"), RichLabel.Plain(it.label)) } + extraTracks,
            items = items,
            range = TimeRange(minMs, max(maxMs, minMs + 1L)),
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(
                extras = buildMap {
                    put("plantuml.timeseries.kind", "timing")
                    put("gantt.axisFormat", "%s")
                    put("gantt.displayMode", "compact")
                    if (hideTimeAxis) put("timing.hideAxis", "true")
                    scaleMs?.let { put("timing.scaleMs", it.toString()) }
                    scaleLabel?.let { put("timing.scaleLabel", it) }
                },
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun advanceTime(next: Long) {
        if (next < currentTimeMs) return
        currentTimeMs = next
    }

    private fun parseTimeMarker(raw: String): Long? {
        val token = raw.trim().removePrefix("@")
        if (token.startsWith("+")) {
            val delta = PlantUmlTemporalSupport.parseOffset(token.drop(1)) ?: PlantUmlTemporalSupport.parseDuration(token.drop(1)) ?: return null
            return currentTimeMs + delta
        }
        return PlantUmlTemporalSupport.parseOffset(raw)
    }

    private fun parseScale(line: String): Boolean {
        val m = SCALE.matchEntire(line) ?: return false
        val parsed = PlantUmlTemporalSupport.parseOffset(m.groupValues[1]) ?: PlantUmlTemporalSupport.parseDuration(m.groupValues[1]) ?: return false
        scaleMs = parsed.coerceAtLeast(1L)
        scaleLabel = m.groupValues[2].trim().ifBlank { null }
        return true
    }

    private fun parseDeclaration(line: String): Track? {
        val lower = line.lowercase()
        val kind = listOf("clock", "binary", "concise", "robust").firstOrNull { lower.startsWith("$it ") } ?: return null
        var rest = line.substring(kind.length).trim()
        var clockOptions: ClockOptions? = null
        if (kind == "clock") {
            WITH_PERIOD.find(rest)?.let { match ->
                rest = rest.removeRange(match.range).trim()
                clockOptions = parseClockOptions(match.groupValues[1])
            }
        }
        val aliasSplit = rest.split(Regex("\\s+as\\s+", RegexOption.IGNORE_CASE), limit = 2)
        val label = aliasSplit[0].trim().removeSurrounding("\"")
        val id = aliasSplit.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: PlantUmlTemporalSupport.slug(label)
        return Track(
            id = id,
            label = label.ifBlank { id },
            kind = kind,
            clockPeriodMs = clockOptions?.periodMs?.coerceAtLeast(1L),
            clockDutyPercent = clockOptions?.dutyPercent,
            clockOffsetMs = clockOptions?.offsetMs,
        )
    }

    private fun autoClockSegments(explicitSegments: List<Segment>): List<Segment> {
        val explicitTrackIds = explicitSegments.map { it.trackId }.toSet()
        val markerMax = markers.maxOfOrNull { it.range.endMs } ?: 0L
        return tracks.values
            .filter { it.kind == "clock" && it.id !in explicitTrackIds }
            .flatMap { track ->
                val period = track.clockPeriodMs ?: scaleMs ?: 1L
                val lowHigh = clockDurations(period, track.clockDutyPercent)
                val cycle = lowHigh.first + lowHigh.second
                val offset = track.clockOffsetMs?.coerceAtLeast(0L) ?: 0L
                val end = max(max(currentTimeMs, markerMax), offset + cycle * 2L).coerceAtLeast(cycle)
                val out = ArrayList<Segment>()
                var cursor = 0L
                var high = false
                if (offset > 0L) {
                    out += Segment(track.id, "low", 0L, min(offset, end))
                    cursor = min(offset, end)
                    high = true
                }
                while (cursor < end && out.size < 1024) {
                    val duration = if (high) lowHigh.second else lowHigh.first
                    val next = min(cursor + duration, end)
                    out += Segment(track.id, if (high) "high" else "low", cursor, next)
                    cursor = next
                    high = !high
                }
                out
            }
    }

    private fun clockDurations(period: Long, dutyPercent: Double?): Pair<Long, Long> {
        val cycle = if (dutyPercent == null) period * 2L else period
        val high = ((cycle * ((dutyPercent ?: 50.0) / 100.0)).toLong()).coerceIn(1L, cycle - 1L)
        return (cycle - high) to high
    }

    private fun parseDutyPercent(rawDuty: String, rawPulse: String, periodMs: Long?): Double? {
        rawDuty.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { return it.coerceIn(1.0, 99.0) }
        val pulse = rawPulse.trim().takeIf { it.isNotEmpty() } ?: return null
        val pulseMs = PlantUmlTemporalSupport.parseOffset(pulse) ?: PlantUmlTemporalSupport.parseDuration(pulse) ?: return null
        val period = periodMs?.coerceAtLeast(1L) ?: return null
        return ((pulseMs.toDouble() / period.toDouble()) * 100.0).coerceIn(1.0, 99.0)
    }

    private fun parseClockOptions(raw: String): ClockOptions? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val period = PlantUmlTemporalSupport.parseOffset(tokens[0]) ?: PlantUmlTemporalSupport.parseDuration(tokens[0]) ?: return null
        var duty: Double? = null
        var offset: Long? = null
        var i = 1
        while (i < tokens.size) {
            when (tokens[i].lowercase()) {
                "duty" -> {
                    duty = tokens.getOrNull(i + 1)?.removeSuffix("%")?.toDoubleOrNull()?.coerceIn(1.0, 99.0)
                    i += 2
                }
                "pulse" -> {
                    val pulse = tokens.getOrNull(i + 1)?.let { PlantUmlTemporalSupport.parseOffset(it) ?: PlantUmlTemporalSupport.parseDuration(it) }
                    duty = pulse?.let { ((it.toDouble() / period.toDouble()) * 100.0).coerceIn(1.0, 99.0) }
                    i += 2
                }
                "offset" -> {
                    offset = tokens.getOrNull(i + 1)?.let { PlantUmlTemporalSupport.parseOffset(it) ?: PlantUmlTemporalSupport.parseDuration(it) }?.coerceAtLeast(0L)
                    i += 2
                }
                else -> i++
            }
        }
        return ClockOptions(period, duty, offset)
    }

    private fun parseState(line: String): ParsedState? {
        val split = line.split(Regex("\\s+is\\s+", RegexOption.IGNORE_CASE), limit = 2)
        if (split.size != 2) return null
        val id = split[0].trim()
        if (id.isEmpty()) return null
        var rhs = split[1].trim()
        val boundary = BOUNDARY_HINT.find(rhs)?.let {
            rhs = rhs.removeRange(it.range).trim()
            it.groupValues[1].lowercase()
        }
        val display = rhs.substringAfter(":", missingDelimiterValue = "").trim().removeSurrounding("\"").ifBlank { null }
        val rawState = rhs.substringBefore(":").trim().removeSurrounding("\"").ifBlank { "state" }
        return ParsedState(id, rawState, display, boundary)
    }

    private fun parseMessage(line: String): Marker? {
        val m = MESSAGE.matchEntire(line) ?: return null
        val from = m.groupValues[1].trim()
        val to = m.groupValues[2].trim()
        val label = m.groupValues[3].trim().ifBlank { "$from -> $to" }
        if (from !in tracks) tracks[from] = Track(from, from, "concise")
        if (to !in tracks) tracks[to] = Track(to, to, "concise")
        val start = currentTimeMs
        val end = max(start + 1L, start)
        markerSeq++
        return Marker(
            id = NodeId("timing:message:$markerSeq"),
            label = label,
            range = TimeRange(start, end),
            trackId = NodeId("timing:track:$from"),
            payload = mapOf(
                "timing.kind" to "message",
                "timing.from" to from,
                "timing.to" to to,
            ),
        )
    }

    private fun timeLabelMarker(label: String): Marker {
        markerSeq++
        return Marker(
            id = NodeId("timing:time-label:$markerSeq"),
            label = label,
            range = TimeRange(currentTimeMs, currentTimeMs + 1L),
            trackId = NodeId("timing:track:events"),
            payload = mapOf(
                "timing.kind" to "timeLabel",
                "timing.track" to "events",
            ),
        )
    }

    private fun parseConstraint(line: String): Marker? {
        val m = CONSTRAINT.matchEntire(line) ?: return null
        val start = PlantUmlTemporalSupport.parseOffset(m.groupValues[1]) ?: return null
        val end = PlantUmlTemporalSupport.parseOffset(m.groupValues[2]) ?: return null
        val label = m.groupValues[3].trim().ifBlank { "constraint" }
        markerSeq++
        return Marker(
            id = NodeId("timing:constraint:$markerSeq"),
            label = label,
            range = TimeRange(min(start, end), max(start, end)),
            trackId = NodeId("timing:track:constraints"),
            payload = mapOf(
                "timing.kind" to "constraint",
                "timing.track" to "constraints",
            ),
        )
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E016")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
