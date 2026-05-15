package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.parser.plantuml.PlantUmlStructParser
import com.hrm.diagram.render.cache.DrawCommandStore
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import com.hrm.diagram.render.streaming.SessionPipeline

/**
 * Streaming PlantUML dispatcher for the Phase-4 MVP.
 *
 * Current scope:
 * - `sequence` sub-pipeline
 * - `class` sub-pipeline
 * - `@startuml ... @enduml`
 * - `skinparam` warning passthrough
 *
 * Unsupported PlantUML diagram families keep yielding diagnostics instead of throwing.
 */
internal class PlantUmlSessionPipeline(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : SessionPipeline {
    companion object {
        private val ANCHORED_NOTE = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )
        private val ANCHORED_NOTE_BLOCK = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val WBS_PREFIX_CUE = Regex("""^(?:\*+|[+\-]+)[<>]?_?(?:\s+|:).+""")
    }

    private val diagnosticsAll: MutableList<Diagnostic> = ArrayList()
    private val drawStore = DrawCommandStore()

    private var rawPending: String = ""
    private var blockStarted: Boolean = false
    private var blockClosed: Boolean = false
    private val bufferedBodyLines: MutableList<String> = ArrayList()
    private val bufferedSkinparamLines: MutableList<String> = ArrayList()
    private var bufferingSkinparamBlock: Boolean = false
    private var bufferingStyleBlock: Boolean = false
    private var ignoredSkinparamBlock: Boolean = false
    private var subPipeline: PlantUmlSubPipeline? = null
    private var closingDirective: String = "@enduml"

    private enum class DiagramKind { Sequence, Class, State, Component, Usecase, Activity, Object, Deployment, Erd, Mindmap, Wbs, Json, Yaml, Network, Gantt, Timing, Salt, Archimate, C4, Ditaa, Pie, BarChart, LineChart, ScatterChart }

    override fun advance(
        previousSnapshot: DiagramSnapshot,
        chunk: CharSequence,
        absoluteOffset: Int,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val merged = rawPending + chunk.toString()
        val lines = ArrayList<String>()
        var start = 0
        for (i in merged.indices) {
            if (merged[i] == '\n') {
                lines += merged.substring(start, i).trimEnd('\r')
                start = i + 1
            }
        }
        rawPending = if (start < merged.length) merged.substring(start) else ""
        if (isFinal && rawPending.isNotEmpty()) {
            lines += rawPending.trimEnd('\r')
            rawPending = ""
        }

        val newPatches = ArrayList<IrPatch>()
        for (line in lines) {
            processLine(line, newPatches)
        }
        if (isFinal) {
            materializeDeferredBodyIfNeeded(newPatches)
            subPipeline?.let { newPatches += it.finish(blockClosed).patches }
        }
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }
        if (newDiagnostics.isNotEmpty()) diagnosticsAll += newDiagnostics

        val rendered = subPipeline?.render(previousSnapshot, seq, isFinal)
        return if (rendered != null) {
            val drawDelta = drawStore.updateFullFrame(rendered.drawCommands)
            PipelineAdvance(
                snapshot = DiagramSnapshot(
                    ir = rendered.ir,
                    laidOut = rendered.laidOut,
                    drawCommands = drawDelta.fullFrame,
                    diagnostics = diagnosticsAll + rendered.diagnostics,
                    seq = seq,
                    isFinal = isFinal,
                    sourceLanguage = previousSnapshot.sourceLanguage,
                ),
                patch = SessionPatch(
                    seq = seq,
                    addedNodes = emptyList(),
                    addedEdges = emptyList(),
                    addedDrawCommands = drawDelta.addedCommands,
                    newDiagnostics = newDiagnostics,
                    isFinal = isFinal,
                ),
                irBatch = IrPatchBatch(seq, newPatches),
            )
        } else {
            PipelineAdvance(
                snapshot = previousSnapshot.copy(seq = seq, isFinal = isFinal),
                patch = SessionPatch.empty(seq, isFinal),
                irBatch = IrPatchBatch(seq, newPatches),
            )
        }
    }

    private fun processLine(line: String, out: MutableList<IrPatch>) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return

        if (!blockStarted) {
            if (trimmed.equals("@startuml", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@enduml"
            } else if (trimmed.equals("@startmindmap", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endmindmap"
                attachSubPipeline(DiagramKind.Mindmap, out)
            } else if (trimmed.equals("@startwbs", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endwbs"
                attachSubPipeline(DiagramKind.Wbs, out)
            } else if (trimmed.equals("@startjson", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endjson"
                attachSubPipeline(DiagramKind.Json, out)
            } else if (trimmed.equals("@startyaml", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endyaml"
                attachSubPipeline(DiagramKind.Yaml, out)
            } else if (trimmed.equals("@startnwdiag", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endnwdiag"
                attachSubPipeline(DiagramKind.Network, out)
            } else if (trimmed.equals("@startgantt", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endgantt"
                attachSubPipeline(DiagramKind.Gantt, out)
            } else if (trimmed.equals("@startsalt", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endsalt"
                attachSubPipeline(DiagramKind.Salt, out)
            } else if (trimmed.equals("@startditaa", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endditaa"
                attachSubPipeline(DiagramKind.Ditaa, out)
            } else if (trimmed.equals("@startpie", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endpie"
                attachSubPipeline(DiagramKind.Pie, out)
            } else if (trimmed.equals("@startbar", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endbar"
                attachSubPipeline(DiagramKind.BarChart, out)
            } else if (trimmed.equals("@startline", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endline"
                attachSubPipeline(DiagramKind.LineChart, out)
            } else if (trimmed.equals("@startscatter", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endscatter"
                attachSubPipeline(DiagramKind.ScatterChart, out)
            } else if (trimmed.equals("@startchart", ignoreCase = true)) {
                blockStarted = true
                closingDirective = "@endchart"
                attachSubPipeline(DiagramKind.BarChart, out)
            }
            return
        }
        if (blockClosed) return

        if (trimmed.equals(closingDirective, ignoreCase = true)) {
            blockClosed = true
            return
        }
        if (bufferingSkinparamBlock) {
            bufferedSkinparamLines += trimmed
            if (trimmed == "}") bufferingSkinparamBlock = false
            return
        }
        if (ignoredSkinparamBlock) {
            if (trimmed == "}") ignoredSkinparamBlock = false
            return
        }
        if (bufferingStyleBlock) {
            val chosen = subPipeline
            if (chosen != null) {
                out += chosen.acceptLine(trimmed).patches
            } else {
                bufferedBodyLines += trimmed
            }
            if (trimmed.equals("</style>", ignoreCase = true)) bufferingStyleBlock = false
            return
        }
        if (trimmed.equals("<style>", ignoreCase = true) || trimmed.startsWith("<style ", ignoreCase = true)) {
            bufferingStyleBlock = true
            val chosen = subPipeline
            if (chosen != null) {
                out += chosen.acceptLine(trimmed).patches
            } else {
                bufferedBodyLines += trimmed
            }
            return
        }
        if (trimmed.startsWith("skinparam", ignoreCase = true)) {
            val chosen = subPipeline
            if (
                chosen is PlantUmlSequenceSubPipeline ||
                chosen is PlantUmlActivitySubPipeline ||
                chosen is PlantUmlUsecaseSubPipeline ||
                chosen is PlantUmlStateSubPipeline ||
                chosen is PlantUmlClassSubPipeline ||
                chosen is PlantUmlComponentSubPipeline ||
                chosen is PlantUmlDeploymentSubPipeline ||
                chosen is PlantUmlObjectSubPipeline ||
                chosen is PlantUmlPieSubPipeline ||
                chosen is PlantUmlXYChartSubPipeline ||
                (chosen is PlantUmlDitaaSubPipeline && trimmed.startsWith("skinparam handwritten", ignoreCase = true))
            ) {
                out += chosen.acceptLine(trimmed).patches
            } else if (chosen != null) {
                out += ignoredSkinparamWarning()
                if (trimmed.endsWith("{")) ignoredSkinparamBlock = true
            } else {
                bufferedSkinparamLines += trimmed
                if (trimmed.endsWith("{")) bufferingSkinparamBlock = true
            }
            return
        }

        val chosen = subPipeline
        if (chosen != null) {
            val payloadLine = if (chosen is PlantUmlStructSubPipeline || chosen is PlantUmlDitaaSubPipeline) line else trimmed
            out += chosen.acceptLine(payloadLine).patches
            return
        }

        bufferedBodyLines += trimmed
        val kind = classifyImmediate(trimmed)
        if (kind != null && !shouldDeferImmediate(kind, trimmed)) {
            attachSubPipeline(kind, out)
        }
    }

    private fun materializeDeferredBodyIfNeeded(out: MutableList<IrPatch>) {
        if (subPipeline != null || bufferedBodyLines.isEmpty()) return
        val kind = detectBufferedKind() ?: DiagramKind.Sequence
        attachSubPipeline(kind, out)
    }

    private fun attachSubPipeline(kind: DiagramKind, out: MutableList<IrPatch>) {
        if (subPipeline != null) return
        subPipeline = when (kind) {
            DiagramKind.Sequence -> PlantUmlSequenceSubPipeline(textMeasurer)
            DiagramKind.Class -> PlantUmlClassSubPipeline(textMeasurer)
            DiagramKind.State -> PlantUmlStateSubPipeline(textMeasurer)
            DiagramKind.Component -> PlantUmlComponentSubPipeline(textMeasurer)
            DiagramKind.Usecase -> PlantUmlUsecaseSubPipeline(textMeasurer)
            DiagramKind.Activity -> PlantUmlActivitySubPipeline(textMeasurer)
            DiagramKind.Object -> PlantUmlObjectSubPipeline(textMeasurer)
            DiagramKind.Deployment -> PlantUmlDeploymentSubPipeline(textMeasurer)
            DiagramKind.Erd -> PlantUmlErdSubPipeline(textMeasurer)
            DiagramKind.Mindmap -> PlantUmlMindmapSubPipeline(textMeasurer)
            DiagramKind.Wbs -> PlantUmlWbsSubPipeline(textMeasurer)
            DiagramKind.Json -> PlantUmlStructSubPipeline(PlantUmlStructParser.Format.JSON, textMeasurer)
            DiagramKind.Yaml -> PlantUmlStructSubPipeline(PlantUmlStructParser.Format.YAML, textMeasurer)
            DiagramKind.Network -> PlantUmlNetworkSubPipeline(textMeasurer)
            DiagramKind.Gantt -> PlantUmlTimeSeriesSubPipeline(PlantUmlTimeSeriesSubPipeline.Kind.Gantt, textMeasurer)
            DiagramKind.Timing -> PlantUmlTimeSeriesSubPipeline(PlantUmlTimeSeriesSubPipeline.Kind.Timing, textMeasurer)
            DiagramKind.Salt -> PlantUmlSaltSubPipeline(textMeasurer)
            DiagramKind.Archimate -> PlantUmlArchimateSubPipeline(textMeasurer)
            DiagramKind.C4 -> PlantUmlC4SubPipeline(textMeasurer)
            DiagramKind.Ditaa -> PlantUmlDitaaSubPipeline(textMeasurer)
            DiagramKind.Pie -> PlantUmlPieSubPipeline(textMeasurer)
            DiagramKind.BarChart -> PlantUmlXYChartSubPipeline(SeriesKind.Bar, textMeasurer)
            DiagramKind.LineChart -> PlantUmlXYChartSubPipeline(SeriesKind.Line, textMeasurer)
            DiagramKind.ScatterChart -> PlantUmlXYChartSubPipeline(SeriesKind.Scatter, textMeasurer)
        }
        if (bufferedSkinparamLines.isNotEmpty()) {
            if (
                kind == DiagramKind.Sequence ||
                kind == DiagramKind.Activity ||
                kind == DiagramKind.Usecase ||
                kind == DiagramKind.State ||
                kind == DiagramKind.Class ||
                kind == DiagramKind.Component ||
                kind == DiagramKind.Deployment ||
                kind == DiagramKind.Object ||
                kind == DiagramKind.Pie ||
                kind == DiagramKind.BarChart ||
                kind == DiagramKind.LineChart ||
                kind == DiagramKind.ScatterChart
            ) {
                for (line in bufferedSkinparamLines) {
                    out += subPipeline!!.acceptLine(line).patches
                }
            } else if (kind == DiagramKind.Ditaa) {
                for (line in bufferedSkinparamLines) {
                    if (line.startsWith("skinparam handwritten", ignoreCase = true)) {
                        out += subPipeline!!.acceptLine(line).patches
                    } else {
                        out += ignoredSkinparamWarning()
                    }
                }
            } else {
                repeat(bufferedSkinparamLines.size) { out += ignoredSkinparamWarning() }
            }
            bufferedSkinparamLines.clear()
        }
        val pending = bufferedBodyLines.toList()
        bufferedBodyLines.clear()
        for (line in pending) {
            out += subPipeline!!.acceptLine(line).patches
        }
    }

    private fun classifyImmediate(line: String): DiagramKind? {
        val lower = line.lowercase()
        if (lower == "nwdiag {" || lower == "nwdiag{") {
            return DiagramKind.Network
        }
        if (lower == "salt" || lower == "salt {" || lower == "salt{") {
            return DiagramKind.Salt
        }
        if (isWbsCue(line)) {
            return DiagramKind.Wbs
        }
        if (lower == "pie") {
            return DiagramKind.Pie
        }
        if (lower == "bar") {
            return DiagramKind.BarChart
        }
        if (lower == "line") {
            return DiagramKind.LineChart
        }
        if (lower == "scatter") {
            return DiagramKind.ScatterChart
        }
        if (lower == "chart") {
            return DiagramKind.BarChart
        }
        if (lower.startsWith("bar ") || lower.startsWith("h-axis ") || lower.startsWith("v-axis ")) {
            return DiagramKind.BarChart
        }
        if (lower.startsWith("line ")) {
            return DiagramKind.LineChart
        }
        if (lower.startsWith("scatter ")) {
            return DiagramKind.ScatterChart
        }
        if (isC4Cue(line)) {
            return DiagramKind.C4
        }
        if (lower.startsWith("archimate ") || Regex("""^Rel(?:_[A-Za-z0-9_]+)?\(""").containsMatchIn(line)) {
            return DiagramKind.Archimate
        }
        if (lower.startsWith("project starts") || Regex("""^\[[^\]]+\]\s+(starts|lasts|ends|happens)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            return DiagramKind.Gantt
        }
        if (isTimingCue(line)) {
            return DiagramKind.Timing
        }
        if (
            lower.startsWith("object ") ||
            (line.contains(':') && line.contains('=') && !line.startsWith(":") && !line.contains("->") && !line.contains("<-"))
        ) {
            return DiagramKind.Object
        }
        if (
            lower.startsWith("entity ") ||
            Regex("""^[A-Za-z0-9_.:-]+\s+[|}{o.\-]+\s+[A-Za-z0-9_.:-]+(?:\s*:\s*.+)?$""").matches(line)
        ) {
            return DiagramKind.Erd
        }
        if (lower.startsWith("artifact ")) {
            return DiagramKind.Deployment
        }
        if (lower.startsWith("storage ")) {
            return DiagramKind.Deployment
        }
        if (
            lower == "start" ||
            lower == "stop" ||
            lower == "end" ||
            lower == "(*)" ||
            lower == "(*top)" ||
            lower == "}" ||
            lower.startsWith("partition ") ||
            line.trim().startsWith("===") ||
            line.startsWith("#") ||
            (line.startsWith(":") && line.endsWith(";")) ||
            lower.startsWith("if ") ||
            lower.startsWith("if(") ||
            lower.startsWith("else") ||
            lower == "endif" ||
            lower.startsWith("while ") ||
            lower.startsWith("while(") ||
            lower == "endwhile" ||
            lower == "end note" ||
            lower == "endnote" ||
            lower.startsWith("note:") ||
            (lower.startsWith("note ") && !ANCHORED_NOTE.matches(line) && !ANCHORED_NOTE_BLOCK.matches(line) && !line.contains(" of ")) ||
            isLegacyActivityArrowCue(line)
        ) {
            return DiagramKind.Activity
        }
        if (
            lower.startsWith("usecase ") ||
            lower.startsWith("rectangle ") ||
            (line.startsWith("(") && line.contains(")")) ||
            (line.startsWith(":") && (line.endsWith(":") || line.endsWith(":/") || Regex("^:[^:]+:\\s+as\\s+[A-Za-z0-9_.:-]+$").matches(line) || Regex("^:[^:]+:/\\s+as\\s+[A-Za-z0-9_.:-]+$").matches(line))) ||
            lower.startsWith("actor/") ||
            (
                line.contains("(") &&
                    line.contains(")") &&
                    (line.contains("--") || line.contains("..") || line.contains(".>") || line.contains("<."))
                )
        ) {
            return DiagramKind.Usecase
        }
        if (
            lower.startsWith("component ") ||
            lower.startsWith("()") ||
            (
                line.startsWith("[") &&
                    line.contains("]") &&
                    !line.contains("[*]") &&
                    !line.contains("[H]") &&
                    !line.contains("[H*]")
                ) ||
            lower.startsWith("database ") ||
            lower.startsWith("queue ") ||
            lower.startsWith("frame ") ||
            lower.startsWith("port ") ||
            lower.startsWith("portin ") ||
            lower.startsWith("portout ")
        ) {
            return DiagramKind.Component
        }
        if (
            lower.startsWith("state ") ||
            line.contains("[*]") ||
            line.contains("[H]") ||
            line.contains("[H*]") ||
            line == "--"
        ) {
            return DiagramKind.State
        }
        if (isWbsCue(line)) {
            return DiagramKind.Wbs
        }
        if (
            lower.startsWith("class ") ||
            lower.startsWith("abstract class ") ||
            lower.startsWith("interface ") ||
            lower.startsWith("enum ") ||
            line.contains("<|--") ||
            line.contains("<|..") ||
            line.contains("..|>") ||
            line.contains("*--") ||
            line.contains("o--")
        ) {
            return DiagramKind.Class
        }
        if (
            lower.startsWith("participant ") ||
            lower.startsWith("boundary ") ||
            lower.startsWith("control ") ||
            lower.startsWith("entity ") ||
            lower.startsWith("database ") ||
            lower.startsWith("collections ") ||
            lower.startsWith("queue ") ||
            lower.startsWith("activate ") ||
            lower.startsWith("deactivate ") ||
            lower.startsWith("autonumber") ||
            lower.startsWith("create ") ||
            lower.startsWith("destroy ") ||
            lower.startsWith("ref ") ||
            lower.startsWith("box") ||
            lower == "end box" ||
            lower == "endbox" ||
            lower.startsWith("return") ||
            lower.startsWith("loop ") ||
            lower.startsWith("alt ") ||
            lower.startsWith("opt ") ||
            lower.startsWith("par ") ||
            lower.startsWith("critical ") ||
            lower.startsWith("break ") ||
            lower == "end" ||
            line.contains("->>") ||
            line.contains("<<-") ||
            line.contains("-> ") ||
            line.contains("->:") ||
            line.contains(" <-")
        ) {
            return DiagramKind.Sequence
        }
        return null
    }

    private fun shouldDeferImmediate(kind: DiagramKind, line: String): Boolean {
        val lower = line.lowercase()
        val sawAmbiguousContainerCue = bufferedBodyLines.any {
            val candidate = it.lowercase()
            candidate.startsWith("node ") || candidate.startsWith("cloud ")
        }
        if (!sawAmbiguousContainerCue) return false
        return when (kind) {
            DiagramKind.Component ->
                lower.startsWith("database ") ||
                    line.startsWith("[") ||
                    lower.startsWith("queue ") ||
                    lower.startsWith("frame ")
            DiagramKind.Sequence -> true
            DiagramKind.Activity -> line == "}"
            else -> false
        }
    }

    private fun detectBufferedKind(): DiagramKind? {
        var sawStateCue = false
        var sawClassCue = false
        var sawSequenceCue = false
        var sawComponentCue = false
        var sawExplicitComponentCue = false
        var sawUsecaseCue = false
        var sawPackageCue = false
        var sawActorCue = false
        var sawActivityCue = false
        var sawObjectCue = false
        var sawDeploymentCue = false
        var sawErdCue = false
        var sawNetworkCue = false
        var sawGanttCue = false
        var sawTimingCue = false
        var sawSaltCue = false
        var sawArchimateCue = false
        var sawC4Cue = false
        var sawWbsCue = false
        var sawPieCue = false
        var sawBarCue = false
        var sawLineCue = false
        var sawScatterCue = false
        var sawAmbiguousContainerCue = false
        var sawBracketArtifactCue = false
        for (line in bufferedBodyLines) {
            val lower = line.lowercase()
            if (lower == "nwdiag {" || lower == "nwdiag{") {
                sawNetworkCue = true
            }
            if (lower == "salt" || lower == "salt {" || lower == "salt{") {
                sawSaltCue = true
            }
            if (isWbsCue(line)) {
                sawWbsCue = true
            }
            if (lower == "pie") {
                sawPieCue = true
            }
            if (lower == "bar") {
                sawBarCue = true
            }
            if (lower == "line") {
                sawLineCue = true
            }
            if (lower == "scatter") {
                sawScatterCue = true
            }
            if (isC4Cue(line)) {
                sawC4Cue = true
            }
            if (lower.startsWith("archimate ") || Regex("""^Rel(?:_[A-Za-z0-9_]+)?\(""").containsMatchIn(line)) {
                sawArchimateCue = true
            }
            if (lower.startsWith("project starts") || Regex("""^\[[^\]]+\]\s+(starts|lasts|ends|happens)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                sawGanttCue = true
            }
            if (isTimingCue(line)) {
                sawTimingCue = true
            }
            if (
                lower.startsWith("object ") ||
                (line.contains(':') && line.contains('=') && !line.startsWith(":") && !line.contains("->") && !line.contains("<-"))
            ) {
                sawObjectCue = true
            }
            if (
                lower.startsWith("entity ") ||
                Regex("""^[A-Za-z0-9_.:-]+\s+[|}{o.\-]+\s+[A-Za-z0-9_.:-]+(?:\s*:\s*.+)?$""").matches(line)
            ) {
                sawErdCue = true
            }
            if (line.startsWith("[") && line.contains("]")) {
                sawBracketArtifactCue = true
            }
            if (
                lower.startsWith("artifact ") ||
                lower.startsWith("database ") ||
                lower.startsWith("frame ") ||
                lower.startsWith("storage ")
            ) {
                sawDeploymentCue = true
            }
            if (
                lower.startsWith("node ") ||
                lower.startsWith("cloud ")
            ) {
                sawAmbiguousContainerCue = true
            }
            if (
                lower == "start" ||
                lower == "stop" ||
                lower == "end" ||
                lower == "(*)" ||
                lower == "(*top)" ||
                lower == "}" ||
                lower.startsWith("partition ") ||
                line.trim().startsWith("===") ||
                line.startsWith("#") ||
                (line.startsWith(":") && line.endsWith(";")) ||
                lower.startsWith("if ") ||
                lower.startsWith("if(") ||
                lower.startsWith("else") ||
                lower == "endif" ||
                lower.startsWith("while ") ||
                lower.startsWith("while(") ||
                lower == "endwhile" ||
                lower == "end note" ||
                lower == "endnote" ||
                lower.startsWith("note:") ||
                (lower.startsWith("note ") && !ANCHORED_NOTE.matches(line) && !ANCHORED_NOTE_BLOCK.matches(line) && !line.contains(" of ")) ||
                isLegacyActivityArrowCue(line)
            ) {
                sawActivityCue = true
            }
            if (lower.startsWith("actor ") || lower.startsWith("actor/")) {
                sawActorCue = true
            }
            if (lower.startsWith("package ")) {
                sawPackageCue = true
            }
            if (
                lower.startsWith("usecase ") ||
                lower.startsWith("rectangle ") ||
                (line.startsWith("(") && line.contains(")")) ||
                (line.startsWith(":") && (line.endsWith(":") || line.endsWith(":/") || Regex("^:[^:]+:\\s+as\\s+[A-Za-z0-9_.:-]+$").matches(line) || Regex("^:[^:]+:/\\s+as\\s+[A-Za-z0-9_.:-]+$").matches(line))) ||
                (
                    line.contains("(") &&
                        line.contains(")") &&
                        (line.contains("--") || line.contains("..") || line.contains(".>") || line.contains("<."))
                    )
            ) {
                sawUsecaseCue = true
            }
            if (
                lower.startsWith("component ") ||
                lower.startsWith("()") ||
                lower.startsWith("port ") ||
                lower.startsWith("portin ") ||
                lower.startsWith("portout ")
            ) {
                sawExplicitComponentCue = true
            }
            if (
                lower.startsWith("component ") ||
                lower.startsWith("()") ||
                lower.startsWith("database ") ||
                lower.startsWith("queue ") ||
                lower.startsWith("frame ") ||
                lower.startsWith("port ") ||
                lower.startsWith("portin ") ||
                lower.startsWith("portout ")
            ) {
                sawComponentCue = true
            }
            if (
                lower.startsWith("state ") ||
                trimmedEqualsDirection(lower) ||
                line.contains("[*]") ||
                line.contains("[H]") ||
                line.contains("[H*]") ||
                line == "--"
            ) {
                sawStateCue = true
            }
            if (
                lower.startsWith("class ") ||
                lower.startsWith("abstract class ") ||
                lower.startsWith("interface ") ||
                lower.startsWith("enum ") ||
                line.contains("<|--") ||
                line.contains("--|>") ||
                line.contains("<|..") ||
                line.contains("..|>") ||
                line.contains("*--") ||
                line.contains("o--") ||
                line.contains("..>") ||
                line.contains(" -- ") ||
                line.contains(" .. ")
            ) {
                sawClassCue = true
            }
            if (
                lower.startsWith("participant ") ||
                lower.startsWith("boundary ") ||
                lower.startsWith("control ") ||
                lower.startsWith("database ") ||
                lower.startsWith("collections ") ||
                lower.startsWith("queue ") ||
                lower.startsWith("activate ") ||
                lower.startsWith("deactivate ") ||
                lower.startsWith("autonumber") ||
                lower.startsWith("create ") ||
                lower.startsWith("destroy ") ||
                lower.startsWith("ref ") ||
                lower.startsWith("box") ||
                lower == "end box" ||
                lower == "endbox" ||
                lower.startsWith("return") ||
                lower.startsWith("loop ") ||
                lower.startsWith("alt ") ||
                lower.startsWith("opt ") ||
                lower.startsWith("par ") ||
                lower.startsWith("critical ") ||
                lower.startsWith("break ") ||
                lower == "end" ||
                line.contains("->") ||
                line.contains("<-")
            ) {
                sawSequenceCue = true
            }
        }
        return when {
            sawNetworkCue -> DiagramKind.Network
            sawSaltCue -> DiagramKind.Salt
            sawPieCue -> DiagramKind.Pie
            sawBarCue -> DiagramKind.BarChart
            sawLineCue -> DiagramKind.LineChart
            sawScatterCue -> DiagramKind.ScatterChart
            sawC4Cue -> DiagramKind.C4
            sawArchimateCue -> DiagramKind.Archimate
            sawGanttCue -> DiagramKind.Gantt
            sawTimingCue -> DiagramKind.Timing
            sawObjectCue -> DiagramKind.Object
            sawErdCue -> DiagramKind.Erd
            sawAmbiguousContainerCue && sawBracketArtifactCue && !sawExplicitComponentCue -> DiagramKind.Deployment
            sawAmbiguousContainerCue && sawActorCue && !sawUsecaseCue && !sawExplicitComponentCue -> DiagramKind.Deployment
            sawComponentCue -> DiagramKind.Component
            sawDeploymentCue -> DiagramKind.Deployment
            sawWbsCue -> DiagramKind.Wbs
            sawActivityCue -> DiagramKind.Activity
            sawStateCue -> DiagramKind.State
            sawClassCue -> DiagramKind.Class
            sawPackageCue && sawClassCue -> DiagramKind.Class
            sawPackageCue && sawUsecaseCue -> DiagramKind.Usecase
            sawActorCue && sawUsecaseCue -> DiagramKind.Usecase
            sawUsecaseCue -> DiagramKind.Usecase
            sawSequenceCue -> DiagramKind.Sequence
            sawActorCue -> DiagramKind.Usecase
            sawAmbiguousContainerCue -> DiagramKind.Component
            sawPackageCue -> DiagramKind.Component
            else -> null
        }
    }

    private fun trimmedEqualsDirection(lower: String): Boolean =
        lower == "left to right direction" ||
            lower == "right to left direction" ||
            lower == "top to bottom direction" ||
            lower == "bottom to top direction"

    private fun isWbsCue(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed == "--" || trimmed.contains("->") || trimmed.contains("<-")) return false
        if (trimmed.startsWith("@") || trimmed.startsWith("!") || trimmed.startsWith("'") || trimmed.startsWith("//")) return false
        return WBS_PREFIX_CUE.matches(trimmed)
    }

    private fun isLegacyActivityArrowCue(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("[*]") || trimmed.startsWith("[H]") || trimmed.startsWith("[H*]")) return false
        return trimmed.startsWith("->") ||
            trimmed.startsWith("-->") ||
            trimmed.startsWith("-up->") ||
            trimmed.startsWith("-down->") ||
            trimmed.startsWith("-left->") ||
            trimmed.startsWith("-right->") ||
            trimmed.startsWith("(*)") ||
            trimmed.startsWith("(*top)") ||
            trimmed.startsWith("\"")
    }

    private fun isTimingCue(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("clock ") ||
            lower.startsWith("binary ") ||
            lower.startsWith("concise ") ||
            lower.startsWith("robust ") ||
            line.startsWith("@") ||
            Regex("""^[A-Za-z0-9_.:-]+\s+is\s+.+$""", RegexOption.IGNORE_CASE).matches(line)
    }

    private fun isC4Cue(line: String): Boolean {
        val trimmed = line.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("!include") && lower.contains("c4")) return true
        if (trimmed in setOf("C4Context", "C4Container", "C4Component", "C4Dynamic", "C4Deployment")) return true
        return Regex("""^(Person|Person_Ext|System|System_Ext|SystemDb|SystemDb_Ext|SystemQueue|SystemQueue_Ext|Container|Container_Ext|ContainerDb|ContainerDb_Ext|ContainerQueue|ContainerQueue_Ext|Component|Component_Ext|ComponentDb|ComponentDb_Ext|ComponentQueue|ComponentQueue_Ext|Boundary|Enterprise_Boundary|System_Boundary|Container_Boundary)\(""").containsMatchIn(trimmed)
    }

    private fun ignoredSkinparamWarning(): IrPatch =
        IrPatch.AddDiagnostic(
            Diagnostic(
                severity = Severity.WARNING,
                message = "Unsupported 'skinparam' ignored",
                code = "PLANTUML-W001",
            ),
        )

    override fun dispose() {
        drawStore.clear()
        diagnosticsAll.clear()
        rawPending = ""
        bufferedBodyLines.clear()
        bufferedSkinparamLines.clear()
        subPipeline?.dispose()
        subPipeline = null
    }

    @Suppress("unused")
    private fun unusedOffset(offset: Int) = offset
}
