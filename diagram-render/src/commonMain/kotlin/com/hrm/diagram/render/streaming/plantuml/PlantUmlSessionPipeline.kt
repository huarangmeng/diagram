package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import com.hrm.diagram.render.streaming.SessionPipeline
import com.hrm.diagram.render.streaming.StreamingDiff
import com.hrm.diagram.render.streaming.dispatcher.DiagramKindDispatcher
import com.hrm.diagram.render.streaming.kernel.StreamingFamilyPipelineKernel

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
    private val familyKernel = StreamingFamilyPipelineKernel(textMeasurer)

    private var rawPending: String = ""
    private var blockStarted: Boolean = false
    private var blockClosed: Boolean = false
    private val bufferedBodyLines: MutableList<String> = ArrayList()
    private val styleState = PlantUmlLanguageStyleState()
    private val subPipelineRegistry = PlantUmlSubPipelineRegistry(textMeasurer)
    private val dispatcher = DiagramKindDispatcher(subPipelineRegistry)
    private val styleRouter = PlantUmlStyleBlockRouter(styleState, subPipelineRegistry)
    private val subPipeline: PlantUmlSubPipeline?
        get() = dispatcher.current
    private var closingDirective: String = "@enduml"

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
            familyKernel.assembleRendered(
                seq = seq,
                isFinal = isFinal,
                sourceLanguage = previousSnapshot.sourceLanguage,
                model = rendered.ir,
                laidOut = rendered.laidOut,
                drawEntities = rendered.drawEntities,
                diagnostics = diagnosticsAll + rendered.diagnostics,
                diff = StreamingDiff(
                    addedNodes = emptyList(),
                    addedEdges = emptyList(),
                    newDiagnostics = newDiagnostics,
                    irPatches = newPatches,
                ),
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
                return
            }
            val explicitStart = subPipelineRegistry.matchStartDirective(trimmed)
            if (explicitStart != null) {
                blockStarted = true
                closingDirective = explicitStart.closingDirective
                attachSubPipeline(explicitStart.kind, out)
                return
            }
            return
        }
        if (blockClosed) return

        if (trimmed.equals(closingDirective, ignoreCase = true)) {
            blockClosed = true
            return
        }
        if (styleRouter.route(trimmed, dispatcher.currentKind, subPipeline, bufferedBodyLines, out, ::ignoredSkinparamWarning)) return

        val chosen = subPipeline
        if (chosen != null) {
            val chosenKind = dispatcher.currentKind
            val payloadLine = if (chosenKind != null && subPipelineRegistry.requiresRawPayload(chosenKind)) line else trimmed
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
        val kind = detectBufferedKind() ?: PlantUmlDiagramKind.Sequence
        attachSubPipeline(kind, out)
    }

    private fun attachSubPipeline(kind: PlantUmlDiagramKind, out: MutableList<IrPatch>) {
        if (subPipeline != null) return
        val selected = dispatcher.attach(kind) ?: return
        if (styleState.bufferedSkinparamLines.isNotEmpty()) {
            for (line in styleState.bufferedSkinparamLines) {
                if (subPipelineRegistry.acceptsBufferedSkinparamLine(kind, line)) {
                    out += selected.acceptLine(line).patches
                } else {
                    out += ignoredSkinparamWarning()
                }
            }
            styleState.bufferedSkinparamLines.clear()
        }
        val pending = bufferedBodyLines.toList()
        bufferedBodyLines.clear()
        for (line in pending) {
            out += selected.acceptLine(line).patches
        }
    }

    private fun classifyImmediate(line: String): PlantUmlDiagramKind? {
        val lower = line.lowercase()
        if (lower == "nwdiag {" || lower == "nwdiag{") {
            return PlantUmlDiagramKind.Network
        }
        if (lower == "salt" || lower == "salt {" || lower == "salt{") {
            return PlantUmlDiagramKind.Salt
        }
        if (isWbsCue(line)) {
            return PlantUmlDiagramKind.Wbs
        }
        if (lower == "pie") {
            return PlantUmlDiagramKind.Pie
        }
        if (lower == "bar") {
            return PlantUmlDiagramKind.BarChart
        }
        if (lower == "line") {
            return PlantUmlDiagramKind.LineChart
        }
        if (lower == "scatter") {
            return PlantUmlDiagramKind.ScatterChart
        }
        if (lower == "chart") {
            return PlantUmlDiagramKind.BarChart
        }
        if (lower.startsWith("bar ") || lower.startsWith("h-axis ") || lower.startsWith("v-axis ")) {
            return PlantUmlDiagramKind.BarChart
        }
        if (lower.startsWith("line ")) {
            return PlantUmlDiagramKind.LineChart
        }
        if (lower.startsWith("scatter ")) {
            return PlantUmlDiagramKind.ScatterChart
        }
        if (isC4Cue(line)) {
            return PlantUmlDiagramKind.C4
        }
        if (lower.startsWith("archimate ") || Regex("""^Rel(?:_[A-Za-z0-9_]+)?\(""").containsMatchIn(line)) {
            return PlantUmlDiagramKind.Archimate
        }
        if (lower.startsWith("project starts") || Regex("""^\[[^\]]+\]\s+(starts|lasts|ends|happens)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            return PlantUmlDiagramKind.Gantt
        }
        if (isTimingCue(line)) {
            return PlantUmlDiagramKind.Timing
        }
        if (
            lower.startsWith("object ") ||
            (line.contains(':') && line.contains('=') && !line.startsWith(":") && !line.contains("->") && !line.contains("<-"))
        ) {
            return PlantUmlDiagramKind.Object
        }
        if (
            lower.startsWith("entity ") ||
            Regex("""^[A-Za-z0-9_.:-]+\s+[|}{o.\-]+\s+[A-Za-z0-9_.:-]+(?:\s*:\s*.+)?$""").matches(line)
        ) {
            return PlantUmlDiagramKind.Erd
        }
        if (lower.startsWith("artifact ")) {
            return PlantUmlDiagramKind.Deployment
        }
        if (lower.startsWith("storage ")) {
            return PlantUmlDiagramKind.Deployment
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
            return PlantUmlDiagramKind.Activity
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
            return PlantUmlDiagramKind.Usecase
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
            return PlantUmlDiagramKind.Component
        }
        if (
            lower.startsWith("state ") ||
            line.contains("[*]") ||
            line.contains("[H]") ||
            line.contains("[H*]") ||
            line == "--"
        ) {
            return PlantUmlDiagramKind.State
        }
        if (isWbsCue(line)) {
            return PlantUmlDiagramKind.Wbs
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
            return PlantUmlDiagramKind.Class
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
            return PlantUmlDiagramKind.Sequence
        }
        return null
    }

    private fun shouldDeferImmediate(kind: PlantUmlDiagramKind, line: String): Boolean {
        val lower = line.lowercase()
        val sawAmbiguousContainerCue = bufferedBodyLines.any {
            val candidate = it.lowercase()
            candidate.startsWith("node ") || candidate.startsWith("cloud ")
        }
        if (!sawAmbiguousContainerCue) return false
        return when (kind) {
            PlantUmlDiagramKind.Component ->
                lower.startsWith("database ") ||
                    line.startsWith("[") ||
                    lower.startsWith("queue ") ||
                    lower.startsWith("frame ")
            PlantUmlDiagramKind.Sequence -> true
            PlantUmlDiagramKind.Activity -> line == "}"
            else -> false
        }
    }

    private fun detectBufferedKind(): PlantUmlDiagramKind? {
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
            sawNetworkCue -> PlantUmlDiagramKind.Network
            sawSaltCue -> PlantUmlDiagramKind.Salt
            sawPieCue -> PlantUmlDiagramKind.Pie
            sawBarCue -> PlantUmlDiagramKind.BarChart
            sawLineCue -> PlantUmlDiagramKind.LineChart
            sawScatterCue -> PlantUmlDiagramKind.ScatterChart
            sawC4Cue -> PlantUmlDiagramKind.C4
            sawArchimateCue -> PlantUmlDiagramKind.Archimate
            sawGanttCue -> PlantUmlDiagramKind.Gantt
            sawTimingCue -> PlantUmlDiagramKind.Timing
            sawObjectCue -> PlantUmlDiagramKind.Object
            sawErdCue -> PlantUmlDiagramKind.Erd
            sawAmbiguousContainerCue && sawBracketArtifactCue && !sawExplicitComponentCue -> PlantUmlDiagramKind.Deployment
            sawAmbiguousContainerCue && sawActorCue && !sawUsecaseCue && !sawExplicitComponentCue -> PlantUmlDiagramKind.Deployment
            sawComponentCue -> PlantUmlDiagramKind.Component
            sawDeploymentCue -> PlantUmlDiagramKind.Deployment
            sawWbsCue -> PlantUmlDiagramKind.Wbs
            sawActivityCue -> PlantUmlDiagramKind.Activity
            sawStateCue -> PlantUmlDiagramKind.State
            sawClassCue -> PlantUmlDiagramKind.Class
            sawPackageCue && sawClassCue -> PlantUmlDiagramKind.Class
            sawPackageCue && sawUsecaseCue -> PlantUmlDiagramKind.Usecase
            sawActorCue && sawUsecaseCue -> PlantUmlDiagramKind.Usecase
            sawUsecaseCue -> PlantUmlDiagramKind.Usecase
            sawSequenceCue -> PlantUmlDiagramKind.Sequence
            sawActorCue -> PlantUmlDiagramKind.Usecase
            sawAmbiguousContainerCue -> PlantUmlDiagramKind.Component
            sawPackageCue -> PlantUmlDiagramKind.Component
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
        familyKernel.clear()
        diagnosticsAll.clear()
        rawPending = ""
        bufferedBodyLines.clear()
        styleState.reset()
        dispatcher.clear()
    }

    @Suppress("unused")
    private fun unusedOffset(offset: Int) = offset
}
