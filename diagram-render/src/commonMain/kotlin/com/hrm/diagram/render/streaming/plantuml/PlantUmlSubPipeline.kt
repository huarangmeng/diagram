package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.render.streaming.DiagramSnapshot

internal data class PlantUmlRenderState(
    val ir: DiagramModel,
    val laidOut: LaidOutDiagram,
    val drawCommands: List<DrawCommand>,
    val diagnostics: List<Diagnostic>,
)

internal interface PlantUmlSubPipeline {
    fun acceptLine(line: String): IrPatchBatch
    fun finish(blockClosed: Boolean): IrPatchBatch
    fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState
    fun dispose() {}
}
