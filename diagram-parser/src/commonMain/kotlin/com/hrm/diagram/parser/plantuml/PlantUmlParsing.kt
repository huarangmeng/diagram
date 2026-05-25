package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.streaming.IrPatchBatch

@DiagramApi
enum class PlantUmlStructFormat {
    JSON,
    YAML,
}

@DiagramApi
interface PlantUmlParsing<out T : DiagramModel> {
    fun acceptLine(line: String): IrPatchBatch

    fun finish(blockClosed: Boolean): IrPatchBatch

    fun snapshot(): T

    fun diagnosticsSnapshot(): List<Diagnostic>
}

@DiagramApi
object PlantUmlParsingFactory {
    @DiagramApi
    fun sequence(): PlantUmlParsing<com.hrm.diagram.core.ir.SequenceIR> = PlantUmlSequenceParser()

    @DiagramApi
    fun struct(format: PlantUmlStructFormat): PlantUmlParsing<StructIR> =
        PlantUmlStructParser(
            when (format) {
                PlantUmlStructFormat.JSON -> PlantUmlStructParser.Format.JSON
                PlantUmlStructFormat.YAML -> PlantUmlStructParser.Format.YAML
            },
        )
}

@DiagramApi
object PlantUmlSequenceHints {
    private const val REF_PREFIX: String = "__plantuml_sequence_ref__::"

    @DiagramApi
    fun isReferenceLabel(text: String): Boolean = text.startsWith(REF_PREFIX)

    @DiagramApi
    fun stripReferenceLabel(text: String): String = text.removePrefix(REF_PREFIX)
}
