package com.hrm.diagram.parser.dot

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.GraphIR

@DiagramApi
data class DotParseResult(
    val ir: GraphIR,
    val diagnostics: List<Diagnostic>,
)

@DiagramApi
interface DotIncrementalSession {
    fun feed(chunk: CharSequence, eos: Boolean = false): DotParseResult

    fun reset()
}

@DiagramApi
object DotParsing {
    @DiagramApi
    fun parse(source: String): DotParseResult = DotParser().parse(source)

    @DiagramApi
    fun incrementalSession(): DotIncrementalSession = DotParser().incrementalSession()
}
