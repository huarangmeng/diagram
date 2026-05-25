package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.LineStreamingSubPipeline
import com.hrm.diagram.render.streaming.RenderStateProvider

internal data class PlantUmlRenderState(
    val ir: DiagramModel,
    val laidOut: LaidOutDiagram,
    val diagnostics: List<Diagnostic>,
    val drawEntities: List<DrawEntity>,
)

internal interface PlantUmlSubPipeline : LineStreamingSubPipeline<String, IrPatchBatch>, RenderStateProvider<PlantUmlRenderState> {
    override fun acceptLine(line: String): IrPatchBatch
    fun finish(blockClosed: Boolean): IrPatchBatch
    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState
}
