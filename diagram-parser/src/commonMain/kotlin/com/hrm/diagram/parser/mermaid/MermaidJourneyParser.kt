package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.JourneyStage
import com.hrm.diagram.core.ir.JourneyStep
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.parser.common.ParserSession
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `journey`.
 *
 * Supported subset:
 * - `journey` header
 * - `title ...`
 * - `section ...`
 * - step lines: `Task: <score>: <actor1>, <actor2>`
 */
class MermaidJourneyParser {
    private val session = ParserSession()
    private var headerSeen = false
    private var title: String? = null

    private data class MutableStage(
        val label: String,
        val steps: MutableList<JourneyStep> = ArrayList(),
    )

    private val stages: MutableList<MutableStage> = ArrayList()
    private var currentStage: MutableStage? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        session.beginLine()
        if (line.isEmpty()) return session.emptyBatch()
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return session.emptyBatch()
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.JOURNEY_HEADER) {
                headerSeen = true
                return session.emptyBatch()
            }
            return errorBatch("Expected 'journey' header")
        }

        val s = normalize(toks)
        when {
            s.startsWith("title ") -> {
                title = stripQuotes(s.removePrefix("title ").trim()).ifBlank { title }
            }
            s.startsWith("section ") -> {
                val label = stripQuotes(s.removePrefix("section ").trim())
                if (label.isBlank()) {
                    session += Diagnostic(Severity.ERROR, "Journey section label cannot be empty", "MERMAID-E208")
                } else {
                    val stage = MutableStage(label)
                    stages += stage
                    currentStage = stage
                }
            }
            else -> parseStep(s)
        }
        return session.emptyBatch()
    }

    fun snapshot(): JourneyIR =
        JourneyIR(
            stages = stages.map { stage ->
                JourneyStage(
                    label = RichLabel.Plain(stage.label),
                    steps = stage.steps.toList(),
                )
            },
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = session.diagnosticsSnapshot()

    private fun parseStep(s: String) {
        val stage = currentStage ?: MutableStage("default").also {
            stages += it
            currentStage = it
        }
        val firstColon = s.indexOf(':')
        val secondColon = s.indexOf(':', startIndex = firstColon + 1)
        if (firstColon <= 0 || secondColon <= firstColon) {
            session += Diagnostic(Severity.ERROR, "Invalid journey step syntax", "MERMAID-E208")
            return
        }
        val label = stripQuotes(s.substring(0, firstColon).trim())
        val score = s.substring(firstColon + 1, secondColon).trim().toIntOrNull()
        val actorsRaw = s.substring(secondColon + 1).trim()
        if (label.isBlank() || score == null || score !in 1..5) {
            session += Diagnostic(Severity.ERROR, "Invalid journey step syntax", "MERMAID-E208")
            return
        }
        val actors = if (actorsRaw.isBlank()) {
            emptyList()
        } else {
            actorsRaw.split(',')
                .map { actor -> stripQuotes(actor.trim()) }
                .filter { actor -> actor.isNotBlank() }
                .map { actor -> RichLabel.Plain(actor) }
        }
        stage.steps += JourneyStep(
            label = RichLabel.Plain(label),
            score = score,
            actors = actors,
        )
    }

    private fun normalize(toks: List<Token>): String = toks.joinToString(" ") { it.text.toString() }.trim()

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E208")
        session += d
        return IrPatchBatch(session.value, listOf(IrPatch.AddDiagnostic(d)))
    }
}
