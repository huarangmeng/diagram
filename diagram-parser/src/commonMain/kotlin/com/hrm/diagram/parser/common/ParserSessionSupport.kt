package com.hrm.diagram.parser.common

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/** Shared append-only sequence counter for streaming line parsers. */
internal class ParserSessionSeq {
    var value: Long = 0L
        private set

    fun next(): Long {
        value += 1
        return value
    }

    fun emptyBatch(): IrPatchBatch = IrPatchBatch(value, emptyList())

    fun diagnosticBatch(diagnostic: IrPatch.AddDiagnostic): IrPatchBatch =
        IrPatchBatch(value, listOf(diagnostic))

    fun diagnosticBatch(diagnostics: List<IrPatch.AddDiagnostic>): IrPatchBatch =
        IrPatchBatch(value, diagnostics)
}

/** Shared diagnostic accumulator that also emits matching IrPatch entries. */
internal class ParserDiagnosticSink {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    fun snapshot(): List<Diagnostic> = diagnostics.toList()

    fun last(): Diagnostic = diagnostics.last()

    fun add(diagnostic: Diagnostic): IrPatch.AddDiagnostic {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    operator fun plusAssign(diagnostic: Diagnostic) {
        add(diagnostic)
    }

    fun error(message: String, code: String): IrPatch.AddDiagnostic =
        add(Diagnostic(severity = Severity.ERROR, message = message, code = code))

    fun warning(message: String, code: String): IrPatch.AddDiagnostic =
        add(Diagnostic(severity = Severity.WARNING, message = message, code = code))

    fun errorBatch(seq: ParserSessionSeq, message: String, code: String): IrPatchBatch =
        seq.diagnosticBatch(error(message, code))

    fun warningBatch(seq: ParserSessionSeq, message: String, code: String): IrPatchBatch =
        seq.diagnosticBatch(warning(message, code))
}

/** Facade for the common streaming line-parser session pattern. */
internal class ParserSession {
    private val seq = ParserSessionSeq()
    private val diagnostics = ParserDiagnosticSink()

    val value: Long
        get() = seq.value

    fun beginLine(): Long = seq.next()

    fun emptyBatch(): IrPatchBatch = seq.emptyBatch()

    fun diagnosticBatch(diagnostic: IrPatch.AddDiagnostic): IrPatchBatch =
        seq.diagnosticBatch(diagnostic)

    fun diagnosticBatch(diagnostics: List<IrPatch.AddDiagnostic>): IrPatchBatch =
        seq.diagnosticBatch(diagnostics)

    fun add(diagnostic: Diagnostic): IrPatch.AddDiagnostic =
        diagnostics.add(diagnostic)

    operator fun plusAssign(diagnostic: Diagnostic) {
        add(diagnostic)
    }

    fun lastDiagnostic(): Diagnostic = diagnostics.last()

    fun error(message: String, code: String): IrPatch.AddDiagnostic =
        diagnostics.error(message, code)

    fun warning(message: String, code: String): IrPatch.AddDiagnostic =
        diagnostics.warning(message, code)

    fun errorBatch(message: String, code: String): IrPatchBatch =
        seq.diagnosticBatch(error(message, code))

    fun warningBatch(message: String, code: String): IrPatchBatch =
        seq.diagnosticBatch(warning(message, code))

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.snapshot()
}
