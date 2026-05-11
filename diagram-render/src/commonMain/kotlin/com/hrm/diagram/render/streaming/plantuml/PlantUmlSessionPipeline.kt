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
    }

    private val diagnosticsAll: MutableList<Diagnostic> = ArrayList()

    private var rawPending: String = ""
    private var blockStarted: Boolean = false
    private var blockClosed: Boolean = false
    private val bufferedBodyLines: MutableList<String> = ArrayList()
    private val bufferedSkinparamLines: MutableList<String> = ArrayList()
    private var bufferingSkinparamBlock: Boolean = false
    private var ignoredSkinparamBlock: Boolean = false
    private var subPipeline: PlantUmlSubPipeline? = null
    private var closingDirective: String = "@enduml"

    private enum class DiagramKind { Sequence, Class, State, Component, Usecase, Activity, Object, Deployment, Erd, Mindmap, Wbs }

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
            PipelineAdvance(
                snapshot = DiagramSnapshot(
                    ir = rendered.ir,
                    laidOut = rendered.laidOut,
                    drawCommands = rendered.drawCommands,
                    diagnostics = diagnosticsAll + rendered.diagnostics,
                    seq = seq,
                    isFinal = isFinal,
                    sourceLanguage = previousSnapshot.sourceLanguage,
                ),
                patch = SessionPatch(
                    seq = seq,
                    addedNodes = emptyList(),
                    addedEdges = emptyList(),
                    addedDrawCommands = rendered.drawCommands,
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
                chosen is PlantUmlObjectSubPipeline
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
            out += chosen.acceptLine(trimmed).patches
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
                kind == DiagramKind.Object
            ) {
                for (line in bufferedSkinparamLines) {
                    out += subPipeline!!.acceptLine(line).patches
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
        var sawAmbiguousContainerCue = false
        var sawBracketArtifactCue = false
        for (line in bufferedBodyLines) {
            val lower = line.lowercase()
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
            sawObjectCue -> DiagramKind.Object
            sawErdCue -> DiagramKind.Erd
            sawAmbiguousContainerCue && sawBracketArtifactCue && !sawExplicitComponentCue -> DiagramKind.Deployment
            sawAmbiguousContainerCue && sawActorCue && !sawUsecaseCue && !sawExplicitComponentCue -> DiagramKind.Deployment
            sawComponentCue -> DiagramKind.Component
            sawDeploymentCue -> DiagramKind.Deployment
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

    private fun ignoredSkinparamWarning(): IrPatch =
        IrPatch.AddDiagnostic(
            Diagnostic(
                severity = Severity.WARNING,
                message = "Unsupported 'skinparam' ignored",
                code = "PLANTUML-W001",
            ),
        )

    @Suppress("unused")
    private fun unusedOffset(offset: Int) = offset
}
