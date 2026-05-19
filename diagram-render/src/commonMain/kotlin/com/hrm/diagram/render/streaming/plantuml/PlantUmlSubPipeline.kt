package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.cache.structuredDrawEntities
import com.hrm.diagram.render.streaming.DiagramSnapshot

internal data class PlantUmlRenderState(
    val ir: DiagramModel,
    val laidOut: LaidOutDiagram,
    val diagnostics: List<Diagnostic>,
    val drawEntities: List<DrawEntity>,
) {
    val drawCommands: List<DrawCommand> = drawEntities.flatMap { it.commands }

    companion object {
        fun fromCommands(
            ir: DiagramModel,
            laidOut: LaidOutDiagram,
            drawCommands: List<DrawCommand>,
            diagnostics: List<Diagnostic>,
        ): PlantUmlRenderState =
            PlantUmlRenderState(
                ir = ir,
                laidOut = laidOut,
                diagnostics = diagnostics,
                drawEntities = structuredDrawEntities(
                    prefix = "plantuml",
                    model = ir,
                    laidOut = laidOut,
                    commands = drawCommands,
                ),
            )
    }
}

internal interface PlantUmlSubPipeline {
    fun acceptLine(line: String): IrPatchBatch
    fun finish(blockClosed: Boolean): IrPatchBatch
    fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState
    fun dispose() {}
}
