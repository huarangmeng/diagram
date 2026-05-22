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
}

/** Shared diagnostic accumulator that also emits matching IrPatch entries. */
internal class ParserDiagnosticSink {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    fun snapshot(): List<Diagnostic> = diagnostics.toList()

    fun add(diagnostic: Diagnostic): IrPatch.AddDiagnostic {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    fun error(message: String, code: String): IrPatch.AddDiagnostic =
        add(Diagnostic(severity = Severity.ERROR, message = message, code = code))

    fun warning(message: String, code: String): IrPatch.AddDiagnostic =
        add(Diagnostic(severity = Severity.WARNING, message = message, code = code))
}
